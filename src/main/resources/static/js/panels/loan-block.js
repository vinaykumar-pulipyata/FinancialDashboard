/* A loan's transactions, under its row in the Accounts view (Liabilities level): a fold "Transactions" that starts closed and then lists
   what went into or out of the loan, newest first, in pages like the Transactions list. The rows are the ones of the Transactions list,
   so each shows what was still owed after it. The credit card has the same kind of fold, by statement cycle. */

import { transactions } from "../core/state.js";
import { el } from "../core/util.js";
import { txnRowElement } from "./transactions.js";

const LOAN_PAGE_SIZES = [10, 25, 50, 100];
let loanRowsPerPage = 10;                // shared by every loan, as in the Transactions list
const loanBlocksOpen = new Set();        // loan ids whose fold is open, until the page is reloaded
const loanPage = new Map();              // loan id -> the page on show (from 1)

/* The transactions that touch a loan, newest first. */
function loanRows(loan) {
    return transactions.filter(t => t.fromAccountId === loan.id || t.toAccountId === loan.id)
        .sort((x, y) => y.date.localeCompare(x.date) || y.id - x.id);
}

/* The fold for one loan. redraw() draws the Accounts view again after a click. */
export function loanTransactionsBlock(loan, redraw) {
    const rows = loanRows(loan);
    const open = loanBlocksOpen.has(loan.id);
    const toggle = el("button", { className: "cc-toggle", type: "button", textContent: `Transactions (${rows.length}) ${open ? "▾" : "▸"}`,
        title: "The loan's payments and other transactions" });
    toggle.setAttribute("aria-expanded", String(open));
    toggle.addEventListener("click", () => { open ? loanBlocksOpen.delete(loan.id) : loanBlocksOpen.add(loan.id); redraw(); });
    const block = el("div", { className: "cc-block" }, toggle);
    if (!open) return block;

    const part = el("div", { className: "cc-part" });
    if (rows.length === 0) {
        part.append(el("div", { className: "cc-none", textContent: "Nothing recorded on this loan yet. Use Pay to record a payment." }));
        block.append(part);
        return block;
    }
    const pages = Math.ceil(rows.length / loanRowsPerPage);
    const page = Math.min(Math.max(loanPage.get(loan.id) || 1, 1), pages);
    const start = (page - 1) * loanRowsPerPage;
    const shown = rows.slice(start, start + loanRowsPerPage);
    part.append(el("div", { className: "txn-row head" },
        ...["Date", "Type", "Category", "Sub category", "Note", "Account"].map(text => el("span", { textContent: text })),
        el("span", { className: "amt", textContent: "Amount" }), el("span")));
    shown.forEach(t => part.append(txnRowElement(t)));

    const prev = el("button", { className: "btn btn-secondary", type: "button", textContent: "‹ Previous", disabled: page <= 1 });
    const next = el("button", { className: "btn btn-secondary", type: "button", textContent: "Next ›", disabled: page >= pages });
    prev.addEventListener("click", () => { loanPage.set(loan.id, page - 1); redraw(); });
    next.addEventListener("click", () => { loanPage.set(loan.id, page + 1); redraw(); });
    const size = el("select", { className: "page-size" }, ...LOAN_PAGE_SIZES.map(n => el("option", { value: n, textContent: `${n} per page`, selected: n === loanRowsPerPage })));
    size.addEventListener("change", () => { loanRowsPerPage = Number(size.value); loanPage.set(loan.id, 1); redraw(); });
    part.append(el("div", { className: "pager" },
        el("span", { textContent: `Showing ${start + 1}–${start + shown.length} of ${rows.length}` }),
        el("div", { className: "pager-controls" }, size, prev, el("span", { textContent: `Page ${page} of ${pages}` }), next)));
    block.append(part);
    return block;
}
