#!/usr/bin/env python3
"""Imports an export of the Money Manager app (one .xlsx sheet) into a NEW, empty Financial Dashboard database, after a review.

  python3 scripts/import-money-manager.py plan  FILE.xlsx [--until 2026-09-26 [--extend "HDFC,Cash=2026-10-29"]] [--from-row N] [--accounts HDFC,Cash] [--out import-review]
  python3 scripts/import-money-manager.py apply [--out import-review] [--url http://localhost:8080] [--clear-starter-categories]

Start the app on its own database first:  scripts/financial-dashboard.sh moneymanager   (./data/moneymanager, not your real data).

`plan` changes nothing. It reads the workbook and writes the files to review into --out:

  account-map.csv   each Money Manager account -> the name, type and currency it gets in the app. EDIT, then run `plan` again.
  category-map.csv  each Money Manager category / sub category -> the app's. EDIT, then run `plan` again.
  row-overrides.csv (optional, you write it) one line per row that must be saved differently: file_row, action, target. The actions are
                    OPENING (the row is an opening balance of its account: it is not a transaction, the amount becomes that account's
                    opening balance), TRANSFER (an expense or income row that is really money moved to the Money Manager account named in
                    target; "Account=amount" says how much arrived there, in its currency, when two currencies are involved), TRANSFER_FROM (an income row that is really money that came in from the account named in target), CATEGORY
                    (target is "Category > Sub category" in the app's names: that row gets it instead of the category-map.csv one), OK
                    (checked, no change: it only records that the row was reviewed), SPLIT (one row saved as several: target is
                    "Category > Sub = amount | Category > Sub = rest", the amounts adding up to the row), PAYMENT (an expense row that is
                    really a payment into the loan or card named in target: saved as a Liability Payments expense with that account as its
                    destination, which lowers what is owed) and SKIP and EXPENSE (a transfer row that is really money spent: target is the Money Manager
                    "Category > Sub category", such as a payment to a friend you lend money to). --until DATE leaves out every row after
                    that day.
  category-renames.csv (optional, you write it) kind, from_category, from_sub, to_category, to_sub: a category or sub category is renamed
                    everywhere it is used in the plan, whatever put it there (the category map, a row's own answer, a split). from_sub
                    "*" means every sub category of it (the category is renamed, the subs keep their names, to_sub "*"); to_sub empty
                    means no sub category. Two optional columns, from_date and to_date (yyyy-mm-dd), limit a rename to the rows of those
                    days, which is how a trip gets a category of its own.
  rates.csv         the exchange rates worked out from the rows in a foreign currency (rupees per 1 unit, on the days they were used)
  plan.csv          every row of the workbook as it will be saved, in the app's names

An existing account-map.csv and category-map.csv are read back, so the plan follows your edits (plan.csv is rewritten each time).

`apply` talks to the running app's API (the password comes from FINANCE_PASSWORD, or is asked for; a new database has none yet, and the one
you give is set). It REFUSES to run unless the database has no accounts and no transactions, so it can never add to your real data. It sets
the exchange rates, creates the accounts, creates the categories that are missing, saves the transactions oldest first, and sets every
opening balance to 0 (Money Manager's export holds no balances, so each account's balance is just what the rows add up to: change an
opening balance in the app to match the real bank balance on 1 January). Only the Python standard library is used. The workbook is read
as data only; nothing in it is ever run.
"""

import argparse
import base64
import collections
import csv
import datetime
import getpass
import json
import os
import re
import sys
import urllib.error
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

M = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
# The accounts the app keeps up to date itself (what was already in a fund or deposit shifts their opening balance): never reset.
MANAGED_ACCOUNTS = {"Mutual Funds", "Shares", "Recurring Deposits", "Future Expenses Ledger"}
RD_COLUMNS = ("rd_start_date", "rd_term_months", "rd_monthly_amount", "rd_interest_rate")
# A fund or deposit that was fed every month before the file starts: backfill_months of backfill_amount from the account named in backfill_from,
# on the same day of the month as its first row in the file, going back from there. The money left that account before the file began, so its
# opening balance rises by the total (its balance on the first day stays what the file says).
BACKFILL_COLUMNS = ("backfill_months", "backfill_amount", "backfill_from")
# A goal fund's target and a credit card's statement and due days, for accounts that are not in the Money Manager file (see extra-plan-rows.csv).
OTHER_COLUMNS = ("target_amount", "due_day", "statement_day")
CUSHION = 10 ** 10   # what accounts start with while the rows are saved, so no balance check ever stops one; set back to 0 at the end

# The Money Manager account -> (app name, type, currency, credit limit). The names are the same; edit account-map.csv to rename or retype.
ACCOUNT_DEFAULTS = {
    "HDFC": ("HDFC", "BANK", "INR", ""),
    "Kiwi Credit Card": ("Kiwi Credit Card", "CREDIT_CARD", "INR", "225000"),
    "Niyo Visa": ("Niyo Visa", "BANK", "INR", ""),
    "Cash": ("Cash", "CASH", "INR", ""),
    "Vietnamese Dong": ("Vietnamese Dong", "CASH", "VND", ""),
    "Dollars": ("Dollars", "BANK", "USD", ""),
    "Monthly Expense": ("Future Expenses Ledger", "LEDGER", "INR", ""),
    "Emergency Funds": ("Emergency Fund", "EMERGENCY_FUND", "INR", ""),
    "Amazon Wallet": ("Amazon Wallet", "WALLET", "INR", ""),
    "Mid Cap Mutual fund": ("Mid Cap Mutual fund", "INVESTMENT", "INR", ""),
    "Small Mutual funds": ("Small Mutual funds", "INVESTMENT", "INR", ""),
    "Recurring Deposit": ("Recurring Deposit", "INVESTMENT", "INR", ""),   # not in the file: only a row override can send money to it
    "Personal Loan": ("Personal Loan", "DEBT", "INR", ""),                 # not in the file either: row overrides (PAYMENT) pay into it
}
# A type that names a portfolio is not an account: the Money Manager account becomes a HOLDING (a fund or a share) inside the app's Mutual Funds
# or Shares account, money into it is saved with Invest and money out of it with Withdraw, both on the day of the row. opening_balance is what the
# holding already held before the file (added first, invested and worth that); money put in is worth what it cost on that day, so the value
# follows the invested amount until a sale; a withdrawal larger than its worth first sets its value to
# the amount, on that day, because a sale pays what the fund is worth then (the gain stays in the fund's value, it is not income).
HOLDING_KINDS = {"HOLDING_MUTUAL_FUNDS": "MUTUAL_FUNDS", "HOLDING_SHARES": "SHARES"}
# HOLDING_RECURRING_DEPOSIT makes the account a deposit of the app's Recurring Deposits card: money into it is a dated Deposit, money out of it
# closes it (what came back is the amount). Its terms come from account-map.csv (rd_start_date, rd_term_months, rd_monthly_amount,
# rd_interest_rate; opening_balance is what was already in it); a term left out is a placeholder of 60 months to change in the app.
RD_TYPE = "HOLDING_RECURRING_DEPOSIT"
# EMERGENCY_FUND makes the account the app's own Emergency Fund (a goal fund the Emergency Fund card is set on), not a plain goal fund. The app asks for a
# target before it can be set up, so it starts at EF_TARGET_MONTHS months of EF_MONTHLY_EXPENSES a month: change them on the card.
# LEDGER makes the account the app's own Future Expenses Ledger (set up with the Buffer bucket; money into it is a dated funding that goes to the Buffer).
# The app fixes its name, so the app_name must be "Future Expenses Ledger"; a bill paid from it becomes a bucket named after the bill, paid on its day from money moved out of the Buffer, and interest credited to it is Ledger interest.
LEDGER_TYPE, LEDGER_NAME = "LEDGER", "Future Expenses Ledger"
EF_TYPE = "EMERGENCY_FUND"
EF_TARGET_MONTHS, EF_MONTHLY_EXPENSES = 6, 90000
ACCOUNT_TYPES = {"BANK", "CASH", "WALLET", "INVESTMENT", "PROPERTY", "VEHICLE", "OTHER", "CREDIT_CARD", "DEBT", "GOAL_FUND"} | set(HOLDING_KINDS) | {RD_TYPE, EF_TYPE, LEDGER_TYPE}
FOREIGN_OK = {"BANK", "CASH", "WALLET", "INVESTMENT", "PROPERTY", "VEHICLE", "OTHER"}   # the types the app lets be in a foreign currency
CURRENCIES = {"INR": 2, "USD": 2, "EUR": 2, "GBP": 2, "VND": 0}                          # decimals of each currency the app knows
TYPO_FIXES = {"Vechile": "Vehicle", "Accomodation": "Accommodation", "Stationary": "Stationery"}   # corrected in the default names only
BALANCE_CORRECTION = "Balance correction"   # Money Manager's "Modified Bal." rows: it moved a balance to match the bank; kept so balances agree


def clean(text):
    """A name without the emoji and spaces Money Manager puts in front of it."""
    return re.sub(r"^[^\w]+", "", (text or "").strip()).strip()


def tidy(text):
    """A default app name: cleaned, a typo fixed, and a first letter in capitals."""
    name = clean(text)
    name = TYPO_FIXES.get(name, name)
    return name[:1].upper() + name[1:]


def read_workbook(path):
    z = zipfile.ZipFile(path)
    shared = ["".join(t.text or "" for t in si.iter(M + "t")) for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall(M + "si")]
    rows = []
    for r in ET.fromstring(z.read("xl/worksheets/sheet1.xml")).iter(M + "row"):
        cells = {}
        for c in r.findall(M + "c"):
            v = c.find(M + "v")
            cells[re.match(r"[A-Z]+", c.get("r")).group(0)] = "" if v is None else shared[int(v.text)] if c.get("t") == "s" else v.text
        rows.append((int(r.get("r")), cells))
    header, body = rows[0][1], rows[1:]
    if not (header.get("A") == "Date" and header.get("G") == "Income/Expense" and header.get("K") == "Currency"):
        sys.exit("this is not a Money Manager export (the columns are not the expected ones)")
    out = []
    for number, c in body:
        stamp = float(c["A"])
        out.append({"row": number, "stamp": stamp, "date": datetime.date(1899, 12, 30) + datetime.timedelta(days=stamp),
                    "account": c.get("B", "").strip(), "category": c.get("C", "").strip(), "sub": c.get("D", "").strip(),
                    "note": c.get("E", "").strip(), "inr": float(c["F"]), "type": c["G"], "description": c.get("I", "").strip(),
                    "amount": float(c["J"]), "currency": c["K"]})
    return out


def read_csv(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def write_csv(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


def rates_from(rows):
    """Rupees per 1 unit of each foreign currency, one per day it was used (the row's INR figure over its own amount)."""
    found = {}
    for r in rows:
        if r["currency"] != "INR" and r["amount"] != 0 and r["inr"] != 0:
            found.setdefault((r["currency"], r["date"]), []).append(abs(r["inr"]) / abs(r["amount"]))
    return sorted((cur, day, sum(v) / len(v)) for (cur, day), v in found.items())


def rate_on(rates, currency, day):
    mine = [(d, v) for c, d, v in rates if c == currency]
    best = mine[0] if mine else None
    for d, v in mine:
        if d <= day:
            best = (d, v)
    return best[1] if best else None


def place(amount, currency):
    return round(amount, CURRENCIES[currency])


def plan(args):
    rows = read_workbook(args.file)
    os.makedirs(args.out, exist_ok=True)
    if args.until:
        until = datetime.date.fromisoformat(args.until)
        later = {}   # --extend "HDFC,Cash=2026-10-29": these accounts go on to a later day than --until
        if args.extend:
            names, _, day = args.extend.partition("=")
            later = {name.strip(): datetime.date.fromisoformat(day.strip()) for name in names.split(",")}
        rows = [r for r in rows if r["date"] <= max(until, later.get(r["account"], until))]
    if args.from_row:   # the export lists the newest rows first, so "row N or higher" is "the oldest rows, up to the one in row N"
        rows = [r for r in rows if r["row"] >= args.from_row]
    if args.accounts:   # only the rows of these Money Manager accounts (as reviewed account by account); transfers into others still land there
        wanted = {name.strip() for name in args.accounts.split(",")}
        rows = [r for r in rows if r["account"] in wanted]

    # ---- rows that are saved differently (row-overrides.csv)
    splits = {}                                 # file row -> [(app category, app sub category, amount or None for the rest)] from a SPLIT override
    forced = {}                                 # file row -> (app category, app sub category) from a CATEGORY override
    opening = collections.defaultdict(float)   # Money Manager account -> the opening balance its OPENING rows add up to
    ov_path = os.path.join(args.out, "row-overrides.csv")
    overrides = {}
    if os.path.exists(ov_path):
        overrides = {int(o["file_row"]): o for o in read_csv(ov_path)}
        kept = []
        for r in rows:
            o = overrides.get(r["row"])
            action = o["action"].strip().upper() if o else ""
            if action == "SKIP":
                continue
            if action == "OPENING":
                opening[r["account"]] += r["amount"]
                continue
            if action == "TRANSFER":
                target, _, got = o["target"].partition("=")   # "Account" or "Account=amount that arrived" (in that account's currency)
                r = dict(r, type="Transfer-Out", category=target.strip(), sub="", arrived=float(got) if got.strip() else None)
            elif action == "TRANSFER_FROM":   # the money came in from another account: it leaves that one and arrives here
                r = dict(r, type="Transfer-Out", account=o["target"].strip(), category=r["account"], sub="")
            elif action == "EXPENSE":   # a transfer row that is really money spent: target is the Money Manager "Category > Sub category" it belongs to
                cat, _, sub_name = o["target"].partition(">")
                r = dict(r, type="Expense", category=cat.strip(), sub=sub_name.strip())
            elif action == "PAYMENT":
                r = dict(r, pay_to=o["target"].strip())
            elif action == "SPLIT":
                parts = []
                for chunk in o["target"].split("|"):
                    left, _, amount_text = chunk.partition("=")
                    cat, _, sub_name = left.partition(">")
                    amount_text = amount_text.strip().lower()
                    parts.append((cat.strip(), sub_name.strip(), None if amount_text in ("", "rest") else float(amount_text)))
                if sum(1 for x in parts if x[2] is None) > 1:
                    sys.exit(f"row-overrides.csv: row {r['row']}: only one part of a split can take 'the rest'")
                splits[r["row"]] = parts
            elif action == "CATEGORY":
                cat, _, sub_name = o["target"].partition(">")
                forced[r["row"]] = (cat.strip(), sub_name.strip())
            elif o and action != "OK":
                sys.exit(f"row-overrides.csv: row {r['row']} has the unknown action '{o['action']}'")
            kept.append(r)
        rows = kept

    # ---- a fund or deposit fed monthly before the file starts (backfill_* columns of account-map.csv): one transfer per month, going back
    acc_file = os.path.join(args.out, "account-map.csv")
    for e in (read_csv(acc_file) if os.path.exists(acc_file) else []):
        months, amount, source = (e.get(k, "").strip() for k in BACKFILL_COLUMNS)
        if not (months and amount and source):
            continue
        target = e["money_manager_account"]
        first = min((r for r in rows if r["type"] == "Transfer-Out" and r["category"] == target), key=lambda r: r["stamp"], default=None)
        if first is None:
            continue   # none of this plan's rows feeds it, so there is nothing to go back from
        for k in range(1, int(months) + 1):
            total_months = first["date"].year * 12 + first["date"].month - 1 - k
            year, month = divmod(total_months, 12)
            day = min(first["date"].day, [31, 29 if year % 4 == 0 and (year % 100 or year % 400 == 0) else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month])
            when = datetime.date(year, month + 1, day)
            rows.append({"row": -(len(rows) + 1), "stamp": (when - datetime.date(1899, 12, 30)).days + 0.5, "date": when, "account": source,
                         "category": target, "sub": "", "note": f"Monthly deposit (back-filled, {k} of {months} before the file)", "inr": float(amount),
                         "type": "Transfer-Out", "description": "", "amount": float(amount), "currency": "INR"})
        opening[source] += float(amount) * int(months)

    # ---- accounts (an edited account-map.csv wins)
    seen = collections.OrderedDict()
    for r in sorted(rows, key=lambda r: r["row"]):
        seen[r["account"]] = seen.get(r["account"], 0) + 1
        if r["type"] == "Transfer-Out":
            seen.setdefault(r["category"], 0)
        if r.get("pay_to"):
            seen.setdefault(r["pay_to"], 0)
    for name in seen:
        if name not in ACCOUNT_DEFAULTS:
            sys.exit(f"Money Manager account '{name}' has no default: add it to ACCOUNT_DEFAULTS")
    acc_path = os.path.join(args.out, "account-map.csv")
    edited = {a["money_manager_account"]: a for a in read_csv(acc_path)} if os.path.exists(acc_path) else {}
    accounts = collections.OrderedDict()
    for name in seen:
        d = ACCOUNT_DEFAULTS[name]
        e = edited.get(name)
        accounts[name] = {"app_name": (e["app_name"] if e else d[0]).strip(), "type": (e["type"] if e else d[1]).strip().upper(),
                          "currency": (e["currency"] if e else d[2]).strip().upper(), "credit_limit": (e["credit_limit"] if e else d[3]).strip(),
                          "opening": f"{opening[name]:.2f}" if name in opening else (e.get("opening_balance") or "0") if e else "0",
                          "emi": ((e.get("emi_amount") or "") if e else "").strip(),
                          "rd": {k: ((e.get(k) or "") if e else "").strip() for k in RD_COLUMNS},
                          "backfill": {k: ((e.get(k) or "") if e else "").strip() for k in BACKFILL_COLUMNS},
                          "other": {k: ((e.get(k) or "") if e else "").strip() for k in OTHER_COLUMNS}}
    names = [a["app_name"].lower() for a in accounts.values()]
    for mm, a in accounts.items():
        if not a["app_name"] or names.count(a["app_name"].lower()) > 1:
            sys.exit(f"account '{mm}': the app name '{a['app_name']}' is empty or used twice")
        if a["type"] not in ACCOUNT_TYPES:
            sys.exit(f"account '{mm}': type {a['type']} is not one of {sorted(ACCOUNT_TYPES)}")
        if a["currency"] not in CURRENCIES:
            sys.exit(f"account '{mm}': currency {a['currency']} is not one of {sorted(CURRENCIES)}")
        if a["currency"] != "INR" and a["type"] not in FOREIGN_OK:
            sys.exit(f"account '{mm}': a {a['type']} account can only be in rupees")
        if a["type"] == "CREDIT_CARD" and not (a["credit_limit"] and float(a["credit_limit"]) > 0):
            sys.exit(f"account '{mm}': a credit card needs a credit limit")
    write_csv(acc_path, ["money_manager_account", "app_name", "type", "currency", "credit_limit", "opening_balance", "emi_amount", *RD_COLUMNS, *BACKFILL_COLUMNS, *OTHER_COLUMNS, "rows_in_the_file"],
              [[mm, a["app_name"], a["type"], a["currency"], a["credit_limit"], a["opening"], a["emi"], *[a["rd"][k] for k in RD_COLUMNS],
                *[a["backfill"][k] for k in BACKFILL_COLUMNS], *[a["other"][k] for k in OTHER_COLUMNS], seen[mm]] for mm, a in accounts.items()]
              + [[e["money_manager_account"], e["app_name"], e["type"], e["currency"], e["credit_limit"], e.get("opening_balance", ""), e.get("emi_amount", ""), *[e.get(k, "") for k in RD_COLUMNS], *[e.get(k, "") for k in BACKFILL_COLUMNS], *[e.get(k, "") for k in OTHER_COLUMNS], 0]
                 for name, e in edited.items() if name not in accounts])   # accounts the rows of this plan do not use keep their settings for a later, wider plan

    # ---- categories (an edited category-map.csv wins)
    def classify(r):
        """(kind, key category, key sub category, default app category, default app sub category) of an income or expense row."""
        c, s = clean(r["category"]), clean(r["sub"])
        if r["type"] == "Income":
            if c == "Modified Bal.":
                return "INCOME", c, s, BALANCE_CORRECTION, ""
            return "INCOME", c, s, tidy(c), tidy(s) if s else ""
        if r["amount"] < 0:   # a negative expense is money given back
            if c == "Loan":
                return "INCOME", "(refund) " + c, s, "Loan Repaid", tidy(s)
            return "INCOME", "(refund) " + c, "", "Refund", tidy(c)
        return "EXPENSE", c, s, tidy(c), tidy(s) if s else ""

    needed = collections.OrderedDict()
    for r in rows:
        if r["type"] != "Transfer-Out":
            kind, c, s, c2, s2 = classify(r)
            needed.setdefault((kind, c, s), [0, c2, s2])[0] += 1
    map_path = os.path.join(args.out, "category-map.csv")
    old = {(m["kind"], m["file_category"], m["file_sub"]): (m["app_category"].strip(), m["app_sub"].strip()) for m in read_csv(map_path)} \
        if os.path.exists(map_path) else {}
    cat_rows = []
    for (kind, c, s), (n, c2, s2) in needed.items():
        if (kind, c, s) in old:
            c2, s2 = old[(kind, c, s)]
        if not c2:
            sys.exit(f"category-map.csv: '{c}' / '{s}' has no app_category")
        cat_rows.append([kind, c, s, n, c2, s2])
    cat_rows.sort(key=lambda x: (x[0] != "EXPENSE", x[1].lower(), -x[3]))
    used = {(c[0], c[1], c[2]) for c in cat_rows}
    write_csv(map_path, ["kind", "file_category", "file_sub", "rows", "app_category", "app_sub"],
              cat_rows + [[k, c, sub_name, 0, a, b] for (k, c, sub_name), (a, b) in old.items() if (k, c, sub_name) not in used])   # kept for a wider plan
    cmap = {(k, c, s): (c2, s2) for k, c, s, _, c2, s2 in cat_rows}

    # ---- renames of categories, applied to every row at the end (category-renames.csv)
    rename_path = os.path.join(args.out, "category-renames.csv")
    renames = collections.defaultdict(list)   # (kind, category, sub) -> [(new category, new sub, first day or "", last day or "")]
    if os.path.exists(rename_path):
        for m in read_csv(rename_path):
            renames[(m["kind"], m["from_category"], m["from_sub"])].append(
                (m["to_category"], m["to_sub"], (m.get("from_date") or "").strip(), (m.get("to_date") or "").strip()))

    def renamed(kind, c, sub_name, day=""):
        for key in ((kind, c, sub_name), (kind, c, "*")):
            for to_cat, to_sub, first, last in renames.get(key, ()):
                if (first and day < first) or (last and day > last):
                    continue   # a rename with dates only applies to the rows of those days (a trip's own category)
                return to_cat, (sub_name if to_sub == "*" else to_sub)
        return c, sub_name

    # ---- the rows
    rates = rates_from(rows)
    plan_rows = []
    problems = []
    for r in sorted(rows, key=lambda r: (r["stamp"], -r["row"])):
        src = accounts[r["account"]]
        head = [r["row"], r["date"].isoformat()]
        note, desc, flag = r["note"], r["description"], ""
        if len(note) > 255:
            note, flag = note[:255], "note cut to 255 characters"
        if len(desc) > 1000:
            desc, flag = desc[:1000], "description cut to 1000 characters"
        if r["type"] == "Transfer-Out":
            dst = accounts[r["category"]]
            src_cur, dst_cur = src["currency"], dst["currency"]
            amount = r["inr"] if src_cur == "INR" else r["amount"]   # Money Manager gives the rupee figure in INR and the destination's in Amount
            received = ""
            if src_cur != dst_cur:
                if r.get("arrived"):
                    received, flag = r["arrived"], "two currencies: the amount that arrived was given in row-overrides.csv"
                elif r["currency"] == dst_cur:
                    received, flag = r["amount"], "two currencies: the amount that arrived is the one in the file"
                else:
                    rate_to, rate_from = rate_on(rates, dst_cur, r["date"]), 1 if src_cur == "INR" else rate_on(rates, src_cur, r["date"])
                    if not rate_to or not rate_from:
                        problems.append(f"row {r['row']}: no exchange rate to work out what arrived in {dst_cur}")
                        continue
                    received, flag = amount * rate_from / rate_to, "two currencies: what arrived is worked out from the rate of that day, please check"
                received = place(received, dst_cur)
            plan_rows.append(head + ["TRANSFER", src["app_name"], dst["app_name"], place(amount, src_cur), src_cur, received, "", "", note, desc, flag])
            continue
        kind, c, s, _, _ = classify(r)
        c2, s2 = forced.get(r["row"]) or cmap[(kind, c, s)]
        c2, s2 = renamed(kind, c2, s2, r["date"].isoformat())
        pay_to = accounts[r["pay_to"]]["app_name"] if r.get("pay_to") else ""
        if pay_to:
            c2, s2 = "Liability Payments", ""   # the app's fixed category for paying a loan or card
        own = src["currency"]
        amount = abs(r["amount"])
        if r["currency"] != own:   # e.g. a fee in rupees on the dollar account: converted at that day's rate
            rate = rate_on(rates, own, r["date"])
            if not rate:
                problems.append(f"row {r['row']}: the row is in {r['currency']} but the account is in {own} and there is no rate")
                continue
            amount, flag = abs(r["inr"]) / rate, f"entered in {r['currency']} in Money Manager, converted to {own} at the rate of that day"
        if r["row"] in splits:
            parts = splits[r["row"]]
            rest = round(place(amount, own) - sum(x[2] for x in parts if x[2] is not None), 2)
            if rest < 0 or (all(x[2] is not None for x in parts) and rest != 0):
                sys.exit(f"row-overrides.csv: the parts of row {r['row']} do not add up to {place(amount, own)}")
            for part_cat, part_sub, part_amount in parts:
                part_cat, part_sub = renamed(kind, part_cat, part_sub, r["date"].isoformat())
                plan_rows.append(head + [kind, src["app_name"], "", part_amount if part_amount is not None else rest, own, "", part_cat, part_sub,
                                         f"{note} - {part_sub or part_cat}", desc, "split from one Money Manager row"])
            continue
        plan_rows.append(head + [kind, src["app_name"], pay_to, place(amount, own), own, "", c2, s2, note, desc,
                                 flag or ("a negative expense: money given back, saved as income" if r["amount"] < 0 else "")])
    # rows that are not in the Money Manager file, in the app's own names (extra-plan-rows.csv: the same columns as plan.csv), such as the
    # transactions of accounts kept only in the dashboard: renames and CATEGORY row overrides apply to them, and they join the rest by date
    extra_path = os.path.join(args.out, "extra-plan-rows.csv")
    if os.path.exists(extra_path):
        for x in read_csv(extra_path):
            kind, c2, s2 = x["kind"], x["category"], x["sub_category"]
            o = overrides.get(int(x["file_row"]))
            if o and o["action"].strip().upper() == "CATEGORY":
                c2, _, s2 = (part.strip() for part in o["target"].partition(">"))
            c2, s2 = renamed(kind, c2, s2, x["date"]) if c2 else (c2, s2)
            plan_rows.append([int(x["file_row"]), x["date"], kind, x["account_or_from"], x["to_account"], x["amount"], x["currency"] or "INR",
                              x["amount_received"], c2, s2, x["note"], x["description"], x["flag"] or "from the dashboard's own database"])
        plan_rows.sort(key=lambda p: str(p[1]))   # a stable sort: rows of one day keep their order, the extra ones after them
    if problems:
        sys.exit("cannot plan:\n  " + "\n  ".join(problems))
    write_csv(os.path.join(args.out, "plan.csv"),
              ["file_row", "date", "kind", "account_or_from", "to_account", "amount", "currency", "amount_received", "category", "sub_category",
               "note", "description", "flag"], plan_rows)
    write_csv(os.path.join(args.out, "rates.csv"), ["currency", "date", "rupees_per_unit"], [[c, d.isoformat(), f"{v:.8f}"] for c, d, v in rates])

    by_kind = collections.Counter(p[2] for p in plan_rows)
    print(f"{len(plan_rows)} rows planned: {dict(by_kind)}")
    print(f"{len(accounts)} accounts, {len({c[4] for c in cat_rows})} categories, {len({(c[4], c[5]) for c in cat_rows if c[5]})} sub categories, {len(rates)} exchange rates")
    print("flagged rows:", sum(1 for p in plan_rows if p[12]))
    print("files in", os.path.abspath(args.out))


# ------------------------------------------------------------------------------------------------------------------------ apply

class Api:
    def __init__(self, base, password):
        self.base = base.rstrip("/")
        self.auth = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode() if password else ""

    def call(self, method, path, body=None):
        headers = {"Content-Type": "application/json", "X-Requested-With": "FinancialDashboard"}
        if self.auth:
            headers["Authorization"] = self.auth
        req = urllib.request.Request(self.base + path, method=method, data=json.dumps(body).encode() if body is not None else None, headers=headers)
        try:
            with urllib.request.urlopen(req) as resp:
                text = resp.read().decode()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            if e.code == 401:
                sys.exit("The app refused the login (401): set FINANCE_PASSWORD to the dashboard password.")
            raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode()[:300]}")
        except urllib.error.URLError as e:
            sys.exit(f"Cannot reach the app at {self.base} ({e.reason}). Start it with scripts/financial-dashboard.sh moneymanager first.")


def apply(args):
    out = args.out
    for f in ("account-map.csv", "category-map.csv", "plan.csv", "rates.csv"):
        if not os.path.exists(os.path.join(out, f)):
            sys.exit(f"{out}/{f} is missing: run `plan` first")
    password = os.environ.get("FINANCE_PASSWORD") or getpass.getpass("Dashboard password of the Money Manager database (a new one is set if none exists): ")
    api = Api(args.url, password)
    status = api.call("GET", "/api/auth/status")
    if not status["passwordSet"]:
        if not password:
            sys.exit("This database has no password yet: give one (FINANCE_PASSWORD) and it is set now.")
        api.call("POST", "/api/auth/password", {"password": password})
        print("password set")
    if api.call("GET", "/api/accounts") or api.call("GET", "/api/transactions"):
        sys.exit("REFUSED: this database already has accounts or transactions. The import only runs on a new, empty database "
                 "(scripts/financial-dashboard.sh moneymanager), never on your real data.")

    plan_rows = read_csv(os.path.join(out, "plan.csv"))
    used = {p["account_or_from"] for p in plan_rows} | {p["to_account"] for p in plan_rows if p["to_account"]}
    mapped = [a for a in read_csv(os.path.join(out, "account-map.csv")) if a["app_name"] in used]   # only the accounts these rows use
    holding_kind = {a["app_name"]: HOLDING_KINDS[a["type"]] for a in mapped if a["type"] in HOLDING_KINDS}   # funds and shares: holdings, not accounts
    rd_accounts = {a["app_name"]: a for a in mapped if a["type"] == RD_TYPE}   # deposits of the Recurring Deposits card, not accounts
    ledger_names = {a["app_name"] for a in mapped if a["type"] == LEDGER_TYPE}   # the app's Ledger, set up by the app, not created as an account
    for name in ledger_names:
        if name != LEDGER_NAME:
            sys.exit(f"the Ledger account must be called '{LEDGER_NAME}' in account-map.csv, not '{name}'")
    accounts = [a for a in mapped if a["type"] not in HOLDING_KINDS and a["type"] not in (RD_TYPE, LEDGER_TYPE)]
    rates = read_csv(os.path.join(out, "rates.csv"))

    final_opening = {a["app_name"]: float(a.get("opening_balance") or 0) for a in accounts}
    for r in rates:
        api.call("PUT", "/api/exchange-rates", {"currency": r["currency"], "date": r["date"], "rate": float(r["rupees_per_unit"])})
    print(f"{len(rates)} exchange rates saved")

    items = []
    for a in accounts:
        liability = a["type"] in ("CREDIT_CARD", "DEBT")
        # a card or loan keeps the amount owed on the first day as its opening balance; everything else starts with the cushion
        owed = float(a.get("opening_balance") or 0) if a["type"] == "DEBT" else 0
        items.append({"name": a["app_name"], "type": "GOAL_FUND" if a["type"] == EF_TYPE else a["type"], "openingBalance": owed if liability else CUSHION, "currency": a["currency"],
                      "creditLimit": float(a["credit_limit"]) if a["type"] == "CREDIT_CARD" else None,
                      "targetAmount": float(a["target_amount"]) if (a.get("target_amount") or "").strip() else None,
                      "dueDay": int(a["due_day"]) if (a.get("due_day") or "").strip() else None,
                      "statementDay": int(a["statement_day"]) if (a.get("statement_day") or "").strip() else None,
                      "emiAmount": float(a["emi_amount"]) if a["type"] == "DEBT" and a.get("emi_amount") else None})
    made = api.call("POST", "/api/setup/accounts", items)
    for kind in sorted(set(holding_kind.values())):
        api.call("POST", f"/api/portfolios/{kind}/setup")   # the Mutual Funds / Shares account the holdings live in
    rd_ids = {}   # deposit name -> id in the Recurring Deposits card
    if rd_accounts:
        api.call("POST", "/api/recurring-deposits/setup")
        for name, a in rd_accounts.items():
            rows = sorted((p for p in plan_rows if name in (p["account_or_from"], p["to_account"])), key=lambda p: p["date"])
            first = rows[0] if rows else None
            term = int(a.get("rd_term_months") or 60)
            if not a.get("rd_term_months"):
                print(f"  note: the term of '{name}' is a placeholder of 60 months: change it with the pencil on the card")
            view = api.call("POST", "/api/recurring-deposits/deposits", {
                "name": name, "startDate": a.get("rd_start_date") or (first["date"] if first else datetime.date.today().isoformat()),
                "termMonths": term, "monthlyAmount": float(a["rd_monthly_amount"]) if a.get("rd_monthly_amount") else (float(first["amount"]) if first else None),
                "interestRate": float(a["rd_interest_rate"]) if a.get("rd_interest_rate") else None,
                "alreadyDeposited": float(a.get("opening_balance") or 0)})
            rd_ids[name] = next(d["id"] for d in view["open"] if d["name"] == name)
    if ledger_names:
        api.call("POST", "/api/ledger/setup")
    ids = {a["name"]: a["id"] for a in api.call("GET", "/api/accounts")}
    ef_path = os.path.join(out, "emergency-fund.csv")   # the real card's settings, when you have them
    ef_given = read_csv(ef_path)[0] if os.path.exists(ef_path) else None
    for a in accounts:   # the account of type EMERGENCY_FUND is the app's Emergency Fund
        if a["type"] == EF_TYPE and ef_given:
            amount_mode = ef_given["target_mode"].strip().upper() == "AMOUNT"
            api.call("PUT", "/api/emergency-fund", {
                "accountId": ids[a["app_name"]], "targetMode": "AMOUNT" if amount_mode else "MONTHS",
                "targetMonths": None if amount_mode else int(ef_given["target_months"]),
                "targetAmount": float(ef_given["target_amount"]) if amount_mode else None,
                "monthlyEssentialExpenses": float(ef_given["monthly_essential_expenses"] or 0) or None,
                "plannedMonthlyContribution": float(ef_given["planned_monthly_contribution"] or 0) or None})
            print(f"  '{a['app_name']}' is the Emergency Fund, set from emergency-fund.csv")
        elif a["type"] == EF_TYPE:
            api.call("PUT", "/api/emergency-fund", {"accountId": ids[a["app_name"]], "targetMode": "MONTHS", "targetMonths": EF_TARGET_MONTHS,
                                                    "monthlyEssentialExpenses": EF_MONTHLY_EXPENSES})
            print(f"  note: '{a['app_name']}' is the Emergency Fund, with a placeholder target of {EF_TARGET_MONTHS} months of {EF_MONTHLY_EXPENSES:,} a month: change it on the card")
    holding_ids = {}   # (portfolio, holding name) -> id, known once the first Invest has made the holding
    holding_values = {}   # (portfolio, holding name) -> what it is worth now, from the last answer of the app

    def note_holdings(kind, view):
        for h in view["holdings"]:
            holding_ids[(kind, h["name"])] = h["id"]
            holding_values[(kind, h["name"])] = float(h["currentValue"])

    for name, kind in holding_kind.items():   # what a fund already held before the file starts
        already = next((float(a.get("opening_balance") or 0) for a in mapped if a["app_name"] == name), 0)
        if already > 0:
            note_holdings(kind, api.call("POST", f"/api/portfolios/{kind}/holdings", {"name": name, "investedAmount": already, "currentValue": already}))
    # holdings that are in the dashboard's own database (portfolio-holdings.csv: kind, name, quantity, invested, current_value), such as the shares
    ph_path = os.path.join(out, "portfolio-holdings.csv")
    own_holdings = read_csv(ph_path) if os.path.exists(ph_path) else []
    for kind in sorted({h["kind"].strip().upper() for h in own_holdings}):
        api.call("POST", f"/api/portfolios/{kind}/setup")
    for h in own_holdings:
        kind = h["kind"].strip().upper()
        note_holdings(kind, api.call("POST", f"/api/portfolios/{kind}/holdings", {
            "name": h["name"], "investedAmount": float(h["invested"]), "currentValue": float(h["current_value"]),
            "quantity": float(h["quantity"]) if (h.get("quantity") or "").strip() else None}))
    print(f"{len(made)} accounts created" + (f", {len(set(holding_kind.values()))} portfolio set up for {len(holding_kind)} holdings" if holding_kind else "")
          + (f", {len(own_holdings)} holdings from portfolio-holdings.csv" if own_holdings else ""))

    # a new database starts with the app's own categories (Food, Housing, ...): with --clear-starter-categories they go, so only the
    # categories of the file remain (the three fixed ones, Loans Given, Loans Collected and Liability Payments, cannot be removed)
    existing = api.call("GET", "/api/categories")
    if getattr(args, "clear_starter_categories", False):
        removed = 0
        for c in sorted((c for c in existing if not c.get("systemKey")), key=lambda c: c["parentId"] is None):   # sub categories first
            try:
                api.call("DELETE", f"/api/categories/{c['id']}")
                removed += 1
            except RuntimeError:
                pass
        print(f"{removed} starter categories removed")
        existing = api.call("GET", "/api/categories")
    # categories: reuse one that is there already, else make it
    top = {(c["kind"], c["name"].lower()): c["id"] for c in existing if c["parentId"] is None}
    sub = {(c["kind"], c["parentId"], c["name"].lower()): c["id"] for c in existing if c["parentId"] is not None}
    created = 0

    def category_id(kind, c, s):
        nonlocal created
        if (kind, c.lower()) not in top:
            top[(kind, c.lower())] = api.call("POST", "/api/categories", {"name": c, "kind": kind})["id"]
            created += 1
        parent = top[(kind, c.lower())]
        if not s:
            return parent
        if (kind, parent, s.lower()) not in sub:
            sub[(kind, parent, s.lower())] = api.call("POST", "/api/categories", {"name": s, "kind": kind, "parentId": parent})["id"]
            created += 1
        return sub[(kind, parent, s.lower())]

    # money lent to a person and paid back is not an ordinary category: it is the app's fixed Loans Given / Loans Collected with the person as
    # a borrower (one shared list, so the Receivables account and each person's balance follow)
    fixed = {c["systemKey"]: c["id"] for c in existing if c.get("systemKey")}
    bp = os.path.join(out, "borrowers.csv")   # people the dashboard already owed you before: name, opening_amount (what they owed before the first loan row)
    b_rows = read_csv(bp) if os.path.exists(bp) else []
    if b_rows:
        first = api.call("POST", "/api/borrowers", {"name": b_rows[0]["name"], "openingAmount": 0})   # the first person makes the Receivables account
        receivables_id = api.call("GET", "/api/loans")["receivablesAccountId"]
        recv = next(a for a in api.call("GET", "/api/accounts") if a["id"] == receivables_id)
        api.call("PUT", f"/api/accounts/{receivables_id}", {"name": recv["name"], "type": recv["type"], "level": recv["level"], "parentId": recv["parentId"],
                                                            "openingBalance": sum(float(r["opening_amount"] or 0) for r in b_rows), "targetAmount": recv["targetAmount"], "notes": recv["notes"]})
        api.call("PUT", f"/api/borrowers/{first['id']}", {"name": b_rows[0]["name"], "openingAmount": float(b_rows[0]["opening_amount"] or 0)})
        for r in b_rows[1:]:
            api.call("POST", "/api/borrowers", {"name": r["name"], "openingAmount": float(r["opening_amount"] or 0)})
        print(f"{len(b_rows)} people with earlier loans, {sum(float(r['opening_amount'] or 0) for r in b_rows):,.2f} in all")
    borrower_ids = {b["name"].lower(): b["id"] for b in api.call("GET", "/api/loans")["borrowers"]}
    loan_rows = {("EXPENSE", "Loan"): "LOANS_GIVEN", ("INCOME", "Loan Repaid"): "LOANS_COLLECTED"}

    def borrower_id(name):
        if name.lower() not in borrower_ids:
            borrower_ids[name.lower()] = api.call("POST", "/api/borrowers", {"name": name, "openingAmount": 0})["id"]
        return borrower_ids[name.lower()]

    lb_path = os.path.join(out, "ledger-buckets.csv")   # the real Ledger's buckets, when you have them
    ledger_plan = read_csv(lb_path) if os.path.exists(lb_path) else []
    pays = {}   # a bill's name in Money Manager (lower case) -> the bucket that pays it
    for lb in ledger_plan:
        for bill in (lb.get("pays") or "").split(";"):
            if bill.strip():
                pays[bill.strip().lower()] = lb["name"]
    ledger_buckets = {}   # bucket name (lower case) -> id; "buffer" is the Ledger's own Buffer
    if ledger_names:
        ledger_buckets = {b["name"].lower(): b["id"] for b in api.call("GET", "/api/ledger")["buckets"]}
        ledger_buckets["buffer"] = next(b["id"] for b in api.call("GET", "/api/ledger")["buckets"] if b["buffer"])

    failed, saved = [], 0
    for p in plan_rows:
        try:
            if p["kind"] == "TRANSFER" and p["to_account"] in ledger_names:   # a funding of the Ledger, on its day (more than the monthly plan goes to the Buffer)
                api.call("POST", "/api/ledger/fund", {"fromAccountId": ids[p["account_or_from"]], "amount": float(p["amount"]), "date": p["date"]})
                saved += 1
                continue
            if p["account_or_from"] in ledger_names and p["kind"] == "EXPENSE":   # a bill paid from the Ledger: a bucket named after it takes the money from the Buffer, then pays on the day
                bucket = pays.get((p["sub_category"] or p["category"]).lower(), p["sub_category"] or p["category"])
                if bucket.lower() not in ledger_buckets:
                    view = api.call("POST", "/api/ledger/buckets", {"name": bucket, "monthlyAllocation": 0})
                    ledger_buckets[bucket.lower()] = next(b["id"] for b in view["buckets"] if b["name"].lower() == bucket.lower())
                amount = float(p["amount"])
                api.call("POST", "/api/ledger/transfer", {"fromBucketId": ledger_buckets["buffer"], "toBucketId": ledger_buckets[bucket.lower()], "amount": amount})
                api.call("POST", "/api/ledger/pay", {"bucketId": ledger_buckets[bucket.lower()], "amount": amount, "date": p["date"],
                                                      "categoryId": category_id("EXPENSE", p["category"], p["sub_category"]), "note": p["note"] or None})
                saved += 1
                continue
            if p["account_or_from"] in ledger_names and p["kind"] == "INCOME":   # interest the bank credited to the Ledger
                api.call("POST", "/api/ledger/interest", {"amount": float(p["amount"]), "date": p["date"], "categoryId": category_id("INCOME", p["category"], p["sub_category"])})
                saved += 1
                continue
            if p["account_or_from"] in ledger_names:
                raise RuntimeError("only bills paid from the Ledger and interest are imported for it")
            if p["kind"] == "TRANSFER" and p["to_account"] in rd_ids:   # a Deposit into a recurring deposit, on its day
                api.call("POST", f"/api/recurring-deposits/deposits/{rd_ids[p['to_account']]}/deposit",
                         {"fromAccountId": ids[p["account_or_from"]], "amount": float(p["amount"]), "date": p["date"]})
                saved += 1
                continue
            if p["kind"] == "TRANSFER" and p["account_or_from"] in rd_ids:   # money coming out of it closes it: the amount is what came back
                api.call("POST", f"/api/recurring-deposits/deposits/{rd_ids[p['account_or_from']]}/close",
                         {"toAccountId": ids[p["to_account"]], "amountReceived": float(p["amount"]), "date": p["date"]})
                saved += 1
                continue
            if p["kind"] == "TRANSFER" and (p["to_account"] in holding_kind or p["account_or_from"] in holding_kind):
                into = p["to_account"] in holding_kind
                name = p["to_account"] if into else p["account_or_from"]
                kind = holding_kind[name]
                if into:   # Invest: from a bank or cash account into a fund (the first one makes the fund)
                    body = {"fromAccountId": ids[p["account_or_from"]], "amount": float(p["amount"]), "date": p["date"]}
                    if (kind, name) in holding_ids:
                        body["holdingId"] = holding_ids[(kind, name)]
                    else:
                        body["newName"] = name
                    before = holding_values.get((kind, name))   # None until the first Invest makes the fund
                    note_holdings(kind, api.call("POST", f"/api/portfolios/{kind}/invest", body))
                    if before is not None:   # the app leaves a fund's value alone when money goes in; here money put in is worth what it cost, so the graph follows the invested amount
                        note_holdings(kind, api.call("POST", f"/api/portfolios/{kind}/holdings/{holding_ids[(kind, name)]}/value",
                                                     {"currentValue": round(before + float(p["amount"]), 2), "date": p["date"]}))
                else:      # Withdraw: out of a fund into a bank or cash account
                    hid, amount = holding_ids[(kind, name)], float(p["amount"])
                    if amount > holding_values.get((kind, name), 0):   # the sale paid what the fund was worth that day
                        note_holdings(kind, api.call("POST", f"/api/portfolios/{kind}/holdings/{hid}/value", {"currentValue": amount, "date": p["date"]}))
                    note_holdings(kind, api.call("POST", f"/api/portfolios/{kind}/holdings/{hid}/withdraw",
                                                 {"toAccountId": ids[p["to_account"]], "amount": amount, "date": p["date"]}))
                saved += 1
                continue
            body = {"date": p["date"], "kind": p["kind"], "amount": float(p["amount"]), "note": p["note"] or None,
                    "description": p["description"] or None, "fee": None, "borrowerId": None, "tags": None, "toAmount": None,
                    "fromAccountId": None, "toAccountId": None, "categoryId": None}
            if p["kind"] == "INCOME":
                body["toAccountId"] = ids[p["account_or_from"]]
            elif p["kind"] == "EXPENSE":
                body["fromAccountId"] = ids[p["account_or_from"]]
                if p["to_account"]:   # a payment into a loan or card
                    body["toAccountId"] = ids[p["to_account"]]
            else:
                body["fromAccountId"], body["toAccountId"] = ids[p["account_or_from"]], ids[p["to_account"]]
                body["toAmount"] = float(p["amount_received"]) if p["amount_received"] else None
            if (p["kind"], p["category"]) in loan_rows and p["sub_category"]:
                body["categoryId"] = fixed[loan_rows[(p["kind"], p["category"])]]
                body["borrowerId"] = borrower_id(p["sub_category"])
            elif p["kind"] != "TRANSFER":
                body["categoryId"] = category_id(p["kind"], p["category"], p["sub_category"])
            api.call("POST", "/api/transactions", body)
            saved += 1
        except RuntimeError as e:
            failed.append((p["file_row"], str(e)))
    print(f"{saved} transactions saved, {created} categories created, {len(failed)} failed")
    for row, why in failed[:20]:
        print(f"  file row {row}: {why}")

    if ledger_names and ledger_plan:   # the Ledger's buckets as they really are: settings, dates and what each holds (the rest stays in the Buffer)
        view = api.call("GET", "/api/ledger")
        by_name = {b["name"].lower(): b["id"] for b in view["buckets"]}
        buffer_id = next(b["id"] for b in view["buckets"] if b["buffer"])
        text = lambda v: (v or "").strip() or None
        for lb in ledger_plan:
            if lb["name"].lower() == "buffer":
                continue
            body = {"name": lb["name"], "monthlyAllocation": float(lb.get("monthly_allocation") or 0),
                    "paymentAmount": float(lb["payment_amount"]) if text(lb.get("payment_amount")) else None,
                    "frequencyMonths": int(lb["frequency_months"]) if text(lb.get("frequency_months")) else None,
                    "nextPaymentDate": text(lb.get("next_payment_date")), "previousPaymentDate": text(lb.get("previous_payment_date")),
                    "endDate": text(lb.get("end_date"))}
            if lb["name"].lower() in by_name:
                api.call("PUT", f"/api/ledger/buckets/{by_name[lb['name'].lower()]}", body)
            else:
                api.call("POST", "/api/ledger/buckets", body)
        view = api.call("GET", "/api/ledger")
        held = {b["name"].lower(): (b["id"], float(b["balance"])) for b in view["buckets"]}
        for lb in ledger_plan:
            if lb["name"].lower() == "buffer":
                continue
            bucket_id, have = held[lb["name"].lower()]
            change = round(float(lb["balance"] or 0) - have, 2)
            if change > 0:
                api.call("POST", "/api/ledger/transfer", {"fromBucketId": buffer_id, "toBucketId": bucket_id, "amount": change})
            elif change < 0:
                api.call("POST", "/api/ledger/transfer", {"fromBucketId": bucket_id, "toBucketId": buffer_id, "amount": -change})
        view = api.call("GET", "/api/ledger")
        want_buffer = next((float(lb["balance"]) for lb in ledger_plan if lb["name"].lower() == "buffer" and (lb.get("balance") or "").strip()), None)
        left = next(float(b["balance"]) for b in view["buckets"] if b["buffer"])
        print(f"The Ledger's {len(ledger_plan) - 1} buckets are set; the Buffer holds {left:,.2f}" + (f" (the file expects {want_buffer:,.2f})" if want_buffer is not None and abs(left - want_buffer) > 0.005 else ""))

    # what the holdings are worth on a day (portfolio-values.csv: kind, name, current_value, date), when the dashboard has a market value the rows cannot know
    pv = os.path.join(out, "portfolio-values.csv")
    for v in (read_csv(pv) if os.path.exists(pv) else []):
        kind = v["kind"].strip().upper()
        note_holdings(kind, api.call("POST", f"/api/portfolios/{kind}/holdings/{holding_ids[(kind, v['name'])]}/value",
                                     {"currentValue": float(v["current_value"]), "date": (v.get("date") or "").strip() or None}))
    # monthly spending limits (budgets.csv: category, sub_category, amount; an empty category is a limit on all spending)
    bg = os.path.join(out, "budgets.csv")
    for b in (read_csv(bg) if os.path.exists(bg) else []):
        api.call("PUT", "/api/budgets", {"categoryId": category_id("EXPENSE", b["category"].strip(), (b.get("sub_category") or "").strip()) if b["category"].strip() else None,
                                         "amount": float(b["amount"])})
    # the dashboard's own notes (notes.csv: date, title, body, tags separated by |) and reminders (reminders.csv: date, title, repeat NONE / WEEKLY /
    # MONTHLY / YEARLY, done true or false, done_on, ticked_days "day=day it was done" separated by ;), when there are some
    for n in (read_csv(os.path.join(out, "notes.csv")) if os.path.exists(os.path.join(out, "notes.csv")) else []):
        api.call("POST", "/api/notes", {"date": n["date"], "title": n["title"], "body": n["body"], "tags": [t for t in (n.get("tags") or "").split("|") if t]})
    reminder_rows = read_csv(os.path.join(out, "reminders.csv")) if os.path.exists(os.path.join(out, "reminders.csv")) else []
    for r in reminder_rows:
        made_reminder = api.call("POST", "/api/reminders", {"date": r["date"], "title": r["title"], "repeat": (r.get("repeat") or "NONE").strip().upper()})
        if (r.get("done") or "").strip().lower() == "true":
            api.call("PUT", f"/api/reminders/{made_reminder['id']}/done", {"done": True, "doneOn": (r.get("done_on") or "").strip() or None})
        for tick in [t for t in (r.get("ticked_days") or "").split(";") if t.strip()]:
            day, _, done_on = tick.partition("=")
            api.call("PUT", f"/api/reminders/{made_reminder['id']}/occurrences/{day.strip()}/done", {"done": True, "doneOn": done_on.strip() or None})
    if os.path.exists(os.path.join(out, "notes.csv")) or reminder_rows:
        print(f"notes and reminders saved from notes.csv and reminders.csv")

    # the cushion goes: every opening balance back to 0
    for a in api.call("GET", "/api/accounts"):
        if a["type"] not in ("CREDIT_CARD", "DEBT", "RECEIVABLE"):   # Receivables is worked out from the loans, not set
            api.call("PUT", f"/api/accounts/{a['id']}", {"name": a["name"], "type": a["type"], "level": a["level"], "parentId": a["parentId"],
                                                          "openingBalance": final_opening.get(a["name"], a["openingBalance"] if a["name"] in MANAGED_ACCOUNTS else 0), "targetAmount": a["targetAmount"], "notes": a["notes"]})
    print("\nBalances now (an opening balance of 0 unless account-map.csv gave one: set the real balance of 1 January in the app):")
    for a in sorted(api.call("GET", "/api/accounts"), key=lambda a: a["name"]):
        negative = float(a["balance"]) < 0 and a["type"] not in ("CREDIT_CARD", "DEBT")
        print(f"  {a['name']:24} {a['currency']} {float(a['balance']):>16,.2f}   {'(negative: this account needs an opening balance)' if negative else ''}")
    sys.exit(1 if failed else 0)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="mode", required=True)
    a = sub.add_parser("plan")
    a.add_argument("file")
    a.add_argument("--out", default="import-review")
    a.add_argument("--until", help="leave out every row after this day (yyyy-mm-dd)")
    a.add_argument("--extend", help='with --until: accounts that go on to a later day, such as "HDFC,Cash=2026-10-29"')
    a.add_argument("--accounts", help="only the rows of these Money Manager accounts, separated by commas (for example HDFC)")
    a.add_argument("--from-row", type=int, help="keep only the rows from this row of the sheet upwards (the oldest ones, as the export is newest first)")
    b = sub.add_parser("apply")
    b.add_argument("--out", default="import-review")
    b.add_argument("--url", default="http://localhost:8080")
    b.add_argument("--clear-starter-categories", action="store_true", help="delete the app's built-in categories first, keeping only the file's")
    args = p.parse_args()
    plan(args) if args.mode == "plan" else apply(args)


if __name__ == "__main__":
    main()