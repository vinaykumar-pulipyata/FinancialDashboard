const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Withdraw dialog: the invested amount being sold and the current value of it (what is received); the difference is the profit. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/summary.js", "panels/allocation.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js",
        "panels/ledger.js", "panels/portfolio-stats.js", "panels/portfolio.js");
    p.run(`function renderAccounts() {} function clientLog() {} var calls = []; transactions = [];
        function api(path, options) { calls.push({ path, method: options && options.method, body: options && options.body }); return Promise.resolve({}); }
        accounts = [{ id: 1, name: "Savings Bank", type: "BANK", active: true, balance: 50000 }];
        portfolios.MUTUAL_FUNDS = { configured: true, accountId: 9, kind: "MUTUAL_FUNDS", investedAmount: 90000, totalInvested: 90000, currentValue: 103000, realizedGain: 0, holdings: [
            { id: 1, name: "Small Cap Fund", investedAmount: 60000, currentValue: 63756.55, gain: 3756.55, gainPercent: 6.3 },
            { id: 2, name: "Mid Cap Fund", investedAmount: 30000, currentValue: 39243.45, gain: 9243.45, gainPercent: 30.8 }] };`);
    return p;
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0));
const inputs = p => p.node("ledgerBody").find(x => x.tag === "input");

test("the sale is described as a profit or a loss with its percent", () => {
    const p = page();
    assert.equal(p.run(`saleResultText(60000, 63756.55)`), "Profit on this sale: ₹3,756.55 (+6.3%)");
    assert.equal(p.run(`saleResultText(10000, 9000)`), "Loss on this sale: ₹1,000.00 (−10.0%)");
    assert.equal(p.run(`saleResultText(500, 500)`), "No profit or loss on this sale.");
    assert.equal(p.run(`saleResultText(0, 250)`), "Profit on this sale: ₹250.00", "nothing invested: no percent");
});

test("the dialog starts with the whole fund and shows the profit; choosing another fund fills in that one", () => {
    const p = page();
    p.run(`openWithdrawDialog("MUTUAL_FUNDS")`);
    const [invested, received] = inputs(p);
    const fund = p.node("ledgerBody").find(x => x.tag === "select")[0];
    assert.deepEqual([Number(invested.value), Number(received.value)], [30000, 39243.45], "the first fund by name: Mid Cap");
    assert.ok(p.node("ledgerBody").allText.includes("Profit on this sale: ₹9,243.45 (+30.8%)"), p.node("ledgerBody").allText);
    fund.value = "1"; fund.fire("change");
    assert.deepEqual([Number(invested.value), Number(received.value)], [60000, 63756.55]);
    received.value = "55000"; received.fire("input");
    assert.ok(p.node("ledgerBody").allText.includes("Loss on this sale: ₹5,000.00 (−8.3%)"), p.node("ledgerBody").allText);
});

test("a sale sends what it fetched as the amount and what it cost as the invested amount", async () => {
    const p = page();
    p.run(`openWithdrawDialog("MUTUAL_FUNDS")`);
    const [invested, received] = inputs(p);
    const [fund, to] = p.node("ledgerBody").find(x => x.tag === "select");
    fund.value = "1"; fund.fire("change"); to.value = "1";
    invested.value = "10000"; received.value = "11200.5";
    p.run(`calls.length = 0`);
    p.run(`dlg.onPrimary()`);
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/portfolios/MUTUAL_FUNDS/holdings/1/withdraw", method: "POST",
        body: { toAccountId: 1, amount: 11200.5, investedAmount: 10000 } }]);
});

test("it refuses more invested than the fund holds, nothing received and no account", () => {
    const p = page();
    p.run(`openWithdrawDialog("MUTUAL_FUNDS")`);
    const [invested, received] = inputs(p);
    const [fund, to] = p.node("ledgerBody").find(x => x.tag === "select");
    fund.value = "1"; fund.fire("change");
    p.run(`dlg.onPrimary()`);
    assert.match(p.node("ledgerError").textContent, /bank or cash account first/, "no account chosen yet");
    to.value = "1";
    invested.value = "60000.01";
    p.run(`dlg.onPrimary()`);
    assert.match(p.node("ledgerError").textContent, /has only ₹60,000.00 invested/);
    invested.value = "100"; received.value = "0";
    p.run(`dlg.onPrimary()`);
    assert.match(p.node("ledgerError").textContent, /current value of it/);
});
