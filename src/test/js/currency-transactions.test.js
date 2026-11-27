const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* Transactions in more than one currency: which currency a transaction is in, what the rupee-only views leave out, the rows and the CSV,
   the running balance, and the transfer form asking what arrived. */
function listPage() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/period-filter.js", "panels/transaction-tags.js", "panels/transactions.js");
    p.run(`var shown = []; function showWorkspaceView(v) { shown.push(v); }
        function calendarFollowPeriod() {} function renderPeriod() { renderTransactions(); }
        function openTransaction() {} function isLedgerFunding() { return false; } function ledgerFundingDelete() { return el("span"); } function isInvestment() { return false; } function investmentDelete() { return el("span"); } function isRdDeposit() { return false; } function rdDepositDelete() { return el("span"); }
        accounts = [{ id: 1, name: "Bank", type: "BANK", currency: "INR", openingBalance: 1000 },
                    { id: 2, name: "US bank", type: "BANK", currency: "USD", openingBalance: 100 },
                    { id: 3, name: "Hanoi", type: "CASH", currency: "VND", openingBalance: 0 }];
        allCategories = [{ id: 10, name: "Food", parentId: null }];
        loans = { receivablesAccountId: null, unnamed: 0, borrowers: [] };
        transactions = [
            { id: 1, date: "2026-10-02", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 200, categoryId: 10, note: "Lunch" },
            { id: 2, date: "2026-10-03", kind: "INCOME", fromAccountId: null, toAccountId: 2, amount: 50, categoryId: 10, note: "Gig" },
            { id: 3, date: "2026-10-04", kind: "TRANSFER", fromAccountId: 1, toAccountId: 2, amount: 800, toAmount: 10, categoryId: null, note: "Top-up", fee: 5 },
            { id: 4, date: "2026-10-05", kind: "EXPENSE", fromAccountId: 3, toAccountId: null, amount: 50000, categoryId: 10, note: "Coffee" }];`);
    return p;
}

test("a transaction is in the currency of the account its amount belongs to, and foreign ones are told apart", () => {
    const p = listPage();
    assert.deepEqual(p.run("transactions.map(txnCurrency)"), ["INR", "USD", "INR", "VND"]);
    assert.deepEqual(p.run("transactions.map(isForeignTxn)"), [false, true, true, true], "a transfer touching a foreign account is foreign");
    assert.deepEqual(p.run("rupeeTransactions().map(t => t.id)"), [1], "with no rate saved, only what is entirely in rupees is counted by the rupee-only views");
    assert.equal(p.run("accountCurrency(null)"), "INR");
    assert.equal(p.run("accountCurrency(99)"), "INR", "an unknown account counts as rupees");
});

test("with rates saved, a foreign income or expense is counted in rupees at the rate of its day, and a transfer touching one is not", () => {
    const p = listPage();
    p.run(`exchangeRates = [{ currency: "USD", date: "2026-10-01", rate: 80 }, { currency: "USD", date: "2026-10-04", rate: 90 },
                           { currency: "VND", date: "2026-10-01", rate: 0.003 }];`);
    const list = p.run("rupeeTransactions().map(t => [t.id, t.amount])");
    assert.deepEqual(list, [[1, 200], [2, 4000], [4, 150]], "50 dollars at 80 on the 3rd, 50000 Dong at 0.003; the transfer is left out");
    assert.equal(p.run("transactions[1].amount"), 50, "the transaction itself is not changed");
    p.run(`exchangeRates = [{ currency: "USD", date: "2026-10-01", rate: 80 }];`);
    assert.deepEqual(p.run("rupeeTransactions().map(t => t.id)"), [1, 2], "no rate for Dong: that one stays out");
});

test("the amount and the fee are shown in their own currency, and a transfer between two says what arrived", () => {
    const p = listPage();
    const row = id => p.raw(`txnRowElement(transactions.find(t => t.id === ${id}))`);
    const amt = r => r.find(n => String(n.className).startsWith("amt "))[0];
    assert.equal(amt(row(1)).textContent, "−₹200");
    assert.equal(amt(row(2)).textContent, "+$50");
    assert.equal(amt(row(4)).textContent, "−₫50,000");
    const transfer = amt(row(3));
    assert.equal(transfer.textContent, "₹800", "the amount is in the rupee account it left");
    const subs = transfer.find(n => n.className && String(n.className).includes("sub")).map(n => n.textContent);
    assert.deepEqual(subs, ["fee ₹5", "received $10"]);
});

test("the balance under a row follows each account's currency and counts what arrived, not what left", () => {
    const p = listPage();
    assert.equal(p.run("balanceAfter(transactions[1], 2)"), 150, "100 + 50 dollars");
    assert.equal(p.run("balanceAfter(transactions[2], 1)"), 1000 - 200 - 800 - 5, "the bank lost the amount and the fee");
    assert.equal(p.run("balanceAfter(transactions[2], 2)"), 160, "the dollar account got ten dollars, not 800");
    assert.equal(p.run("balanceAfter(transactions[3], 3)"), -50000);
});

test("the CSV names the currency and what arrived", () => {
    const p = listPage();
    const lines = p.run("transactionsCsv(transactions)").slice(1).split("\r\n");
    assert.ok(lines[0].endsWith('"Currency","Amount received","Received in"'));
    assert.ok(lines[2].endsWith('"USD","",""'));
    assert.ok(lines[3].endsWith('"INR","10","USD"'), lines[3]);
    assert.ok(lines[4].endsWith('"VND","",""'));
});

test("the found-totals count only rupee transactions and say how many were left out", () => {
    const p = listPage();
    p.run(`txnQuery = { text: "", category: "10", account: "", min: null, max: null, tag: "" }; renderTransactions();`);
    const text = p.node("txnFound").find(() => true).map(n => n.textContent).join("|");
    assert.match(text, /In ₹0/);
    assert.match(text, /Out ₹200/);
    assert.match(text, /2 in a foreign currency with no exchange rate, not counted/);
});

test("the found-totals convert foreign amounts at the rate of their day", () => {
    const p = listPage();
    p.run(`exchangeRates = [{ currency: "USD", date: "2026-10-01", rate: 80 }, { currency: "VND", date: "2026-10-01", rate: 0.003 }];
        txnQuery = { text: "", category: "10", account: "", min: null, max: null, tag: "" }; renderTransactions();`);
    const text = p.node("txnFound").find(() => true).map(n => n.textContent).join("|");
    assert.match(text, /In ₹4,000/);
    assert.match(text, /Out ₹350/, "200 rupees + 50000 Dong at 0.003");
    assert.doesNotMatch(text, /not counted/);
});

test("the notes under the rupee-only views show only while a foreign income or expense has no exchange rate", () => {
    const p = listPage();
    p.run(`var __hits = []; document.querySelectorAll = sel => [{ classList: { toggle(c, on) { __hits.push([sel, c, on]); } } }];`);
    p.run("renderForeignNotes()");
    assert.deepEqual(p.run("__hits"), [[".foreign-note", "hidden", false]], "shown while one has no rate");
    p.run(`exchangeRates = [{ currency: "USD", date: "2026-10-01", rate: 80 }, { currency: "VND", date: "2026-10-01", rate: 0.003 }]; __hits.length = 0; renderForeignNotes()`);
    assert.deepEqual(p.run("__hits"), [[".foreign-note", "hidden", true]], "hidden once every one can be converted");
});

/* ---- the transfer form */

function formPage() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js");
    p.run(`function isPortfolioAccount() { return false; } function isJewelleryAccount() { return false; } function isRecurringDepositAccount() { return false; }
        var logged = []; function clientLog(level, event, detail) { logged.push(event + " " + detail); }  function describeError() { return ""; }
        accounts = [{ id: 1, name: "Bank", type: "BANK", active: true, currency: "INR" },
                    { id: 2, name: "US bank", type: "BANK", active: true, currency: "USD" },
                    { id: 3, name: "Hanoi", type: "CASH", active: true, currency: "VND" },
                    { id: 4, name: "Savings", type: "BANK", active: true, currency: "INR" }];
        exchangeRates = [{ currency: "USD", date: "2026-01-01", rate: "80" }, { currency: "USD", date: "2026-06-01", rate: "85" }, { currency: "VND", date: "2026-01-01", rate: "0.0033" }];
        loans = { borrowers: [] };
        function api() { return Promise.resolve([]); }`);
    return p;
}

test("rateOn gives the latest saved rate on or before the day, the first one before any, 1 for rupees and null with none", () => {
    const p = formPage();
    assert.equal(p.run('rateOn("USD", "2026-03-01")'), 80);
    assert.equal(p.run('rateOn("USD", "2026-07-01")'), 85);
    assert.equal(p.run('rateOn("USD", "2025-01-01")'), 80, "before the first rate the first one is used, as the server does");
    assert.equal(p.run('rateOn("INR", "2026-07-01")'), 1);
    assert.equal(p.run('rateOn("GBP", "2026-07-01")'), null);
});

test("a transfer between two currencies asks what arrived, filled in from the saved rates until typed over", async () => {
    const p = formPage();
    await p.raw(`openTransaction("TRANSFER", null, "2026-07-01")`);
    p.run(`txn.fields.from.value = "1"; txn.fields.to.value = "4"; syncCurrencies();`);
    assert.equal(p.run('txn.fields.receivedRow.classList.contains("hidden")'), true, "two rupee accounts: nothing arrived differently");

    p.run(`txn.fields.to.value = "2"; txn.fields.amount.value = "850"; syncCurrencies();`);
    assert.equal(p.run('txn.fields.receivedRow.classList.contains("hidden")'), false);
    assert.equal(p.run("txn.fields.received.value"), "10.00", "850 rupees at 85 rupees a dollar");
    assert.equal(p.run("txn.fields.amountRow.children[0].textContent"), "Amount (₹)");
    assert.equal(p.run("txn.fields.receivedRow.children[0].textContent"), "Amount received ($)");

    p.run(`txn.fields.received.value = "9.9"; txn.fields.received.listeners.input[0](); txn.fields.amount.value = "1700"; suggestReceived();`);
    assert.equal(p.run("txn.fields.received.value"), "9.9", "typed over: the saved rate no longer overwrites it");
});

test("dong has no decimals: the amount box takes whole numbers and a suggestion is rounded to one", async () => {
    const p = formPage();
    await p.raw(`openTransaction("TRANSFER", null, "2026-07-01")`);
    p.run(`txn.fields.from.value = "1"; txn.fields.to.value = "3"; txn.fields.amount.value = "100"; syncCurrencies();`);
    assert.equal(p.run("txn.fields.received.value"), "30303", "100 rupees at 0.0033 is 30,303.03 dong");
    assert.equal(p.run("txn.fields.received.step"), "1");
    p.run(`txn.fields.from.value = "3"; txn.fields.to.value = "1"; txn.fields.receivedEdited = false; syncCurrencies();`);
    assert.equal(p.run("txn.fields.amount.step"), "1", "the amount is in dong now");
    assert.equal(p.run("txn.fields.amountRow.children[0].textContent"), "Amount (₫)");
});

test("an income or expense form labels the amount in the chosen account's currency", async () => {
    const p = formPage();
    await p.raw(`openTransaction("INCOME")`);
    p.run(`txn.fields.account.value = "2"; syncCurrencies();`);
    assert.equal(p.run("txn.fields.amountRow.children[0].textContent"), "Amount ($)");
});

test("the account lists name a foreign account's currency; the Pay window offers only rupee accounts", () => {
    const p = formPage();
    const labels = sel => sel.children.map(o => o.textContent);
    assert.ok(labels(p.raw("accountSelect()")).includes("US bank (Bank · USD)"));
    assert.ok(!labels(p.raw("accountSelect(null, true, null, null, true)")).some(t => t.includes("USD") || t.includes("VND")));
});
