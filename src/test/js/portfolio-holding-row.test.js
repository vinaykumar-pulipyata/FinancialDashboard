const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* One holding on a Mutual Funds or Shares card: two columns, every figure to the paisa. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/summary.js", "panels/allocation.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js",
        "panels/ledger.js", "panels/portfolio-stats.js", "panels/portfolio.js");
    p.run(`function renderAccounts() {} function clientLog() {}
        portfolios.SHARES = { configured: true, accountId: 9, holdings: [] }; transactions = [];`);
    return p;
}
const row = (p, h, kind = "SHARES") => p.raw(`holdingRow(${JSON.stringify(h)}, "${kind}")`);
const text = (node, cls) => node.find(n => n.className.split(" ").includes(cls))[0].textContent;

test("a share with a quantity reads Name - Qty n, Avg invested/quantity, then the current value, then the invested value, all exact", () => {
    const p = page();
    const r = row(p, { id: 1, name: "Infosys", quantity: 10, investedAmount: 15234.5, currentValue: 17500.25, gain: 2265.75, gainPercent: 14.9 });
    const left = r.find(n => n.className === "pf-left")[0];
    assert.equal(left.find(n => n.className === "lg-name")[0].textContent, "Infosys - Qty 10, Avg ₹1,523.45");
    assert.equal(text(left, "pf-current"), "₹17,500.25", "not rounded to whole rupees");
    assert.equal(text(left, "pf-invested"), "Invested ₹15,234.50", "two decimals, never rounded");
    assert.ok(left.find(n => n.className.includes("pf-current"))[0].className.includes("up"), "profit is green");
    const right = r.find(n => n.className === "pf-right")[0];
    assert.equal(text(right, "pf-gain"), "▲ +₹2,265.75");
    assert.equal(text(right, "pf-percent"), "+14.9%");
});

test("a loss is red with a minus and a down arrow; with no quantity only the name is shown", () => {
    const p = page();
    const r = row(p, { id: 2, name: "Yes Bank", quantity: null, investedAmount: 1000, currentValue: 874.1, gain: -125.9, gainPercent: -12.6 });
    assert.equal(r.find(n => n.className === "lg-name")[0].textContent, "Yes Bank");
    assert.ok(r.find(n => n.className.includes("pf-current"))[0].className.includes("down"));
    assert.equal(text(r, "pf-gain"), "▼ −₹125.90");
    assert.equal(text(r, "pf-percent"), "−12.6%");
});

test("a mutual fund calls the quantity Units", () => {
    const p = page();
    const r = row(p, { id: 3, name: "Index fund", quantity: 25.5, investedAmount: 51, currentValue: 51, gain: 0, gainPercent: 0 }, "MUTUAL_FUNDS");
    assert.equal(r.find(n => n.className === "lg-name")[0].textContent, "Index fund - Units 25.5, Avg ₹2.00");
});

test("the holdings of a card are listed by name, A to Z, ignoring capitals, and so is the list in the dialogs", () => {
    const p = page();
    p.run(`portfolios.SHARES = { configured: true, accountId: 9, totalInvested: 0, investedAmount: 0, currentValue: 0, gain: 0, gainPercent: 0, holdings: [
        { id: 1, name: "tata Motors", investedAmount: 1, currentValue: 1, gain: 0, gainPercent: 0 },
        { id: 2, name: "Infosys", investedAmount: 1, currentValue: 1, gain: 0, gainPercent: 0 },
        { id: 3, name: "HDFC Bank", investedAmount: 1, currentValue: 1, gain: 0, gainPercent: 0 },
        { id: 4, name: "ITC", investedAmount: 1, currentValue: 1, gain: 0, gainPercent: 0 }] }`);
    p.run(`portfolioExpanded.SHARES = true`);
    const names = p.raw(`portfolioCard("SHARES")`).find(n => n.className === "lg-name").map(n => n.textContent);
    assert.deepEqual(names, ["HDFC Bank", "Infosys", "ITC", "tata Motors"]);
    assert.deepEqual(p.run(`portfolios.SHARES.holdings.map(h => h.name)`), ["tata Motors", "Infosys", "HDFC Bank", "ITC"], "the state keeps its own order");
});
