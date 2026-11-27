/* The maths of expenses split with people. Pure: every function takes what it needs as arguments, so it is tested on its own (src/test/js/split-calc.test.js).

   An expense keeps its full amount (that is what left the account). `t.splits` says what each other person owes of it, by person id
   ({ "7": 295 }); the rest is the user's own share. A payment from a person clears their oldest open shares first (or the ones chosen);
   `settlements` ({ borrowerId, expenseTransactionId, incomeTransactionId, amount }) says which. Money is worked out in paise so the shares
   always add up to the total. splitShares, sharedTotal, ownAmount and atOwnShare live in currency.js (the rupee views use them). */

import { splitShares } from "./currency.js";

export const SPLIT_EQUAL = "equal";
export const SPLIT_AMOUNT = "amount";
export const SPLIT_PERCENT = "percent";

const paise = n => Math.round(Number(n) * 100);
const rupeesOf = p => p / 100;

/* Shares for a new split. members is [{ id, value }]: value is the typed amount or percent (ignored when equal). Returns
   { shares: { id: amount }, mine, error }; error is a sentence when the figures cannot be used (shares is then empty). */
export function computeShares(total, mode, members) {
    const whole = paise(total);
    const none = error => ({ shares: {}, mine: whole / 100, error });
    if (!(whole > 0)) return none("Enter the amount first.");
    if (members.length === 0) return none("Choose at least one person to split with.");
    const shares = {};
    if (mode === SPLIT_EQUAL) {
        const each = Math.floor(whole / (members.length + 1));
        for (const m of members) shares[m.id] = rupeesOf(each);
    } else if (mode === SPLIT_PERCENT) {
        let percentSum = 0;
        for (const m of members) {
            const pct = Number(m.value);
            if (!(pct > 0)) return none("Every person needs a percent greater than zero.");
            percentSum += pct;
            shares[m.id] = rupeesOf(Math.round(whole * pct / 100));
        }
        if (percentSum > 100 + 1e-9) return none("The percents add up to more than 100.");
    } else {
        for (const m of members) {
            const amount = paise(m.value);
            if (!(amount > 0)) return none("Every person needs an amount greater than zero.");
            shares[m.id] = rupeesOf(amount);
        }
    }
    const others = Object.values(shares).reduce((sum, s) => sum + paise(s), 0);
    if (others > whole) return none("The shares add up to more than the expense.");
    return { shares, mine: rupeesOf(whole - others), error: null };
}

/* What a settlement has cleared of one person's share in one expense. */
function clearedOf(settlements, personId, expenseId) {
    return rupeesOf(settlements.filter(s => s.borrowerId === personId && s.expenseTransactionId === expenseId)
        .reduce((sum, s) => sum + paise(s.amount), 0));
}

/* A person's shared expenses, oldest first: { txn, share, cleared, left, payments } where payments are the settlements that went towards it. */
function personSplits(personId, transactions, settlements) {
    const rows = [];
    for (const t of transactions) {
        const mine = splitShares(t).find(s => s.personId === personId);
        if (!mine) continue;
        const cleared = clearedOf(settlements, personId, t.id);
        rows.push({ txn: t, share: mine.share, cleared, left: rupeesOf(paise(mine.share) - paise(cleared)),
            payments: settlements.filter(s => s.borrowerId === personId && s.expenseTransactionId === t.id) });
    }
    return rows.sort((a, b) => a.txn.date.localeCompare(b.txn.date) || a.txn.id - b.txn.id);
}

/* The expenses a person paid for the user, oldest first: { txn, share (the user's own share, what is owed them), cleared, left, payments }.
   A payment back clears them with the same settlements rows as a payment from the person clears a split. */
function personPaidFor(personId, transactions, settlements) {
    const rows = [];
    for (const t of transactions) {
        if (t.paidByBorrowerId !== personId || t.ownShare == null) continue;
        const cleared = clearedOf(settlements, personId, t.id);
        rows.push({ txn: t, share: Number(t.ownShare), cleared, left: rupeesOf(paise(t.ownShare) - paise(cleared)),
            payments: settlements.filter(s => s.borrowerId === personId && s.expenseTransactionId === t.id) });
    }
    return rows.sort((a, b) => a.txn.date.localeCompare(b.txn.date) || a.txn.id - b.txn.id);
}

/* How much of a person's shared expenses is still open. */
function openSplitTotal(rows) {
    return rupeesOf(rows.reduce((sum, r) => sum + paise(r.left), 0));
}

/* Everything between you and one person that came from sharing bills, newest first: the expenses split with them (they owe you their share,
   side "they") and the bills they paid for you (you owe them your share, side "you"), each with what is settled and what is left. */
export function personLedger(personId, transactions, settlements) {
    const rows = [
        ...personSplits(personId, transactions, settlements).map(r => ({ ...r, side: "they" })),
        ...personPaidFor(personId, transactions, settlements).map(r => ({ ...r, side: "you" })),
    ];
    return rows.sort((a, b) => b.txn.date.localeCompare(a.txn.date) || b.txn.id - a.txn.id);
}

/* What is open on each side: theirs (the shares they owe you) and yours (the bills you owe them). */
export function ledgerBalance(rows) {
    const theirs = openSplitTotal(rows.filter(r => r.side === "they" && r.left > 0));
    const yours = openSplitTotal(rows.filter(r => r.side === "you" && r.left > 0));
    return { theirs, yours };
}

/* The money payments between you and a person for shared bills, newest first: { incomeTransactionId, total, payment, cleared: [{ expense, amount }] },
   an income when they paid you and an expense when you paid them. What was only set off against each other (no payment, id 0) is not here. */
export function personPayments(personId, transactions, settlements) {
    const byPayment = new Map();
    for (const s of settlements.filter(s => s.borrowerId === personId && s.incomeTransactionId !== 0)) {
        const entry = byPayment.get(s.incomeTransactionId) || { incomeTransactionId: s.incomeTransactionId, total: 0, cleared: [] };
        entry.total = rupeesOf(paise(entry.total) + paise(s.amount));
        entry.cleared.push({ expense: transactions.find(t => t.id === s.expenseTransactionId) || null, amount: Number(s.amount) });
        byPayment.set(s.incomeTransactionId, entry);
    }
    return [...byPayment.values()].map(p => ({ ...p, payment: transactions.find(t => t.id === p.incomeTransactionId) || null }))
        .sort((a, b) => ((b.payment && b.payment.date) || "").localeCompare((a.payment && a.payment.date) || "") || b.incomeTransactionId - a.incomeTransactionId);
}

/* What has been cancelled between a person's bills and shares so far (each pairing is saved on both sides, so it is counted on the bills side). */
export function personOffset(personId, transactions, settlements) {
    const bills = new Set(transactions.filter(t => t.paidByBorrowerId === personId).map(t => t.id));
    return rupeesOf(settlements.filter(s => s.borrowerId === personId && s.incomeTransactionId === 0 && bills.has(s.expenseTransactionId))
        .reduce((sum, s) => sum + paise(s.amount), 0));
}
