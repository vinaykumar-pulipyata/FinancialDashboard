const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* Under a person in Receivables: one fold of what is shared with them and what they paid for you, and one Settle for the balance. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/summary.js", "panels/allocation.js", "panels/transaction-tags.js", "core/split-calc.js",
        "panels/split-form.js", "panels/transaction-form.js", "panels/ledger.js", "panels/split-block.js");
    p.run(`function renderAccounts() {} function clientLog() {} function isPortfolioAccount() { return false; } function isJewelleryAccount() { return false; } function isRecurringDepositAccount() { return false; } var redraws = 0; var calls = [];
        function api(path, options) { calls.push({ path, options }); return Promise.resolve({}); }
        accounts = [{ id: 1, name: "Bank", type: "BANK", active: true }, { id: 2, name: "Cash", type: "CASH", active: true }];
        transactions = [
            { id: 1, date: "2026-11-01", kind: "EXPENSE", amount: 1000, note: "Dinner", splits: { 7: 500 } },
            { id: 2, date: "2026-11-05", kind: "EXPENSE", amount: 600, note: "Movie", splits: { 7: 300, 8: 100 } },
            { id: 3, date: "2026-11-06", kind: "EXPENSE", amount: 80, note: "Tea" },
            { id: 9, date: "2026-11-10", kind: "INCOME", amount: 200, note: "Settled", borrowerId: 7 },
            { id: 20, date: "2026-11-02", kind: "EXPENSE", amount: 1200, note: "Hotel", paidByBorrowerId: 8, ownShare: 400 },
            { id: 21, date: "2026-11-04", kind: "EXPENSE", amount: 600, note: "Cab", paidByBorrowerId: 8, ownShare: 300 },
            { id: 22, date: "2026-11-12", kind: "EXPENSE", amount: 250, note: "Paid back", borrowerId: 8 }];
        splitSettlements = [{ id: 1, borrowerId: 7, expenseTransactionId: 1, incomeTransactionId: 9, amount: 200 },
            { id: 2, borrowerId: 8, expenseTransactionId: 20, incomeTransactionId: 22, amount: 250 },
            { id: 3, borrowerId: 8, expenseTransactionId: 21, incomeTransactionId: 0, amount: 50 },
            { id: 4, borrowerId: 8, expenseTransactionId: 2, incomeTransactionId: 0, amount: 50 }];`);
    return p;
}


test("the arrow before a person's name folds their transactions out under the name; folded, nothing is shown", () => {
    const p = page();
    const count = id => p.raw(`personToggle({ id: ${id}, name: "X" }, () => {})`).title.match(/\((\d+)\)/)[1];
    assert.equal(count(7), "2");
    assert.equal(count(99), "0", "nothing recorded with this person");
    assert.equal(p.run(`hasOpenBalance({ id: 7, owed: 600 })`), true);
    const arrow = p.raw(`personToggle({ id: 8, name: "Ravi" }, () => { redraws++; })`);
    assert.equal(arrow.textContent, "▸");
    assert.match(arrow.title, /Show the transactions with Ravi \(3\)/);   // two bills he paid and one share (Ravi owes 100 of the movie)
    assert.equal(p.raw(`personTransactionsBlock({ id: 8, name: "Ravi" })`), null, "folded: nothing under the name");
    arrow.fire("click");
    assert.equal(p.run("redraws"), 1);
    assert.equal(p.run(`splitBlocksOpen.has(8)`), true);
    assert.equal(p.raw(`personToggle({ id: 8, name: "Ravi" }, () => {})`).textContent, "▾");
    arrow.fire("click");
    assert.equal(p.run(`splitBlocksOpen.has(8)`), false);
});

test("the open fold lists both kinds together, newest first, with what is settled and what is left, and the payments with what they cleared", () => {
    const p = page();
    p.run(`splitBlocksOpen.add(8)`);
    const text = p.raw(`personTransactionsBlock({ id: 8, name: "Ravi" })`).allText;
    assert.match(text, /Hotel \(they paid\)/);
    assert.match(text, /Cab \(they paid\)/);
    assert.match(text, /Movie/);
    assert.ok(text.indexOf("Cab") < text.indexOf("Hotel"), "newest first");
    assert.match(text, /You paid them: cleared Hotel ₹250/);
    assert.match(text, /Set off against each other/);
    p.run(`splitBlocksOpen.add(7)`);
    assert.match(p.raw(`personTransactionsBlock({ id: 7, name: "Teja" })`).allText, /They paid you: cleared Dinner ₹200/);
});

test("the Settle button sits on the name line and opens one window for the balance", () => {
    const p = page();
    const button = p.raw(`settleButton({ id: 7, name: "Teja", owed: 600 })`);
    assert.equal(button.textContent, "Settle");
    button.fire("click");
    assert.match(p.node("ledgerTitle").textContent, /Settle · Teja/);
    assert.match(p.node("ledgerBody").allText, /Teja owes you ₹600 for shared expenses/);
    assert.match(p.node("ledgerBody").allText, /pays you the rest, ₹600/);
});

test("when you owe more the window says so, sets off both sides and sends the account the money leaves from", async () => {
    const p = page();
    p.run(`splitSettlements = []`);   // Ravi: you owe 700, he owes you 100
    p.run(`openSettleBalance({ id: 8, name: "Ravi", owed: 100, youOwe: 700 })`);
    const body = p.node("ledgerBody");
    assert.match(body.allText, /You owe Ravi ₹700 for bills they paid/);
    assert.match(body.allText, /₹100 of it is set off/);
    assert.match(body.allText, /You pay Ravi the rest, ₹600/);
    body.find(n => n.tag === "select")[0].value = "1";
    const [amount] = body.find(n => n.tag === "input" && n.type === "number");
    assert.equal(amount.value, 600);
    await p.raw(`dlg.onPrimary()`);
    const call = p.run(`calls[0]`);
    assert.equal(call.path, "/api/borrowers/8/settle-balance");
    assert.equal(call.options.body.amount, 600);
    assert.equal(call.options.body.accountId, 1);
});

test("the window refuses more than the balance, and an offset alone needs no amount or account", async () => {
    const p = page();
    p.run(`openSettleBalance({ id: 7, name: "Teja", owed: 600 })`);
    const body = p.node("ledgerBody");
    body.find(n => n.tag === "input" && n.type === "number")[0].value = "601";
    body.find(n => n.tag === "select")[0].value = "1";
    p.run(`dlg.onPrimary()`);
    assert.match(p.node("ledgerError").textContent, /more than the ₹600/);
    assert.equal(p.run(`calls.length`), 0);
    // equal on both sides: nothing to pay, only the offset
    p.run(`transactions = [{ id: 1, date: "2026-11-01", kind: "EXPENSE", amount: 1000, note: "Dinner", splits: { 7: 300 } },
        { id: 2, date: "2026-11-02", kind: "EXPENSE", amount: 600, note: "Hotel", paidByBorrowerId: 7, ownShare: 300 }]; splitSettlements = []`);
    p.run(`openSettleBalance({ id: 7, name: "Teja", owed: 300, youOwe: 300 })`);
    assert.match(p.node("ledgerBody").allText, /Nothing is left to pay/);
    await p.raw(`dlg.onPrimary()`);
    const call = p.run(`calls[0]`);
    assert.equal(call.path, "/api/borrowers/7/settle-balance");
    assert.equal(call.options.body.amount, null);
});

test("a person with only plain loans still gets the fold, listing what you lent and what they paid back; one with nothing says so", () => {
    const p = page();
    p.run(`transactions.push({ id: 30, date: "2026-10-01", kind: "EXPENSE", amount: 5000, note: "Medical", borrowerId: 50 },
        { id: 31, date: "2026-10-20", kind: "INCOME", amount: 2000, borrowerId: 50 })`);
    p.run(`splitBlocksOpen.add(50); splitBlocksOpen.add(51)`);
    const text = p.raw(`personTransactionsBlock({ id: 50, name: "Meera" })`).allText;
        assert.match(text, /Lent: Medical/);
    assert.match(text, /Paid back: Loan/);
        const empty = p.raw(`personTransactionsBlock({ id: 51, name: "Nobody" })`).allText;
        assert.match(empty, /Nothing recorded with this person yet/);
    // the payments that settled shared bills are listed under Payments, not twice as loans
    p.run(`splitBlocksOpen.add(7)`);
    assert.doesNotMatch(p.raw(`personTransactionsBlock({ id: 7, name: "Teja" })`).allText, /Paid back: Loan|Lent:/);
});

test("a person who owes you a loan pays you, even when you owe them a bill: the bill is set off against the loan and the money comes from them", async () => {
    const p = page();
    p.run(`splitSettlements = []; transactions = [
        { id: 1, date: "2026-11-02", kind: "EXPENSE", amount: 600, note: "Hotel", paidByBorrowerId: 7, ownShare: 300 },
        { id: 2, date: "2026-11-01", kind: "EXPENSE", amount: 10000, note: "Loan", borrowerId: 7 }]`);
    const person = `{ id: 7, name: "Teja", owed: 10000, youOwe: 300 }`;
    assert.equal(p.run(`hasOpenBalance(${person})`), true);
    p.run(`openSettleBalance(${person})`);
    const body = p.node("ledgerBody");
    assert.match(body.allText, /Teja owes you ₹10,000 in loans/);
    assert.match(body.allText, /You owe Teja ₹300 for bills they paid/);
    assert.match(body.allText, /₹300 of it is set off/);
    assert.match(body.allText, /Teja pays you the rest, ₹9,700/);
    assert.match(body.allText, /Into account/);
    assert.doesNotMatch(body.allText, /From account/);
    body.find(n => n.tag === "select")[0].value = "1";
    assert.equal(body.find(n => n.tag === "input" && n.type === "number")[0].value, 9700);
    await p.raw(`dlg.onPrimary()`);
    assert.equal(p.run(`calls[0].options.body.amount`), 9700);
});

test("what a person owed before you started is a line of its own, so the list adds up to their balance", () => {
    const p = page();
    p.run(`transactions = [{ id: 40, date: "2026-05-11", kind: "EXPENSE", amount: 40000, note: "Medical bills", borrowerId: 10 },
        { id: 41, date: "2026-10-09", kind: "INCOME", amount: 10000, note: "Loan repaid", borrowerId: 10 }]; splitSettlements = []; splitBlocksOpen.add(10)`);
    const text = p.raw(`personTransactionsBlock({ id: 10, name: "Kiran", openingAmount: 150000 })`).allText;
    assert.match(text, /Lent: Medical bills/);
    assert.match(text, /Paid back: Loan repaid/);
    assert.match(text, /Owed before you started using the app/);
    assert.match(text, /\+₹1,50,000/);
    assert.match(p.raw(`personToggle({ id: 10, name: "Kiran", openingAmount: 150000 }, () => {})`).title, /\(2\)/, "the count is of transactions, not the opening line");
});

test("a write-off is listed under the person with its day and reason, lowering what they owe, so you can see when it happened", () => {
    const p = page();
    p.run(`transactions = [{ id: 40, date: "2026-05-11", kind: "EXPENSE", amount: 40000, note: "Medical bills", borrowerId: 10 }]; splitSettlements = [];
        writeOffs = [{ id: 1, borrowerId: 10, amount: 20000, writtenOn: "2026-09-01", note: "Lost his job" },
                     { id: 2, borrowerId: 11, amount: -300, writtenOn: "2026-09-02", note: "Forgot it" }]; splitBlocksOpen.add(10); splitBlocksOpen.add(11)`);
    const text = p.raw(`personTransactionsBlock({ id: 10, name: "Kiran" })`).allText;
    assert.match(text, /Written off: Lost his job/);
    assert.match(text, /−₹20,000/);
    assert.ok(text.indexOf("Written off") < text.indexOf("Lent: Medical bills"), "newest first");
    assert.match(p.raw(`personToggle({ id: 10, name: "Kiran" }, () => {})`).title, /\(2\)/);
    assert.match(p.raw(`personToggle({ id: 11, name: "RM" }, () => {})`).title, /\(1\)/, "a write-off alone is a transaction to keep track of");
    assert.match(p.raw(`personTransactionsBlock({ id: 11, name: "RM" })`).allText, /\+₹300/, "you owed him and let it go: the balance goes up");
});

test("a write-off row has a pencil that changes its day and reason and a bin that takes it back", async () => {
    const p = page();
    p.run(`transactions = []; splitSettlements = []; writeOffs = [{ id: 5, borrowerId: 10, amount: 20000, writtenOn: "2026-09-01", note: "Lost his job" }];
        splitBlocksOpen.add(10); var refreshed = 0; function refreshData() { refreshed++; return Promise.resolve(); }; var asked = 0; function confirm() { asked++; return true; }`);
    const block = p.raw(`personTransactionsBlock({ id: 10, name: "Kiran" })`);
    const [edit] = block.find(n => n.tag === "button" && n.textContent === "✏️");
    const [bin] = block.find(n => n.tag === "button" && n.textContent === "🗑");
    assert.ok(edit && bin, "both buttons are on the row");
    edit.fire("click");
    assert.match(p.node("ledgerTitle").textContent, /Write-off/);
    assert.match(p.node("ledgerBody").allText, /₹20,000 was let go of/);
    const body = p.node("ledgerBody");
    const [date, note] = body.find(n => n.tag === "input");
    assert.equal(date.value, "2026-09-01");
    assert.equal(note.value, "Lost his job");
    note.value = "  Moved away  ";
    date.value = "2026-09-03";
    await p.raw(`dlg.onPrimary()`);
    const call = p.run(`calls[0]`);
    assert.equal(call.path, "/api/loans/write-offs/5");
    assert.equal(call.options.method, "PUT");
    assert.deepEqual(call.options.body, { date: "2026-09-03", note: "Moved away" });
    p.run(`calls.length = 0`);
    bin.fire("click");
    await new Promise(r => setTimeout(r, 0));
    assert.equal(p.run("asked"), 1);
    assert.equal(p.run(`calls[0].path`), "/api/loans/write-offs/5");
    assert.equal(p.run(`calls[0].options.method`), "DELETE");
    assert.equal(p.run("refreshed"), 2, "the page was redrawn after the save and after the delete");
});

test("the write-off window refuses an empty reason", () => {
    const p = page();
    p.run(`transactions = []; splitSettlements = []; writeOffs = [{ id: 5, borrowerId: 10, amount: 20000, writtenOn: "2026-09-01", note: "Lost his job" }]; splitBlocksOpen.add(10)`);
    p.raw(`personTransactionsBlock({ id: 10, name: "Kiran" })`).find(n => n.textContent === "✏️")[0].fire("click");
    p.node("ledgerBody").find(n => n.tag === "input")[1].value = "   ";
    p.run(`dlg.onPrimary()`);
    assert.match(p.node("ledgerError").textContent, /Say why/);
    assert.equal(p.run(`calls.length`), 0);
});
