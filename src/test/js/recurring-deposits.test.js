const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The Recurring Deposits card: the term bar, the fold of transactions under each deposit, closed deposits kept, and the dialogs. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/tooltip.js", "core/state.js", "core/currency.js", "panels/charts.js",
        "panels/emergency-fund.js", "panels/period-filter.js", "panels/summary.js", "panels/allocation.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js",
        "panels/ledger.js", "panels/portfolio-stats.js", "panels/portfolio.js", "panels/jewellery.js", "panels/transactions.js", "panels/recurring-deposits.js");
    p.run(`function renderAccounts() {} function clientLog() {} var calls = [];
        function api(path, options) { calls.push({ path, method: options && options.method, body: options && options.body }); return Promise.resolve({}); }
        accounts = [{ id: 1, name: "Savings Bank", type: "BANK", active: true, balance: 50000, currency: "INR" },
                    { id: 20, name: "Recurring Deposits", type: "INVESTMENT", active: true, balance: 63000, currency: "INR" },
                    { id: 30, name: "Dollars", type: "BANK", active: true, balance: 5, currency: "USD" }];
        allCategories = []; loans = { borrowers: [] };
        recurringDeposits = { configured: true, accountId: 20, name: "Recurring Deposits", deposited: 63000, open: [
            { id: 1, name: "Bank RD", startDate: "2026-01-07", termMonths: 36, maturesOn: "2029-01-07", monthlyAmount: 21000, interestRate: 6.5, status: "OPEN",
              closedOn: null, deposited: 63000, instalments: 3, plannedTotal: 756000, expectedMaturity: 850000.5, received: null, interestEarned: null,
              monthsElapsed: 7, progressPercent: 19 }],
          closed: [
            { id: 2, name: "Old RD", startDate: "2025-01-19", termMonths: 12, maturesOn: "2026-01-19", monthlyAmount: null, interestRate: null, status: "CLOSED",
              closedOn: "2026-01-19", deposited: 200000, instalments: 0, plannedTotal: null, expectedMaturity: null, received: 215001, interestEarned: 15001,
              monthsElapsed: 12, progressPercent: 100 }] };
        transactions = [
            { id: 11, date: "2026-01-07", kind: "TRANSFER", fromAccountId: 1, toAccountId: 20, amount: 21000, recurringDepositId: 1, note: "Deposit: Bank RD" },
            { id: 12, date: "2026-02-07", kind: "TRANSFER", fromAccountId: 1, toAccountId: 20, amount: 21000, recurringDepositId: 1, note: "Deposit: Bank RD" },
            { id: 13, date: "2026-03-07", kind: "TRANSFER", fromAccountId: 1, toAccountId: 20, amount: 21000, recurringDepositId: 1, note: "Deposit: Bank RD" },
            { id: 14, date: "2026-01-19", kind: "TRANSFER", fromAccountId: 20, toAccountId: 1, amount: 200000, recurringDepositId: 2, note: "Closed: Old RD" },
            { id: 15, date: "2026-01-19", kind: "INCOME", fromAccountId: null, toAccountId: 1, amount: 15001, recurringDepositId: 2, note: "Interest: Old RD" },
            { id: 16, date: "2026-01-10", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 50, note: "Tea" }];`);
    return p;
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0));
const card = p => p.raw(`recurringDepositsCard()`);

test("the account is recognised, so ordinary forms leave it out", () => {
    const p = page();
    assert.equal(p.run(`isRecurringDepositAccount(20)`), true);
    assert.equal(p.run(`isRecurringDepositAccount(1)`), false);
    p.run(`recurringDeposits = { configured: false, open: [], closed: [] }`);
    assert.equal(p.run(`isRecurringDepositAccount(20)`), false);
});

test("before set up the card offers Set up; with the account but no deposits, Open a deposit", () => {
    const p = page();
    p.run(`recurringDeposits = { configured: false, open: [], closed: [], deposited: 0 }`);
    assert.equal(card(p).find(n => n.tag === "button")[0].textContent, "Set up");
    p.run(`recurringDeposits = { configured: true, accountId: 20, open: [], closed: [], deposited: 0 }`);
    assert.equal(card(p).find(n => n.tag === "button")[0].textContent, "Open a deposit");
});

test("closed, the card shows what the open deposits hold and how many are open and closed; the list starts folded", () => {
    const p = page();
    const c = card(p);
    assert.ok(c.allText.includes("Paid in ₹63,000.00"), c.allText);
    assert.ok(c.allText.includes("1 open · 1 closed"), c.allText);
    assert.equal(c.find(n => n.className === "rd-item").length, 0);
});

test("an open deposit shows its term bar, its figures and what it should pay", () => {
    const p = page();
    p.run(`rdExpanded = true`);
    const c = card(p);
    assert.equal(c.find(n => n.className === "lg-name").map(n => n.textContent)[0], "Bank RD");
    assert.ok(c.allText.includes("Month 7 of 36 · 19%"), c.allText);
    const bar = c.find(n => n.className === "ef-bar")[0];
    assert.equal(bar.attrs.role, "progressbar");
    assert.equal(bar.attrs["aria-valuenow"], "19");
    assert.equal(c.find(n => String(n.className).startsWith("ef-fill"))[0].style.width, "19%");
    for (const text of ["3 deposits", "matures", "₹21,000.00 a month", "6.5% a year", "Paid in ₹63,000.00 of ₹7,56,000.00", "expected at maturity ₹8,50,000.50"]) {
        assert.ok(c.allText.includes(text), text + " is missing: " + c.allText);
    }
    assert.deepEqual(c.find(n => n.tag === "button" && ["Deposit", "Close"].includes(n.textContent)).map(n => n.textContent), ["Deposit", "Close"]);
});

test("a matured deposit has a full green bar, and a closed one says when it closed", () => {
    const p = page();
    assert.equal(p.run(`rdProgressText({ status: "OPEN", progressPercent: 100, maturesOn: "2029-01-07" })`), "Matured on 07 Jan 2029");
    assert.equal(p.run(`rdProgressText({ status: "OPEN", progressPercent: 0, monthsElapsed: 0, startDate: "2030-01-07" })`), "Starts 07 Jan 2030");
    assert.equal(p.run(`rdProgressText({ status: "CLOSED", closedOn: "2026-01-19" })`), "Closed on 19 Jan 2026");
    p.run(`rdExpanded = true; recurringDeposits.open[0].progressPercent = 100`);
    assert.ok(String(card(p).find(n => String(n.className).startsWith("ef-fill"))[0].className).includes("reached"));
});

test("each deposit has a Transactions fold that starts closed and then lists its own transfers, newest first", () => {
    const p = page();
    p.run(`rdExpanded = true`);
    const fold = () => card(p).find(n => n.tag === "button" && String(n.textContent).startsWith("Transactions"))[0];
    assert.equal(fold().textContent, "Transactions (3) ▸");
    assert.equal(fold().attrs["aria-expanded"], "false");
    assert.equal(card(p).find(n => n.className === "txn-row head").length, 0, "no rows while folded");

    p.run(`rdOpenTransactions.add(1)`);
    assert.equal(fold().textContent, "Transactions (3) ▾");
    const rows = card(p).find(n => String(n.className).startsWith("txn-row") && !String(n.className).includes("head"));
    assert.deepEqual(rows.map(n => n.dataset.txnId), [13, 12, 11], "only this deposit's transactions, the newest first");
    assert.equal(rows.length, 3, "the tea and the other deposit's rows are not here");
});

test("a deposit with no transactions says so when its fold is open", () => {
    const p = page();
    p.run(`rdExpanded = true; transactions = []; rdOpenTransactions.add(1)`);
    assert.ok(card(p).allText.includes("No deposits yet."));
});

test("closed deposits are kept in a fold of their own, with what came back and the interest, and no buttons", () => {
    const p = page();
    p.run(`rdExpanded = true`);
    assert.equal(card(p).find(n => n.tag === "button" && String(n.textContent).startsWith("Closed ("))[0].textContent, "Closed (1) ▸");
    assert.ok(!card(p).allText.includes("Old RD"), "folded");

    p.run(`rdClosedShown = true`);
    const c = card(p);
    assert.ok(c.allText.includes("Old RD") && c.allText.includes("Closed on 19 Jan 2026"), c.allText);
    assert.ok(c.allText.includes("Paid in ₹2,00,000.00 · got back ₹2,15,001.00 · interest ₹15,001.00"), c.allText);
    const closedItem = c.find(n => String(n.className).includes("rd-item closed"))[0];
    assert.deepEqual(closedItem.find(n => n.tag === "button").map(n => n.textContent), ["Transactions (2) ▸"], "only the fold: no Deposit or Close once closed");
});

test("only a deposit into an open recurring deposit can be deleted, and it is the row that gets the bin", () => {
    const p = page();
    assert.equal(p.run(`isRdDeposit(transactions.find(t => t.id === 11))`), true);
    assert.equal(p.run(`isRdDeposit(transactions.find(t => t.id === 14))`), false, "the payout of a closed deposit");
    assert.equal(p.run(`isRdDeposit(transactions.find(t => t.id === 16))`), false, "an ordinary expense");
    const bins = id => p.raw(`txnRowElement(transactions.find(t => t.id === ${id}))`).find(n => n.tag === "button" && n.textContent === "🗑️").length;
    assert.equal(bins(11), 1);
    assert.equal(bins(14), 0);
    assert.equal(p.raw(`txnRowElement(transactions.find(t => t.id === 11))`).find(n => n.tag === "button" && n.textContent === "✏️").length, 0, "the server refuses to edit it");
});

test("the Open dialog sends the terms and refuses a term that is not a whole number of months from 1 to 240", async () => {
    const p = page();
    p.run(`openRdForm(null)`);
    const inputs = () => p.node("ledgerBody").find(x => x.tag === "input");
    const [name, start, term, monthly, rate, already] = inputs();
    name.value = "Post office RD"; start.value = "2026-03-01"; term.value = "0"; monthly.value = "5000"; rate.value = "6.7"; already.value = "";
    p.run(`dlg.onPrimary()`);
    assert.match(p.node("ledgerError").textContent, /1 to 240/);
    term.value = "60";
    p.run(`calls.length = 0`);
    p.run(`dlg.onPrimary()`);
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/recurring-deposits/deposits", method: "POST",
        body: { name: "Post office RD", startDate: "2026-03-01", termMonths: 60, monthlyAmount: 5000, interestRate: 6.7, alreadyDeposited: 0 } }]);
});

test("changing a deposit starts from its terms and sends a PUT; what was paid in before is shown without the later deposits", async () => {
    const p = page();
    p.run(`recurringDeposits.open[0].deposited = 68000`);   // 5000 was already in, 63000 went in through Deposit
    p.run(`openRdForm(recurringDeposits.open[0])`);
    const [name, start, term, monthly, rate, already] = p.node("ledgerBody").find(x => x.tag === "input");
    assert.deepEqual([name.value, start.value, term.value, monthly.value, rate.value, already.value], ["Bank RD", "2026-01-07", 36, 21000, 6.5, 5000]);
    term.value = "48";
    p.run(`calls.length = 0`);
    p.run(`dlg.onPrimary()`);
    await flush();
    assert.equal(p.run("calls")[0].path, "/api/recurring-deposits/deposits/1");
    assert.equal(p.run("calls")[0].method, "PUT");
    assert.equal(p.run("calls")[0].body.termMonths, 48);
    assert.equal(p.run("calls")[0].body.alreadyDeposited, 5000);
});

test("the Deposit dialog starts with the monthly amount, offers rupee bank and cash accounts, and sends the day", async () => {
    const p = page();
    p.run(`openRdDepositDialog(recurringDeposits.open[0])`);
    const body = p.node("ledgerBody");
    const [from] = body.find(x => x.tag === "select");
    assert.deepEqual(from.children.map(o => o.textContent.split(" · ")[0]), ["Savings Bank"], "no foreign account, no investment account");
    from.value = "1";
    const [amount, date] = body.find(x => x.tag === "input");
    assert.equal(Number(amount.value), 21000);
    date.value = "2026-04-07";
    p.run(`calls.length = 0`);
    p.run(`dlg.onPrimary()`);
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/recurring-deposits/deposits/1/deposit", method: "POST", body: { fromAccountId: 1, amount: 21000, date: "2026-04-07" } }]);
});

test("the Close dialog needs at least what was paid in and sends where it goes and the day", async () => {
    const p = page();
    p.run(`openRdCloseDialog(recurringDeposits.open[0])`);
    const body = p.node("ledgerBody");
    body.find(x => x.tag === "select")[0].value = "1";
    const [received, date] = body.find(x => x.tag === "input");
    assert.equal(Number(received.value), 850000.5, "the expected maturity to start with");
    received.value = "62999";
    p.run(`dlg.onPrimary()`);
    assert.match(p.node("ledgerError").textContent, /at least ₹63,000.00/);
    received.value = "65000"; date.value = "2026-04-07";
    p.run(`calls.length = 0`);
    p.run(`dlg.onPrimary()`);
    await flush();
    assert.deepEqual(p.run("calls"), [{ path: "/api/recurring-deposits/deposits/1/close", method: "POST", body: { toAccountId: 1, amountReceived: 65000, date: "2026-04-07" } }]);
});
