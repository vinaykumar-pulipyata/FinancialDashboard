/* What currency a transaction is in, and the rates to compare them. An account is held in one currency (account.currency, INR when it
   has none); a transaction is in the currency of the account its amount belongs to: the account that took it in for an income, the
   one it left for an expense or a transfer. A transfer between two currencies also carries `toAmount`, what arrived. Rupee-only views
   (Stats, Budgets, Spending Review, the Calendar totals) use rupeeTransactions(): an income or expense in a foreign currency is
   counted in rupees at the rate of its day (the latest saved on or before it), and what cannot be converted (no rate saved for
   that currency) or is a transfer touching a foreign account is left out. An expense split with people counts at the user's own share
   (its amount less what the others owe), because the rest is owed to the user, not spent. */

import { accounts, exchangeRates, transactions } from "./state.js";

/* The currency code of an account (by id); INR for an account that is not known. */
export function accountCurrency(id) {
    const a = id == null ? null : accounts.find(a => a.id === id);
    return (a && a.currency) || "INR";
}

/* The currency of a transaction's amount and fee. */
export function txnCurrency(t) {
    return accountCurrency(t.kind === "INCOME" ? t.toAccountId : t.fromAccountId);
}

/* Whether a transaction touches an account that is not in rupees. */
function isForeignTxn(t) {
    return accountCurrency(t.fromAccountId) !== "INR" || accountCurrency(t.toAccountId) !== "INR";
}

/* An income or expense in rupees: the transaction itself when it already is, else a copy whose amount and fee are converted at the
   rate of its day; null for a transfer touching a foreign account (it has two amounts) or when no rate is saved for the currency. */
export function toRupees(t) {
    if (!isForeignTxn(t)) return t;
    if (t.kind === "TRANSFER") return null;
    const rate = rateOn(txnCurrency(t), t.date);
    if (rate == null) return null;
    const inRupees = n => Math.round(Number(n) * rate * 100) / 100;
    return { ...t, amount: inRupees(t.amount), fee: t.fee == null ? t.fee : inRupees(t.fee) };
}

/* What each person owes of a transaction, as a list of { personId, share } (empty when it is not split). */
export function splitShares(t) {
    return Object.entries((t && t.splits) || {}).map(([id, share]) => ({ personId: Number(id), share: Number(share) }));
}

/* The other people's shares added up, in rupees. */
export function sharedTotal(t) {
    return splitShares(t).reduce((sum, s) => sum + Math.round(s.share * 100), 0) / 100;
}

/* The user's own share of an expense: its amount less what others owe. A transaction that is not split is all theirs. */
export function ownAmount(t) {
    if (t.paidByBorrowerId != null && t.ownShare != null) return Number(t.ownShare);   // somebody else paid the bill: only your share is yours
    return (Math.round(Number(t.amount) * 100) - Math.round(sharedTotal(t) * 100)) / 100;
}

/* The same transaction counted at the user's own share (a copy), for Stats, Budgets and the like; any other transaction as it is. */
export function atOwnShare(t) {
    if (t.kind !== "EXPENSE" || (splitShares(t).length === 0 && t.paidByBorrowerId == null)) return t;
    return { ...t, amount: ownAmount(t) };
}

/* The transactions in rupees, foreign ones converted. Worked out once per load (a refresh brings new lists). */
let rupeesFor = { transactions: null, accounts: null, rates: null, list: [] };
export function rupeeTransactions() {
    if (rupeesFor.transactions !== transactions || rupeesFor.accounts !== accounts || rupeesFor.rates !== exchangeRates) {
        rupeesFor = { transactions, accounts, rates: exchangeRates, list: transactions.map(toRupees).filter(Boolean).map(atOwnShare) };   // an expense split with people counts at the user's own share
    }
    return rupeesFor.list;
}

/* Shows the "no exchange rate, not counted here" notes (class .foreign-note) only while an income or expense in a foreign currency
   cannot be converted. */
export function renderForeignNotes() {
    const any = transactions.some(t => t.kind !== "TRANSFER" && isForeignTxn(t) && toRupees(t) === null);
    document.querySelectorAll(".foreign-note").forEach(n => n.classList.toggle("hidden", !any));
}

/* Rupees per 1 unit of a currency on a day (yyyy-mm-dd): the latest saved rate on or before it, else the earliest; 1 for INR; null when
   there is no rate. The server does the same sums (ExchangeRateService.Table.on); this is only for pre-filling a form. */
export function rateOn(code, day) {
    if (code === "INR") return 1;
    const mine = exchangeRates.filter(r => r.currency === code).sort((a, b) => a.date.localeCompare(b.date));
    if (mine.length === 0) return null;
    let found = mine[0];
    for (const r of mine) {
        if (r.date > day) break;
        found = r;
    }
    return Number(found.rate);
}
