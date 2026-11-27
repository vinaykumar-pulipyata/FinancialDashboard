const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Stats rings: every category is its own slice, never folded into "Other". */
function page(count) {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "core/tooltip.js", "panels/net-worth.js", "panels/period-filter.js",
        "panels/stats-period.js", "panels/transactions.js", "panels/charts.js");
    p.run(`allCategories = Array.from({ length: ${count} }, (_, i) => ({ id: i + 1, name: "Cat " + i, kind: "EXPENSE", parentId: null }));
        statsMode = "PERIOD"; statsFrom = "2026-01-01"; statsTo = "2026-12-31";
        transactions = [...allCategories.map((c, i) => ({ id: c.id, kind: "EXPENSE", categoryId: c.id, amount: 1000 - i * 10, date: "2026-03-05" })),
                        { id: 99, kind: "EXPENSE", categoryId: null, amount: 5, date: "2026-03-06" }];`);
    return p;
}

test("eight categories make eight slices plus one for no category, and none is called Other", () => {
    const p = page(8);
    const { slices } = p.run("breakdown('EXPENSE')");
    assert.deepEqual(slices.map(s => s.name), ["Cat 0", "Cat 1", "Cat 2", "Cat 3", "Cat 4", "Cat 5", "Cat 6", "Cat 7", "Uncategorised"]);
    assert.deepEqual(slices.map(s => s.colour), ["s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "so"]);
});

test("a thirteenth category reuses the first colour but keeps its own key", () => {
    const p = page(13);
    const { slices } = p.run("breakdown('EXPENSE')");
    assert.equal(new Set(slices.map(s => s.key)).size, 14);
    assert.equal(slices[12].key, "s12");
    assert.equal(slices[12].colour, "s0");
});

test("the slices are listed by this period's amount, biggest first, while each keeps the colour of its all-time rank", () => {
    const p = page(4);
    p.run(`statsFrom = "2026-03-01"; statsTo = "2026-03-31";
        transactions.push({ id: 100, kind: "EXPENSE", categoryId: 4, amount: 5000, date: "2026-03-10" },
                          { id: 101, kind: "EXPENSE", categoryId: 3, amount: 5000, date: "2025-12-10" });`);
    // all time Cat 2 ranks first (5,980 against Cat 3's 5,970), so it has the first colour; in March Cat 3 is far bigger and must be listed first
    const { slices } = p.run("breakdown('EXPENSE')");
    assert.equal(slices[0].name, "Cat 3");
    assert.equal(slices[0].colour, "s1", "its colour is its all-time rank, not its place in the list");
    const real = slices.filter(s => s.colour !== "so").map(s => s.amount);
    assert.deepEqual(real, [...real].sort((a, b) => b - a), "decreasing");
    assert.equal(slices[slices.length - 1].colour, "so", "no category last");
});

test("a Balance Adjustment is left out of the rings, like a loan: it only corrects a balance", () => {
    const p = page(2);
    p.run(`allCategories.push({ id: 90, name: "Balance Adjustment", kind: "EXPENSE", parentId: null, systemKey: "BALANCE_ADJUSTMENT_EXPENSE" });
        transactions.push({ id: 200, kind: "EXPENSE", categoryId: 90, amount: 50000, date: "2026-03-07" });`);
    const { slices, total } = p.run("breakdown('EXPENSE')");
    assert.ok(!slices.some(s => s.name === "Balance Adjustment"));
    assert.equal(total, 1000 + 990 + 5, "the two categories and the one with no category");
});
