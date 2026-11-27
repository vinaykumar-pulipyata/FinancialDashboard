/* Transactions panel: list, kind filter and pagination. The date range comes from the period filter. */

import { ADJUSTMENT_KEYS, KIND_LABELS, MONTH_NAMES } from "../core/constants.js";
import { accountCurrency, ownAmount, splitShares, toRupees, txnCurrency } from "../core/currency.js";
import { accounts, allCategories, creditLimits, jewellery, ledger, loans, portfolios, recurringDeposits, transactions } from "../core/state.js";
import { $, el, formatDate, inCurrency, isoDay, riseOnce, rupees, takeFlash } from "../core/util.js";
import { isLedgerFunding, ledgerFundingDelete } from "./ledger.js";
import { investmentDelete, isInvestment } from "./portfolio.js";
import { isRdDeposit, rdDepositDelete } from "./recurring-deposits.js";
import { isLiabilityPayment, openLiabilityPayment } from "./liability-payment.js";
import { inPeriod, setSharedRange } from "./period-filter.js";
import { openTransaction } from "./transaction-form.js";
import { txnTagPills } from "./transaction-tags.js";
import { showWorkspaceView } from "./workspace.js";

let txnFilter = "ALL";
let txnPage = 1;
let txnPageSize = 10;
/* The search box and the filters under the chips: words that must all appear, a category (with its sub categories), an
   account (either side of the transaction), a range of amounts and one tag (chosen from the row of tags above the list). */
let txnQuery = { text: "", category: "", account: "", min: null, max: null, tag: "" };
let txnTagsAll = false;   // the row of tags shows the most used ones; "+ N more" shows them all

function accountName(id) {
    const a = accounts.find(a => a.id === id);
    return a ? a.name : `Account #${id}`;
}

/* What every account held (or, for a debt or a credit card, owed) just after each transaction that touched it: the opening balance, plus
   money in, less money out (fees included), in date order (then id). The same rule as the balance on the Accounts view, so the last figure
   of an account is that balance. Receivables, Jewellery and the Mutual Funds and Shares accounts are left out: their balance is worked out
   from loans, items or holdings, not from these transactions. Worked out once per load (the lists are replaced on a refresh) and keyed
   "transactionId:accountId". */
let runningFor = { transactions: null, accounts: null, map: new Map() };
function balanceAfter(t, accountId) {
    if (runningFor.transactions !== transactions || runningFor.accounts !== accounts) {
        const skip = new Set([jewellery, ...Object.values(portfolios)].filter(m => m && m.configured).map(m => m.accountId));
        const held = new Map(accounts.filter(a => a.type !== "RECEIVABLE" && !skip.has(a.id)).map(a => [a.id, Number(a.openingBalance) || 0]));
        const liability = new Set(accounts.filter(a => a.type === "CREDIT_CARD" || a.type === "DEBT").map(a => a.id));
        const map = new Map();
        for (const x of [...transactions].sort((p, q) => p.date.localeCompare(q.date) || p.id - q.id)) {
            const out = Number(x.amount) + Number(x.fee || 0);
            if (held.has(x.fromAccountId)) {   // money out lowers what an account holds and raises what a debt or card owes
                held.set(x.fromAccountId, held.get(x.fromAccountId) + (liability.has(x.fromAccountId) ? out : -out));
                map.set(`${x.id}:${x.fromAccountId}`, held.get(x.fromAccountId));
            }
            if (held.has(x.toAccountId)) {
                held.set(x.toAccountId, held.get(x.toAccountId) + (liability.has(x.toAccountId) ? -Number(x.toAmount ?? x.amount) : Number(x.toAmount ?? x.amount)));   // what arrived, when the currencies differ
                map.set(`${x.id}:${x.toAccountId}`, held.get(x.toAccountId));
            }
        }
        runningFor = { transactions, accounts, map };
    }
    return runningFor.map.get(`${t.id}:${accountId}`);
}

/* The amount of the statement a credit card generates on `statementIso`: its opening balance plus everything spent on it (fees included)
   BEFORE that day, less every payment into it before that day. Transactions dated on the statement day itself belong to the next
   statement. It includes what was left unpaid from earlier statements, not only the purchases of the last cycle. */
export function cardStatementAmount(card, statementIso) {
    let owed = Number(card.openingBalance) || 0;
    for (const t of transactions) {
        if (t.date >= statementIso) continue;
        if (t.fromAccountId === card.id) owed += Number(t.amount) + Number(t.fee || 0);
        if (t.toAccountId === card.id) owed -= Number(t.amount);
    }
    return owed;
}

/* The Ledger, Mutual Funds, Shares, Recurring Deposits and Jewellery are used through their own buttons: the server refuses to edit or delete a
   transaction on them, so their rows get no pencil. */
function managedAccountIds() {
    return [ledger, jewellery, recurringDeposits, ...Object.values(portfolios)].filter(m => m && m.configured).map(m => m.accountId);
}

function touchesLedger(t) {
    return managedAccountIds().some(id => t.fromAccountId === id || t.toAccountId === id);
}

function personName(id) {
    const b = loans.borrowers.find(b => b.id === id);
    return b ? b.name : "";
}

function categoryName(id) {
    const c = allCategories.find(c => c.id === id);
    if (!c) return "";
    const parent = c.parentId != null && allCategories.find(p => p.id === c.parentId);
    return parent ? `${parent.name} › ${c.name}` : c.name;
}

/* The category and sub category columns: a sub category has its parent as the category; a top-level one has no sub category. */
function categoryParts(id) {
    const c = allCategories.find(c => c.id === id);
    if (!c) return ["", ""];
    const parent = c.parentId != null && allCategories.find(p => p.id === c.parentId);
    return parent ? [parent.name, c.name] : [c.name, ""];
}

/* 1234567.5 -> "12,34,567.5", the Indian way (the last three digits, then pairs), without the slow locale formatting. */
function groupedAmount(amount) {
    const [whole, decimals] = String(amount).split(".");
    const last3 = whole.slice(-3), rest = whole.slice(0, -3);
    return (rest ? rest.replace(/\B(?=(\d{2})+(?!\d))/g, ",") + "," : "") + last3 + (decimals ? "." + decimals : "");
}

const txnHaystacks = new WeakMap();   // a transaction -> its search text; a refresh brings new objects, so it never goes stale

/* Everything a transaction can be found by, as one lower-case line: its words, names, kind, date and amount. Built once per
   transaction (searching 10,000 of them while typing must stay quick). */
function txnHaystack(t) {
    let text = txnHaystacks.get(t);
    if (text === undefined) {
        const [year, month, day] = t.date.split("-");
        const date = `${t.date} ${day} ${MONTH_NAMES[Number(month) - 1]} ${year}`;
        const amount = `${t.amount} ${groupedAmount(t.amount)} ₹${groupedAmount(t.amount)}`;
        text = [t.note, t.description, (t.tags || []).join(" "), categoryName(t.categoryId), t.borrowerId ? personName(t.borrowerId) : "", splitShares(t).length ? "split " + splitShares(t).map(s => personName(s.personId)).join(" ") : "", t.paidByBorrowerId != null ? "paid by " + personName(t.paidByBorrowerId) : "",
            t.fromAccountId != null ? accountName(t.fromAccountId) : "", t.toAccountId != null ? accountName(t.toAccountId) : "",
            KIND_LABELS[t.kind], date, amount].join(" ").toLowerCase();
        txnHaystacks.set(t, text);
    }
    return text;
}

/* Does the transaction pass the search and the filters? ("" and null mean no limit.) */
function txnMatchesQuery(t, q = txnQuery) {
    if (q.category === "none") {
        if (t.categoryId != null || t.kind === "TRANSFER") return false;          // "Uncategorised"
    } else if (q.category) {
        const id = Number(q.category);
        const c = allCategories.find(c => c.id === t.categoryId);
        if (!c || (c.id !== id && c.parentId !== id)) return false;                 // the category itself, or one of its sub categories
    }
    if (q.account && t.fromAccountId !== Number(q.account) && t.toAccountId !== Number(q.account)) return false;
    if (q.tag && !(t.tags || []).includes(q.tag)) return false;
    const amount = Number(t.amount);
    if (q.min != null && amount < q.min) return false;
    if (q.max != null && amount > q.max) return false;
    const words = q.text.trim().toLowerCase().split(/\s+/).filter(Boolean);
    if (words.length) {
        const haystack = txnHaystack(t);
        if (!words.every(word => haystack.includes(word))) return false;
    }
    return true;
}

const txnQueryActive = (q = txnQuery) => Boolean(q.text.trim() || q.category || q.account || q.min != null || q.max != null || q.tag);

/* The two drop-downs, rebuilt from the data and keeping the choice made. Categories are listed with their sub categories
   under them; "Uncategorised" and every account follow. */
function fillTxnFilterOptions() {
    const category = $("txnCategory"), account = $("txnAccount");
    category.innerHTML = "";
    category.append(el("option", { value: "", textContent: "Any category" }), el("option", { value: "none", textContent: "Uncategorised" }));
    const tops = allCategories.filter(c => c.parentId == null).sort((a, b) => a.name.localeCompare(b.name));
    for (const top of tops) {
        category.append(el("option", { value: String(top.id), textContent: top.name }));
        allCategories.filter(c => c.parentId === top.id).sort((a, b) => a.name.localeCompare(b.name))
            .forEach(sub => category.append(el("option", { value: String(sub.id), textContent: `${top.name} › ${sub.name}` })));
    }
    category.value = txnQuery.category;
    account.innerHTML = "";
    account.append(el("option", { value: "", textContent: "Any account" }));
    [...accounts].sort((a, b) => a.name.localeCompare(b.name))
        .forEach(a => account.append(el("option", { value: String(a.id), textContent: a.name })));
    account.value = txnQuery.account;
}

/* Wires the search box and the filters. */
export function initTransactionSearch() {
    const changed = () => {
        txnQuery = { text: $("txnSearch").value, category: $("txnCategory").value, account: $("txnAccount").value,
            min: $("txnMin").value === "" ? null : Number($("txnMin").value), max: $("txnMax").value === "" ? null : Number($("txnMax").value),
            tag: txnQuery.tag };   // the tag is chosen in the row above, not in these boxes
        txnPage = 1;
        renderTransactions();
    };
    let timer = null;
    $("txnSearch").addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(changed, 200); });   // not on every key at once
    for (const id of ["txnCategory", "txnAccount", "txnMin", "txnMax"]) $(id).addEventListener("change", changed);
    $("txnExport").addEventListener("click", exportTransactions);
    $("txnClear").addEventListener("click", () => {
        $("txnSearch").value = ""; $("txnMin").value = ""; $("txnMax").value = "";
        $("txnCategory").value = ""; $("txnAccount").value = "";
        txnQuery.tag = "";
        changed();
    });
}

/* Every transaction the list stands for (all pages): the kind chip, the shared range, the search and the filters. */
function visibleTransactions() {
    return transactions.filter(t => (txnFilter === "ALL" || t.kind === txnFilter) && inPeriod(t) && txnMatchesQuery(t));
}

/* Opens the Transactions view on exactly the transactions behind a figure: any of an account (either side), a category
   (with its sub categories) and a kind, in the range from..to. The search words and amounts are cleared. */
export function showTransactionsFor({ account = "", category = "", kind = "ALL", from = "", to = "" } = {}) {
    txnQuery = { text: "", category: category === "" ? "" : String(category), account: account === "" ? "" : String(account), min: null, max: null, tag: "" };
    for (const id of ["txnSearch", "txnMin", "txnMax"]) $(id).value = "";
    txnFilter = kind;
    txnPage = 1;
    setSharedRange(from, to);          // redraws everything, the list included
    showWorkspaceView("transactions");
}

/* Opens the Transactions view on the transactions that carry a tag (every other filter is cleared). */
function showTransactionsTagged(tag) {
    showTransactionsFor();
    txnQuery.tag = tag;
    renderTransactions();
}

const TAG_BAR_SHOWN = 20;   // how many tags the row above the list shows before "+ N more"

/* The row of tags above the list, as in Notes: each tag with how many of the listed transactions carry it (the other filters, the kind
   and the range are applied, the tag itself is not), the most used first. The tag the list is narrowed to is filled in, and clicking
   it again clears it. */
function renderTxnTagBar() {
    const bar = $("txnTags");
    bar.innerHTML = "";
    const counts = new Map();
    for (const t of transactions) {
        if ((txnFilter !== "ALL" && t.kind !== txnFilter) || !inPeriod(t) || !txnMatchesQuery(t, { ...txnQuery, tag: "" })) continue;
        for (const tag of t.tags || []) counts.set(tag, (counts.get(tag) || 0) + 1);
    }
    const ranked = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
    const shown = txnTagsAll ? ranked : ranked.slice(0, TAG_BAR_SHOWN);
    if (txnQuery.tag && !shown.some(([tag]) => tag === txnQuery.tag)) shown.unshift([txnQuery.tag, counts.get(txnQuery.tag) || 0]);
    bar.classList.toggle("hidden", shown.length === 0);
    for (const [tag, count] of shown) {
        const on = tag === txnQuery.tag;
        const pill = el("button", { className: "tag-pill" + (on ? " active" : ""), type: "button",
            title: on ? "Show all transactions again" : "Show only the transactions tagged " + tag }, "#" + tag + " ",
            el("span", { className: "tag-count", textContent: count ? "(" + count + ")" : "✕" }));
        pill.addEventListener("click", () => { txnQuery.tag = on ? "" : tag; txnPage = 1; renderTransactions(); });
        bar.append(pill);
    }
    if (ranked.length > TAG_BAR_SHOWN) {
        const more = el("button", { className: "tag-more", type: "button", textContent: txnTagsAll ? "show fewer" : `+ ${ranked.length - TAG_BAR_SHOWN} more` });
        more.addEventListener("click", () => { txnTagsAll = !txnTagsAll; renderTransactions(); });
        bar.append(more);
    }
}

/* One CSV cell: quoted, with " doubled. A cell a spreadsheet would read as a formula (=, +, -, @, tab, return) gets a
   leading ' so opening the file can never run anything. */
function csvCell(value) {
    let text = value == null ? "" : String(value);
    if (/^[=+\-@\t\r]/.test(text)) text = "'" + text;
    return '"' + text.replace(/"/g, '""') + '"';
}

/* The rows as CSV text: a header, then one line per transaction, in the order of the list (newest first). Amounts are plain numbers. */
function transactionsCsv(rows) {
    const lines = [["Date", "Type", "Category", "Person", "From", "To", "Amount", "Fee", "Note", "Description", "Tags", "Currency", "Amount received", "Received in"].map(csvCell).join(",")];
    for (const t of rows) {
        lines.push([t.date, KIND_LABELS[t.kind], categoryName(t.categoryId), t.borrowerId ? personName(t.borrowerId) : "",
            t.fromAccountId != null ? accountName(t.fromAccountId) : "", t.toAccountId != null ? accountName(t.toAccountId) : "",
            t.amount, t.fee || 0, t.note, t.description, (t.tags || []).join(" "),
            txnCurrency(t), t.toAmount ?? "", t.toAmount != null ? accountCurrency(t.toAccountId) : ""].map(csvCell).join(","));
    }
    return "\uFEFF" + lines.join("\r\n") + "\r\n";       // the BOM makes Excel read ₹ and accents as UTF-8
}

function exportTransactions() {
    const rows = visibleTransactions();
    const url = URL.createObjectURL(new Blob([transactionsCsv(rows)], { type: "text/csv;charset=utf-8" }));
    const link = el("a", { href: url, download: `transactions-${isoDay(new Date())}.csv` });
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function renderTransactions() {
    const chips = $("txnFilters");
    chips.innerHTML = "";
    for (const [value, label] of [["ALL", "All"], ["INCOME", "Income"], ["EXPENSE", "Expense"], ["TRANSFER", "Transfer"]]) {
        const chip = el("button", { className: "chip" + (txnFilter === value ? " active" : ""), textContent: label });
        chip.addEventListener("click", () => { txnFilter = value; txnPage = 1; renderTransactions(); });
        chips.append(chip);
    }

    const list = $("txnList");
    const pager = $("txnPager");
    list.innerHTML = "";
    pager.classList.add("hidden");

    fillTxnFilterOptions();
    renderTxnTagBar();
    $("txnClear").classList.toggle("hidden", !txnQueryActive());
    const rows = visibleTransactions();
    renderTxnFound(rows);
    if (rows.length === 0) {
        list.append(el("div", { className: "empty-state",
            textContent: transactions.length === 0 ? "No transactions yet. Use Income, Expense or Transfer to record one."
                : "No transactions match these filters." }));
        return;
    }

    const pages = Math.ceil(rows.length / txnPageSize);
    txnPage = Math.min(Math.max(txnPage, 1), pages);
    const start = (txnPage - 1) * txnPageSize;
    const shown = rows.slice(start, start + txnPageSize);

    list.append(el("div", { className: "txn-row head" },
        el("span", { textContent: "Date" }), el("span", { textContent: "Type" }),
        el("span", { textContent: "Category" }), el("span", { textContent: "Sub category" }),
        el("span", { textContent: "Note" }), el("span", { textContent: "Account" }), el("span", { className: "amt", textContent: "Amount" }),
        el("span")));
    for (const t of shown) list.append(txnRowElement(t));

    renderPager(pager, rows.length, pages, start, shown.length);
}

/* The account column. A line under it gives the remaining balance of each account on either side as it stood after this transaction, so a
   transfer shows both: "Savings Bank remaining balance ₹1,23,456". A debt says what is still owed, and a credit card the balance left on
   it (its limit less what has been withdrawn), or the amount withdrawn when it has no limit. */
function accountCell(t, where) {
    const cell = el("span", { className: "acct", textContent: where });
    for (const id of new Set([t.fromAccountId, t.toAccountId])) {
        const account = id != null && accounts.find(a => a.id === id);
        const after = account ? balanceAfter(t, account.id) : undefined;
        if (after === undefined) continue;
        let text;
        if (account.type === "CREDIT_CARD") {
            const limit = creditLimits.get(account.id);   // the balance left on the card is its limit less what has been withdrawn; with no limit set only the amount owed is known
            text = limit > 0 ? `${account.name} remaining balance ${rupees(limit - after)}` : `${account.name} withdrawal ${rupees(after)}`;
        } else {
            text = account.type === "DEBT" ? `${account.name} remaining owed ${rupees(after)}` : `${account.name} remaining balance ${inCurrency(after, account.currency)}`;
        }
        cell.append(el("div", { className: "sub", textContent: text }));
    }
    return cell;
}

/* One row of the list. The calendar reuses it for the transactions of a chosen day. */
export function txnRowElement(t) {
    const kind = t.kind.toLowerCase();
    const [category, subCategory] = categoryParts(t.categoryId);
    const type = el("span", {}, el("span", { className: "kind-badge " + kind, textContent: KIND_LABELS[t.kind] }));
    // The note is the headline; the description and the person (for a loan) go underneath.
    const note = el("span", { className: "note", textContent: t.note || (t.description ? "" : "—") });
    if (t.note ? t.description : "") note.append(el("div", { className: "sub", textContent: t.description }));
    else if (!t.note && t.description) note.textContent = t.description;
    if (t.borrowerId) note.append(el("div", { className: "sub", textContent: personName(t.borrowerId) }));
    const shares = t.kind === "EXPENSE" ? splitShares(t) : [];
    if (shares.length) note.append(el("div", { className: "sub split-line", textContent: "Split with " + shares.map(s => `${personName(s.personId)} ${rupees(s.share)}`).join(", ") }));
    if (t.tags && t.tags.length) note.append(txnTagPills(t.tags, showTransactionsTagged));

    const where = t.kind === "INCOME" ? `to ${accountName(t.toAccountId)}`
        : t.kind === "EXPENSE" && t.toAccountId != null ? `${accountName(t.fromAccountId)} → ${accountName(t.toAccountId)}`   // a payment towards a loan or card
        : t.kind === "EXPENSE" && t.paidByBorrowerId != null ? `paid by ${personName(t.paidByBorrowerId)}`   // somebody else paid: no account of yours
        : t.kind === "EXPENSE" ? `from ${accountName(t.fromAccountId)}`
        : `${accountName(t.fromAccountId)} → ${accountName(t.toAccountId)}`;
    const sign = t.kind === "INCOME" ? "+" : t.kind === "EXPENSE" ? "−" : "";
    const currency = txnCurrency(t);   // the amount and the fee are in the currency of the account they belong to
    const amount = el("span", { className: "amt " + kind, textContent: sign + inCurrency(t.amount, currency) });
    if (shares.length || (t.kind === "EXPENSE" && t.paidByBorrowerId != null)) amount.append(el("div", { className: "sub split-own", textContent: `your share ${rupees(ownAmount(t))}` }));
    if (t.fee) amount.append(el("div", { className: "sub", textContent: `fee ${inCurrency(t.fee, currency)}` }));
    if (t.toAmount != null) amount.append(el("div", { className: "sub received", textContent: `received ${inCurrency(t.toAmount, accountCurrency(t.toAccountId))}` }));

    const edit = el("button", { className: "icon-btn", textContent: "✏️", title: "Edit or delete" });
    edit.addEventListener("click", () => (isLiabilityPayment(t) ? openLiabilityPayment(accounts.find(a => a.id === t.toAccountId), t) : openTransaction(t.kind, t))
        .catch(e => alert(e.message)));

    const rowKey = "txn-row-" + t.id;
    const flash = takeFlash(rowKey);
    const row = riseOnce(el("div", { className: "txn-row" },
        el("span", { className: "date", textContent: formatDate(t.date) }), type,
        el("span", { className: "cat", textContent: category }), el("span", { className: "cat", textContent: subCategory }),
        note, accountCell(t, where), amount,
        touchesLedger(t) ? (isLedgerFunding(t) ? ledgerFundingDelete(t) : isInvestment(t) ? investmentDelete(t) : isRdDeposit(t) ? rdDepositDelete(t) : el("span")) : edit), rowKey);   // the rest of these transactions are managed on the card of the Ledger, portfolio or Jewellery
    if (flash && !row.classList.contains("rise")) row.classList.add("flash");   // a saved change lights up; a new row rises instead
    row.dataset.txnId = t.id;
    return row;
}

/* While searching or filtering: how many were found and what came in and went out (transfers are not counted). */
function renderTxnFound(rows) {
    const box = $("txnFound");
    box.innerHTML = "";
    const show = txnQueryActive() && rows.length > 0;
    box.classList.toggle("hidden", !show);
    if (!show) return;
    let income = 0, expense = 0, foreign = 0;
    for (const row of rows) {
        const c = row.categoryId == null ? null : allCategories.find(c => c.id === row.categoryId);
        if (c && ADJUSTMENT_KEYS.has(c.systemKey)) continue;   // a balance adjustment only corrects a balance
        const t = toRupees(row);   // the totals are in rupees: a foreign amount is converted at the rate of its day
        if (!t) { if (row.kind !== "TRANSFER") foreign++; continue; }
        if (t.kind === "INCOME") income += Number(t.amount);
        else if (t.kind === "EXPENSE") expense += ownAmount(t);   // an expense split with people counts at the user's own share
    }
    box.append(el("strong", { textContent: `${rows.length} ${rows.length === 1 ? "transaction" : "transactions"}` }), " found · ",
        el("span", { className: "in", textContent: "In " + rupees(income) }), "  ",
        el("span", { className: "out", textContent: "Out " + rupees(expense) }));
    if (foreign) box.append(el("span", { className: "foreign-found", textContent: `  (${foreign} in a foreign currency with no exchange rate, not counted)` }));
}

function renderPager(pager, total, pages, start, count) {
    pager.classList.remove("hidden");
    pager.innerHTML = "";
    const prev = el("button", { className: "btn btn-secondary", textContent: "‹ Previous", disabled: txnPage <= 1 });
    const next = el("button", { className: "btn btn-secondary", textContent: "Next ›", disabled: txnPage >= pages });
    prev.addEventListener("click", () => { txnPage--; renderTransactions(); });
    next.addEventListener("click", () => { txnPage++; renderTransactions(); });

    const size = el("select", { className: "page-size" },
        ...[10, 25, 50, 100].map(n => el("option", { value: n, textContent: `${n} per page`, selected: n === txnPageSize })));
    size.addEventListener("change", () => { txnPageSize = Number(size.value); txnPage = 1; renderTransactions(); });

    pager.append(
        el("span", { textContent: `Showing ${start + 1}–${start + count} of ${total}` }),
        el("div", { className: "pager-controls" }, size, prev, el("span", { textContent: `Page ${txnPage} of ${pages}` }), next));
}

/* Setters: a file that imports a variable cannot assign to it, so the files that replace it call these. */
export function setTxnPage(value) { txnPage = value; }
