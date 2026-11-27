/* What is between you and one person from sharing bills, under their name in Receivables: one fold "Transactions (n)" that starts closed and
   lists, newest first, the expenses split with them (they owe you their share) and the bills they paid for you (you owe them your share),
   each with what is settled and what is left, then the payments made. A Settle button beside the name settles the whole BALANCE, not single
   transactions: what you owe each other is set off first and the rest is paid in the direction of the balance (POST /api/borrowers/{id}/settle-balance).
   The maths is core/split-calc.js. */

import { accountSelect, field } from "./transaction-form.js";
import { splitSettlements, transactions, writeOffs } from "../core/state.js";
import { ledgerBalance, personLedger, personOffset, personPayments } from "../core/split-calc.js";
import { $, api, el, formatDate, isoDay, rupees } from "../core/util.js";
import { refreshData } from "../core/refresh.js";
import { dlg, ledgerFail, openLedgerDialog, runLedger } from "./ledger.js";

const splitBlocksOpen = new Set();   // person ids whose fold is open, until the page is reloaded

const rowsOf = person => personLedger(person.id, transactions, splitSettlements);

/* The plain loans with a person (Loans Given and Loans Collected), newest first, leaving out the payments that settled shared bills (those are
   listed under Payments). side "lent" is money you handed over, "back" is money they returned. */
function loanRowsOf(person) {
    const settlementPayments = new Set(splitSettlements.filter(s => s.borrowerId === person.id).map(s => s.incomeTransactionId));
    return transactions.filter(t => t.borrowerId === person.id && !settlementPayments.has(t.id))
        .map(t => ({ txn: t, side: t.kind === "EXPENSE" ? "lent" : "back" }));
}

/* The write-offs of a person, shaped like the other rows (a write-off is not a transaction: it has its own day, amount and reason). */
function writeOffRowsOf(person) {
    return writeOffs.filter(w => w.borrowerId === person.id)
        .map(w => ({ txn: { id: "w" + w.id, date: w.writtenOn, amount: w.amount, note: w.note }, side: "off", writeOff: w }));
}

/* Everything with a person, newest first: loans, expenses shared with them and bills they paid for you. */
function allRowsOf(person) {
    return [...rowsOf(person), ...loanRowsOf(person), ...writeOffRowsOf(person)].sort((a, b) => b.txn.date.localeCompare(a.txn.date) || (Number(b.txn.id) || 0) - (Number(a.txn.id) || 0));
}

/* What is open between you and a person, the way SplitService.settleBalance works it out: what they owe you (their open shares plus the plain loans
   still open, which is their whole balance less those shares), what you owe them (the bills), what is set off against each other first (bills
   against shares, then against loans) and what is left to pay, in which direction. */
function balanceOf(person) {
    const b = ledgerBalance(rowsOf(person));
    const loans = Math.max(Math.round((Number(person.owed) - b.theirs) * 100) / 100, 0);
    const againstShares = Math.min(b.theirs, b.yours);
    const againstLoans = Math.min(b.yours - againstShares, loans);
    const offset = Math.round((againstShares + againstLoans) * 100) / 100;
    const theyStillOwe = Math.round((b.theirs - againstShares + loans - againstLoans) * 100) / 100;
    const youStillOwe = Math.round((b.yours - offset) * 100) / 100;
    return { shares: b.theirs, loans, bills: b.yours, offset, theyStillOwe, youStillOwe };
}

/* Whether there is anything open between you (the Settle button is only offered then). */
export function hasOpenBalance(person) {
    const b = balanceOf(person);
    return b.shares > 0 || b.loans > 0 || b.bills > 0;
}

const what = t => t.note || t.description || "Expense";

/* The pencil and bin on a write-off row: correct its day and reason, or take it back (the balance goes up again). */
function writeOffButtons(writeOff) {
    const edit = el("button", { className: "icon-btn row-edit", type: "button", textContent: "✏️", title: "Change the day or the reason" });
    edit.setAttribute("aria-label", "Edit this write-off");
    edit.addEventListener("click", () => openEditWriteOff(writeOff));
    const remove = el("button", { className: "icon-btn row-edit", type: "button", textContent: "🗑", title: "Take this write-off back" });
    remove.setAttribute("aria-label", "Delete this write-off");
    remove.addEventListener("click", async () => {
        if (!confirm("Take this write-off back? What was let go of is owed again.")) return;
        try {
            await api(`/api/loans/write-offs/${writeOff.id}`, { method: "DELETE" });
            await refreshData();
        } catch (e) {
            alert(e.message);
        }
    });
    return el("span", { className: "write-off-buttons" }, " ", edit, remove);
}

/* The day and the reason of a write-off. The amount cannot change: it was the whole balance when it was made. */
function openEditWriteOff(writeOff) {
    const f = {};
    openLedgerDialog("Write-off", "Save", body => {
        f.date = el("input", { type: "date", value: writeOff.writtenOn });
        f.note = el("input", { type: "text", maxLength: 255, autocomplete: "off", value: writeOff.note });
        body.append(el("div", { className: "edit-hint", textContent: `${rupees(Math.abs(writeOff.amount))} was let go of. The amount cannot change; to change it, delete this write-off and write off again.` }),
            field("Day", f.date), field("Why did you let it go?", f.note));
    });
    dlg.onPrimary = () => {
        if (!f.date.value) return ledgerFail("Choose the day.");
        if (!(f.note.value || "").trim()) return ledgerFail("Say why you let it go.");
        runLedger(() => api(`/api/loans/write-offs/${writeOff.id}`, { method: "PUT", body: { date: f.date.value, note: f.note.value.trim() } }));
    };
}

/* The arrow before a person's name: it folds their transactions out under the name (and back). redraw() draws the Accounts view again. */
export function personToggle(person, redraw) {
    const open = splitBlocksOpen.has(person.id);
    const count = allRowsOf(person).length;
    const toggle = el("button", { className: "icon-btn ledger-toggle", type: "button", textContent: open ? "▾" : "▸" });
    const label = `${open ? "Hide" : "Show"} the transactions with ${person.name} (${count})`;
    toggle.title = label;
    toggle.setAttribute("aria-label", label);
    toggle.setAttribute("aria-expanded", String(open));
    toggle.addEventListener("click", () => { splitBlocksOpen.has(person.id) ? splitBlocksOpen.delete(person.id) : splitBlocksOpen.add(person.id); redraw(); });
    return toggle;
}

/* Their transactions under the name, when the arrow has opened them (null while folded). */
export function personTransactionsBlock(person) {
    const rows = allRowsOf(person);
    if (!splitBlocksOpen.has(person.id)) return null;
    const block = el("div", { className: "cc-block" });

    const part = el("div", { className: "cc-part split-fold" });
    const opening = Number(person.openingAmount) > 0 ? Number(person.openingAmount) : 0;   // what they owed before you started: no transaction, so it has its own line
    if (rows.length === 0 && opening === 0) {
        part.append(el("div", { className: "cc-none", textContent: "Nothing recorded with this person yet. Loans Given, Loans Collected, split expenses and bills they paid will show here." }));
        block.append(part);
        return block;
    }
    part.append(el("div", { className: "split-head" },
        ...["Date", "Expense", "Bill", "Balance", "Settled", "Left"].map((text, i) => el("span", { className: i >= 2 ? "amt" : "", textContent: text }))));
    for (const r of rows) {
        if (r.side === "off") {   // let go of: lowers what they owe (a negative amount means you owed them and let that go)
            const owedToYou = Number(r.txn.amount) > 0;
            part.append(el("div", { className: `split-row loan ${owedToYou ? "back" : "lent"}` },
                el("span", { textContent: formatDate(r.txn.date) }),
                el("span", { className: "split-what" }, "Written off: " + r.txn.note, writeOffButtons(r.writeOff)),
                el("span", { className: "amt", textContent: rupees(Math.abs(r.txn.amount)) }),
                el("span", { className: "amt split-balance", textContent: (owedToYou ? "−" : "+") + rupees(Math.abs(r.txn.amount)) }),
                el("span", { className: "amt" }), el("span", { className: "amt" })));
            continue;
        }
        if (r.side === "lent" || r.side === "back") {   // a plain loan: money you handed over or they returned
            const lent = r.side === "lent";
            part.append(el("div", { className: `split-row loan ${r.side}` },
                el("span", { textContent: formatDate(r.txn.date) }),
                el("span", { className: "split-what", textContent: (lent ? "Lent: " : "Paid back: ") + (r.txn.note || r.txn.description || "Loan") }),
                el("span", { className: "amt", textContent: rupees(r.txn.amount) }),
                el("span", { className: "amt split-balance", textContent: (lent ? "+" : "−") + rupees(r.txn.amount) }),
                el("span", { className: "amt" }), el("span", { className: "amt" })));
            continue;
        }
        const status = r.left <= 0 ? "settled" : r.cleared > 0 ? "part" : "open";
        const sign = r.side === "they" ? "+" : "−";   // + they owe you, − you owe them
        part.append(el("div", { className: `split-row ${status} ${r.side}` },
            el("span", { textContent: formatDate(r.txn.date) }),
            el("span", { className: "split-what", textContent: what(r.txn) + (r.side === "you" ? " (they paid)" : "") }),
            el("span", { className: "amt", textContent: rupees(r.txn.amount) }),
            el("span", { className: "amt split-balance", textContent: sign + rupees(r.share) }),
            el("span", { className: "amt", textContent: r.cleared > 0 ? rupees(r.cleared) : "" }),
            el("span", { className: "amt split-left", textContent: r.left > 0 ? sign + rupees(r.left) : "✓" })));
    }
    if (opening > 0) {
        part.append(el("div", { className: "split-row loan lent" },
            el("span"), el("span", { className: "split-what", textContent: "Owed before you started using the app" }),
            el("span", { className: "amt", textContent: rupees(opening) }), el("span", { className: "amt split-balance", textContent: "+" + rupees(opening) }),
            el("span", { className: "amt" }), el("span", { className: "amt" })));
    }
    const payments = personPayments(person.id, transactions, splitSettlements);
    const offset = personOffset(person.id, transactions, splitSettlements);
    if (payments.length || offset > 0) {
        part.append(el("div", { className: "split-sub", textContent: "Payments" }));
        if (offset > 0) {
            part.append(el("div", { className: "split-pay" }, el("span"),
                el("span", { className: "split-what", textContent: "Set off against each other (no money moved)" }),
                el("span", { className: "amt", textContent: rupees(offset) })));
        }
        for (const p of payments) {
            const theyPaid = p.payment && p.payment.kind === "INCOME";
            const cleared = p.cleared.map(c => `${c.expense ? what(c.expense) : "an expense"} ${rupees(c.amount)}`).join(", ");
            part.append(el("div", { className: "split-pay" },
                el("span", { textContent: p.payment ? formatDate(p.payment.date) : "" }),
                el("span", { className: "split-what", textContent: `${theyPaid ? "They paid you" : "You paid them"}: cleared ${cleared}` }),
                el("span", { className: "amt", textContent: rupees(p.total) })));
        }
    }
    block.append(part);
    return block;
}

/* The Settle button for the name line. */
export function settleButton(person) {
    const button = el("button", { className: "btn btn-secondary btn-small settle-btn", type: "button", textContent: "Settle", title: "Settle the balance with " + person.name });
    button.setAttribute("aria-label", "Settle the balance with " + person.name);
    button.addEventListener("click", () => openSettleBalance(person));
    return button;
}

/* The Settle window: the balance in words, what will be set off, and one amount to pay (they pay you when they owe more, you pay them when you
   owe more) with the day and account. The offset needs no money. */
function openSettleBalance(person) {
    const b = balanceOf(person);
    const theyPay = b.theyStillOwe > 0;   // they owe you more: the money comes from them; only when you owe more does it go from you
    const net = theyPay ? b.theyStillOwe : b.youStillOwe;
    const f = {};
    openLedgerDialog(`Settle · ${person.name}`, "Settle", body => {
        const lines = [];
        if (b.shares + b.loans > 0) lines.push(`${person.name} owes you ${rupees(b.shares + b.loans)}${b.shares > 0 && b.loans > 0 ? ` (${rupees(b.shares)} shared expenses, ${rupees(b.loans)} loans)` : b.loans > 0 ? " in loans" : " for shared expenses"}.`);
        if (b.bills > 0) lines.push(`You owe ${person.name} ${rupees(b.bills)} for bills they paid.`);
        if (b.offset > 0) lines.push(`${rupees(b.offset)} of it is set off against each other first (no money moves).`);
        lines.push(net > 0 ? (theyPay ? `${person.name} pays you the rest, ${rupees(net)}.` : `You pay ${person.name} the rest, ${rupees(net)}.`) : "Nothing is left to pay after that.");
        f.amount = el("input", { type: "number", min: 0, step: "any", value: net || "" });
        f.date = el("input", { type: "date", value: isoDay(new Date()) });
        f.account = accountSelect(null, !theyPay, null, new Set(["BANK", "CASH", "WALLET"]), true);
        f.note = el("input", { type: "text", maxLength: 255, autocomplete: "off" });
        body.append(...lines.map(text => el("div", { className: "edit-hint", textContent: text })));
        if (net > 0) {
            body.append(field(theyPay ? "Amount they paid (₹)" : "Amount you paid (₹)", f.amount), field("Day", f.date),
                field(theyPay ? "Into account" : "From account", f.account), field("Note (optional)", f.note));
            f.amount.focus();
        }
    });
    dlg.onPrimary = () => {
        const amount = net > 0 ? Number(f.amount.value) : 0;
        if (net > 0 && !(amount > 0)) return ledgerFail("Enter the amount.");
        if (net > 0 && amount > net) return ledgerFail(`That is more than the ${rupees(net)} left to pay.`);
        if (amount > 0 && !f.account.value) return ledgerFail(theyPay ? "Choose the account the money went into." : "Choose the account the money came from.");
        const body = { date: amount > 0 ? f.date.value : null, accountId: amount > 0 ? Number(f.account.value) : null, amount: amount > 0 ? amount : null,
            note: amount > 0 ? (f.note.value || "").trim() || null : null };
        runLedger(() => api(`/api/borrowers/${person.id}/settle-balance`, { method: "POST", body }));
    };
}
