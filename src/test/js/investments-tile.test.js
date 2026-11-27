const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Investments level: the big total is what was invested, the small line what it is worth today and the gain or loss in percent. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js",
        "panels/loan-calc.js", "panels/loan-block.js", "panels/add-account.js", "panels/accounts.js");
    p.run(`function isPortfolioAccount(id) { return [7, 10].includes(id); } function isJewelleryAccount() { return false; } function isRecurringDepositAccount() { return false; }
        function clientLog() {} function openEditAccount() {} function openWriteOffDebt() {} function showTransactionsFor() {} function openLiabilityPayment() { return Promise.resolve(); }
        function showWorkspaceView() {} function ledgerCard() { return el("div"); } function emergencyCard() { return el("div"); }
        function portfolioCard() { return el("div"); } function recurringDepositsCard() { return el("div"); } function jewelleryCard() { return el("div"); }
        function openPerson() {} function openWriteOffPerson() {} function efTile() { return el("div"); } function monthYear() { return ""; }
        function txnRowElement() { return el("div"); } function cardCyclesBlock() { return null; }
        accounts = [
            { id: 1, name: "Bank", type: "BANK", level: 1, active: true, balance: 5000, balanceInr: 5000, currency: "INR", openingBalance: 5000 },
            { id: 7, name: "Mutual Funds", type: "INVESTMENT", level: 3, active: true, balance: 100000, balanceInr: 100000, currency: "INR", openingBalance: 0 },
            { id: 10, name: "Shares", type: "INVESTMENT", level: 3, active: true, balance: 27950.75, balanceInr: 27950.75, currency: "INR", openingBalance: 0 },
            { id: 11, name: "Gold coin", type: "GOLD", level: 3, active: true, balance: 1000, balanceInr: 1000, currency: "INR", openingBalance: 1000 }];
        portfolios = { MUTUAL_FUNDS: { configured: true, accountId: 7, investedAmount: 100000, currentValue: 100615.68, holdings: [] },
                       SHARES: { configured: true, accountId: 10, investedAmount: 27950.75, currentValue: 32170, holdings: [] } };
        emergencyFund = { configured: false }; ledger = { configured: false, buckets: [], closed: [] };
        loans = { receivablesAccountId: null, unnamed: 0, borrowers: [] }; transactions = [];`);
    return p;
}
const tile = (p, level) => p.node("accountList").find(n => String(n.className).startsWith("level-tile level-" + level))[0];

test("the Investments tile shows the total invested big, and the current value with the gain in percent small", () => {
    const p = page();
    p.run("renderAccounts()");
    const t = tile(p, 3);
    assert.equal(t.find(n => n.className === "lt-total")[0].textContent, "₹1,28,950.75", "100000 + 27950.75 + the gold at its balance 1000");
    // invested 128950.75, worth 100615.68 + 32170 + 1000 = 133785.68: +4834.93, 3.75% of what was invested
    assert.equal(t.find(n => n.className === "lt-sub")[0].textContent, "Current value ₹1,33,785.68 · ▲ +3.75%");
});

test("a loss shows a down arrow and a minus, and a level with no Mutual Funds or Shares has no small line", () => {
    const p = page();
    p.run(`portfolios.SHARES.currentValue = 20000; renderAccounts()`);
    assert.equal(tile(p, 3).find(n => n.className === "lt-sub")[0].textContent, "Current value ₹1,21,615.68 · ▼ −5.69%");
    assert.equal(tile(p, 1).find(n => n.className === "lt-sub").length, 0);
});

test("opening the Investments level puts the same two totals at the top of its list", () => {
    const p = page();
    p.run("openLevel = 3; renderAccounts()");
    const head = p.node("levelBody").find(n => n.className === "level-market")[0];
    assert.match(head.allText, /Invested ₹1,28,950\.75/);
    assert.match(head.allText, /Current value ₹1,33,785\.68 · ▲ \+3\.75%/);
});

test("a deactivated account is listed after the active ones, whatever its balance", () => {
    const p = page();
    p.run(`accounts.push({ id: 12, name: "Old bank", type: "BANK", level: 1, active: false, balance: 90000, balanceInr: 90000, currency: "INR", openingBalance: 0 },
        { id: 13, name: "Small bank", type: "BANK", level: 1, active: true, balance: 10, balanceInr: 10, currency: "INR", openingBalance: 0 });
        openLevel = 1; renderAccounts()`);
    const names = p.node("levelBody").find(n => n.className === "name" || String(n.className).startsWith("name")).map(n => n.allText);
    const at = n => names.findIndex(t => t.includes(n));
    assert.ok(at("Bank") >= 0 && at("Small bank") >= 0 && at("Old bank") >= 0, names.join("|"));
    assert.ok(at("Small bank") < at("Old bank"));
});

test("ring-fenced money is not added into a level's total, but the level says so and net worth still has it", () => {
    const p = page();
    p.run(`accounts.push({ id: 14, name: "Locked fund", type: "GOAL_FUND", level: 2, active: true, ringFenced: true, balance: 5000, balanceInr: 5000, currency: "INR", openingBalance: 0 },
        { id: 15, name: "Open fund", type: "GOAL_FUND", level: 2, active: true, ringFenced: false, balance: 700, balanceInr: 700, currency: "INR", openingBalance: 0 });
        renderAccounts()`);
    assert.equal(tile(p, 2).find(n => n.className === "lt-total")[0].textContent, "₹700.00");
    assert.equal(tile(p, 2).find(n => n.className === "lt-count")[0].textContent, "2 accounts");
    p.run(`function peekAmount() { return el("span"); } function goalTargetReached() { return false; } function fenceBanner() { return el("div"); } openLevel = 2; renderAccounts()`);
    assert.match(p.node("levelBody").find(n => n.className === "level-fenced")[0].textContent, /₹5,000\.00 is ring-fenced.*net worth/);
    assert.equal(p.run("accounts.reduce((s, a) => s + a.balanceInr, 0)"), 5000 + 700 + 5000 + 27950.75 + 100000 + 1000, "the page's own sums still hold every account");
});

test("a deactivated account is marked with a badge and a heading, so it does not look like the live ones", () => {
    const p = page();
    p.run(`accounts.push({ id: 12, name: "Old bank", type: "BANK", level: 1, active: false, balance: 90000, balanceInr: 90000, currency: "INR", openingBalance: 0 });
        openLevel = 1; renderAccounts()`);
    const body = p.node("levelBody");
    assert.equal(body.find(n => n.className === "off-badge").length, 1);
    assert.match(body.find(n => n.className === "level-off")[0].textContent, /^Deactivated \(1\)/);
    assert.ok(body.find(n => String(n.className).includes("account-row inactive")).length === 1);
});
