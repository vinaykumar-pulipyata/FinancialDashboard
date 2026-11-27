const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The month and spending helpers shared with Budgets. */
const page = () => createPage().load("core/constants.js", "core/util.js", "panels/insights-calc.js");
const cats = [
    { id: 1, name: "Food", parentId: null },
    { id: 2, name: "Groceries", parentId: 1 },
    { id: 3, name: "Shopping", parentId: null },
    { id: 4, name: "Loans Given", parentId: null, systemKey: "LOANS_GIVEN" },
    { id: 5, name: "Balance Adjustment", parentId: null, systemKey: "BALANCE_ADJUSTMENT_EXPENSE" },
];
let nextId = 1;
const exp = (date, amount, categoryId) => ({ id: nextId++, date, kind: "EXPENSE", amount, categoryId });
test("month helpers cross the year end", () => {
    const p = page();
    assert.equal(p.run(`insPreviousMonth("2026-01")`), "2025-12");
});

test("a Balance Adjustment is not spending: it is left out of the spending, like a loan", () => {
    const p = page();
    const spending = p.run(`insSpending(${JSON.stringify([exp("2026-10-05", 100, 1), exp("2026-10-06", 5000, 5), exp("2026-10-07", 700, 4)])}, ${JSON.stringify(cats)})`);
    assert.deepEqual(spending.map(t => t.amount), [100]);
});

test("the biggest expense of a month ignores other months, lending and adjustments; none gives null", () => {
    const p = page();
    const spending = JSON.stringify([exp("2026-10-05", 100, 1), exp("2026-10-20", 900, 3), exp("2026-10-21", 99999, 4), exp("2026-09-30", 5000, 3)]);
    const calc = (list, m) => p.run(`(() => { const s = insSpending(${list}, ${JSON.stringify(cats)}); return biggestExpense(s, "${m}"); })()`);
    assert.equal(calc(spending, "2026-10").amount, 900);
    assert.equal(calc(spending, "2026-08"), null);
});

test("observations: rises, falls and frequent small expenses; a running month uses the same days of the month before", () => {
    const p = page();
    const list = [exp("2026-09-05", 1000, 1), exp("2026-09-25", 5000, 1), exp("2026-10-04", 3000, 1),
        exp("2026-09-06", 4000, 3), exp("2026-10-06", 1000, 3),
        ...[1, 2, 3, 4, 5].map(d => exp(`2026-10-0${d}`, 100, 2))];
    const run = today => p.run(`spendingObservations(insSpending(${JSON.stringify(list)}, ${JSON.stringify(cats)}), "2026-10", "${today}")`);
    const done = run("2026-11-02");
    assert.deepEqual(done.increased.map(r => [r.category, r.pct]), []);          // Food 3500 vs 6000 fell
    assert.deepEqual(done.decreased.map(r => r.category), ["Shopping", "Food"]);
    assert.deepEqual(done.frequentSmall.map(r => [r.category, r.count, r.total]), [["Food", 5, 500]]);
    const running = run("2026-10-10");                                           // Food: 3500 against 1000 of the same days
    assert.deepEqual(running.increased.map(r => [r.category, r.pct]), [["Food", 250]]);
    assert.equal(running.running, true);
});

test("month transaction stats count kinds, the busiest day and active days", () => {
    const p = page();
    const list = [{ date: "2026-10-02", kind: "EXPENSE" }, { date: "2026-10-02", kind: "INCOME" }, { date: "2026-10-05", kind: "TRANSFER" },
        { date: "2026-10-05", kind: "EXPENSE" }, { date: "2026-10-05", kind: "EXPENSE" }, { date: "2026-09-30", kind: "EXPENSE" }];
    const r = p.run(`monthTransactionStats(${JSON.stringify(list)}, "2026-10")`);
    assert.deepEqual([r.total, r.expense, r.income, r.transfer, r.busiestDay, r.busiestCount, r.activeDays], [5, 3, 1, 1, "2026-10-05", 3, 2]);
});
