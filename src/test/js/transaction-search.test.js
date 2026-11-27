const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The search box and the filters of the Transactions list. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/period-filter.js", "panels/transactions.js");
    p.run(`function openTransaction() {} function isLedgerFunding() { return false; } function ledgerFundingDelete() { return el("span"); } function isInvestment() { return false; } function investmentDelete() { return el("span"); } function isRdDeposit() { return false; } function rdDepositDelete() { return el("span"); }
        accounts = [{ id: 1, name: "Savings Bank" }, { id: 2, name: "Cash" }, { id: 3, name: "Travel Card" }];
        allCategories = [
            { id: 10, name: "Food", parentId: null }, { id: 11, name: "Groceries", parentId: 10 }, { id: 12, name: "Dining out", parentId: 10 },
            { id: 20, name: "Salary", parentId: null }, { id: 21, name: "Base pay", parentId: 20 }];
        loans = { receivablesAccountId: null, unnamed: 0, borrowers: [{ id: 7, name: "Ravi" }] };
        transactions = [
            { id: 1, date: "2026-10-01", kind: "INCOME", toAccountId: 1, fromAccountId: null, amount: 130000, categoryId: 21, note: "Salary", description: null },
            { id: 2, date: "2026-10-02", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 2400, categoryId: 11, note: "Weekly shop", description: "Big supermarket" },
            { id: 3, date: "2026-10-03", kind: "EXPENSE", fromAccountId: 2, toAccountId: null, amount: 450, categoryId: 12, note: "Tea and snacks", description: null },
            { id: 4, date: "2026-10-04", kind: "EXPENSE", fromAccountId: 3, toAccountId: null, amount: 1800, categoryId: null, note: "Mystery", description: null },
            { id: 5, date: "2026-10-05", kind: "TRANSFER", fromAccountId: 1, toAccountId: 2, amount: 5000, categoryId: null, note: "Cash top-up", description: null },
            { id: 6, date: "2026-10-06", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 3000, categoryId: 10, note: null, description: null },
            { id: 7, date: "2026-10-07", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 20000, categoryId: null, note: "Lent", description: null, borrowerId: 7 }];`);
    return p;
}
/* The ids of the transactions that pass a query (anything not given means no limit). */
const found = (p, query) => p.run(`transactions.filter(t => txnMatchesQuery(t, Object.assign({ text: "", category: "", account: "", min: null, max: null }, ${JSON.stringify(query)}))).map(t => t.id)`);
const rows = p => p.node("txnList").find(n => n.className.split(" ")[0] === "txn-row" && !n.className.includes("head"));   // a new row also has "rise"

test("words are found in the note, the description, the category, the accounts, the person, the kind, the date and the amount", () => {
    const p = page();
    assert.deepEqual(found(p, { text: "weekly" }), [2], "note");
    assert.deepEqual(found(p, { text: "supermarket" }), [2], "description");
    assert.deepEqual(found(p, { text: "groceries" }), [2], "category");
    assert.deepEqual(found(p, { text: "dining" }), [3], "sub category");
    assert.deepEqual(found(p, { text: "travel card" }), [4], "account");
    assert.deepEqual(found(p, { text: "ravi" }), [7], "person");
    assert.deepEqual(found(p, { text: "transfer" }), [5], "kind");
    assert.deepEqual(found(p, { text: "2026-10-03" }), [3], "date");
    assert.deepEqual(found(p, { text: "03 oct" }), [3], "date in words");
    assert.deepEqual(found(p, { text: "2400" }), [2], "amount");
    assert.deepEqual(found(p, { text: "2,400" }), [2], "amount with its commas");
    assert.deepEqual(found(p, { text: "1,30,000" }), [1], "amount in lakhs");
});

test("the Indian digit grouping used for finding amounts", () => {
    const p = page();
    assert.deepEqual(["5", "450", "2400", "130000", "1234567", "12345678", "2400.5", "999.99"].map(v => p.run(`groupedAmount(${v})`)),
        ["5", "450", "2,400", "1,30,000", "12,34,567", "1,23,45,678", "2,400.5", "999.99"]);
    assert.equal(p.run(`groupedAmount("1500.00")`), "1,500.00");
});

test("capitals do not matter, every word must be found, and extra spaces are ignored", () => {
    const p = page();
    assert.deepEqual(found(p, { text: "WEEKLY Shop" }), [2]);
    assert.deepEqual(found(p, { text: "  weekly   shop  " }), [2]);
    assert.deepEqual(found(p, { text: "weekly tea" }), [], "both words must be there");
    assert.deepEqual(found(p, { text: "food savings" }), [2, 6], "a category word and an account word together");
    assert.deepEqual(found(p, { text: "nothing like this" }), []);
    assert.equal(found(p, { text: "   " }).length, 7, "blank is no search");
});

test("choosing a category includes its sub categories; a sub category is exact; Uncategorised is what has none", () => {
    const p = page();
    assert.deepEqual(found(p, { category: "10" }), [2, 3, 6], "Food and everything under it");
    assert.deepEqual(found(p, { category: "11" }), [2]);
    assert.deepEqual(found(p, { category: "12" }), [3]);
    assert.deepEqual(found(p, { category: "20" }), [1], "Salary and Base pay");
    assert.deepEqual(found(p, { category: "none" }), [4, 7], "transfers have no category and are not 'uncategorised'");
});

test("an account matches either side of a transaction", () => {
    const p = page();
    assert.deepEqual(found(p, { account: "2" }), [3, 5], "Cash: spent from it, and a transfer into it");
    assert.deepEqual(found(p, { account: "1" }), [1, 2, 5, 6, 7]);
    assert.deepEqual(found(p, { account: "3" }), [4]);
});

test("amounts: a smallest, a largest or both, edges included", () => {
    const p = page();
    assert.deepEqual(found(p, { min: 5000 }), [1, 5, 7]);
    assert.deepEqual(found(p, { max: 450 }), [3]);
    assert.deepEqual(found(p, { min: 450, max: 2400 }), [2, 3, 4]);
    assert.deepEqual(found(p, { min: 0 }).length, 7, "a minimum of zero is a real limit that excludes nothing");
});

test("search and filters combine", () => {
    const p = page();
    assert.deepEqual(found(p, { text: "shop", category: "10", account: "1", min: 1000, max: 3000 }), [2]);
    assert.deepEqual(found(p, { text: "shop", account: "2" }), []);
});

test("the list shows only what matches, together with the kind chips and the date range, and goes back to page 1", () => {
    const p = page();
    p.run(`txnPageSize = 10; txnPage = 4; txnQuery = { text: "", category: "10", account: "", min: null, max: null }; renderTransactions()`);
    assert.equal(rows(p).length, 3);
    p.run(`txnFilter = "EXPENSE"; txnQuery.account = "1"; renderTransactions()`);
    assert.equal(rows(p).length, 2, "Food from the Savings Bank");
    p.run(`periodFrom = "2026-10-03"; periodTo = "2026-10-31"; renderTransactions()`);
    assert.equal(rows(p).length, 1, "and only from the 3rd");
    p.run(`periodFrom = ""; periodTo = ""; txnQuery = { text: "zzz", category: "", account: "", min: null, max: null }; txnFilter = "ALL"; renderTransactions()`);
    assert.match(p.node("txnList").allText, /No transactions match these filters/);
});

test("while searching it says how many were found and what came in and went out, never counting transfers", () => {
    const p = page();
    const found_ = () => p.node("txnFound");
    p.run(`renderTransactions()`);
    assert.ok(found_().classList.contains("hidden"), "nothing is said when there is no search");
    p.run(`txnQuery = { text: "", category: "", account: "1", min: null, max: null }; renderTransactions()`);
    assert.match(found_().allText, /^5 transactions found · In ₹1,30,000\s+Out ₹25,400$/);   // 2400 + 3000 + 20000, the 5000 transfer left out
    p.run(`txnQuery = { text: "mystery", category: "", account: "", min: null, max: null }; renderTransactions()`);
    assert.match(found_().allText, /^1 transaction found/);
    p.run(`txnQuery = { text: "zzz", category: "", account: "", min: null, max: null }; renderTransactions()`);
    assert.ok(found_().classList.contains("hidden"), "nothing found: the empty message says it");
});

test("the drop-downs list the categories with their sub categories under them, then every account, and keep the choice", () => {
    const p = page();
    p.run("fillTxnFilterOptions()");
    assert.deepEqual(p.node("txnCategory").children.map(o => o.textContent),
        ["Any category", "Uncategorised", "Food", "Food › Dining out", "Food › Groceries", "Salary", "Salary › Base pay"]);
    assert.deepEqual(p.node("txnAccount").children.map(o => o.textContent), ["Any account", "Cash", "Savings Bank", "Travel Card"]);
    p.run(`txnQuery.category = "11"; txnQuery.account = "3"; fillTxnFilterOptions()`);
    assert.equal(p.node("txnCategory").value, "11");
    assert.equal(p.node("txnAccount").value, "3");
});

test("typing in the box searches after a short pause; the filters and Clear work at once", async () => {
    const p = page();
    p.run("initTransactionSearch(); renderTransactions()");
    assert.ok(p.node("txnClear").classList.contains("hidden"));
    p.run("txnPageSize = 2; txnPage = 3");                      // three or more pages of results, and we are on the third
    p.node("txnSearch").value = "o";
    p.node("txnSearch").fire("input");
    assert.equal(p.run("txnQuery.text"), "", "not yet: it waits for a pause in the typing");
    await new Promise(resolve => setTimeout(resolve, 300));
    assert.equal(p.run("txnQuery.text"), "o");
    assert.equal(p.run("txnPage"), 1, "a new search starts at the first page");
    assert.equal(rows(p).length, 2, "two to a page");
    p.run("txnPageSize = 10");
    p.node("txnSearch").value = "shop";
    p.node("txnSearch").fire("input");
    await new Promise(resolve => setTimeout(resolve, 300));
    assert.equal(rows(p).length, 1);
    assert.ok(!p.node("txnClear").classList.contains("hidden"), "Clear appears once something is set");

    p.node("txnMin").value = "1000"; p.node("txnMin").fire("change");
    p.node("txnMax").value = "";   p.node("txnMax").fire("change");
    assert.deepEqual([p.run("txnQuery.min"), p.run("txnQuery.max")], [1000, null], "an empty box means no limit");

    p.node("txnCategory").value = "10"; p.node("txnCategory").fire("change");
    p.node("txnAccount").value = "1";   p.node("txnAccount").fire("change");
    assert.deepEqual([p.run("txnQuery.category"), p.run("txnQuery.account")], ["10", "1"]);

    p.node("txnClear").fire("click");
    assert.equal(p.node("txnCategory").value, "", "the category box is emptied too");
    assert.equal(p.node("txnAccount").value, "", "and the account box");
    assert.deepEqual(p.run("txnQuery"), { text: "", category: "", account: "", min: null, max: null, tag: "" });
    assert.equal(p.node("txnSearch").value, "");
    assert.equal(rows(p).length, 7);
    assert.ok(p.node("txnClear").classList.contains("hidden"));
});

test("searching 10,000 transactions is quick enough to do on a pause in typing", () => {
    const p = page();
    p.run(`transactions = Array.from({ length: 10000 }, (_, i) => ({ id: i, date: "2026-10-" + String(1 + i % 28).padStart(2, "0"),
        kind: i % 5 === 0 ? "INCOME" : "EXPENSE", fromAccountId: 1 + i % 3, toAccountId: null, amount: 100 + i, categoryId: 11 + i % 2,
        note: "note number " + i, description: i % 7 === 0 ? "with a description" : null }))`);
    const start = Date.now();
    const count = p.run(`transactions.filter(t => txnMatchesQuery(t, { text: "number 99 description", category: "10", account: "", min: null, max: null })).length`);
    const ms = Date.now() - start;
    assert.ok(count > 0 && count < 10000);
    assert.ok(ms < 400, `${ms} ms for 10,000 transactions`);   // measured about 60 ms; the first search builds each transaction's text
});

test("after the first search the text of each transaction is not built again", () => {
    const p = page();
    p.run(`var first = transactions[1]; first.note = "before"; var a = txnHaystack(first); first.note = "after"; var b = txnHaystack(first)`);
    assert.match(p.run("b"), /before/, "the text built the first time is reused (this is why a refresh must bring new objects)");
    assert.equal(p.run("a === b"), true);
    p.run(`transactions = transactions.map(t => Object.assign({}, t, { note: "changed" })); var c = txnHaystack(transactions[1])`);
    assert.match(p.run("c"), /changed/, "a refresh brings new objects, so a changed note is found");
});


test("a row that uses a credit card says what the card owed just after it, counting the opening balance, fees and payments", () => {
    const p = page();
    p.run(`accounts[2].type = "CREDIT_CARD"; accounts[2].openingBalance = 1000;
        transactions.push(
            { id: 8, date: "2026-10-04", kind: "EXPENSE", fromAccountId: 3, toAccountId: null, amount: 500, fee: 25, categoryId: null, note: "Same day, later", description: null },
            { id: 9, date: "2026-10-08", kind: "EXPENSE", fromAccountId: 1, toAccountId: 3, amount: 1300, categoryId: null, note: "Card bill", description: null });
        renderTransactions()`);
    assert.equal(p.run(`balanceAfter(transactions[3], 3)`), 2800, "1,000 opening + 1,800");
    assert.equal(p.run(`balanceAfter(transactions[7], 3)`), 3325, "the later one the same day, with its 25 fee");
    assert.equal(p.run(`balanceAfter(transactions[8], 3)`), 2025, "a payment into the card lowers it");
    assert.equal(p.run(`balanceAfter(transactions[1], 3)`), undefined, "a transaction that does not touch the card has none");
    const text = id => p.raw(`txnRowElement(transactions.find(t => t.id === ${id}))`).allText;
    assert.ok(text(4).includes("Travel Card withdrawal ₹2,800"), "no limit set: the amount withdrawn");
    p.run(`creditLimits = new Map([[3, 10000]])`);
    assert.ok(text(4).includes("Travel Card remaining balance ₹7,200"), text(4));
    assert.ok(text(8).includes("Travel Card remaining balance ₹6,675"), text(8));
    assert.ok(text(9).includes("Travel Card remaining balance ₹7,975"), "a payment gives balance back");
    assert.ok(text(2).includes("Savings Bank remaining balance ₹127,600") || text(2).includes("Savings Bank remaining balance ₹1,27,600"), "a bank row gives the bank's balance: " + text(2));
    assert.ok(!text(2).includes("withdrawal"), "only a card talks about a withdrawal");
});

test("every row gives the remaining balance of its account(s) after the transaction; a transfer gives both; a debt says what is still owed", () => {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "Savings Bank", type: "BANK", openingBalance: 1000 }, { id: 2, name: "Cash", type: "CASH", openingBalance: 0 },
        { id: 3, name: "Travel Card", type: "CREDIT_CARD", openingBalance: 0 }, { id: 4, name: "Car Loan", type: "DEBT", openingBalance: 50000 },
        { id: 5, name: "Receivables", type: "RECEIVABLE", openingBalance: 0 }];
        creditLimits = new Map();
        transactions = [
            { id: 1, date: "2026-10-01", kind: "INCOME", toAccountId: 1, fromAccountId: null, amount: 9000, note: "Pay" },
            { id: 2, date: "2026-10-02", kind: "TRANSFER", fromAccountId: 1, toAccountId: 2, amount: 500, fee: 5, note: "Top-up" },
            { id: 3, date: "2026-10-02", kind: "EXPENSE", fromAccountId: 2, toAccountId: null, amount: 120, note: "Tea" },
            { id: 4, date: "2026-10-03", kind: "EXPENSE", fromAccountId: 1, toAccountId: 4, amount: 15000, note: "EMI" },
            { id: 5, date: "2026-10-04", kind: "INCOME", toAccountId: 5, fromAccountId: null, amount: 1, note: "Not an ordinary account" }];`);
    const text = id => p.raw(`txnRowElement(transactions.find(t => t.id === ${id}))`).allText;
    const money = n => p.run(`rupees(${n})`);
    assert.ok(text(1).includes("Savings Bank remaining balance " + money(10000)), "1,000 opening + 9,000: " + text(1));
    assert.ok(text(2).includes("Savings Bank remaining balance " + money(9495)) && text(2).includes("Cash remaining balance " + money(500)), "both sides of a transfer, the fee taken from the source: " + text(2));
    assert.ok(text(3).includes("Cash remaining balance " + money(380)), text(3));
    assert.ok(text(4).includes("Savings Bank remaining balance " + money(-5505)) && text(4).includes("Car Loan remaining owed " + money(35000)), "a payment into a debt lowers what is owed: " + text(4));
    assert.ok(!text(5).includes("remaining"), "Receivables is worked out from loans, so no running balance");
    assert.equal(p.run(`balanceAfter(transactions[3], 1)`), -5505);
});

test("transactions on one day are taken in id order, and the last figure of an account is its balance (opening + in - out, fees included)", () => {
    const p = page();
    p.run(`accounts = [{ id: 1, name: "Savings Bank", type: "BANK", openingBalance: 300 }, { id: 2, name: "Cash", type: "CASH", openingBalance: 0 }];
        transactions = [
            { id: 1, date: "2026-10-01", kind: "INCOME", toAccountId: 1, fromAccountId: null, amount: 100 },
            { id: 3, date: "2026-10-03", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 40 },
            { id: 2, date: "2026-10-03", kind: "TRANSFER", fromAccountId: 1, toAccountId: 2, amount: 10, fee: 1 }];`);
    assert.equal(p.run(`balanceAfter(transactions[2], 1)`), 389, "300 + 100 - 10 - 1 fee");
    assert.equal(p.run(`balanceAfter(transactions[1], 1)`), 349, "then the expense of 40: the account's balance");
});

test("a card's statement amount counts everything before the statement day, and nothing dated on it", () => {
    const p = page();
    p.run(`accounts[2].type = "CREDIT_CARD"; accounts[2].openingBalance = 1000;
        transactions.push({ id: 8, date: "2026-10-04", kind: "EXPENSE", fromAccountId: 3, toAccountId: null, amount: 500, fee: 25 },
            { id: 9, date: "2026-10-08", kind: "EXPENSE", fromAccountId: 1, toAccountId: 3, amount: 1300 })`);
    const amount = day => p.run(`cardStatementAmount(accounts[2], "${day}")`);
    assert.equal(amount("2026-10-04"), 1000, "a statement on the 4th: the 1,800 and the 525 spent on the 4th are left out");
    assert.equal(amount("2026-10-05"), 3325, "they count for the statement of the 5th (1,000 + 1,800 + 500 + 25)");
    assert.equal(amount("2026-10-08"), 3325, "a payment on the statement day is not in it either");
    assert.equal(amount("2026-10-09"), 2025, "but is for the next one");
    assert.equal(amount("2026-09-01"), 1000, "before any spending: the opening balance");
});

test("a transaction on the Ledger, a portfolio or Jewellery has no pencil, because the server refuses to edit it; others keep theirs", () => {
    const p = page();
    p.run(`jewellery = { configured: true, accountId: 3, metals: [] }; portfolios.SHARES = { configured: true, accountId: 2, holdings: [] }; renderTransactions()`);
    const pencil = id => p.raw(`txnRowElement(transactions.find(t => t.id === ${id}))`).find(n => n.tag === "button" && n.textContent === "✏️").length;
    assert.equal(pencil(4), 0, "paid from the Jewellery account");
    assert.equal(pencil(3), 0, "paid from the Shares account");
    assert.equal(pencil(2), 1, "an ordinary bank expense can still be edited");
});
