const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The figures beside a Mutual Funds or Shares card's heading: Total invested, the difference, and what the sales made. */
function page(view) {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/summary.js", "panels/allocation.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js",
        "panels/ledger.js", "panels/portfolio-stats.js", "panels/portfolio.js");
    p.run(`function renderAccounts() {} function clientLog() {} transactions = [];
        portfolios.MUTUAL_FUNDS = Object.assign({ configured: true, accountId: 9, kind: "MUTUAL_FUNDS", investedAmount: 0, totalInvested: 0, currentValue: 0,
            gain: 0, gainPercent: null, realizedGain: 0, holdings: [{ id: 1, name: "Small Cap Fund", investedAmount: 0, currentValue: 0, gain: 0, gainPercent: null }] }, ${JSON.stringify(view)});`);
    return p;
}
const pills = p => p.raw(`portfolioCard("MUTUAL_FUNDS")`).find(n => n.className === "pf-total").map(n => n.allText);

test("after everything is sold, Total invested and the difference are back to nothing and the card shows what the sales made", () => {
    const p = page({ realizedGain: 4415.28 });
    const shown = pills(p);
    assert.ok(shown.includes("Total invested ₹0.00"), shown.join(" | "));
    assert.ok(shown.includes("Difference ₹0.00"), shown.join(" | "));
    const profit = shown.find(t => t.startsWith("Profit from sales"));
    assert.equal(profit, "Profit from sales +₹4,415.28");
    assert.ok(p.raw(`portfolioCard("MUTUAL_FUNDS")`).find(n => n.tag === "strong" && String(n.className).includes("up")).length >= 1, "a profit is green");
});

test("a loss from sales says loss, with a minus", () => {
    const shown = pills(page({ realizedGain: -100 }));
    assert.equal(shown.find(t => t.startsWith("Loss from sales")), "Loss from sales −₹100.00");
});

test("before anything has been sold there is no sales figure, and what is still in shows as before", () => {
    const shown = pills(page({ totalInvested: 120000, investedAmount: 120000, currentValue: 130000, realizedGain: 0 }));
    assert.ok(shown.includes("Total invested ₹1,20,000.00"), shown.join(" | "));
    assert.ok(!shown.some(t => t.includes("from sales")), shown.join(" | "));
});
