const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* A loan row: how much of it is repaid (a bar and a line), and a fold with its transactions under it, as a credit card has. */
function page() {
    const p = createPage().load("core/constants.js", "core/util.js", "core/state.js", "core/currency.js", "panels/transaction-tags.js", "core/split-calc.js", "panels/split-form.js", "panels/transaction-form.js",
        "panels/loan-calc.js", "panels/loan-block.js", "panels/accounts.js");
    p.run(`function isPortfolioAccount() { return false; } function isJewelleryAccount() { return false; } function isRecurringDepositAccount() { return false; } function clientLog() {}
        function openEditAccount() {}  function showTransactionsFor() {}  function openLiabilityPayment() { return Promise.resolve(); }
        function txnRowElement(t) { const n = el("div", { className: "txn-row" }); n.dataset.txnId = t.id; return n; }
        var redraws = 0; function renderAccounts() { redraws++; }
        accounts = [{ id: 1, name: "Bank", type: "BANK", active: true, balance: 50000 }];
        debtEmis = new Map([[6, 10000]]);
        transactions = [
            { id: 1, date: "2026-01-07", kind: "EXPENSE", fromAccountId: 1, toAccountId: 6, amount: 10000, note: "EMI" },
            { id: 2, date: "2026-02-07", kind: "EXPENSE", fromAccountId: 1, toAccountId: 6, amount: 10000, note: "EMI" },
            { id: 3, date: "2026-03-07", kind: "EXPENSE", fromAccountId: 1, toAccountId: 6, amount: 10000, note: "EMI" },
            { id: 4, date: "2026-03-08", kind: "EXPENSE", fromAccountId: 1, toAccountId: null, amount: 99, note: "Tea" }];
        const row = a => accountRow(Object.assign({ level: 6, parentId: null, active: true, ringFenced: false, openingBalance: 100000 }, a), new Map());`);
    return p;
}
const loan = (p, extra = "") => p.raw(`row({ id: 6, name: "Car loan", type: "DEBT", balance: 70000 ${extra} })`);

test("the figures: what the loan was, what was paid in, what is left, the percent and how many EMIs remain", () => {
    const p = page();
    const r = p.run(`loanProgress({ id: 6, openingBalance: 100000, balance: 70000 }, transactions, 10000)`);
    assert.deepEqual(r, { total: 100000, repaid: 30000, left: 70000, percent: 30, payments: 3, paymentsLeft: 7, finished: false });
});

test("borrowing more on the loan raises the total, a paid-off loan is finished, and a loan with no fixed EMI has no count of EMIs", () => {
    const p = page();
    p.run(`transactions.push({ id: 9, date: "2026-04-01", kind: "EXPENSE", fromAccountId: 6, toAccountId: null, amount: 20000 })`);
    const more = p.run(`loanProgress({ id: 6, openingBalance: 100000, balance: 90000 }, transactions, 0)`);
    assert.equal(more.total, 120000);
    assert.equal(more.paymentsLeft, null);
    const done = p.run(`loanProgress({ id: 6, openingBalance: 30000, balance: 0 }, transactions.filter(t => t.id < 4), 10000)`);
    assert.deepEqual([done.percent, done.finished, done.paymentsLeft], [100, true, null]);
    assert.equal(p.run(`loanProgress({ id: 7, openingBalance: 0, balance: 0 }, [], 0).percent`), 0, "nothing borrowed, nothing to show");
});

test("a loan row shows its repayment line and a bar of that width", () => {
    const p = page();
    const node = loan(p);
    assert.ok(node.allText.includes("30.0% repaid · ₹30,000 of ₹1,00,000 paid · ₹70,000 left · about 7 EMIs to go"), node.allText);
    const bar = node.find(n => n.className === "usage-bar")[0];
    assert.equal(bar.attrs.role, "progressbar");
    assert.equal(bar.attrs["aria-valuenow"], "30");
    assert.equal(node.find(n => String(n.className).startsWith("usage-fill"))[0].style.width, "30%");
});

test("a loan that is paid off says so and its bar is full and green", () => {
    const p = page();
    p.run(`transactions = transactions.filter(t => t.id < 4).map(t => ({ ...t, amount: 33333.34 }))`);
    const node = loan(p, ", openingBalance: 100000.02, balance: 0");
    assert.ok(node.allText.includes("Fully repaid"), node.allText);
    const fill = node.find(n => String(n.className).startsWith("usage-fill"))[0];
    assert.equal(fill.style.width, "100%");
    assert.ok(String(fill.className).includes("low"), "green");
});

test("a loan with nothing left shows Closed and no Pay button; one still owing keeps Pay, and a card at zero is not closed", () => {
    const p = page();
    p.run(`transactions = transactions.filter(t => t.id < 4).map(t => ({ ...t, amount: 33333.34 }))`);
    const done = loan(p, ", openingBalance: 100000.02, balance: 0");
    assert.ok(done.allText.includes("Closed") && done.allText.includes("· closed"), done.allText);
    assert.ok(!done.find(n => n.tag === "button").some(b => b.textContent === "Pay"), "nothing more to pay");
    assert.ok(done.find(n => String(n.className).includes("account-row closed")).length > 0, "marked closed");
    const open = loan(p);
    assert.ok(open.find(n => n.tag === "button").some(b => b.textContent === "Pay"));
    assert.ok(!open.allText.includes("Closed"));
    p.run(`function cardCyclesBlock() { return null; }`);
    const card = p.raw(`row({ id: 8, name: "Card", type: "CREDIT_CARD", balance: 0, openingBalance: 0 })`);
    assert.ok(!card.allText.includes("Closed"));
});

test("the transactions fold starts closed, shows how many there are, and then lists the loan's own rows, newest first", () => {
    const p = page();
    const fold = n => n.find(x => x.tag === "button" && String(x.textContent).startsWith("Transactions"))[0];
    const closed = loan(p);
    assert.equal(fold(closed).textContent, "Transactions (3) ▸");
    assert.equal(fold(closed).attrs["aria-expanded"], "false");
    assert.equal(closed.find(n => n.className === "txn-row").length, 0);

    fold(closed).fire("click");
    assert.equal(p.run("redraws"), 1, "the Accounts view is drawn again");
    const open = loan(p);
    assert.equal(fold(open).textContent, "Transactions (3) ▾");
    assert.deepEqual(open.find(n => n.className === "txn-row").map(n => n.dataset.txnId), [3, 2, 1], "only this loan's rows, newest first");
    assert.ok(open.allText.includes("Showing 1–3 of 3"));
});

test("a loan with nothing recorded points to the Pay button", () => {
    const p = page();
    p.run(`transactions = []; loanBlocksOpen.add(6)`);
    assert.ok(loan(p).allText.includes("Nothing recorded on this loan yet. Use Pay to record a payment."));
});

test("a long list is paged like the Transactions list", () => {
    const p = page();
    p.run(`transactions = Array.from({ length: 23 }, (_, i) => ({ id: i + 1, date: "2026-01-" + String(i + 1).padStart(2, "0"), kind: "EXPENSE", fromAccountId: 1, toAccountId: 6, amount: 100 }));
        loanBlocksOpen.add(6)`);
    const first = loan(p);
    assert.equal(first.find(n => n.className === "txn-row").length, 10);
    assert.ok(first.allText.includes("Showing 1–10 of 23") && first.allText.includes("Page 1 of 3"), first.allText);
    first.find(n => n.tag === "button" && n.textContent === "Next ›")[0].fire("click");
    const second = loan(p);
    assert.ok(second.allText.includes("Showing 11–20 of 23"), second.allText);
    assert.equal(second.find(n => n.tag === "button" && n.textContent === "Next ›")[0].disabled, false);
});
