const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Fund Ledger dialog only offers accounts the server accepts as a source. */
test("Jewellery, Mutual Funds and Shares are not offered as a funding source", () => {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/summary.js", "panels/allocation.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js",
        "panels/ledger.js");
    p.run(`function renderAccounts() {} function clientLog() {}
        ledger = { accountId: 99 };
        portfolios = { MUTUAL_FUNDS: { configured: true, accountId: 5, holdings: [] }, SHARES: { configured: false, holdings: [] } };
        accounts = [
            { id: 1, name: "Bank", type: "BANK", active: true }, { id: 2, name: "Cash", type: "CASH", active: true },
            { id: 3, name: "Gold", type: "JEWELLERY", active: true }, { id: 5, name: "Funds", type: "INVESTMENT", active: true },
            { id: 6, name: "Other investment", type: "INVESTMENT", active: true }, { id: 7, name: "Old", type: "BANK", active: false },
            { id: 8, name: "Card", type: "CREDIT_CARD", active: true }, { id: 99, name: "Ledger", type: "GOAL_FUND", active: true }];`);
    assert.deepEqual(p.run(`ledgerFundingSources().map(a => a.id)`), [1, 2, 6]);
});
