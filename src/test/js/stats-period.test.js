const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* Stats has its own period: Weekly, Monthly (default), Annually, Period. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "panels/period-filter.js", "panels/stats-period.js");
    p.run("var drawn = 0; function renderCharts() { drawn++; renderStatsPeriod(); }");
    return p;
}

test("Monthly is the default and shows the current calendar month", () => {
    const p = page();
    const now = new Date();
    assert.equal(p.run("statsMode"), "MONTHLY");
    const r = p.run("statsRange()");
    assert.equal(r.from, `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-01`);
    assert.equal(r.to.slice(0, 7), r.from.slice(0, 7));
    assert.ok(Number(r.to.slice(8)) >= 28);
});

test("a week runs Monday to Sunday, whichever day inside it is shown", () => {
    const p = page();
    p.run(`statsMode = "WEEKLY"`);
    for (const day of [12, 14, 18]) {
        p.run(`statsAnchor = new Date(2026, 9, ${day})`);
        const r = p.run("statsRange()");
        assert.deepEqual([r.from, r.to], ["2026-10-12", "2026-10-18"], `anchor ${day}`);
    }
});

test("the arrows move by a week, a month or a year, also over year ends", () => {
    const p = page();
    p.run(`statsMode = "WEEKLY"; statsAnchor = new Date(2026, 9, 14); statsStep(-1)`);
    assert.equal(p.run("statsRange().from"), "2026-10-05");
    p.run(`statsStep(1); statsStep(1)`);
    assert.equal(p.run("statsRange().from"), "2026-10-19");
    p.run(`statsMode = "MONTHLY"; statsAnchor = new Date(2026, 0, 15); statsStep(-1)`);
    assert.equal(p.run("statsRange().label"), "December 2025");
    assert.equal(p.run("statsRange().to"), "2025-12-31");
    p.run(`statsMode = "ANNUALLY"; statsAnchor = new Date(2026, 5, 1)`);
    assert.deepEqual([p.run("statsRange().from"), p.run("statsRange().to"), p.run("statsRange().phrase")], ["2026-01-01", "2026-12-31", "in 2026"]);
    p.run("statsStep(-1)");
    assert.equal(p.run("statsRange().label"), "2025");
});

test("the weekly arrows move exactly one week from the first and the last day of a week", () => {
    const p = page();
    p.run(`statsMode = "WEEKLY"; statsAnchor = new Date(2026, 9, 12); statsStep(-1)`);        // a Monday, back
    assert.deepEqual([p.run("statsRange().from"), p.run("statsRange().to")], ["2026-10-05", "2026-10-11"]);
    p.run(`statsAnchor = new Date(2026, 9, 18); statsStep(1)`);                                // a Sunday, forward
    assert.deepEqual([p.run("statsRange().from"), p.run("statsRange().to")], ["2026-10-19", "2026-10-25"]);
    p.run(`statsAnchor = new Date(2026, 9, 12); statsStep(1); statsStep(-1)`);                 // there and back
    assert.equal(p.run("statsRange().from"), "2026-10-12");
});

test("Period uses any From and To, and either may be open", () => {
    const p = page();
    p.run(`statsMode = "PERIOD"; statsFrom = "2026-07-01"; statsTo = "2026-09-30"`);
    assert.match(p.run("statsRange().phrase"), /^in the period/);
    p.run(`statsTo = ""`);
    assert.match(p.run("statsRange().label"), /^from /);
    p.run(`statsFrom = ""; statsTo = "2026-09-30"`);
    assert.match(p.run("statsRange().label"), /^up to /);
    p.run(`statsTo = ""`);
    assert.deepEqual([p.run("statsRange().label"), p.run("statsPeriodIn()")], ["All time", "so far"]);
});

test("statsInPeriod counts the edges and everything when open", () => {
    const p = page();
    p.run(`statsMode = "PERIOD"; statsFrom = "2026-07-01"; statsTo = "2026-09-30"`);
    assert.deepEqual(["2026-07-01", "2026-09-30", "2026-06-30", "2026-10-01"].map(d => p.run(`statsInPeriod({ date: "${d}" })`)), [true, true, false, false]);
    p.run(`statsFrom = ""; statsTo = ""`);
    assert.equal(p.run(`statsInPeriod({ date: "1999-01-01" })`), true);
});

test("the category line graph spans at least 12 months ending with the period, and never starts before the data", () => {
    const p = page();
    const nowKey = p.run(`monthKey(new Date().getFullYear(), new Date().getMonth())`);
    let keys = p.run(`statsMonthKeys("2020-01")`);
    assert.equal(keys.length, 12);
    assert.equal(keys[11], nowKey);
    assert.deepEqual(p.run(`statsMonthKeys("${nowKey}")`), [nowKey]);                       // data only from this month
    p.run(`statsMode = "ANNUALLY"; statsAnchor = new Date(2024, 3, 1)`);
    keys = p.run(`statsMonthKeys("2020-01")`);
    assert.deepEqual([keys[0], keys[11], keys.length], ["2024-01", "2024-12", 12]);
    p.run(`statsMode = "WEEKLY"; statsAnchor = new Date(2024, 3, 10)`);
    keys = p.run(`statsMonthKeys("2020-01")`);
    assert.deepEqual([keys[0], keys[11]], ["2023-05", "2024-04"]);
    p.run(`statsMode = "PERIOD"; statsFrom = "2023-01-15"; statsTo = "2025-06-30"`);
    keys = p.run(`statsMonthKeys("2020-01")`);
    assert.deepEqual([keys.length, keys[0], keys[keys.length - 1]], [30, "2023-01", "2025-06"]);
    p.run(`statsFrom = "2025-05-01"; statsTo = "2025-06-30"`);
    assert.equal(p.run(`statsMonthKeys("2020-01").length`), 12);                            // a short period still gets a line
    p.run(`statsFrom = ""; statsTo = ""`);
    assert.equal(p.run(`statsMonthKeys("2022-03")[0]`), "2022-03");
    assert.equal(p.run(`shiftMonthKey("2026-01", -1)`), "2025-12");
    assert.equal(p.run(`shiftMonthKey("2026-12", 1)`), "2027-01");
});

test("the buttons: four modes, Monthly filled in, arrows with the period's name, nothing after the current period", () => {
    const p = page();
    p.run("renderStatsPeriod()");
    const box = p.node("statsPeriod");
    const chips = box.children[0].children;
    assert.deepEqual(chips.map(c => c.textContent), ["Weekly", "Monthly", "Annually", "Period"]);
    assert.ok(chips[1].classList.contains("active") && !chips[0].classList.contains("active"));
    let nav = box.children[1];
    assert.equal(nav.children.length, 3);                              // ‹ name ›
    assert.ok(nav.children[1].textContent.includes(String(new Date().getFullYear())));
    assert.equal(nav.children[0].disabled === true, false);
    assert.equal(nav.children[2].disabled, true, "no later period than the current one");

    chips[0].fire("click");
    assert.equal(p.run("statsMode"), "WEEKLY");
    assert.equal(p.run("drawn"), 1, "the charts are redrawn");
    box.children[1].children[0].fire("click");                         // one week back
    assert.notEqual(box.children[1].children[2].disabled, true, "now a later week exists");

    box.children[0].children[3].fire("click");                         // Period
    assert.equal(p.run("statsMode"), "PERIOD");
    assert.ok(p.run("statsFrom") && p.run("statsTo"), "it starts from what was on show");
    nav = box.children[1];
    assert.deepEqual(nav.children.map(c => c.tag), ["label", "label"], "From and To boxes instead of arrows");

    box.children[0].children[1].fire("click");                         // back to Monthly
    assert.equal(box.children[1].children.length, 3);
    assert.ok(p.run("statsRange().label").includes(String(new Date().getFullYear())));
});
