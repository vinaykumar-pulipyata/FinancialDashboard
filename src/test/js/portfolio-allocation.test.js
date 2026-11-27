const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Allocation view of a Mutual Funds or Shares card: a donut of how the card's money is split between its holdings. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/summary.js", "panels/allocation.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js",
        "panels/ledger.js", "panels/portfolio-stats.js", "panels/portfolio.js");
    p.run(`var rendered = 0; function renderAccounts() { rendered++; }  function clientLog() {}
        portfolios.MUTUAL_FUNDS = { configured: true, accountId: 9, totalInvested: 90000, investedAmount: 80000, currentValue: 100000, gain: 20000, gainPercent: 25,
            holdings: [{ id: 1, name: "Index fund", investedAmount: 30000, currentValue: 45000, gain: 15000, gainPercent: 50 },
                       { id: 2, name: "Debt fund", investedAmount: 30000, currentValue: 35000, gain: 5000, gainPercent: 16.7 },
                       { id: 3, name: "Gold fund", investedAmount: 20000, currentValue: 20000, gain: 0, gainPercent: 0 },
                       { id: 4, name: "Sold fund", investedAmount: 0, currentValue: 0, gain: 0, gainPercent: 0 }] };`);
    return p;
}

test("the card has an Allocation button next to Stats, and each turns the card to its view and back to the list", () => {
    const p = page();
    const labels = () => p.raw(`portfolioCard("MUTUAL_FUNDS")`).find(n => n.tag === "button").map(b => b.textContent);
    assert.ok(labels().includes("Stats") && labels().includes("Allocation"));
    const press = name => p.raw(`portfolioCard("MUTUAL_FUNDS")`).find(n => n.tag === "button" && n.textContent === name)[0].fire("click");
    press("Allocation");
    assert.equal(p.run(`psView.MUTUAL_FUNDS`), "allocation");
    assert.ok(labels().includes("Mutual Funds") && labels().includes("Stats"), "the button of the view on show is named after the list");
    press("Mutual Funds");
    assert.equal(p.run(`psView.MUTUAL_FUNDS`), "list");
    press("Stats");
    assert.equal(p.run(`psView.MUTUAL_FUNDS`), "stats");
    assert.ok(p.run("rendered") >= 3, "each press redraws the Accounts view");
});

test("the allocation view splits the card by current value, leaves out a holding worth nothing, and uses the whole-rupee format", () => {
    const p = page();
    p.run(`psView.MUTUAL_FUNDS = "allocation"; portfolioExpanded.MUTUAL_FUNDS = true`);
    const card = p.raw(`portfolioCard("MUTUAL_FUNDS")`);
    const box = card.find(n => n.className.split(" ").includes("pf-allocation"))[0];
    assert.ok(box, "the allocation view is in the card");
    assert.ok(box.className.includes("nw-allocation"), "it carries the slice colours");
    const slices = box.find(n => n.attrs && String(n.attrs.class).startsWith("slice "));
    assert.equal(slices.length, 3, "Index, Debt and Gold; the sold fund has no value");
    assert.match(box.allText, /How your mutual funds are split/);
    assert.match(box.allText, /Index fund.*₹45,000.*45%/s, "45,000 of 100,000");
    assert.ok(box.allText.includes("Total value"));
    assert.equal(card.find(n => n.className === "lg-row holding-row").length, 0, "the list of holdings is not shown at the same time");
});

test("when every holding is worth nothing the view says so instead of drawing an empty ring", () => {
    const p = page();
    p.run(`portfolios.MUTUAL_FUNDS.holdings = [portfolios.MUTUAL_FUNDS.holdings[3]]; psView.MUTUAL_FUNDS = "allocation"; portfolioExpanded.MUTUAL_FUNDS = true`);
    const box = p.raw(`portfolioCard("MUTUAL_FUNDS")`).find(n => n.className.split(" ").includes("pf-allocation"))[0];
    assert.equal(box.find(n => n.tag === "svg").length, 0);
    assert.match(box.allText, /Nothing to show yet: use "Add fund"/);
});
