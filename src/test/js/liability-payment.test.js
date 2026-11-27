const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Pay button on a loan or card: an expense from a bank or cash account into the liability, in the fixed Liability Payments category. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js", "panels/liability-payment.js");
    p.run(`var calls = [], refreshed = 0, answer = true;
        function isPortfolioAccount() { return false; } function isJewelleryAccount() { return false; } function isRecurringDepositAccount() { return false; }
        function clientLog() {}  function describeError() { return ""; }
        function api(path, options) { calls.push({ path, method: options && options.method, body: options && options.body }); return Promise.resolve(null); }
        async function refreshData() { refreshed++; }
        function confirm() { return answer; }
        accounts = [
            { id: 1, name: "HDFC", type: "BANK", active: true, balance: 50000, ringFenced: false },
            { id: 2, name: "Cash", type: "CASH", active: true, balance: 500, ringFenced: false },
            { id: 3, name: "Car loan", type: "DEBT", active: true, balance: 400000, ringFenced: false },
            { id: 4, name: "Visa", type: "CREDIT_CARD", active: true, balance: 8000, ringFenced: false },
            { id: 5, name: "Goal", type: "GOAL_FUND", active: true, balance: 100, ringFenced: true },
            { id: 6, name: "Gold", type: "GOLD", active: true, balance: 60000, ringFenced: false }];
        allCategories = [{ id: 10, name: "Liability Payments", kind: "EXPENSE", systemKey: "LIABILITY_PAYMENT", active: true, parentId: null }];
        debtEmis = new Map([[3, 23072]]);`);
    return p;
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0));

test("an expense that names a To account is a payment towards a liability", () => {
    const p = page();
    assert.equal(p.run(`isLiabilityPayment({ kind: "EXPENSE", fromAccountId: 1, toAccountId: 3 })`), true);
    assert.equal(p.run(`isLiabilityPayment({ kind: "EXPENSE", fromAccountId: 1, toAccountId: null })`), false);
    assert.equal(p.run(`isLiabilityPayment({ kind: "TRANSFER", fromAccountId: 1, toAccountId: 3 })`), false);
});

test("paying a fixed loan starts with its EMI; a custom loan starts empty; loans and cards are not offered to pay from", async () => {
    const p = page();
    await p.raw(`openLiabilityPayment(accounts[2])`);
    assert.equal(p.run("txn.fields.amount.value"), 23072);
    assert.equal(p.node("txnTitle").textContent, "Pay · Car loan");
    // ring-fenced Goal is listed but locked; the loan and the card are not listed at all
    assert.deepEqual(p.run("txn.fields.account.children.map(o => o.textContent.split(' (')[0])"), ["HDFC", "Cash", "Goal"]);
    assert.equal(p.node("txnDelete").classList.contains("hidden"), true);
    await p.raw(`openLiabilityPayment(accounts[3])`);
    assert.equal(p.run("txn.fields.amount.value"), "");
});

test("saving a payment sends an expense from the chosen account to the liability in the Liability Payments category", async () => {
    const p = page();
    await p.raw(`openLiabilityPayment(accounts[2])`);
    p.run(`txn.fields.account.value = "1"; txn.fields.date.value = "2026-10-05"; txn.fields.note.value = " October EMI "`);
    await p.raw(`saveTransaction()`);   // hands over to saveLiabilityPayment while the Pay window is open
    assert.equal(p.run("calls.length"), 1);
    assert.deepEqual(p.run("calls[0]"), { path: "/api/transactions", method: "POST", body: {
        date: "2026-10-05", kind: "EXPENSE", amount: 23072, fee: null, categoryId: 10, borrowerId: null,
        fromAccountId: 1, toAccountId: 3, note: "October EMI", description: null } });
    assert.equal(p.run("refreshed"), 1);
});

test("paying more than is owed asks first, and No keeps the window open", async () => {
    const p = page();
    await p.raw(`openLiabilityPayment(accounts[3])`);
    p.run(`txn.fields.account.value = "1"; txn.fields.amount.value = "9000"; answer = false`);
    await p.raw(`saveLiabilityPayment()`);
    assert.equal(p.run("calls.length"), 0);
    p.run("answer = true");
    await p.raw(`saveLiabilityPayment()`);
    assert.equal(p.run("calls.length"), 1);
});

test("editing a payment keeps its own earlier amount out of the 'more than owed' check and sends a PUT", async () => {
    const p = page();
    await p.raw(`openLiabilityPayment(accounts[3], { id: 77, kind: "EXPENSE", date: "2026-10-01", amount: 3000, fromAccountId: 1, toAccountId: 4 })`);
    assert.equal(p.node("txnDelete").classList.contains("hidden"), false);
    p.run(`txn.fields.amount.value = "11000"; answer = false`);   // owed now 8000 + its own 3000 = 11000, so not too much
    await p.raw(`saveLiabilityPayment()`);
    assert.equal(p.run("calls[0].method"), "PUT");
    assert.equal(p.run("calls[0].path"), "/api/transactions/77");
});

test("a payment needs an amount and an account to pay from", async () => {
    const p = page();
    await p.raw(`openLiabilityPayment(accounts[3])`);
    await p.raw(`saveLiabilityPayment()`);
    assert.match(p.node("txnError").textContent, /amount greater than zero/);
    p.run(`txn.fields.amount.value = "10"; txn.fields.account.value = ""`);
    await p.raw(`saveLiabilityPayment()`);
    assert.match(p.node("txnError").textContent, /account you pay from/);
    assert.equal(p.run("calls.length"), 0);
});

test("the ordinary Expense form does not offer Liability Payments, and opening it again leaves the Pay window mode", async () => {
    const p = page();
    p.run(`txn.categories = [
        { id: 10, name: "Liability Payments", kind: "EXPENSE", systemKey: "LIABILITY_PAYMENT", active: true, parentId: null },
        { id: 11, name: "Food", kind: "EXPENSE", systemKey: null, active: true, parentId: null }]; loans = { borrowers: [] }; categoryFields(null); 0`);
    assert.deepEqual(p.run("txn.fields.category.children.map(o => o.textContent)"), ["Select category", "Food"]);
    await p.raw(`openLiabilityPayment(accounts[2])`);
    assert.equal(p.run("txn.liability.id"), 3);
    p.run(`api = path => Promise.resolve([])`);
    await p.raw(`openTransaction("EXPENSE")`);
    assert.equal(p.run("txn.liability"), null);
});

test("the Expense and Income forms do not offer Balance Adjustment, which only an account's Reset balance writes", () => {
    const p = page();
    p.run(`txn.categories = [
        { id: 10, name: "Balance Adjustment", kind: "EXPENSE", systemKey: "BALANCE_ADJUSTMENT_EXPENSE", active: true, parentId: null },
        { id: 11, name: "Food", kind: "EXPENSE", systemKey: null, active: true, parentId: null }]; loans = { borrowers: [] }; categoryFields(null); 0`);
    assert.deepEqual(p.run("txn.fields.category.children.map(o => o.textContent)"), ["Select category", "Food"]);
    p.run(`txn.categories = [
        { id: 12, name: "Balance Adjustment", kind: "INCOME", systemKey: "BALANCE_ADJUSTMENT_INCOME", active: true, parentId: null },
        { id: 13, name: "Salary", kind: "INCOME", systemKey: null, active: true, parentId: null }]; categoryFields(null); 0`);
    assert.deepEqual(p.run("txn.fields.category.children.map(o => o.textContent)"), ["Select category", "Salary"]);
});

test("Expense is spent from money and cards, Income lands in money, and Transfer still offers every account", async () => {
    const p = page();
    const names = () => p.run("txn.fields.account.children.map(o => o.textContent.split(' (')[0])");
    p.run(`api = path => Promise.resolve([]); loans = { borrowers: [] }`);
    await p.raw(`openTransaction("EXPENSE")`);
    assert.deepEqual(names(), ["HDFC", "Cash", "Visa", "Goal"], "no Gold and no loan to spend from");
    await p.raw(`openTransaction("INCOME")`);
    assert.deepEqual(names(), ["HDFC", "Cash", "Goal"], "no card, Gold or loan to earn into");
    await p.raw(`openTransaction("TRANSFER")`);
    assert.deepEqual(p.run("txn.fields.to.children.map(o => o.textContent.split(' (')[0])").sort(), ["Car loan", "Cash", "Goal", "Gold", "HDFC", "Visa"]);
});

test("editing an old transaction still lists the account it already uses, even one that is no longer offered", async () => {
    const p = page();
    p.run(`api = path => Promise.resolve([]); loans = { borrowers: [] }`);
    await p.raw(`openTransaction("EXPENSE", { id: 9, kind: "EXPENSE", date: "2026-10-01", amount: 5, fromAccountId: 6, toAccountId: null, categoryId: null })`);
    assert.ok(p.run("txn.fields.account.children.some(o => o.textContent.startsWith('Gold'))"));
});
