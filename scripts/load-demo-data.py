#!/usr/bin/env python3
"""Loads ALL the demo data through the running app's API, in one go. By default it covers FIVE YEARS of history with
2 to 3 transactions on EVERY day, so the graphs, the Calendar, the Notes, the net worth line, the Stats, the backups
and the Excel export have a lot to chew on:

  1. 9 accounts (including two vehicles and an apartment) and, every single day, 2 or 3 everyday transactions (groceries, tea, cabs,
     fuel, food delivery, subscriptions, medicines, shopping) from cash, the card and the bank, plus a small refund or
     side income now and then, seven monthly budgets (one on all spending, four on main categories and two on sub categories of Food, set so that one is over, three are close and three have room; last month and the month before are shaped too, so the arrows in Budgets show other states: Entertainment close then over, Transport over, Shopping close), every transaction gets tags worked out by the app from its note and category (the stand-outs below carry hand-written ones), and a few deliberate stand-outs for the Spending Review view (a dining-out surge, a laptop as the biggest expense and six small Household expenses this month, a dental bill last month), and a long multi-paragraph note dated today to try the notes reading view on. On top of that the monthly ones: salary with yearly raises, freelance income, a yearly
     bonus, rent, bills, a car loan EMI, monthly investing, the card bill and a cash top-up. About 5,000 in all.
  2. the Emergency Fund, Education and Vacation goal funds (Vacation ring-fenced), loans to Ravi and Meera spread over
     the years, three expenses split with Teja (one settled, one part settled, one open), two bills Teja paid for you (one part paid back), and the Future Expenses Ledger with 4 buckets. The card's bill is due in five days and one
     Ledger bill in three, so the Coming up bar has something to show
  3. diary notes with tags: one or two on EVERY day of the period (about 1,500 a year)
  4. your own reminders: a few done ones every month for the whole period, two late ones and several coming up (today,
     in a few days, next month), and twelve special ones that repeat (every year: birthdays, an anniversary, an insurance renewal; every month: the rent,
     an SIP check, the EMI, the card bill; every week: a spending review, the grocery list), so the Coming up bar, the Calendar marks and the ticking all have something to show
  5. 4 mutual funds and 4 shares, each with a monthly value history for the whole period (for the Stats graphs), plus
     half-yearly dividends on the shares
  6. Jewellery: thirteen pieces of gold, silver, platinum and diamond (ornaments, coins, a bar), one chain bought from the bank
     account and part of the loose gold sold at a profit, so the Jewellery card, the Assets fold-out and the allocation ring have something to show

    python3 scripts/load-demo-data.py [http://localhost:8080] [--years 5]

Run it with the app running on an EMPTY database (stop the app, delete the data/ folder, then ./gradlew bootRun), and
preferably on a separate one so real data is never touched:
    ./gradlew bootRun --args='--spring.datasource.url=jdbc:h2:file:./data/demo'
A step that finds its data already there is skipped with a message, and the next step still runs. The dashboard
password is read from FINANCE_PASSWORD, or asked for (press Enter if no password has been set yet). It makes roughly
8,500 API calls over five years (about 7,000 over four), so allow a minute or two.
"""
import argparse, base64, calendar, getpass, json, os, random, sys, urllib.error, urllib.request
from collections import defaultdict
from datetime import date, timedelta

today = date.today()
BASE = "http://localhost:8080"
AUTH = ""
MONTHS = 60            # months of history, set from --years


class Skip(Exception):
    """A step that cannot run (its data is already there, or the app refused a call)."""


def call(method, path, body=None):
    headers = {"Content-Type": "application/json", "X-Requested-With": "FinancialDashboard"}   # changes must say they come from here
    if AUTH:                       # no password set yet: send no login at all
        headers["Authorization"] = AUTH
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urllib.request.urlopen(req) as r:
            text = r.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        if e.code == 401:
            sys.exit("The app refused the login (401). Set FINANCE_PASSWORD to the dashboard password, or leave it blank "
                     "only if no password has been set yet.")
        raise Skip(f"{method} {path} failed: {e.read().decode()}")
    except urllib.error.URLError as e:
        sys.exit(f"Cannot reach the app at {BASE} ({e.reason}). Start it with ./gradlew bootRun first.")


def on(months_ago, dom):
    y, m = today.year, today.month - months_ago
    while m < 1:
        y, m = y - 1, m + 12
    return date(y, m, min(dom, 28))


def months_from_now(n):
    y, m = today.year, today.month + n
    while m > 12:
        y, m = y + 1, m - 12
    return date(y, m, min(today.day, 28))


def categories():
    return {c["name"]: c["id"] for c in call("GET", "/api/categories")}


# The app starts with a short list of categories. The demo thinks in finer ones, so each maps to one of those, and the finer
# name becomes the note (a transaction in "Food" with the note "Groceries").
CATEGORY_OF = {
    "Base pay": "Salary", "Bonus": "Salary", "Freelance": "Business", "Sales": "Business",
    "Reimbursement": "Other Income", "Interest": "Investment Income",
    "Rent": "Housing", "Electricity": "Bills", "Internet": "Bills", "Mobile": "Bills",
    "Groceries": "Food", "Dining out": "Food", "Delivery": "Food",
    "Fuel": "Transport", "Public transport": "Transport", "Cab": "Transport",
    "Doctor": "Health", "Medicines": "Health", "Insurance premium": "Health",
    "Clothes": "Shopping", "Electronics": "Shopping", "Household": "Shopping",
    "Movies": "Entertainment", "Subscriptions": "Entertainment", "Travel": "Entertainment",
}


# The finer names that get a real sub category under their starter category (name: (starter category, kind)), so the lists, Stats and
# the Budgets groups have sub categories to show. Names not here ("Reimbursement") are filed straight under the starter category.
SUB_OF = {
    "Groceries": ("Food", "EXPENSE"), "Dining out": ("Food", "EXPENSE"), "Delivery": ("Food", "EXPENSE"),
    "Fuel": ("Transport", "EXPENSE"), "Cab": ("Transport", "EXPENSE"), "Public transport": ("Transport", "EXPENSE"),
    "Clothes": ("Shopping", "EXPENSE"), "Electronics": ("Shopping", "EXPENSE"), "Household": ("Shopping", "EXPENSE"),
    "Movies": ("Entertainment", "EXPENSE"), "Subscriptions": ("Entertainment", "EXPENSE"), "Travel": ("Entertainment", "EXPENSE"),
    "Doctor": ("Health", "EXPENSE"), "Medicines": ("Health", "EXPENSE"), "Insurance premium": ("Health", "EXPENSE"),
    "Electricity": ("Bills", "EXPENSE"), "Internet": ("Bills", "EXPENSE"), "Mobile": ("Bills", "EXPENSE"),
    "Rent": ("Housing", "EXPENSE"),
    "Base pay": ("Salary", "INCOME"), "Bonus": ("Salary", "INCOME"),
    "Freelance": ("Business", "INCOME"), "Sales": ("Business", "INCOME"),
    "Interest": ("Investment Income", "INCOME"),
}


def tx(cat_id, day, kind, src, dst, amount, category=None, note=None, borrower=None, tags=None, splits=None, paid_by=None, own_share=None):
    if day > today:
        return
    sub_id = cat_id.get(category) if category in SUB_OF else None
    if category in CATEGORY_OF:
        note = note or category
        category = CATEGORY_OF[category]
    call("POST", "/api/transactions", dict(date=day.isoformat(), kind=kind, fromAccountId=src, toAccountId=dst,
                                           amount=amount, fee=None, categoryId=sub_id or cat_id.get(category), note=note,
                                           description=None, borrowerId=borrower, tags=tags, splits=splits, paidByBorrowerId=paid_by, ownShare=own_share))   # tags left out (None): the app works them out from the note and the category


def year_of(back):
    """0 for the oldest year of the history, 1 for the next, and so on (the oldest month is back = MONTHS - 1)."""
    return (MONTHS - 1 - back) // 12


def grown(amount, back, yearly):
    """An amount that rises by `yearly` (0.06 = 6%) at the start of each year of the history, rounded to 100."""
    return round(amount * (1 + yearly) ** year_of(back), -2)


# ------------------------------------------------------------------------------------------------- 1. accounts

EMI = 15000

# Everyday spending, one of these (picked by weight) for each of the 2 or 3 transactions of a day:
# (paid from, category, lowest, highest, note, weight). Amounts rise 5% a year.
DAILY = [
    ("cash", "Groceries", 120, 650, "Vegetables and milk", 3),
    ("cash", "Dining out", 150, 700, "Tea and snacks", 3),
    ("cash", "Cab", 80, 450, "Auto", 2),
    ("cash", "Fuel", 300, 900, None, 1),
    ("cash", "Public transport", 40, 220, "Metro", 2),
    ("card", "Delivery", 200, 1100, "Food delivery", 3),
    ("card", "Dining out", 300, 1800, None, 2),
    ("card", "Cab", 150, 900, None, 1),
    ("card", "Movies", 400, 1200, None, 1),
    ("card", "Subscriptions", 99, 799, None, 1),
    ("card", "Clothes", 600, 3200, None, 1),
    ("card", "Electronics", 500, 4500, None, 0.3),
    ("card", "Household", 250, 1800, None, 1),
    ("phonepe", "Dining out", 100, 600, "Cafe", 0.6),
    ("phonepe", "Cab", 60, 350, "Auto via the app", 0.5),
    ("phonepe", "Mobile", 199, 399, "Recharge", 0.1),
    ("paytm", "Public transport", 40, 200, "Metro card", 0.5),
    ("paytm", "Fuel", 300, 800, "Petrol pump", 0.3),
    ("paytm", "Household", 100, 700, None, 0.4),
    ("amazon", "Household", 200, 1500, "Amazon order", 0.5),
    ("amazon", "Clothes", 400, 2500, "Amazon order", 0.3),
    ("amazon", "Subscriptions", 99, 499, "Prime", 0.1),
    ("bank", "Groceries", 250, 1400, "UPI", 3),
    ("bank", "Medicines", 120, 900, None, 1),
    ("bank", "Mobile", 199, 599, "Recharge", 0.3),
    ("bank", "Household", 200, 1500, None, 1),
]
SMALL_INCOME = [("Reimbursement", 800, 6000, "Expense claim"), ("Sales", 1500, 9000, "Old things sold"),
                ("Freelance", 2000, 9000, "Small job")]


def month_days(back):
    """Every date of the month `back` months ago, up to today."""
    first = on(back, 1)
    last = date(first.year, first.month, calendar.monthrange(first.year, first.month)[1])
    day = first
    while day <= min(last, today):
        yield day
        day += timedelta(days=1)


def set_budgets(cat_id, this_month, this_month_subs):
    """Monthly budgets set from what this month has cost so far, so the Budgets view shows every state: Food is over its limit,
    Shopping and the limit on all spending are close, Entertainment and Transport have room. Skips a category with nothing spent yet.
    Dining out and Groceries, sub categories of Food, get their own limits too (Dining out close to it, Groceries with room), next to Food's.
    Returns the limit set for each category name (None is the limit on all spending)."""
    emi = EMI if on(0, 10) <= today else 0                     # the loan EMI is a Liability Payments expense and counts as spending
    everything = sum(this_month.values()) + emi
    wanted = [(None, everything * 1.08), ("Food", this_month["Food"] * 0.85), ("Shopping", this_month["Shopping"] * 1.12),
              ("Entertainment", this_month["Entertainment"] * 2), ("Transport", this_month["Transport"] * 1.4),
              ("Dining out", this_month_subs["Dining out"] * 1.15), ("Groceries", this_month_subs["Groceries"] * 1.3)]
    limits = {}
    for name, amount in wanted:
        amount = round(amount, -2)
        if amount > 0:
            call("PUT", "/api/budgets", dict(categoryId=cat_id.get(name) if name else None, amount=amount))
            limits[name] = amount
    return limits


# (months back, category, note, share of the budget the month should reach): the earlier months get their own story, so stepping back
# with the arrows in Budgets shows other states than this month. A month already past its target is left alone.
BUDGET_HISTORY = [
    (1, "Entertainment", "Concert tickets", 0.92),    # last month: close to the limit
    (1, "Transport", "Airport transfers", 1.12),      # last month: over
    (2, "Entertainment", "Weekend trip", 1.18),       # the month before: over
    (2, "Shopping", "Wedding shopping", 0.85),        # the month before: close
]


def shape_budget_history(limits, pay, bank, by_month):
    """Tops up earlier months with one expense each (from the bank) so they reach the share of that category's budget in BUDGET_HISTORY."""
    for back, category, note, share in BUDGET_HISTORY:
        mapped = CATEGORY_OF.get(category, category)
        day = on(back, 24)
        needed = round(limits.get(mapped, 0) * share) - by_month[(day.year, day.month)][mapped]
        if needed > 0:
            pay(day, bank, round(needed, -1), category, note)


def accounts_and_transactions():
    if call("GET", "/api/accounts"):
        raise Skip("this database already has accounts. Start the app on an empty database.")
    acc = lambda name, type, opening: dict(name=name, type=type, level=None, parentName=None,
                                           openingBalance=opening, targetAmount=None, creditLimit=None)
    created = call("POST", "/api/setup/accounts", [
        acc("Savings Bank", "BANK", 300000),
        acc("Cash", "CASH", 40000),
        acc("PhonePe Wallet", "WALLET", 10000),
        acc("Paytm Wallet", "WALLET", 10000),
        acc("Amazon Pay", "WALLET", 10000),
        acc("Index Fund", "INVESTMENT", 200000),
        acc("Apartment", "PROPERTY", 4200000),
        dict(acc("Honda City", "VEHICLE", 650000), numberPlate="MH12 AB 1234", yearBought=2019),
        dict(acc("Royal Enfield", "VEHICLE", 140000), numberPlate="MH14 CD 4321", yearBought=2022),
        dict(acc("Car Loan", "DEBT", EMI * MONTHS + 60000), emiAmount=EMI),   # a fixed loan; still owing something after the last EMI
        dict(name="Travel Card", type="CREDIT_CARD", level=None, parentName=None, openingBalance=0,
             targetAmount=None, creditLimit=150000, dueDay=(today + timedelta(days=5)).day,   # so "Coming up" has a bill to show
             statementDay=(today - timedelta(days=15)).day),   # the statement is generated 20 days before the bill is due
    ])
    id_of = {a["name"]: a["id"] for a in created}
    bank, cash, fund, card, loan = (id_of[n] for n in ("Savings Bank", "Cash", "Index Fund", "Travel Card", "Car Loan"))
    wallets = {"phonepe": id_of["PhonePe Wallet"], "paytm": id_of["Paytm Wallet"], "amazon": id_of["Amazon Pay"]}
    where = {"bank": bank, "cash": cash, "card": card, **wallets}
    cat_id = categories()
    rnd = random.Random(7)
    spent = defaultdict(float)          # (account, year, month) -> what was spent from it, for the card bill and the top-up
    by_month = defaultdict(lambda: defaultdict(float))   # (year, month) -> category -> what was spent on it, to set and shape the budgets
    by_sub = defaultdict(lambda: defaultdict(float))     # the same for the finer names that have a sub category (SUB_OF)
    for sub_name, (parent, kind) in SUB_OF.items():
        cat_id[sub_name] = call("POST", "/api/categories", dict(name=sub_name, kind=kind, parentId=cat_id[parent]))["id"]

    def pay(day, account, amount, category, note=None, tags=None):
        if day <= today:
            tx(cat_id, day, "EXPENSE", account, None, amount, category, note, tags=tags)
            spent[(account, day.year, day.month)] += amount
            by_month[(day.year, day.month)][CATEGORY_OF.get(category, category)] += amount
            if category in SUB_OF:
                by_sub[(day.year, day.month)][category] += amount

    count = 0
    for back in range(MONTHS - 1, -1, -1):             # oldest month first; the current month is partial
        year = year_of(back)
        previous = on(back + 1, 1)                      # the month before, for the card bill and the cash top-up
        # ---- the monthly ones
        tx(cat_id, on(back, 1), "INCOME", None, bank, grown(130000, back, 0.06), "Base pay", "Salary")
        tx(cat_id, on(back, 14), "INCOME", None, bank, rnd.randint(15, 32) * 1000, "Freelance", "Side projects")
        if on(back, 1).month == 3:
            tx(cat_id, on(back, 20), "INCOME", None, bank, grown(120000, back, 0.06), "Bonus", "Yearly bonus")
        if back % 3 == 0:
            tx(cat_id, on(back, 12), "INCOME", None, bank, 9500, "Interest")
        pay(on(back, 2), bank, grown(28000, back, 0.05), "Rent")
        pay(on(back, 4), bank, rnd.randint(2800, 3600), "Electricity")
        pay(on(back, 5), bank, 1100, "Internet")
        for d in (6, 13, 20, 27):
            pay(on(back, d), bank, rnd.randint(2200, 4200), "Groceries", "Weekly shop")
        if back % 6 == 5:
            pay(on(back, 15), card, rnd.randint(18000, 26000), "Travel")
        if back % 5 == 1:
            pay(on(back, 18), bank, rnd.randint(4000, 9000), "Doctor")
        if back % 12 == 3:
            pay(on(back, 25), bank, rnd.randint(12000, 20000), "Insurance premium")
        tx(cat_id, on(back, 10), "EXPENSE", bank, loan, EMI, "Liability Payments", "Car loan EMI")   # what the loan's Pay button records
        tx(cat_id, on(back, 11), "TRANSFER", bank, fund, grown(20000, back, 0.05))            # monthly investing
        if back < MONTHS - 1:
            tx(cat_id, on(back, 21), "TRANSFER", bank, card, round(spent[(card, previous.year, previous.month)]))   # card bill
            tx(cat_id, on(back, 1), "TRANSFER", bank, cash, round(spent[(cash, previous.year, previous.month)]))    # cash top-up
            for wallet in wallets.values():                                                                          # wallet top-ups, a bank transfer too
                used = round(spent[(wallet, previous.year, previous.month)])
                if used > 0:
                    tx(cat_id, on(back, 3), "TRANSFER", bank, wallet, used)
        if back % 2 == 0:                                                                                            # a little cashback, an ordinary income
            tx(cat_id, on(back, 16), "INCOME", None, wallets["phonepe"], rnd.randint(20, 90), "Reimbursement", "Cashback")
        # ---- every day: 2 or 3 everyday transactions, and now and then a small income
        for day in month_days(back):
            for _ in range(rnd.choice([2, 2, 3])):
                paid_from, category, low, high, note, _w = rnd.choices(DAILY, weights=[row[5] for row in DAILY])[0]
                amount = round(rnd.randint(low, high) * 1.05 ** year)
                pay(day, where[paid_from], amount, category, note)
            if rnd.random() < 0.1:
                category, low, high, note = rnd.choice(SMALL_INCOME)
                tx(cat_id, day, "INCOME", None, bank, rnd.randint(low, high), category, note)
        # ---- something for the Spending Review view to find (no random numbers, so the data above is unchanged): this month eating out
        # runs well above its usual, one large Shopping purchase stands out (it is also the month's biggest expense) and six small Household expenses
        # (under Rs 500) make the frequent-small-expenses insight; last month has a large Health bill and an early Health check-up (so Health
        # fell this month)
        if back == 0:
            for day in month_days(0):
                if day.day <= 8:
                    pay(day, card, 1500 + 150 * day.day, "Dining out", "Team dinner", tags=["team-dinner", "office", "dining-out"])
            pay(on(0, min(3, today.day)), bank, 52000, "Electronics", "Laptop", tags=["laptop", "work", "big-purchase"])
            for day in month_days(0):                                           # six small expenses in one category: the "frequent small expenses" insight
                if day.day <= 6:
                    pay(day, cash, 90 + 25 * day.day, "Household", "Stationery and supplies", tags=["stationery", "household", "small-spend"])
        if back == 1:
            pay(on(1, 15), bank, 38000, "Doctor", "Dental work", tags=["dental", "health", "family"])
            pay(on(1, 3), bank, 14000, "Doctor", "Health check-up", tags=["health-check", "health"])   # early last month: Health then FELL against the same days of this month
    # The wallets are topped up each month with the month BEFORE's spending, so this month's spending would leave them below their opening
    # balance, and a wallet that is small can go negative late in the month. Top each up with what it has spent so far this month too.
    for wallet in wallets.values():
        used = round(spent[(wallet, today.year, today.month)])
        if used > 0:
            tx(cat_id, on(0, min(3, today.day)), "TRANSFER", bank, wallet, used)
    limits = set_budgets(cat_id, by_month[(today.year, today.month)], by_sub[(today.year, today.month)])
    shape_budget_history(limits, pay, bank, by_month)
    budgets_set = len(limits)
    return f"{len(created)} accounts (three of them wallets: PhonePe, Paytm, Amazon Pay) and {MONTHS} months of transactions, 2 to 3 every day, from {on(MONTHS - 1, 1)} to today, and {budgets_set} monthly budgets."


# ------------------------------------------------------------------------------------------------- 2. funds

def funds():
    accounts = {a["name"]: a for a in call("GET", "/api/accounts")}
    if "Savings Bank" not in accounts:
        raise Skip("there is no Savings Bank account yet (the accounts step did not run).")
    if "Emergency Fund" in accounts or "Education Fund" in accounts:
        raise Skip("the funds already exist in this database.")
    bank = accounts["Savings Bank"]["id"]
    cat_id = categories()

    made = call("POST", "/api/setup/accounts", [
        dict(name="Education Fund", type="GOAL_FUND", level=None, parentName=None, openingBalance=20000,
             targetAmount=150000, creditLimit=None),
        dict(name="Vacation Fund", type="GOAL_FUND", level=None, parentName=None, openingBalance=10000,
             targetAmount=60000, creditLimit=None),
        dict(name="", type="RECEIVABLE", level=None, parentName=None, openingBalance=0, targetAmount=None,
             creditLimit=None),
    ])
    ids = {a["name"]: a["id"] for a in made}
    education, vacation = ids["Education Fund"], ids["Vacation Fund"]
    ef = call("PUT", "/api/emergency-fund", dict(accountId=None, openingBalance=60000, targetMode="MONTHS",
                                                 targetMonths=6, targetAmount=None, monthlyEssentialExpenses=35000,
                                                 plannedMonthlyContribution=10000))
    oldest = MONTHS - 1
    # The Emergency Fund (target 210,000), Education Fund (150,000) and Vacation Fund (60,000) are filled up to their
    # targets over the first months of the history and then left alone.
    for i in range(15):
        tx(cat_id, on(oldest - i, 11), "TRANSFER", bank, ef["accountId"], 10000)
    for i in range(17):
        tx(cat_id, on(oldest - i, 12), "TRANSFER", bank, education, 8000)
    for i in range(5):
        tx(cat_id, on(oldest - i, 13), "TRANSFER", bank, vacation, 10000)   # 10,000 + 5 x 10,000 reaches the target
    call("POST", f"/api/ring-fences/{vacation}")

    ravi = call("POST", "/api/borrowers", dict(name="Ravi", openingAmount=0))["id"]
    meera = call("POST", "/api/borrowers", dict(name="Meera", openingAmount=0))["id"]
    at = lambda fraction: int(MONTHS * fraction)
    tx(cat_id, on(MONTHS - 6, 3), "EXPENSE", bank, None, 30000, "Loans Given", "For his deposit", ravi)
    tx(cat_id, on(at(0.7), 6), "EXPENSE", bank, None, 15000, "Loans Given", "Medical bills", meera)
    tx(cat_id, on(at(0.55), 8), "INCOME", None, bank, 10000, "Loans Collected", None, ravi)
    tx(cat_id, on(at(0.4), 9), "INCOME", None, bank, 5000, "Loans Collected", None, meera)
    tx(cat_id, on(at(0.2), 8), "INCOME", None, bank, 10000, "Loans Collected", None, ravi)
    tx(cat_id, on(at(0.1), 4), "EXPENSE", bank, None, 20000, "Loans Given", "Course fees", ravi)

    # Expenses shared with a friend: the bill is saved in full from the bank and Teja's half is owed under Receivables. The cottage is settled in full,
    # the dinner in part (so its row shows what is left), and the movie night is still open. They use a category with no budget, so the budget story is unchanged.
    teja = call("POST", "/api/borrowers", dict(name="Teja", openingAmount=0))["id"]
    tx(cat_id, on(2, 14), "EXPENSE", bank, None, 6000, "Other Expense", "Weekend cottage with Teja", splits={str(teja): 3000})
    tx(cat_id, on(1, 9), "EXPENSE", bank, None, 2400, "Other Expense", "Group dinner with Teja", splits={str(teja): 1200})
    tx(cat_id, on(0, min(2, today.day)), "EXPENSE", bank, None, 1180, "Other Expense", "Movie night with Teja", splits={str(teja): 590})
    for day, amount in ((on(2, 20), 3000), (on(1, 12), 800)):
        call("POST", f"/api/borrowers/{teja}/settle-splits", dict(date=day.isoformat(), toAccountId=bank, amount=amount, expenseIds=None, note=None))
    # The other way round: Teja paid for a taxi and a lunch (no account of yours moves; your share is what you owe him), and you paid part of it back.
    tx(cat_id, on(1, 20), "EXPENSE", None, None, 1800, "Other Expense", "Airport taxi paid by Teja", paid_by=teja, own_share=900)
    tx(cat_id, on(0, min(2, today.day)), "EXPENSE", None, None, 700, "Other Expense", "Lunch paid by Teja", paid_by=teja, own_share=350)
    call("POST", f"/api/borrowers/{teja}/repay-splits", dict(date=on(1, 24).isoformat(), fromAccountId=bank, amount=400, expenseIds=None, note=None))

    # The Ledger writes its transactions on the day they are made, so its history starts today.
    call("POST", "/api/ledger/setup")
    bucket = lambda name, alloc, pay, every, prev, nxt, held: call("POST", "/api/ledger/buckets", dict(
        name=name, monthlyAllocation=alloc, paymentAmount=pay, frequencyMonths=every,
        previousPaymentDate=prev.isoformat(), nextPaymentDate=nxt.isoformat(), endDate=None, heldNow=held))
    bucket("Car insurance", 2000, 24000, 12, on(6, 15), months_from_now(6), 12000)
    bucket("Property tax", 1500, 9000, 6, on(3, 10), months_from_now(3), 4500)
    bucket("Mobile plan", 300, 600, 1, today - timedelta(days=27), today + timedelta(days=3), 600)   # due in 3 days: shows under "Coming up"
    view = bucket("Annual trip", 4000, 48000, 12, on(2, 20), months_from_now(10), 8000)
    for _ in range(5):
        call("POST", "/api/ledger/fund", dict(fromAccountId=bank, amount=view["monthlyPlan"], allocations=None))
    buckets = {b["name"]: b["id"] for b in call("GET", "/api/ledger")["buckets"]}
    call("POST", "/api/ledger/pay", dict(bucketId=buckets["Property tax"], amount=9000))
    call("POST", "/api/ledger/interest", dict(amount=350))
    call("POST", "/api/ledger/transfer", dict(fromBucketId=buckets["Car insurance"], toBucketId=buckets["Annual trip"],
                                              amount=1000))
    return ("Emergency Fund, Education Fund, Vacation Fund (ring-fenced), loans to Ravi and Meera, three expenses split with Teja (one settled, one part settled, one open), two bills Teja paid for you (one part paid back) and the Ledger "
            "with 4 buckets.")


# ------------------------------------------------------------------------------------------------- 3. notes

NOTES = [
    ("Salary came in", "Salary credited. Moved the usual amount to the index fund and paid the card bill.", ["salary", "investing"]),
    ("Rent paid", "Paid the rent. The landlord mentioned a small increase from next year.", ["rent", "home"]),
    ("Insurance", "Premium reminder for the car and health policies. Check the renewal date before the month ends.", ["insurance", "car"]),
    ("Groceries", "Big monthly shop. Prices are up again, worth comparing the two supermarkets.", ["groceries"]),
    ("Doctor visit", "Routine check-up, everything fine. Medicines for a month.", ["health"]),
    ("Car service", "Took the car for its service. Tyres need changing by the next one.", ["car"]),
    ("Trip planning", "Looked at flights for the annual trip. Prices are lowest about three months ahead.", ["trip", "ledger"]),
    ("Tax", "Gathered the documents for the tax return: salary slips, interest certificates, investment proofs.", ["tax"]),
    ("Investing", "Reviewed the mutual funds. The small cap fund is lagging; staying the course for now.", ["investing", "mutual-funds"]),
    ("Dividend", "A dividend arrived from one of the shares. Added it to the savings.", ["shares", "dividend"]),
    ("Gadget", "Bought a new phone. Kept the bill for the warranty.", ["gadgets"]),
    ("Loan", "Paid the car loan EMI. About two more years to go at this rate.", ["car", "loan"]),
    ("Ravi", "Ravi paid back part of what I lent him. Noted it under Loans Collected.", ["loans", "family"]),
    ("Budget", "Went through the month's spending. Dining out and cab rides crept up.", ["budget"]),
    ("Savings goal", "The Education Fund is on track. Vacation Fund reached its target and is ring-fenced.", ["goals", "education"]),
    ("Electricity bill", "The bill was higher than usual because of the AC. Try the timer next month.", ["bills", "home"]),
    ("Internet", "Router acting up again. Called the provider, they promised a visit on Saturday.", ["bills", "home"]),
    ("Cash check", "Counted the cash in the wallet against the app. Off by a few rupees, fine.", ["cash", "budget"]),
    ("Card statement", "Went through the card statement line by line. One duplicate charge to dispute.", ["card", "bills"]),
    ("Festival shopping", "Gifts and clothes for the festival. Stayed inside the budget this time.", ["shopping", "family"]),
    ("Fuel", "Fuel prices went up again. Thinking about carpooling on weekdays.", ["car", "budget"]),
    ("Medicines", "Monthly medicines picked up. The pharmacy gave a discount for the bulk order.", ["health"]),
    ("Gold", "Looked at the gold price. Not buying now, keeping the money in the fund.", ["gold", "investing"]),
    ("Vehicle", "Renewed the bike's insurance online. Saved the receipt in the folder.", ["insurance", "bike"]),
    ("Freelance", "Finished the side project and sent the invoice. Payment expected in two weeks.", ["freelance", "salary"]),
    ("Ledger", "Funded the Future Expenses Ledger. Car insurance and property tax are covered for the year.", ["ledger", "goals"]),
    ("Learning", "Paid for an online course. Will count it under education at tax time.", ["education", "tax"]),
    ("Home repair", "The plumber fixed the leak. Cheaper than I feared, and a good number for next time.", ["home", "repairs"]),
    ("Weekend", "Weekend trip with the family. Kept the receipts to add the expenses tonight.", ["trip", "family"]),
    ("Review", "Quarterly review: net worth is up, debt is down. Nothing to change.", ["review", "budget"]),
]
LONG_NOTE = ("Monthly money review",
             "Sat down with the month's numbers.\n\nSpending: eating out is well above its usual and the laptop was a big one-off, so the month will end over budget. "
             "Small things add up too: stationery and supplies came to six little purchases in the first week.\n\nPlans: move the extra savings into the Education Fund, "
             "check the SIP dates, and look at the card statement before the due date.\n\nNext month: cook at home on weekdays and keep the dining out for the weekend.",
             ["review", "budget", "goals"])
EXTRA = ["", "", "Will check again next week.", "Need to remember this at tax time.", "Told the family about it.",
         "Keeping the paper copy in the folder.", "Good to write this down so I do not forget.", "Not urgent, but worth a look soon.",
         "Compared with last year, this is about the same.", "A bit more than I planned, still fine."]


def notes():
    if call("GET", "/api/notes?size=1")["total"]:
        raise Skip("this database already has notes.")
    rnd = random.Random(11)
    start = on(MONTHS - 1, 1)
    days = [start + timedelta(days=i) for i in range((today - start).days + 1)]
    count = 0
    for day in days:                                                    # every single day has one or two notes
        for _ in range(rnd.choice([1, 1, 2])):
            title, body, tags = rnd.choice(NOTES)
            body = (body + " " + rnd.choice(EXTRA)).strip()
            if rnd.random() < 0.04:                                     # now and then a longer, multi-paragraph note
                body += "\n\n" + rnd.choice(NOTES)[1] + "\n\n" + rnd.choice(NOTES)[1]
            if day == today and _ == 0:                                 # today's first note is a long one, to try the reading view on
                title, body, tags = LONG_NOTE
            call("POST", "/api/notes", dict(date=day.isoformat(), title=title, body=body, tags=tags))
            count += 1
    return f"{count} diary notes with tags, one or two on each of {len(days)} days over {MONTHS} months."


# ------------------------------------------------------------------------------------------------- 4. reminders

REMINDERS = ["Renew the car insurance", "Call the CA about the tax return", "Submit the rent receipts", "Check the SIP dates",
             "Pay the society maintenance", "Collect the Form 16", "Review the credit card statement", "Top up the FASTag",
             "Book the annual health check-up", "Download the bank statement", "Renew the broadband plan", "Update the nominee details",
             "Check the electricity bill", "Pay the property tax", "Compare the FD rates", "File the GST invoice"]


def occurrences(first, repeat, until):
    """Every day a repeating reminder falls on from its first day to `until`, as the page works them out (a short month uses its last day)."""
    days, k = [], 0
    while True:
        if repeat == "WEEKLY":
            d = first + timedelta(days=7 * k)
        else:
            months = k if repeat == "MONTHLY" else 12 * k
            y, m = first.year + (first.month - 1 + months) // 12, (first.month - 1 + months) % 12 + 1
            last = (date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1)).day
            d = date(y, m, min(first.day, last))
        if d > until:
            return days
        days.append(d)
        k += 1


def reminders():
    if call("GET", "/api/reminders"):
        raise Skip("this database already has reminders.")
    rnd = random.Random(23)
    start = on(MONTHS - 1, 1)
    days = [start + timedelta(days=i) for i in range((today - start).days - 14)]     # done ones: everything but the last two weeks
    by_month = {}
    for d in days:
        by_month.setdefault((d.year, d.month), []).append(d)
    done = 0
    for key in sorted(by_month):                                                      # two to four a month, on random days
        for day in sorted(rnd.sample(by_month[key], k=min(rnd.choice([2, 3, 4]), len(by_month[key])))):
            made = call("POST", "/api/reminders", dict(date=day.isoformat(), title=rnd.choice(REMINDERS)))
            ticked = min(day + timedelta(days=rnd.choice([0, 0, 1, 2])), today)         # ticked on its day or a little later, so the days spread over the years
            call("PUT", f"/api/reminders/{made['id']}/done", dict(done=True, doneOn=ticked.isoformat()))
            done += 1
    # Still to do: a few that fill the Coming up bar (two late, today, soon), then random ones over the next five months.
    open_ones = [(-9, "Collect the Form 16"), (-2, "Renew the broadband plan"), (0, "Call the CA about the tax return"),
                 (1, "Pay the society maintenance"), (3, "Renew the car insurance"), (6, "Top up the FASTag"),
                 (20, "Book the annual health check-up"), (45, "Pay the property tax")]
    open_ones += [(rnd.randint(8, 150), rnd.choice(REMINDERS)) for _ in range(10)]
    for offset, title in open_ones:
        call("POST", "/api/reminders", dict(date=(today + timedelta(days=offset)).isoformat(), title=title))
    # Special ones that repeat. Each is dated with its first day (a birth date, a wedding day, the first rent day), comes back
    # by itself and is never ticked or late. The first two or three land inside the Coming up window.
    def first_day(year, when):
        try:
            return date(year, when.month, when.day).isoformat()           # the real day: a birthday on the 30th stays on the 30th
        except ValueError:                                                  # 29 February in a year that has none
            return f"{year}-{when.month:02d}-28"
    yearly = [(today + timedelta(days=4), 1990, "Asha's birthday"), (today - timedelta(days=40), 2015, "Wedding anniversary"),
              (today + timedelta(days=200), 1962, "Mother's birthday"), (today + timedelta(days=rnd.randint(8, 300)), 1958, "Father's birthday"),
              (today + timedelta(days=rnd.randint(8, 300)), 2000, "Ravi's birthday"), (today + timedelta(days=rnd.randint(8, 300)), 2021, "Car insurance renewal")]
    for when, year, title in yearly:
        made = call("POST", "/api/reminders", dict(date=first_day(year, when), title=title, repeat="YEARLY"))
        first = date.fromisoformat(first_day(year, when))
        for occurrence in occurrences(first, "YEARLY", today - timedelta(days=1)):
            if occurrence >= on(MONTHS - 1, 1) and rnd.random() < 0.9:               # the birthdays and the renewal within the period
                call("PUT", f"/api/reminders/{made['id']}/occurrences/{occurrence.isoformat()}/done", dict(done=True, doneOn=occurrence.isoformat()))
    # They start years back, so their ticked days spread over the whole period. The last two weeks are left unticked.
    monthly = [(on(MONTHS - 1, min((today + timedelta(days=2)).day, 28)), "Pay the rent"), (on(MONTHS - 1, min((today + timedelta(days=5)).day, 28)), "Check the SIP dates"),
               (on(MONTHS - 6, rnd.randint(1, 28)), "Car loan EMI"), (on(MONTHS - 12, rnd.randint(1, 28)), "Pay the card bill")]
    weekly = [(today - timedelta(days=min(7 * 52 * 3, MONTHS * 30)), "Review the week's spending"), (today - timedelta(days=min(7 * 52 * 2 + rnd.randint(7, 60), MONTHS * 30)), "Plan the grocery list")]
    ticked = 0
    for first, title, repeat in [(f, t, "MONTHLY") for f, t in monthly] + [(f, t, "WEEKLY") for f, t in weekly]:
        made = call("POST", "/api/reminders", dict(date=first.isoformat(), title=title, repeat=repeat))
        for occurrence in occurrences(first, repeat, today - timedelta(days=14)):
            if rnd.random() < 0.8:                                                   # most days were ticked, a few were missed
                ticked_on = min(occurrence + timedelta(days=rnd.choice([0, 0, 1])), today)
                call("PUT", f"/api/reminders/{made['id']}/occurrences/{occurrence.isoformat()}/done", dict(done=True, doneOn=ticked_on.isoformat()))
                ticked += 1
    return (f"{done} reminders done at random over the months, {len(open_ones)} still open (two late, the rest from today to five months ahead), "
            f"and {len(yearly) + len(monthly) + len(weekly)} special ones that repeat (about {ticked} of their days ticked off over the years; the yearly ones too): {len(yearly)} every year (birthdays, an anniversary, "
            f"an insurance renewal), {len(monthly)} every month and {len(weekly)} every week.")


# ------------------------------------------------------------------------------------------------- 5. portfolios

# (name, quantity: units or shares, invested, value now): to the paisa, the way a broker shows them
DEMO = {"MUTUAL_FUNDS": [("Nifty 50 index fund", 4821.356, 120000, 151500), ("Flexi cap fund", 1632.84, 80000, 93200),
                         ("Small cap fund", 902.115, 50000, 44800), ("Liquid fund", 11.482, 30000, 30900)],
        "SHARES": [("Infosys", 42, 60234.55, 78412.9), ("HDFC Bank", 28, 45118.2, 41207.35), ("Tata Motors", 31, 25087.65, 33806.1),
                   ("ITC", 47, 20041.05, 21489.7)]}


def portfolios():
    bank = next((a["id"] for a in call("GET", "/api/accounts") if a["name"] == "Savings Bank"), None)
    done = []
    for kind, holdings in DEMO.items():
        P = "/api/portfolios/" + kind
        if call("GET", P)["holdings"]:
            print(f"   {kind}: already has holdings, skipped.")
            continue
        call("POST", P + "/setup")
        for name, quantity, invested, value in holdings:
            call("POST", P + "/holdings", dict(name=name, quantity=quantity, investedAmount=invested, currentValue=value))
        # A value for every month of the history, oldest first, climbing (unevenly) to today's value.
        for h in call("GET", P)["holdings"]:
            current, invested = float(h["currentValue"]), float(h["investedAmount"])
            for m in range(MONTHS, 0, -1):
                progress = (MONTHS - m) / MONTHS
                wobble = 1 + ((h["id"] * 7 + m * 13) % 9 - 4) / 100
                value = round(current * (0.55 + 0.45 * progress) * wobble, 2)
                when = (today - timedelta(days=30 * m)).isoformat()
                call("POST", f"{P}/holdings/{h['id']}/value", dict(currentValue=value, investedAmount=invested, date=when))
            call("POST", f"{P}/holdings/{h['id']}/value", dict(currentValue=current, investedAmount=invested))
        if bank:
            call("POST", P + "/invest", dict(fromAccountId=bank, amount=10000))     # from Savings Bank, into Total invested
        if kind == "SHARES" and bank:      # dividends twice a year, linked to the shares that paid them
            for h in call("GET", P)["holdings"]:
                for m in range(MONTHS - 2, 0, -6):
                    pct = 0.012 + 0.003 * ((h["id"] + m) % 2)
                    when = (today - timedelta(days=30 * m + h["id"] % 5)).isoformat()
                    call("POST", f"{P}/holdings/{h['id']}/dividend",
                         dict(toAccountId=bank, amount=round(float(h["investedAmount"]) * pct), date=when))
        done.append(f"{len(holdings)} {kind.replace('_', ' ').lower()}")
    if not done:
        raise Skip("Mutual Funds and Shares already have holdings.")
    return (" and ".join(done) + f", with {MONTHS} months of value history"
            + (" and half-yearly dividends on the shares." if "shares" in " ".join(done) else "."))


# ------------------------------------------------------------------------------------------------- 6. jewellery

# (kind, name, type, grams, price paid): what is kept at home. No current value is kept for jewellery.
JEWELLERY = [("Gold", "Wedding necklace", "ORNAMENT", 40.5, 252340.5), ("Gold", "Bangles (pair)", "ORNAMENT", 32.25, 198750),
             ("Gold", "Mangalsutra", "ORNAMENT", 18.8, 119900.25), ("Gold", "10 g coin", "COIN", 10, 61250), ("Gold", "5 g coin", "COIN", 5, 33100.75),
             ("Gold", "Loose gold", "LOOSE", 6.3, 40870.4), ("Silver", "Pooja plate", "ORNAMENT", 210, 21540),
             ("Silver", "Anklets (pair)", "ORNAMENT", 96.4, 8650.8), ("Silver", "100 g bar", "BAR", 100, 9120), ("Platinum", "Ring", "OTHER", 5.2, 31500),
             ("Diamond", "Solitaire ring", "ORNAMENT", 3.2, 185000), ("Diamond", "Stud earrings", "ORNAMENT", 2.4, 96500)]


def jewellery():
    if call("GET", "/api/jewellery")["configured"]:
        raise Skip("Jewellery is already set up.")
    call("POST", "/api/jewellery/setup")
    for metal, name, form, grams, price in JEWELLERY:
        call("POST", "/api/jewellery/items", dict(metal=metal, name=name, form=form, weight=grams, boughtPrice=price))
    bank = next((a["id"] for a in call("GET", "/api/accounts") if a["name"] == "Savings Bank"), None)
    if bank:       # one paid for now, and a part of the loose gold sold at a profit (both move money in the bank account)
        call("POST", "/api/jewellery/items", dict(metal="Gold", name="Chain (new)", form="ORNAMENT", weight=12.4, boughtPrice=45000, paidFromAccountId=bank))
        loose = next(i for m in call("GET", "/api/jewellery")["metals"] for i in m["items"] if i["name"] == "Loose gold")
        call("POST", f"/api/jewellery/items/{loose['id']}/sell", dict(toAccountId=bank, salePrice=14800, weight=2))
    return f"{len(JEWELLERY) + 1} pieces of gold, silver, platinum and diamond, one bought from the bank and part of the loose gold sold."


STEPS = [("Accounts and transactions", accounts_and_transactions),
         ("Emergency Fund, goal funds, loans and Ledger", funds),
         ("Diary notes with tags", notes),
         ("Reminders", reminders),
         ("Mutual funds and shares", portfolios),
         ("Jewellery", jewellery)]


def run(years=5):
    """Runs every step; returns True when none was skipped."""
    global MONTHS
    MONTHS = 12 * years
    skipped = False
    for title, step in STEPS:
        print(f"== {title}")
        try:
            print("   Loaded:", step())
        except Skip as why:
            print(f"   Skipped: {why}")
            skipped = True
    print("\nDone." + (" Some steps were skipped (see above)." if skipped else ""), "Hard-refresh the dashboard (Cmd+Shift+R).")
    return not skipped


def main():
    global BASE, AUTH
    parser = argparse.ArgumentParser(description="Load demo data through the running app's API.")
    parser.add_argument("base", nargs="?", default=BASE, help="the app's address (default %(default)s)")
    parser.add_argument("--years", type=int, default=5, choices=range(1, 11), metavar="N",
                        help="years of history to create, 1 to 10 (default %(default)s)")
    args = parser.parse_args()
    BASE = args.base
    password = os.environ.get("FINANCE_PASSWORD") or getpass.getpass("Dashboard password (leave blank if none is set yet): ")
    AUTH = "Basic " + base64.b64encode(f"admin:{password}".encode()).decode() if password else ""
    run(args.years)


if __name__ == "__main__":
    main()
