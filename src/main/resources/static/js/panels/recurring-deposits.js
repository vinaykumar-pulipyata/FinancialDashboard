/* Recurring Deposits: a fixed investment in Level 3 beside Mutual Funds, Shares and Jewellery. One account, "Recurring Deposits", holds
   any number of deposits. Each has a term (a start day and a number of months), optionally an instalment and a yearly rate, and is
   opened, put money into and closed, never deleted, so its history stays. A bar shows how far through its term it is, and the transfers
   that belong to it sit under it in a fold that starts closed. An open deposit counts at what was put in; the interest arrives as income
   when it is closed. The dialogs are the Ledger's, reused. */

import { refreshData } from "../core/refresh.js";
import { accounts, recurringDeposits, transactions } from "../core/state.js";
import { api, el, formatDate, growsOnce, isoDay, rupees2, setBarWidth } from "../core/util.js";
import { dlg, ledgerFail, num, numberInput, openLedgerDialog, runLedger } from "./ledger.js";
import { field } from "./transaction-form.js";
import { txnRowElement } from "./transactions.js";

const RD_ABOUT = "A deposit you pay into every month for a fixed term. Open it with its start day and term, put each instalment in with Deposit, " +
    "and close it when it matures or you break it: what came back above what you paid in is saved as interest. Closed deposits stay in the list " +
    "for the history. While it is open it counts at what you have paid in.";

let rdExpanded = false;                  // the whole card starts closed; the choice lasts until the page is reloaded
const rdOpenTransactions = new Set();    // the deposits whose transactions are showing (all start folded)
let rdClosedShown = false;               // the Closed list under the open deposits starts folded

/* Whether an account is the Recurring Deposits account. Ordinary transactions cannot use it. */
export function isRecurringDepositAccount(id) {
    return recurringDeposits.configured && recurringDeposits.accountId === id;
}

const rdApi = (path, body, method = "POST") => api(`/api/recurring-deposits${path}`, { method, body });

/* The transfers (and the interest) that belong to one deposit, newest first. */
function rdTransactionsOf(deposit) {
    return transactions.filter(t => t.recurringDepositId === deposit.id)
        .sort((a, b) => b.date.localeCompare(a.date) || b.id - a.id);
}

/* "Month 7 of 36 · 19%", "Matured", or for a closed deposit "Closed on 19 Jan 2026". */
function rdProgressText(d) {
    if (d.status === "CLOSED") return `Closed on ${formatDate(d.closedOn)}`;
    if (d.progressPercent >= 100) return `Matured on ${formatDate(d.maturesOn)}`;
    if (d.monthsElapsed === 0 && d.progressPercent === 0) return `Starts ${formatDate(d.startDate)}`;
    return `Month ${d.monthsElapsed} of ${d.termMonths} · ${d.progressPercent}%`;
}

/* A transfer into an open deposit can be removed (a mistaken deposit): its row gets a bin, as an investment does. */
export function isRdDeposit(t) {
    return recurringDeposits.configured && t.recurringDepositId != null && t.toAccountId === recurringDeposits.accountId
        && recurringDeposits.open.some(d => d.id === t.recurringDepositId);
}

export function rdDepositDelete(t) {
    const button = el("button", { className: "icon-btn", type: "button", textContent: "🗑️", title: "Delete this deposit and return the money" });
    button.setAttribute("aria-label", "Delete this deposit and return the money to the account it came from");
    button.addEventListener("click", async () => {
        if (!confirm(`Delete this deposit of ${rupees2(t.amount)}? The money goes back to the account it came from.`)) return;
        try {
            await rdApi(`/transfers/${t.id}`, null, "DELETE");
            await refreshData();
        } catch (e) {
            alert(e.message);
        }
    });
    return button;
}

/* Bank and cash accounts (in rupees) that money can come from or go back to. */
function rdMoneySelect() {
    const list = accounts.filter(a => a.active && (a.type === "BANK" || a.type === "CASH") && (a.currency || "INR") === "INR");
    return el("select", {}, ...list.map(a => el("option", { value: a.id, textContent: `${a.name} · ${rupees2(a.balance)}` })));
}

const rdDay = () => el("input", { type: "date", value: isoDay(new Date()), max: isoDay(new Date()) });

function rdDepositBlock(d) {
    const closed = d.status === "CLOSED";
    const fill = el("div", { className: "ef-fill" + (d.progressPercent >= 100 || closed ? " reached" : "") + (growsOnce("rd-" + d.id) ? " grow" : "") });
    setBarWidth(fill, "rd-" + d.id, d.progressPercent);
    const bar = el("div", { className: "ef-bar" }, fill);
    bar.setAttribute("role", "progressbar");
    bar.setAttribute("aria-valuenow", String(d.progressPercent));
    bar.setAttribute("aria-valuemin", "0");
    bar.setAttribute("aria-valuemax", "100");
    bar.setAttribute("aria-label", `${d.name}: ${rdProgressText(d)}`);

    const meta = [`${d.instalments} ${d.instalments === 1 ? "deposit" : "deposits"}`, `matures ${formatDate(d.maturesOn)}`];
    if (d.monthlyAmount != null) meta.push(`${rupees2(d.monthlyAmount)} a month`);
    if (d.interestRate != null) meta.push(`${Number(d.interestRate)}% a year`);
    const lines = [el("div", { className: "rd-meta", textContent: meta.join(" · ") })];
    if (!closed && d.plannedTotal != null) {
        lines.push(el("div", { className: "rd-meta", textContent: `Paid in ${rupees2(d.deposited)} of ${rupees2(d.plannedTotal)}` +
            (d.expectedMaturity != null ? ` · expected at maturity ${rupees2(d.expectedMaturity)}` : "") }));
    } else if (!closed && d.expectedMaturity != null) {
        lines.push(el("div", { className: "rd-meta", textContent: `Expected at maturity ${rupees2(d.expectedMaturity)}` }));
    }
    if (closed) {
        lines.push(el("div", { className: "rd-meta", textContent: `Paid in ${rupees2(d.deposited)} · got back ${rupees2(d.received)} · interest ${rupees2(d.interestEarned)}` }));
    }

    const actions = el("div", { className: "rd-actions" });
    if (!closed) {
        const deposit = el("button", { className: "btn btn-small btn-primary", type: "button", textContent: "Deposit" });
        deposit.addEventListener("click", () => openRdDepositDialog(d));
        const close = el("button", { className: "btn btn-small btn-secondary", type: "button", textContent: "Close" });
        close.addEventListener("click", () => openRdCloseDialog(d));
        const edit = el("button", { className: "icon-btn", type: "button", textContent: "✏️", title: "Change " + d.name });
        edit.setAttribute("aria-label", "Change " + d.name);
        edit.addEventListener("click", () => openRdForm(d));
        actions.append(deposit, close, edit);
    }
    const rows = rdTransactionsOf(d);
    const shown = rdOpenTransactions.has(d.id);
    const fold = el("button", { className: "cc-toggle rd-fold", type: "button", textContent: `Transactions (${rows.length}) ${shown ? "▾" : "▸"}` });
    fold.setAttribute("aria-expanded", String(shown));
    fold.addEventListener("click", () => {
        if (shown) rdOpenTransactions.delete(d.id); else rdOpenTransactions.add(d.id);
        refreshData();
    });
    actions.append(fold);

    const block = el("div", { className: "rd-item" + (closed ? " closed" : "") },
        el("div", { className: "rd-top" },
            el("div", { className: "lg-name", textContent: d.name }),
            el("div", { className: "rd-amount", textContent: rupees2(d.deposited), title: "Paid in" })),
        el("div", { className: "rd-status", textContent: rdProgressText(d) }), bar, ...lines, actions);
    if (shown) {
        const list = el("div", { className: "rd-txns" });
        if (rows.length === 0) {
            list.append(el("div", { className: "cc-none", textContent: "No deposits yet." }));
        } else {
            list.append(el("div", { className: "txn-row head" },
                ...["Date", "Type", "Category", "Sub category", "Note", "Account"].map(text => el("span", { textContent: text })),
                el("span", { className: "amt", textContent: "Amount" }), el("span")));
            rows.forEach(t => list.append(txnRowElement(t)));
        }
        block.append(list);
    }
    return block;
}

export function recurringDepositsCard() {
    const card = el("div", { className: "ef-card rd-card" });
    const head = el("div", { className: "ef-head" },
        el("div", { className: "ef-title" }, "🔁 Recurring Deposits",
            el("span", { className: "info-tag", tabIndex: 0 }, "i", el("span", { className: "info-tooltip", textContent: RD_ABOUT }))));
    card.append(head);

    const view = recurringDeposits;
    if (!view.configured || (view.open.length === 0 && view.closed.length === 0)) {
        const start = el("button", { className: "btn btn-primary", textContent: view.configured ? "Open a deposit" : "Set up" });
        start.addEventListener("click", async () => {
            try {
                if (!view.configured) {
                    await rdApi("/setup");
                    await refreshData();
                }
                openRdForm(null);
            } catch (e) {
                alert(e.message);
            }
        });
        head.append(start);
        card.append(el("div", { className: "ef-empty" },
            el("p", { textContent: "Keep your recurring deposits together. Each one has a start day, a term in months and, if you like, its monthly amount and interest rate." }),
            el("ul", {},
                el("li", { textContent: "A bar shows how far through its term each deposit is" }),
                el("li", { textContent: "Put each instalment in with Deposit; close it when it matures or you break it, and the interest is recorded for you" }),
                el("li", { textContent: "A closed deposit stays in the list, with its transactions, so the history is kept" }))));
        return card;
    }

    const toggle = el("button", { className: "icon-btn ledger-toggle", type: "button", textContent: rdExpanded ? "▾" : "▸" });
    const label = `${rdExpanded ? "Collapse" : "Expand"} Recurring Deposits`;
    toggle.title = label;
    toggle.setAttribute("aria-label", label);
    toggle.setAttribute("aria-expanded", String(rdExpanded));
    toggle.addEventListener("click", () => { rdExpanded = !rdExpanded; refreshData(); });
    const titleBox = head.children[0];
    titleBox.prepend(toggle);
    titleBox.append(el("span", { className: "pf-total", title: "What the open deposits hold" },
        "Paid in ", el("strong", { textContent: rupees2(view.deposited) })));
    const add = el("button", { className: "btn btn-small btn-primary", textContent: "Open a deposit" });
    add.addEventListener("click", () => openRdForm(null));
    head.append(el("div", { className: "ledger-actions" }, add));

    card.append(el("div", { className: "ledger-summary", textContent:
        `${view.open.length} open · ${view.closed.length} closed` }));
    if (rdExpanded) {
        view.open.forEach(d => card.append(rdDepositBlock(d)));
        if (view.open.length === 0) card.append(el("div", { className: "cc-none", textContent: "No open deposits." }));
        if (view.closed.length) {
            const fold = el("button", { className: "cc-toggle", type: "button", textContent: `Closed (${view.closed.length}) ${rdClosedShown ? "▾" : "▸"}` });
            fold.setAttribute("aria-expanded", String(rdClosedShown));
            fold.addEventListener("click", () => { rdClosedShown = !rdClosedShown; refreshData(); });
            card.append(fold);
            if (rdClosedShown) view.closed.forEach(d => card.append(rdDepositBlock(d)));
        }
    }
    return card;
}

/* ---------- dialogs ---------- */

/* What the deposit received through Deposit (its transfers in), so the "already paid in" box shows only the rest. */
function rdDepositsMade(d) {
    return transactions.filter(t => t.recurringDepositId === d.id && t.toAccountId === recurringDeposits.accountId)
        .reduce((sum, t) => sum + Number(t.amount), 0);
}

/* Opens a deposit (d is null) or changes an open one. */
function openRdForm(d) {
    const f = {};
    openLedgerDialog(d ? "Change " + d.name : "Open a deposit", d ? "Save" : "Open", body => {
        f.name = el("input", { type: "text", maxLength: 100, autocomplete: "off", placeholder: "Bank RD", value: d ? d.name : "" });
        f.start = el("input", { type: "date", value: d ? d.startDate : isoDay(new Date()) });
        f.term = numberInput(d ? d.termMonths : "");
        f.term.step = "1";
        f.monthly = numberInput(d && d.monthlyAmount != null ? d.monthlyAmount : "");
        f.rate = numberInput(d && d.interestRate != null ? Number(d.interestRate) : "");
        f.already = numberInput(d ? Number(d.deposited) - rdDepositsMade(d) : "");
        body.append(field("Name", f.name), field("Started on", f.start), field("Term (months)", f.term), field("Monthly amount (₹, optional)", f.monthly),
            field("Interest a year (%, optional)", f.rate), field("Already paid in before today (₹, optional)", f.already),
            el("div", { className: "edit-hint", textContent: "Already paid in is for a deposit you started before you began using this page: it is counted from the start and nothing is taken from your accounts. Each later instalment goes in with Deposit." }));
        f.name.focus();
    });
    dlg.onPrimary = () => {
        const term = num(f.term);
        if (!f.name.value.trim()) return ledgerFail("Enter a name.");
        if (!f.start.value) return ledgerFail("Choose the day it started.");
        if (!Number.isInteger(term) || term < 1 || term > 240) return ledgerFail("The term is a whole number of months, from 1 to 240.");
        const body = { name: f.name.value.trim(), startDate: f.start.value, termMonths: term, monthlyAmount: num(f.monthly), interestRate: num(f.rate), alreadyDeposited: num(f.already) ?? 0 };
        if (d) runLedger(() => rdApi(`/deposits/${d.id}`, body, "PUT"));
        else runLedger(() => rdApi("/deposits", body));
    };
}

function openRdDepositDialog(d) {
    const f = {};
    openLedgerDialog("Deposit into " + d.name, "Deposit", body => {
        f.from = rdMoneySelect();
        f.amount = numberInput(d.monthlyAmount != null ? Number(d.monthlyAmount) : "");
        f.date = rdDay();
        body.append(field("From", f.from), field("Amount (₹)", f.amount), field("Date", f.date));
        f.amount.focus();
    });
    dlg.onPrimary = () => {
        const amount = num(f.amount);
        if (!f.from.value) return ledgerFail("Add a bank or cash account first.");
        if (!(amount > 0)) return ledgerFail("Enter the amount.");
        if (!f.date.value) return ledgerFail("Choose the date.");
        runLedger(() => rdApi(`/deposits/${d.id}/deposit`, { fromAccountId: Number(f.from.value), amount, date: f.date.value }));
    };
}

function openRdCloseDialog(d) {
    const f = {};
    openLedgerDialog("Close " + d.name, "Close deposit", body => {
        f.received = numberInput(d.expectedMaturity != null ? Number(d.expectedMaturity) : "");
        f.to = rdMoneySelect();
        f.date = rdDay();
        body.append(field("Amount that came back (₹)", f.received), field("Money goes to", f.to), field("Date", f.date),
            el("div", { className: "edit-hint", textContent:
                `You paid in ${rupees2(d.deposited)}. That goes back to the account you choose, and anything above it is saved as interest income. The deposit stays in the list as Closed.` }));
        f.received.focus();
    });
    dlg.onPrimary = () => {
        const received = num(f.received);
        if (!f.to.value) return ledgerFail("Add a bank or cash account first.");
        if (received == null || received < Number(d.deposited)) return ledgerFail(`Enter the whole amount that came back: at least ${rupees2(d.deposited)}.`);
        if (!f.date.value) return ledgerFail("Choose the date.");
        runLedger(() => rdApi(`/deposits/${d.id}/close`, { toAccountId: Number(f.to.value), amountReceived: received, date: f.date.value }));
    };
}
