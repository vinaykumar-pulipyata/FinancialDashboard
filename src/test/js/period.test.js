const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The shared Today / From / To range. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/period-filter.js");
    p.run("var txnPage = 3; function setTxnPage(v) { txnPage = v; } var renders = 0; function renderPeriod() { renders++; } function calendarFollowPeriod() {}");   // what it calls into
    return p;
}

test("with no range every transaction counts; the edges of a range are inclusive", () => {
    const p = page();
    assert.equal(p.run(`inPeriod({ date: "1999-01-01" })`), true);
    p.run(`periodFrom = "2026-07-01"; periodTo = "2026-09-30"`);
    assert.equal(p.run(`inPeriod({ date: "2026-07-01" })`), true);
    assert.equal(p.run(`inPeriod({ date: "2026-09-30" })`), true);
    assert.equal(p.run(`inPeriod({ date: "2026-06-30" })`), false);
    assert.equal(p.run(`inPeriod({ date: "2026-10-01" })`), false);
    p.run(`periodFrom = "2026-07-01"; periodTo = ""`);
    assert.equal(p.run(`inPeriod({ date: "2030-01-01" })`), true);
});

test("Today sets the range to today, back to page 1, and redraws; All time clears it", () => {
    const p = page();
    p.run(`applyRange("TODAY")`);
    const today = p.run(`isoDay(new Date())`);
    assert.equal(p.run("periodRange"), "TODAY");
    assert.equal(p.run("periodFrom"), today);
    assert.equal(p.run("periodTo"), today);
    assert.equal(p.run("txnPage"), 1);
    assert.equal(p.run("renders"), 1);
    p.run(`applyRange("ALL")`);
    assert.equal(p.run("periodFrom"), "");
    assert.equal(p.run("periodTo"), "");
    assert.equal(p.run("periodLabel()"), "All time");
});

test("the range is described in words for captions and sentences", () => {
    const p = page();
    assert.equal(p.run("periodIn()"), "so far");
    p.run(`periodRange = "TODAY"`);
    assert.equal(p.run("periodIn()"), "today");
    p.run(`periodRange = "CUSTOM"; periodFrom = "2026-07-01"; periodTo = "2026-09-30"`);
    assert.equal(p.run("periodLabel()"), "01 Jul 2026 to 30 Sept 2026");
    assert.match(p.run("periodIn()"), /^in the period 01 Jul 2026 to 30 Sept 2026$/);
    p.run(`periodTo = ""`);
    assert.equal(p.run("periodLabel()"), "from 01 Jul 2026");
    p.run(`periodFrom = ""; periodTo = "2026-09-30"`);
    assert.equal(p.run("periodLabel()"), "up to 30 Sept 2026");
});

test("periodMonthKeys covers the range's months, never before the first data, never after this month", () => {
    const p = page();
    p.run(`periodRange = "CUSTOM"; periodFrom = "2025-11-15"; periodTo = "2026-02-10"`);
    assert.deepEqual(p.run(`periodMonthKeys("2020-01")`), ["2025-11", "2025-12", "2026-01", "2026-02"]);
    p.run(`periodFrom = "2019-01-01"`);
    assert.equal(p.run(`periodMonthKeys("2025-12")[0]`), "2025-12");           // not before the first month with data
    p.run(`periodFrom = ""; periodTo = "2999-01-01"`);
    const nowKey = p.run(`monthKey(new Date().getFullYear(), new Date().getMonth())`);
    const keys = p.run(`periodMonthKeys("2025-01")`);
    assert.equal(keys[keys.length - 1], nowKey);
    assert.equal(p.run(`periodMonthKeys("2000-01", 5).length`), 5);              // the cap keeps the latest months
    assert.equal(p.run(`monthShort("2026-09")`), "Sep");
});

test("a From after To is never kept: the date just typed wins and the other one moves to meet it", () => {
    const p = page();
    assert.deepEqual(p.run(`rangeAfterEdit("from", "2026-10-20", "2026-10-05")`), ["2026-10-20", "2026-10-20"]);
    assert.deepEqual(p.run(`rangeAfterEdit("to", "2026-10-20", "2026-10-05")`), ["2026-10-05", "2026-10-05"]);
    assert.deepEqual(p.run(`rangeAfterEdit("from", "2026-10-05", "2026-10-20")`), ["2026-10-05", "2026-10-20"]);   // fine as it is
    assert.deepEqual(p.run(`rangeAfterEdit("from", "2026-10-05", "")`), ["2026-10-05", ""]);                       // an open end is fine
    assert.deepEqual(p.run(`rangeAfterEdit("to", "", "2026-10-05")`), ["", "2026-10-05"]);
});
