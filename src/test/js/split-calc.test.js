const test = require("node:test");
const assert = require("node:assert/strict");
const { createPage } = require("./harness");

/* The maths of expenses split with people: shares, the user's own share, and what a payment clears. */
const page = () => createPage().load("core/state.js", "core/currency.js", "core/split-calc.js");
const j = v => JSON.stringify(v);

test("an equal split shares the bill between you and the others, and the odd paise stay with you", () => {
    const p = page();
    const r = p.run(`computeShares(1000, "equal", [{ id: 7 }, { id: 8 }, { id: 9 }])`);
    assert.deepEqual(r.shares, { 7: 250, 8: 250, 9: 250 });
    assert.equal(r.mine, 250);
    const odd = p.run(`computeShares(100, "equal", [{ id: 7 }, { id: 8 }])`);   // 33.33 each, 33.34 yours
    assert.deepEqual(odd.shares, { 7: 33.33, 8: 33.33 });
    assert.equal(odd.mine, 33.34);
    assert.equal(odd.error, null);
});

test("a split by amount or by percent leaves the rest to you, and refuses figures that do not fit", () => {
    const p = page();
    const byAmount = p.run(`computeShares(900, "amount", [{ id: 7, value: "300" }, { id: 8, value: 150.5 }])`);
    assert.deepEqual(byAmount.shares, { 7: 300, 8: 150.5 });
    assert.equal(byAmount.mine, 449.5);
    const byPercent = p.run(`computeShares(590, "percent", [{ id: 7, value: 25 }, { id: 8, value: 25 }])`);
    assert.deepEqual(byPercent.shares, { 7: 147.5, 8: 147.5 });
    assert.equal(byPercent.mine, 295);
    assert.match(p.run(`computeShares(100, "amount", [{ id: 7, value: 60 }, { id: 8, value: 50 }])`).error, /more than the expense/);
    assert.match(p.run(`computeShares(100, "percent", [{ id: 7, value: 60 }, { id: 8, value: 50 }])`).error, /more than 100/);
    assert.match(p.run(`computeShares(100, "amount", [{ id: 7, value: "" }])`).error, /greater than zero/);
    assert.match(p.run(`computeShares(100, "equal", [])`).error, /at least one person/);
    assert.match(p.run(`computeShares(0, "equal", [{ id: 7 }])`).error, /amount first/);
    assert.equal(p.run(`computeShares(100, "amount", [{ id: 7, value: 100 }])`).mine, 0);   // all of it for someone else is allowed
});

test("your own share is the amount less what others owe; only an expense split with people changes", () => {
    const p = page();
    const t = { id: 1, kind: "EXPENSE", amount: "590.00", splits: { 7: "147.50", 8: 147.5 } };
    assert.equal(p.run(`sharedTotal(${j(t)})`), 295);
    assert.equal(p.run(`ownAmount(${j(t)})`), 295);
    assert.equal(p.run(`atOwnShare(${j(t)})`).amount, 295);
    const plain = { id: 2, kind: "EXPENSE", amount: 590, splits: {} };
    assert.equal(p.run(`atOwnShare(${j(plain)})`).amount, 590);
    const income = { id: 3, kind: "INCOME", amount: 590, splits: { 7: 100 } };
    assert.equal(p.run(`atOwnShare(${j(income)})`).amount, 590);
    assert.equal(p.run(`ownAmount({ id: 4, kind: "EXPENSE", amount: 100 })`), 100);
});

const txns = [
    { id: 1, date: "2026-11-01", kind: "EXPENSE", amount: 1000, note: "Dinner", splits: { 7: 500, 8: 250 } },
    { id: 2, date: "2026-11-05", kind: "EXPENSE", amount: 600, note: "Movie", splits: { 7: 300 } },
    { id: 3, date: "2026-11-02", kind: "EXPENSE", amount: 80, note: "Tea" },
    { id: 9, date: "2026-11-10", kind: "INCOME", amount: 600, note: "Settled", splits: {} },
];
const paid = [{ id: 1, borrowerId: 7, expenseTransactionId: 1, incomeTransactionId: 9, amount: 500 },
              { id: 2, borrowerId: 7, expenseTransactionId: 2, incomeTransactionId: 9, amount: 100 }];

test("a person's shared expenses come oldest first with what is settled and what is left", () => {
    const p = page();
    const rows = p.run(`personSplits(7, ${j(txns)}, ${j(paid)}).map(r => ({ id: r.txn.id, share: r.share, cleared: r.cleared, left: r.left }))`);
    assert.deepEqual(rows, [{ id: 1, share: 500, cleared: 500, left: 0 }, { id: 2, share: 300, cleared: 100, left: 200 }]);
    assert.equal(p.run(`openSplitTotal(personSplits(7, ${j(txns)}, ${j(paid)}))`), 200);
    assert.equal(p.run(`openSplitTotal(personSplits(8, ${j(txns)}, ${j(paid)}))`), 250);   // nobody paid for 8
    assert.equal(p.run(`personSplits(99, ${j(txns)}, ${j(paid)}).length`), 0);
});

test("a payment lists what it cleared, newest payment first", () => {
    const p = page();
    const payments = p.run(`personPayments(7, ${j(txns)}, ${j(paid)}).map(x => ({ id: x.incomeTransactionId, total: x.total, cleared: x.cleared.map(c => [c.expense.id, c.amount]) }))`);
    assert.deepEqual(payments, [{ id: 9, total: 600, cleared: [[1, 500], [2, 100]] }]);
});

test("the bills a person paid for you come oldest first with what you paid back, from the same settlement rows", () => {
    const p = page();
    const t = [{ id: 5, date: "2026-11-05", kind: "EXPENSE", amount: 600, paidByBorrowerId: 7, ownShare: 300 },
               { id: 4, date: "2026-11-01", kind: "EXPENSE", amount: 1200, paidByBorrowerId: 7, ownShare: 400 },
               { id: 6, date: "2026-11-02", kind: "EXPENSE", amount: 100, paidByBorrowerId: 8, ownShare: 50 }];
    const s = [{ borrowerId: 7, expenseTransactionId: 4, incomeTransactionId: 30, amount: 150 }];
    assert.deepEqual(p.run(`personPaidFor(7, ${j(t)}, ${j(s)}).map(r => [r.txn.id, r.share, r.cleared, r.left])`), [[4, 400, 150, 250], [5, 300, 0, 300]]);
    assert.equal(p.run(`openSplitTotal(personPaidFor(7, ${j(t)}, ${j(s)}))`), 550);
    assert.equal(p.run(`ownAmount(${j(t[0])})`), 300);
});

test("everything with one person is one list, newest first, and the balance says who owes whom after setting off", () => {
    const p = page();
    const t = [{ id: 1, date: "2026-11-01", kind: "EXPENSE", amount: 1000, splits: { 7: 500 } },
               { id: 2, date: "2026-11-03", kind: "EXPENSE", amount: 400, paidByBorrowerId: 7, ownShare: 200 },
               { id: 3, date: "2026-11-02", kind: "EXPENSE", amount: 90, paidByBorrowerId: 8, ownShare: 45 }];
    const rows = p.run(`personLedger(7, ${j(t)}, []).map(r => [r.txn.id, r.side, r.left])`);
    assert.deepEqual(rows, [[2, "you", 200], [1, "they", 500]]);
    const b = p.run(`ledgerBalance(personLedger(7, ${j(t)}, []))`);
    assert.deepEqual(b, { theirs: 500, yours: 200 });
    const owing = p.run(`ledgerBalance(personLedger(8, ${j(t)}, []))`);
    assert.deepEqual(owing, { theirs: 0, yours: 45 });
});

test("an offset (a settlement with no payment) lowers both sides, is counted once, and is not a payment", () => {
    const p = page();
    const t = [{ id: 1, date: "2026-11-01", kind: "EXPENSE", amount: 1000, splits: { 7: 500 } },
               { id: 2, date: "2026-11-03", kind: "EXPENSE", amount: 400, paidByBorrowerId: 7, ownShare: 200 }];
    const s = [{ borrowerId: 7, expenseTransactionId: 1, incomeTransactionId: 0, amount: 200 }, { borrowerId: 7, expenseTransactionId: 2, incomeTransactionId: 0, amount: 200 }];
    assert.equal(p.run(`personOffset(7, ${j(t)}, ${j(s)})`), 200);
    assert.deepEqual(p.run(`ledgerBalance(personLedger(7, ${j(t)}, ${j(s)}))`), { theirs: 300, yours: 0 });
    assert.deepEqual(p.run(`personPayments(7, ${j(t)}, ${j(s)})`), []);
});
