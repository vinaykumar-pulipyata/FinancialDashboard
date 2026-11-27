const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* A credit card's transactions grouped by statement cycle: the sums, and the fold under the card's row. */
function calc() {
    return createPage().load("panels/card-cycles-calc.js");
}
const card = { id: 1, openingBalance: 1000 };
const tx = [
    { id: 1, date: "2026-09-20", fromAccountId: 1, toAccountId: null, amount: 500 },               // cycle 15 Sep - 14 Oct
    { id: 2, date: "2026-10-02", fromAccountId: 1, toAccountId: null, amount: 300, fee: 5 },
    { id: 3, date: "2026-10-15", fromAccountId: 1, toAccountId: null, amount: 100 },               // ON the statement day: the next cycle
    { id: 4, date: "2026-10-20", fromAccountId: 2, toAccountId: 1, amount: 800 }];                 // a payment, after the statement
const cycles = (p, extra = {}) => p.run(`cardCycles(${JSON.stringify({ card, statementDay: 15, dueDay: 5, transactions: tx, today: "2026-10-31", ...extra })})`);

test("cycles run from one statement day up to, not including, the next; a transaction on the statement day belongs to the next one", () => {
    const r = cycles(calc());
    assert.equal(r.grouped, true);
    assert.deepEqual(r.cycles.map(c => [c.start, c.end, c.open]), [["2026-10-15", "2026-11-15", true], ["2026-09-15", "2026-10-15", false]]);
    const [open, closed] = r.cycles;
    assert.deepEqual(closed.purchases.map(t => t.id), [1, 2]);
    assert.deepEqual(open.purchases.map(t => t.id), [3], "the 15th is in the cycle that starts on it");
    assert.deepEqual(open.payments.map(t => t.id), [4]);
});

test("the sums: carried in, spent with fees, paid, and what was owed at the end (the statement for a closed cycle)", () => {
    const [open, closed] = cycles(calc()).cycles;
    assert.deepEqual([closed.carriedIn, closed.spent, closed.paid, closed.owed], [1000, 805, 0, 1805]);
    assert.deepEqual([open.carriedIn, open.spent, open.paid, open.owed], [1805, 100, 800, 1105], "the open cycle starts from the statement");
});

test("the bill of a closed cycle: due on the first due day after the statement, paid by payments from the statement day to that due day", () => {
    const [open, closed] = cycles(calc()).cycles;
    assert.equal(closed.dueDate, "2026-11-05");
    assert.deepEqual(closed.bill, { amount: 1805, paid: 800, left: 1005, state: "part" });
    assert.equal(open.bill, null, "the cycle running has no bill yet");
    const paid = cycles(calc(), { transactions: [...tx, { id: 5, date: "2026-11-01", fromAccountId: 2, toAccountId: 1, amount: 1005 }], today: "2026-11-02" }).cycles.find(c => c.start === "2026-09-15");
    assert.equal(paid.bill.state, "paid");
    const late = cycles(calc(), { today: "2026-11-06" }).cycles.find(c => c.start === "2026-09-15");
    assert.equal(late.bill.state, "overdue", "after the due day with something left");
    const none = cycles(calc(), { transactions: [], today: "2026-10-31" });
    assert.equal(none.cycles.length, 1, "a card with no transactions has just the open cycle");
});

test("a due day before the statement day falls in the next month; a short month uses its last day", () => {
    const p = calc();
    assert.equal(p.run(`dueDateAfter("2026-10-15", 5)`), "2026-11-05");
    assert.equal(p.run(`dueDateAfter("2026-10-15", 25)`), "2026-10-25");
    assert.equal(p.run(`cycleDate(2026, 1, 31)`), "2026-02-28");
    const feb = cycles(p, { statementDay: 31, today: "2026-03-05", transactions: [{ id: 1, date: "2026-02-10", fromAccountId: 1, toAccountId: null, amount: 10 }] });
    assert.deepEqual(feb.cycles.map(c => [c.start, c.end]), [["2026-02-28", "2026-03-31"], ["2026-01-31", "2026-02-28"]]);
});

test("with no statement day the cycles are calendar months, and the result says so", () => {
    const r = cycles(calc(), { statementDay: null, dueDay: null });
    assert.equal(r.grouped, false);
    assert.deepEqual(r.cycles.map(c => c.start), ["2026-10-01", "2026-09-01"]);
    assert.equal(r.cycles[1].dueDate, null);
    assert.equal(r.cycles[1].bill.state, "part", "no due day: every later payment counts, and nothing can be overdue");
});

/* The fold under the card's row */
function view() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/card-cycles-calc.js", "panels/card-cycles.js");
    p.run(`var redraws = 0; var redraw = () => { redraws++; };
        function txnRowElement(t) { return el("div", { className: "txn-row" }, "row " + t.id); }
        var today = isoDay(new Date());
        cardStatementDays = new Map([[1, 15]]); cardDueDays = new Map([[1, 5]]);
        transactions = [{ id: 1, date: "2020-01-20", fromAccountId: 1, toAccountId: null, amount: 500 }];
        var card = { id: 1, name: "Travel Card", openingBalance: 0 };`);
    return p;
}

test("the fold is closed at first; opening it shows the cycle running now, with ‹ › to page back", () => {
    const p = view();
    const closed = p.raw("cardCyclesBlock(card, redraw)");
    assert.equal(closed.find(n => n.className.startsWith("cc-cycle")).length, 0);
    const toggle = closed.find(n => n.className === "cc-toggle")[0];
    assert.equal(toggle.getAttribute("aria-expanded"), "false");
    toggle.fire("click");
    assert.equal(p.run("redraws"), 1, "a click asks for the Accounts view to be drawn again");

    const open = p.raw("cardCyclesBlock(card, redraw)");
    const blocks = open.find(n => n.className.startsWith("cc-cycle"));
    assert.equal(blocks.length, 1, "one cycle at a time");
    assert.ok(blocks[0].className.includes("current"), "it opens on the one running");
    const [older, newer] = open.find(n => n.className === "cc-nav");
    assert.equal(older.textContent, "‹");
    assert.equal(older.disabled, false, "there are earlier cycles");
    assert.equal(newer.disabled, true, "already on the newest");
    assert.match(open.find(n => n.className === "cc-place")[0].textContent, /^(\d+) of \1$/, "the last page of the pages");
    assert.equal(open.find(n => n.className === "cc-summary").length, 1);
});

test("‹ shows the cycle before and › the next, the pager stops at both ends, and the page is kept across a redraw", () => {
    const p = view();
    p.raw("cardCyclesBlock(card, redraw)").find(n => n.className === "cc-toggle")[0].fire("click");
    const label = () => p.raw("cardCyclesBlock(card, redraw)").find(n => n.className === "cc-dates")[0].textContent;
    const nav = () => p.raw("cardCyclesBlock(card, redraw)").find(n => n.className === "cc-nav");
    const newest = label();
    nav()[0].fire("click");                        // ‹
    const before = label();
    assert.notEqual(before, newest);
    assert.equal(p.raw("cardCyclesBlock(card, redraw)").find(n => n.className.startsWith("cc-cycle"))[0].className.includes("current"), false);
    assert.equal(nav()[1].disabled, false, "› is available now");
    nav()[1].fire("click");                        // ›
    assert.equal(label(), newest, "back on the newest");

    for (let i = 0; i < 200 && !nav()[0].disabled; i++) nav()[0].fire("click");   // page back to the oldest
    assert.equal(nav()[0].disabled, true, "no cycle before the first");
    assert.match(p.raw("cardCyclesBlock(card, redraw)").find(n => n.className === "cc-place")[0].textContent, /^1 of \d+$/);
});

/* A card with 25 purchases and 2 payments in the running cycle */
function busy() {
    const p = view();
    p.run(`transactions = [];
        for (let i = 1; i <= 25; i++) transactions.push({ id: i, date: today, fromAccountId: 1, toAccountId: null, amount: 10 });
        transactions.push({ id: 100, date: today, fromAccountId: 2, toAccountId: 1, amount: 50 }, { id: 101, date: today, fromAccountId: 2, toAccountId: 1, amount: 60 });`);
    p.raw("cardCyclesBlock(card, redraw)").find(n => n.className === "cc-toggle")[0].fire("click");
    return p;
}
const open = p => p.raw("cardCyclesBlock(card, redraw)");
const rowsShown = p => open(p).find(n => n.className === "txn-row").length;
const pagerText = p => open(p).find(n => n.className === "pager")[0].allText;
const button = (p, text) => open(p).find(n => n.tag === "button" && n.textContent === text)[0];

test("the cycle's transactions are in pages like the Transactions list: 10 a page, Previous and Next, a size list, and where you are", () => {
    const p = busy();
    assert.equal(rowsShown(p), 10);
    assert.match(pagerText(p), /Showing 1–10 of 27/);
    assert.match(pagerText(p), /Page 1 of 3/);
    assert.equal(button(p, "‹ Previous").disabled, true);
    button(p, "Next ›").fire("click");
    assert.match(pagerText(p), /Showing 11–20 of 27/);
    assert.match(pagerText(p), /Page 2 of 3/, "the page is kept across the redraw");
    button(p, "Next ›").fire("click");
    assert.equal(rowsShown(p), 7, "the last page");
    assert.equal(button(p, "Next ›").disabled, true);
    button(p, "‹ Previous").fire("click");
    assert.match(pagerText(p), /Page 2 of 3/);
});

test("the page size can be changed (it goes back to page 1), and a short cycle has no second page", () => {
    const p = busy();
    button(p, "Next ›").fire("click");
    const size = open(p).find(n => n.className === "page-size")[0];
    size.value = "25"; size.fire("change");
    assert.match(pagerText(p), /Page 1 of 2/, "27 rows at 25 a page");
    assert.equal(rowsShown(p), 25);
});

test("chips narrow the cycle to what was spent or what was paid into the card, newest first, and back to page 1", () => {
    const p = busy();
    button(p, "Next ›").fire("click");
    assert.deepEqual(open(p).find(n => n.className === "chip" || n.className === "chip active").map(n => n.textContent), ["All (27)", "Spent (25)", "Paid into the card (2)"]);
    button(p, "Paid into the card (2)").fire("click");
    assert.equal(rowsShown(p), 2);
    assert.match(pagerText(p), /Showing 1–2 of 2/);
    const text = open(p).find(n => n.className === "txn-row").map(n => n.allText);
    assert.deepEqual(text, ["row 101", "row 100"], "newest first");
    button(p, "Spent (25)").fire("click");
    assert.match(pagerText(p), /Showing 1–10 of 25/);
});

test("a cycle with no transactions says so and has no pager", () => {
    const p = view();
    p.run(`transactions = []`);
    p.raw("cardCyclesBlock(card, redraw)").find(n => n.className === "cc-toggle")[0].fire("click");
    const block = open(p);
    assert.match(block.allText, /Nothing in this cycle\./);
    assert.equal(block.find(n => n.className === "pager").length, 0);
});

test("cycle labels and statuses read plainly", () => {
    const p = view();
    const label = c => p.run(`cycleLabel(${JSON.stringify(c)})`);
    assert.match(label({ start: "2026-09-15", end: "2026-10-15" }), /^15 Sept? – 14 Oct 2026$/, "September is Sep or Sept depending on the browser's locale data");
    assert.match(label({ start: "2025-12-15", end: "2026-01-15" }), /2025 – 14 Jan 2026$/);
    const status = c => p.run(`cycleStatus(${JSON.stringify(c)})`);
    assert.equal(status({ open: true, owed: 1105 }).text, "Open · ₹1,105 owed so far");
    assert.equal(status({ open: false, dueDate: "2026-11-05", bill: { amount: 1805, paid: 0, left: 1805, state: "due" } }).text, "Bill ₹1,805 · due 5 Nov");
    assert.equal(status({ open: false, dueDate: "2026-11-05", bill: { amount: 1805, paid: 800, left: 1005, state: "part" } }).text, "Part paid ₹800 of ₹1,805 · due 5 Nov");
    assert.equal(status({ open: false, dueDate: "2026-11-05", bill: { amount: 1805, paid: 0, left: 1805, state: "overdue" } }).text, "Overdue ₹1,805 · was due 5 Nov");
    assert.equal(status({ open: false, dueDate: null, bill: { amount: 0, paid: 0, left: 0, state: "none" } }).text, "Nothing to pay");
});
