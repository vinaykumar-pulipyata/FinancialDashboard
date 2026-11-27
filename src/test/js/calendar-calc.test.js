const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

function page() {
    return createPage().load("core/constants.js", "core/util.js", "panels/calendar-calc.js");
}
const cats = `[{ id: 1, name: "Food", systemKey: null }, { id: 2, name: "Loans Given", systemKey: "LOANS_GIVEN" }, { id: 3, name: "Loans Collected", systemKey: "LOANS_COLLECTED" },
    { id: 4, name: "Adj", systemKey: "BALANCE_ADJUSTMENT_EXPENSE" }, { id: 5, name: "Salary", systemKey: null }, { id: 6, name: "Refund", systemKey: null }]`;
const run = (p, tx, y = 2026, m = 10, today = "2026-11-20") => p.run(`monthHeat(${tx}, ${cats}, ${y}, ${m}, "${today}")`);

test("a day totals its expenses and its income apart, with how many and the average", () => {
    const p = page();
    const days = run(p, `[
        { date: "2026-11-15", kind: "EXPENSE", amount: 1000, categoryId: 1 }, { date: "2026-11-15", kind: "EXPENSE", amount: "3250", categoryId: 1 },
        { date: "2026-11-15", kind: "INCOME", amount: 12000, categoryId: 5 }, { date: "2026-11-15", kind: "INCOME", amount: 500, categoryId: 6 }]`);
    const d = days[14];
    assert.equal(d.iso, "2026-11-15");
    assert.deepEqual([d.expense.total, d.expense.count, d.expense.average], [4250, 2, 2125]);
    assert.deepEqual([d.income.total, d.income.count, d.income.average], [12500, 2, 6250], "a refund is income, as in Stats; income and expenses are never netted");
});

test("every day of the month is there, empty ones with zeros and level 0", () => {
    const p = page();
    const days = run(p, "[]");
    assert.equal(days.length, 30);
    assert.deepEqual([days[0].iso, days[29].iso], ["2026-11-01", "2026-11-30"]);
    assert.ok(days.every(d => d.expense.level === 0 && d.income.level === 0 && d.expense.total === 0));
});

test("transfers, Loans Given and Collected and balance adjustments are not income or expenses", () => {
    const p = page();
    const days = run(p, `[
        { date: "2026-11-03", kind: "TRANSFER", amount: 900 }, { date: "2026-11-03", kind: "EXPENSE", amount: 700, categoryId: 2 },
        { date: "2026-11-03", kind: "INCOME", amount: 400, categoryId: 3 }, { date: "2026-11-03", kind: "EXPENSE", amount: 120, categoryId: 4 },
        { date: "2026-11-03", kind: "EXPENSE", amount: 50, categoryId: 1 }]`);
    assert.deepEqual([days[2].expense.total, days[2].expense.count, days[2].income.total], [50, 1, 0]);
});

test("other months' transactions are left out, and the month boundaries are the first and last day", () => {
    const p = page();
    const days = run(p, `[{ date: "2026-10-31", kind: "EXPENSE", amount: 5, categoryId: 1 }, { date: "2026-12-01", kind: "EXPENSE", amount: 6, categoryId: 1 },
        { date: "2026-11-01", kind: "EXPENSE", amount: 7, categoryId: 1 }, { date: "2026-11-30", kind: "EXPENSE", amount: 8, categoryId: 1 }]`);
    assert.deepEqual([days[0].expense.total, days[29].expense.total], [7, 8]);
    assert.equal(days.reduce((s, d) => s + d.expense.total, 0), 15);
});

test("month lengths: February in a leap year and in an ordinary year, and 31-day months", () => {
    const p = page();
    assert.equal(run(p, "[]", 2028, 1).length, 29);
    assert.equal(run(p, "[]", 2026, 1).length, 28);
    assert.equal(run(p, "[]", 2026, 11).length, 31);
    assert.equal(run(p, "[]", 2028, 1)[28].iso, "2028-02-29");
});

test("levels go from the days' own spread: zero is 0, the biggest is 4, the smallest active day 1, and no rupee limit is involved", () => {
    const p = page();
    const level = values => p.run(`(() => { const f = heatLevelOf(${JSON.stringify(values)}); return ${JSON.stringify(values)}.map(f); })()`);
    assert.deepEqual(level([0, 10, 20, 30, 40]), [0, 1, 2, 3, 4]);
    assert.deepEqual(level([0, 100000, 200000, 300000, 400000]), [0, 1, 2, 3, 4], "the same shape at another size gives the same levels");
});

test("one huge day is the top level and does not push the small days into one level; Rs 699, Rs 30,000 and Rs 60,000 are three different levels", () => {
    const p = page();
    const level = values => p.run(`(() => { const f = heatLevelOf(${JSON.stringify(values)}); return ${JSON.stringify(values)}.map(f); })()`);
    assert.deepEqual(level([100, 200, 300, 400, 5000000]), [1, 1, 2, 2, 4]);
    // a month of ordinary days with a few big ones: a small day among the busiest few no longer shares a level with a 60,000 day
    const month = [...Array(20).fill(0).map((_, i) => 100 + i * 30), 699, 30000, 60000];
    const [small, big, biggest] = level(month).slice(-3);
    assert.ok(small < big && big < biggest, `699, 30000 and 60000 should be three levels, got ${small} ${big} ${biggest}`);
    assert.equal(biggest, 4);
});

test("a month with one active day, or all days alike, reads as the middle level; a month with none has no levels", () => {
    const p = page();
    const level = values => p.run(`(() => { const f = heatLevelOf(${JSON.stringify(values)}); return ${JSON.stringify(values)}.map(f); })()`);
    assert.deepEqual(level([0, 0, 750, 0]), [0, 0, 3, 0]);
    assert.deepEqual(level([100, 100, 100]), [3, 3, 3]);
    assert.deepEqual(level([0, 0, 0]), [0, 0, 0]);
});

test("income and expenses get their levels separately", () => {
    const p = page();
    const days = run(p, `[
        { date: "2026-11-02", kind: "EXPENSE", amount: 10, categoryId: 1 }, { date: "2026-11-03", kind: "EXPENSE", amount: 99999, categoryId: 1 },
        { date: "2026-11-02", kind: "INCOME", amount: 50000, categoryId: 5 }, { date: "2026-11-03", kind: "INCOME", amount: 20, categoryId: 5 }]`);
    assert.deepEqual([days[1].expense.level, days[2].expense.level], [1, 4], "two active days: the tiny one is the lowest level, the huge one the highest");
    assert.deepEqual([days[1].income.level, days[2].income.level], [4, 1], "the income order is its own, the opposite of the expenses");
});

test("future days are neutral, level 0, and are not counted as quiet days; today is", () => {
    const p = page();
    const days = run(p, `[{ date: "2026-11-25", kind: "EXPENSE", amount: 300, categoryId: 1 }, { date: "2026-11-10", kind: "EXPENSE", amount: 100, categoryId: 1 }]`);
    assert.equal(days[19].future, false, "the 20th is today");
    assert.equal(days[20].future, true);
    assert.equal(days[24].expense.level, 0, "a future day is never coloured");
    const summary = p.run(`heatSummary(monthHeat([{ date: "2026-11-10", kind: "EXPENSE", amount: 100, categoryId: 1 }], ${cats}, 2026, 10, "2026-11-20"), "expense")`);
    assert.deepEqual([summary.activeDays, summary.quietDays], [1, 19], "twenty days so far, one with spending");
});

test("the summary: total, active days, quiet days, the highest day and the average per active day", () => {
    const p = page();
    const days = `monthHeat([{ date: "2026-11-02", kind: "EXPENSE", amount: 100, categoryId: 1 }, { date: "2026-11-04", kind: "EXPENSE", amount: 300, categoryId: 1 },
        { date: "2026-11-04", kind: "EXPENSE", amount: 200, categoryId: 1 }, { date: "2026-11-09", kind: "INCOME", amount: 9000, categoryId: 5 }], ${cats}, 2026, 10, "2026-11-30")`;
    const expense = p.run(`heatSummary(${days}, "expense")`);
    assert.deepEqual([expense.total, expense.activeDays, expense.quietDays, expense.averagePerActiveDay], [600, 2, 28, 300]);
    assert.deepEqual(expense.highest, { iso: "2026-11-04", amount: 500 });
    const income = p.run(`heatSummary(${days}, "income")`);
    assert.deepEqual([income.total, income.activeDays, income.quietDays], [9000, 1, 29]);
    const none = p.run(`heatSummary(monthHeat([], ${cats}, 2026, 10, "2026-11-30"), "income")`);
    assert.deepEqual([none.total, none.activeDays, none.highest, none.averagePerActiveDay], [0, 0, null, 0]);
});

test("the inputs are not changed", () => {
    const p = page();
    const before = p.run(`(() => { const t = [{ date: "2026-11-02", kind: "EXPENSE", amount: "100", categoryId: 1 }]; const c = ${cats};
        const copy = JSON.stringify([t, c]); monthHeat(t, c, 2026, 10, "2026-11-30"); heatSummary(monthHeat(t, c, 2026, 10, "2026-11-30"), "expense"); return JSON.stringify([t, c]) === copy; })()`);
    assert.equal(before, true);
});

test("the tooltip words: totals, count and average for a day with activity, a plain line for one without or one still to come", () => {
    const p = page();
    const days = `monthHeat([{ date: "2026-11-15", kind: "EXPENSE", amount: 1000, categoryId: 1 }, { date: "2026-11-15", kind: "EXPENSE", amount: 3250, categoryId: 1 }], ${cats}, 2026, 10, "2026-11-20")`;
    assert.deepEqual(p.run(`heatText(${days}[14], "expense", "November 15, 2026")`).lines, ["Total expenses: ₹4,250.00", "Transactions: 2", "Average transaction: ₹2,125.00", "Level: level"].map(s => s.replace("level", p.run(`HEAT_WORDS[${days}[14].expense.level]`))));
    assert.deepEqual(p.run(`heatText(${days}[14], "income", "November 15, 2026")`).lines, ["No income"]);
    assert.deepEqual(p.run(`heatText(${days}[1], "expense", "November 2, 2026")`).lines, ["No expenses"]);
    assert.deepEqual(p.run(`heatText(${days}[25], "expense", "November 26, 2026")`).lines, ["Not yet"]);
});
