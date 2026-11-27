const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Mutual Funds / Shares line graphs fill the card: they are drawn on a canvas about as wide as the page allows. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/net-worth.js", "panels/summary.js", "panels/allocation.js",
        "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js", "panels/ledger.js", "panels/portfolio-stats.js", "panels/portfolio.js");
    p.run(`function renderAccounts() {}  function clientLog() {}`);
    return p;
}

test("the graph width follows the page: wide on a big window, narrower on a small one, never outside 520 to 1280", () => {
    const p = page();
    assert.equal(p.run("psWidth()"), 720, "no window width known (a test page): the old size");
    const at = w => p.run(`window.innerWidth = ${w}; psWidth()`);
    assert.equal(at(1400), 1206, "94% of 1400 is 1316, less 110 for the margins and padding");
    assert.equal(at(800), 642);
    assert.equal(at(5000), 1280, "capped: the container stops growing at 1400");
    assert.equal(at(300), 520, "a floor so the text stays readable");
});

test("the graph height follows its width, between 230 and 340", () => {
    const p = page();
    assert.deepEqual([720, 1206, 1280, 3000].map(w => p.run(`psHeight(${w})`)), [230, 302, 320, 340]);
});

test("the graphs are drawn for the page less the sidebar, open or folded", () => {
    const p = page();
    const at = (w, bar) => p.run(`window.innerWidth = ${w}; document.documentElement.dataset.sidebar = ${JSON.stringify(bar)}; psWidth()`);
    assert.equal(at(1600, "open"), Math.min(1280, Math.round(Math.min(1400, (1600 - 224) * 0.94) - 110)));
    assert.equal(at(1600, "collapsed"), Math.min(1280, Math.round(Math.min(1400, (1600 - 56) * 0.94) - 110)));
    assert.ok(at(1600, "open") < at(1600, "collapsed") || at(1600, "open") === 1280, "less room when the sidebar is open");
});

test("the range presets count back from today, All has no limit and a custom range uses the typed days", () => {
    const p = page();
    const b = (range, today) => p.run(`psBounds(${JSON.stringify(range)}, new Date(${today}))`);
    assert.deepEqual(b({ preset: "6M" }, "2026, 10, 20"), { from: "2026-05-20", to: "" });
    assert.deepEqual(b({ preset: "1Y" }, "2026, 10, 20"), { from: "2025-11-20", to: "" });
    assert.deepEqual(b({ preset: "5Y" }, "2026, 10, 20"), { from: "2021-11-20", to: "" });
    assert.deepEqual(b({ preset: "6M" }, "2026, 7, 31"), { from: "2026-02-28", to: "" }, "a short month uses its last day");
    assert.deepEqual(b({ preset: "ALL" }, "2026, 10, 20"), { from: "", to: "" });
    assert.deepEqual(b({ preset: "CUSTOM", from: "2024-01-01", to: "2024-12-31" }, "2026, 10, 20"), { from: "2024-01-01", to: "2024-12-31" });
});

test("the graph shows only the points inside the chosen range, and the range buttons redraw", () => {
    const p = page();
    p.run(`portfolios.MUTUAL_FUNDS = { configured: true, accountId: 7, holdings: [] };
        portfolioStats.MUTUAL_FUNDS = { holdings: [], overall: [
            { date: "2020-01-10", investedAmount: 1000, currentValue: 1100 }, { date: "2026-10-01", investedAmount: 2000, currentValue: 2300 },
            { date: "2026-11-10", investedAmount: 2000, currentValue: 2400 }] };
        psRange.MUTUAL_FUNDS = { preset: "ALL", from: "", to: "" };`);
    assert.equal(p.run(`psPoints("MUTUAL_FUNDS").length`), 3);
    p.run(`psRange.MUTUAL_FUNDS = { preset: "1Y", from: "", to: "" }`);
    assert.equal(p.run(`psPoints("MUTUAL_FUNDS").length`), 2);
    p.run(`psRange.MUTUAL_FUNDS = { preset: "CUSTOM", from: "2026-10-15", to: "" }`);
    assert.equal(p.run(`psPoints("MUTUAL_FUNDS").length`), 1);
    p.run(`psRange.MUTUAL_FUNDS = { preset: "CUSTOM", from: "2030-01-01", to: "" }`);
    assert.match(p.run(`psRangeText("MUTUAL_FUNDS")`), /^from /);
    const view = p.raw(`portfolioStatsView("MUTUAL_FUNDS")`);
    assert.match(view.allText, /No value was recorded from .*Pick a longer range/);
    const chips = view.find(n => n.className === "ps-range")[0].find(n => String(n.className).startsWith("chip")).map(n => n.textContent);
    assert.deepEqual(chips, ["6M", "1Y", "3Y", "5Y", "All"]);
});
