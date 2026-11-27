/* Credit card cycles, under a card's row in the Accounts view (Liabilities level): a fold "Statement cycles" that shows one cycle at a
   time with ‹ › to page between them (‹ goes to the one before, › to the next; it opens on the cycle running now). A cycle shows its
   dates, what the bill was and whether it was paid, and its transactions, in pages like the Transactions list, with chips for All, Spent
   and Paid into the card. The rows are the ones of the Transactions list, so each shows the card's remaining balance after it. The sums are in card-cycles-calc.js.
   The Transactions list itself is not touched. */

import { cardDueDays, cardStatementDays, transactions } from "../core/state.js";
import { el, isoDay, rupees } from "../core/util.js";
import { cardCycles } from "./card-cycles-calc.js";
import { txnRowElement } from "./transactions.js";

const blocksOpen = new Set();    // card ids whose fold is open, until the page is reloaded
const cyclePage = new Map();     // card id -> which cycle is on show, 0 = the newest (the one running)

const cycleDay = iso => new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { day: "numeric", month: "short" });
const dayBefore = iso => { const d = new Date(iso + "T00:00:00"); d.setDate(d.getDate() - 1); return isoDay(d); };
const year = iso => iso.slice(0, 4);

/* "15 Sep – 14 Oct 2026" (the years are given when the cycle crosses a year end). */
function cycleLabel(cycle) {
    const last = dayBefore(cycle.end);
    return year(cycle.start) === year(last) ? `${cycleDay(cycle.start)} – ${cycleDay(last)} ${year(last)}`
        : `${cycleDay(cycle.start)} ${year(cycle.start)} – ${cycleDay(last)} ${year(last)}`;
}

/* What the line of a cycle says about its bill. */
function cycleStatus(cycle) {
    if (cycle.open) return { text: `Open · ${rupees(cycle.owed)} owed so far`, state: "open" };
    const { amount, paid, left, state } = cycle.bill;
    const due = cycle.dueDate ? cycleDay(cycle.dueDate) : "";
    switch (state) {
        case "none": return { text: "Nothing to pay", state };
        case "paid": return { text: `Paid ${rupees(amount)}`, state };
        case "part": return { text: `Part paid ${rupees(paid)} of ${rupees(amount)}` + (due ? ` · due ${due}` : ""), state };
        case "overdue": return { text: `Overdue ${rupees(left)}` + (due ? ` · was due ${due}` : ""), state };
        default: return { text: `Bill ${rupees(amount)}` + (due ? ` · due ${due}` : ""), state };
    }
}

const PAGE_SIZES = [10, 25, 50, 100];
let rowsPerPage = 10;            // shared by every card, as in the Transactions list
const rowsPage = new Map();      // "cardId:cycleStart" -> page on show (from 1)
const rowsShown = new Map();     // card id -> "ALL", "SPENT" or "PAID": which transactions of the cycle are listed

/* The cycle's transactions, newest first, the kind chosen with the chips, in pages like the Transactions list. */
function transactionsOf(card, cycle, redraw) {
    const shownKind = rowsShown.get(card.id) || "ALL";
    const chips = el("div", { className: "chips cc-chips" });
    for (const [value, label, count] of [["ALL", "All", cycle.purchases.length + cycle.payments.length],
        ["SPENT", "Spent", cycle.purchases.length], ["PAID", "Paid into the card", cycle.payments.length]]) {
        const chip = el("button", { className: "chip" + (shownKind === value ? " active" : ""), type: "button", textContent: `${label} (${count})` });
        chip.addEventListener("click", () => { rowsShown.set(card.id, value); rowsPage.set(`${card.id}:${cycle.start}`, 1); redraw(); });
        chips.append(chip);
    }
    const list = (shownKind === "SPENT" ? cycle.purchases : shownKind === "PAID" ? cycle.payments : [...cycle.purchases, ...cycle.payments])
        .sort((x, y) => y.date.localeCompare(x.date) || y.id - x.id);
    const part = el("div", { className: "cc-part" }, chips);
    if (list.length === 0) {
        part.append(el("div", { className: "cc-none", textContent: "Nothing in this cycle." }));
        return part;
    }
    const pages = Math.ceil(list.length / rowsPerPage);
    const key = `${card.id}:${cycle.start}`;
    const page = Math.min(Math.max(rowsPage.get(key) || 1, 1), pages);
    const start = (page - 1) * rowsPerPage;
    const shown = list.slice(start, start + rowsPerPage);
    part.append(el("div", { className: "txn-row head" },
        el("span", { textContent: "Date" }), el("span", { textContent: "Type" }), el("span", { textContent: "Category" }), el("span", { textContent: "Sub category" }),
        el("span", { textContent: "Note" }), el("span", { textContent: "Account" }), el("span", { className: "amt", textContent: "Amount" }), el("span")));
    shown.forEach(t => part.append(txnRowElement(t)));

    const prev = el("button", { className: "btn btn-secondary", type: "button", textContent: "‹ Previous", disabled: page <= 1 });
    const next = el("button", { className: "btn btn-secondary", type: "button", textContent: "Next ›", disabled: page >= pages });
    prev.addEventListener("click", () => { rowsPage.set(key, page - 1); redraw(); });
    next.addEventListener("click", () => { rowsPage.set(key, page + 1); redraw(); });
    const size = el("select", { className: "page-size" }, ...PAGE_SIZES.map(n => el("option", { value: n, textContent: `${n} per page`, selected: n === rowsPerPage })));
    size.addEventListener("change", () => { rowsPerPage = Number(size.value); rowsPage.set(key, 1); redraw(); });
    part.append(el("div", { className: "pager" },
        el("span", { textContent: `Showing ${start + 1}–${start + shown.length} of ${list.length}` }),
        el("div", { className: "pager-controls" }, size, prev, el("span", { textContent: `Page ${page} of ${pages}` }), next)));
    return part;
}

/* The cycle on show: ‹ dates › with the place in the list, its bill status and totals, then its transactions. */
function cycleBlock(card, cycles, index, redraw) {
    const cycle = cycles[index];
    const go = step => { cyclePage.set(card.id, index + step); redraw(); };
    const older = el("button", { className: "cc-nav", type: "button", textContent: "‹", disabled: index >= cycles.length - 1,
        title: "The cycle before this one" });
    const newer = el("button", { className: "cc-nav", type: "button", textContent: "›", disabled: index <= 0, title: "The next cycle" });
    older.setAttribute("aria-label", "Previous statement cycle");
    newer.setAttribute("aria-label", "Next statement cycle");
    older.addEventListener("click", () => go(1));
    newer.addEventListener("click", () => go(-1));
    const status = cycleStatus(cycle);
    const box = el("div", { className: "cc-cycle" + (cycle.open ? " current" : "") },
        el("div", { className: "cc-pager" }, older, el("strong", { className: "cc-dates", textContent: cycleLabel(cycle) }), newer,
            el("span", { className: "cc-place", textContent: `${cycles.length - index} of ${cycles.length}` }),
            el("span", { className: "cc-status " + status.state, textContent: status.text })));
    box.append(el("div", { className: "cc-summary", textContent:
        `Carried in ${rupees(cycle.carriedIn)} · Spent ${rupees(cycle.spent)} · Paid ${rupees(cycle.paid)} · ` +
        (cycle.open ? `Owed so far ${rupees(cycle.owed)}` : `Statement ${rupees(cycle.owed)} on ${cycleDay(cycle.end)}`) +
        (cycle.dueDate && !cycle.open ? ` · due ${cycleDay(cycle.dueDate)}` : "") }));
    box.append(transactionsOf(card, cycle, redraw));
    return box;
}

/* The fold for one credit card. redraw() draws the Accounts view again after a click. */
export function cardCyclesBlock(card, redraw) {
    const open = blocksOpen.has(card.id);
    const toggle = el("button", { className: "cc-toggle", type: "button", textContent: `Statement cycles ${open ? "▾" : "▸"}`,
        title: "The card's transactions by statement cycle" });
    toggle.setAttribute("aria-expanded", String(open));
    toggle.addEventListener("click", () => { open ? blocksOpen.delete(card.id) : blocksOpen.add(card.id); redraw(); });
    const block = el("div", { className: "cc-block" }, toggle);
    if (!open) return block;

    const { grouped, cycles } = cardCycles({ card, statementDay: cardStatementDays.get(card.id), dueDay: cardDueDays.get(card.id), transactions, today: isoDay(new Date()) });
    if (!grouped) block.append(el("div", { className: "cc-note", textContent: "No statement day is set for this card, so the cycles are calendar months. Set one with the ✏️." }));
    const index = Math.min(Math.max(cyclePage.get(card.id) || 0, 0), cycles.length - 1);   // new transactions can change how many there are
    block.append(cycleBlock(card, cycles, index, redraw));
    return block;
}
