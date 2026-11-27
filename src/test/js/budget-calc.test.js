const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* How much of each monthly limit is used, and which budgets need a warning. */
const page = () => createPage().load("core/constants.js", "core/util.js", "panels/insights-calc.js", "panels/budget-calc.js");
const cats = [
    { id: 1, name: "Food", parentId: null },
    { id: 2, name: "Groceries", parentId: 1 },
    { id: 3, name: "Travel", parentId: null },
    { id: 4, name: "Loans Given", parentId: null, systemKey: "LOANS_GIVEN" },
];
let nextId = 1;
const exp = (date, amount, categoryId) => ({ id: nextId++, date, kind: "EXPENSE", amount, categoryId });
const status = (p, budgets, txns, month = "2026-10", today = "2026-10-28") =>
    p.run(`budgetStatus(${JSON.stringify(budgets)}, ${JSON.stringify(txns)}, ${JSON.stringify(cats)}, "${month}", "${today}")`);

test("a category limit counts its sub categories, and the limit on everything counts all spending without Loans Given", () => {
    const p = page();
    const r = status(p, [{ id: 1, categoryId: null, amount: "20000" }, { id: 2, categoryId: 1, amount: "5000" }], [
        exp("2026-10-03", 1000, 1), exp("2026-10-04", 1500, 2),      // Food 2500
        exp("2026-10-05", 4000, 3),                                  // Travel
        exp("2026-10-06", 9000, 4),                                  // Loans Given: not spending
        exp("2026-09-30", 7000, 1),                                  // last month
    ]);
    assert.equal(r.overall.spent, 6500);
    assert.equal(r.overall.pct, 33);
    assert.equal(r.rows.length, 1);
    assert.deepEqual([r.rows[0].name, r.rows[0].spent, r.rows[0].left, r.rows[0].pct, r.rows[0].state], ["Food", 2500, 2500, 50, "ok"]);
});

test("80% used is close, exactly the limit is still ok-to-close, more than the limit is over", () => {
    const p = page();
    assert.equal(p.run(`budgetState(7999, 10000)`), "ok");
    assert.equal(p.run(`budgetState(8000, 10000)`), "close");
    assert.equal(p.run(`budgetState(10000, 10000)`), "close", "all of it used is not yet over it");
    assert.equal(p.run(`budgetState(10001, 10000)`), "over");
});

test("rows needing attention come first; a limit whose category is gone is left out; the running month stops at today", () => {
    const p = page();
    const r = status(p, [{ id: 1, categoryId: 1, amount: "10000" }, { id: 2, categoryId: 3, amount: "1000" }, { id: 3, categoryId: 99, amount: "5" }], [
        exp("2026-10-03", 9000, 1),      // Food 90%: close
        exp("2026-10-04", 1500, 3),      // Travel 150%: over
        exp("2026-10-30", 9000, 3),      // dated after today: not counted yet
    ]);
    assert.deepEqual(r.rows.map(x => [x.name, x.state]), [["Travel", "over"], ["Food", "close"]]);
    assert.equal(r.overall, null);
});

test("an earlier month is counted whole", () => {
    const p = page();
    const r = status(p, [{ id: 1, categoryId: 1, amount: "1000" }], [exp("2026-09-02", 400, 1), exp("2026-09-30", 700, 1)], "2026-09");
    assert.equal(r.rows[0].spent, 1100);
    assert.equal(r.rows[0].state, "over");
});

test("alerts list only this month's budgets that are close or over, the limit on everything first, with plain texts", () => {
    const p = page();
    const budgets = [{ id: 2, categoryId: 1, amount: "1000" }, { id: 1, categoryId: null, amount: "2000" }, { id: 3, categoryId: 3, amount: "9000" }];
    const txns = [exp("2026-10-03", 900, 1), exp("2026-10-04", 1500, 3)];     // Food 90%, everything 120%, Travel 17%
    const alerts = p.run(`budgetAlerts(${JSON.stringify(budgets)}, ${JSON.stringify(txns)}, ${JSON.stringify(cats)}, "2026-10-28")`);
    assert.deepEqual(alerts.map(a => [a.name, a.state]), [["All spending", "over"], ["Food", "close"]]);
    assert.equal(p.run(`budgetText(${JSON.stringify(alerts[0])})`), "All spending: over by ₹400 (₹2,400 of ₹2,000)");
    assert.equal(p.run(`budgetText(${JSON.stringify(alerts[1])})`), "Food: 90% used, ₹100 left (₹900 of ₹1,000)");
    assert.deepEqual(p.run(`budgetAlerts([], [], [], "2026-10-28")`), []);
});

test("a sub category can have its own limit next to its parent's: the parent counts both, the sub only itself", () => {
    const p = page();
    const r = status(p, [{ id: 1, categoryId: 1, amount: "10000" }, { id: 2, categoryId: 2, amount: "1800" }], [
        exp("2026-10-03", 1500, 2),      // Groceries (a sub category of Food)
        exp("2026-10-04", 4000, 1),      // Food itself
        exp("2026-10-05", 800, 3),       // Travel: in neither
    ]);
    const by = Object.fromEntries(r.rows.map(x => [x.name, x]));
    assert.equal(by["Food"].spent, 5500, "the main category counts its sub categories");
    assert.equal(by["Food › Groceries"].spent, 1500, "the sub category counts only itself");
    assert.equal(by["Food › Groceries"].state, "close");
    assert.equal(by["Food"].state, "ok");
    assert.equal(p.run(`budgetName(${JSON.stringify(cats[0])}, ${JSON.stringify(cats)})`), "Food");
});

test("budgets are grouped by main category: its own budget as the head, its sub categories' as children, the worst state first", () => {
    const p = page();
    const rows = status(p, [
        { id: 1, categoryId: 3, amount: "10000" },     // Travel on its own, 10%
        { id: 2, categoryId: 2, amount: "1000" },      // Food › Groceries only (Food has none), 120%
        { id: 3, categoryId: 1, amount: "100000" },    // Food itself, plenty of room
    ], [exp("2026-10-03", 1200, 2), exp("2026-10-04", 1000, 3)]).rows;
    const groups = p.run(`budgetGroups(${JSON.stringify(rows)}, ${JSON.stringify(cats)})`);
    assert.deepEqual(groups.map(g => [g.name, g.state, g.head && g.head.name, g.children.map(c => c.shortName)]),
        [["Food", "over", "Food", ["Groceries"]], ["Travel", "ok", "Travel", []]]);
    const headless = p.run(`budgetGroups(${JSON.stringify(rows.filter(r => r.categoryId === 2))}, ${JSON.stringify(cats)})`);
    assert.equal(headless[0].head, null, "only the sub category has a budget");
    assert.equal(headless[0].name, "Food");
});
