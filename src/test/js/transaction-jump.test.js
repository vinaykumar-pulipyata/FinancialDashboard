const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* Jumping to the transactions behind an account or a category, and the CSV export. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/period-filter.js", "panels/transactions.js");
    p.run(`var shown = []; function showWorkspaceView(v) { shown.push(v); }
        function calendarFollowPeriod() {} function renderPeriod() { renderTransactions(); }
        function openTransaction() {} function isLedgerFunding() { return false; } function ledgerFundingDelete() { return el("span"); } function isInvestment() { return false; } function investmentDelete() { return el("span"); } function isRdDeposit() { return false; } function rdDepositDelete() { return el("span"); }
        accounts = [{ id: 1, name: "Savings Bank" }, { id: 2, name: "Cash" }];
        allCategories = [{ id: 10, name: "Food", parentId: null }, { id: 11, name: "Groceries", parentId: 10 }, { id: 20, name: "Salary", parentId: null }];
        loans = { receivablesAccountId: null, unnamed: 0, borrowers: [{ id: 7, name: "Ravi" }] };
        transactions = [
            { id: 1, date: "2026-09-30", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 100, categoryId: 11, note: "Old shop" },
            { id: 2, date: "2026-10-02", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 2400, categoryId: 11, note: "Weekly shop" },
            { id: 3, date: "2026-10-03", kind: "EXPENSE", fromAccountId: 2, toAccountId: null, amount: 450, categoryId: 10, note: "Tea" },
            { id: 4, date: "2026-10-05", kind: "INCOME", fromAccountId: null, toAccountId: 1, amount: 9000, categoryId: 20, note: "Pay" },
            { id: 5, date: "2026-10-06", kind: "TRANSFER", fromAccountId: 1, toAccountId: 2, amount: 500, categoryId: null, note: "Top-up", fee: 5 }];`);
    return p;
}
const listed = p => p.run("visibleTransactions().map(t => t.id)");

test("an account's button shows the transactions on either side of it, in every period, with the kind reset", () => {
    const p = page();
    p.run(`txnFilter = "INCOME"; txnQuery.text = "zzz"; periodRange = "TODAY"; showTransactionsFor({ account: 2 })`);
    assert.deepEqual(listed(p), [3, 5]);
    assert.equal(p.run("txnFilter"), "ALL");
    assert.equal(p.run("periodRange"), "ALL");
    assert.equal(p.run("txnQuery.text"), "");
    assert.deepEqual(p.run("shown"), ["transactions"]);
});

test("a category jump carries the kind and the Stats range, and includes the sub categories", () => {
    const p = page();
    p.run(`showTransactionsFor({ category: 10, kind: "EXPENSE", from: "2026-10-01", to: "2026-10-31" })`);
    assert.deepEqual(listed(p), [2, 3], "Food and Groceries inside October only");
    assert.equal(p.run("periodRange"), "CUSTOM");
    assert.deepEqual([p.run("periodFrom"), p.run("periodTo")], ["2026-10-01", "2026-10-31"]);
    assert.equal(p.run("txnFilter"), "EXPENSE");
    p.run(`showTransactionsFor({ category: 11, kind: "EXPENSE", from: "2026-10-01", to: "2026-10-31" })`);
    assert.deepEqual(listed(p), [2], "a sub category is exact");
    p.run(`showTransactionsFor({ category: 11, kind: "EXPENSE", from: "2026-10-01" })`);
    assert.equal(p.run("periodTo"), "", "open ended ranges stay open");
});

test("setSharedRange with no dates is all time", () => {
    const p = page();
    p.run(`setSharedRange("2026-10-01", "2026-10-02"); setSharedRange("", "")`);
    assert.equal(p.run("periodRange"), "ALL");
});

test("a CSV cell is quoted, its quotes doubled, and a formula is defused", () => {
    const p = page();
    assert.equal(p.run(`csvCell('say "hi", ok')`), '"say ""hi"", ok"');
    assert.equal(p.run("csvCell(null)"), '""');
    assert.equal(p.run("csvCell(12.5)"), '"12.5"');
    for (const bad of ["=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx"]) {
        assert.equal(p.run(`csvCell(${JSON.stringify(bad)})`), `"'${bad}"`, bad);
    }
});

test("the CSV has a BOM, a header and one line per transaction", () => {
    const p = page();
    const csv = p.run(`transactionsCsv(transactions.filter(t => t.id === 5 || t.id === 2))`);
    assert.ok(csv.startsWith("﻿\"Date\",\"Type\""));
    const lines = csv.slice(1).split("\r\n");
    assert.equal(lines.length, 4, "header, two rows and the closing line break");
    assert.equal(lines[1], '"2026-10-02","Expense","Food › Groceries","","Savings Bank","","2400","0","Weekly shop","","","INR","",""');
    assert.equal(lines[2], '"2026-10-06","Transfer","","","Savings Bank","Cash","500","5","Top-up","","","INR","",""');
});

test("Export CSV saves only what is listed, every page", () => {
    const p = page();
    p.run(`var saved = null, clicks = 0;
        Blob = class { constructor(parts) { this.text = parts.join(""); } };
        URL = { createObjectURL: b => { saved = b; return "blob:x"; }, revokeObjectURL() {} };
        document.body.append = () => {};
        txnPageSize = 1; showTransactionsFor({ account: 1, kind: "EXPENSE" });
        const realEl = el; el = (tag, attrs) => ({ click() { clicks++; }, remove() {}, ...attrs });
        exportTransactions(); el = realEl;`);
    assert.equal(p.run("clicks"), 1);
    const lines = p.run("saved.text").slice(1).split("\r\n").filter(Boolean);
    assert.equal(lines.length, 3, "header and the two expenses of Savings Bank, though the page shows one");
    assert.match(p.run("saved.text"), /Old shop/);
    assert.doesNotMatch(p.run("saved.text"), /Pay|Tea/);
});
