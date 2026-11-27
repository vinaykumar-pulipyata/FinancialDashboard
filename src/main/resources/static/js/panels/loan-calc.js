/* How far a loan has been repaid. Pure: the loan account, the transactions and the monthly EMI (or none) go in, figures come out.
   A loan's balance is what is still owed: what it started with (its opening amount) plus anything borrowed on it since (money out of the
   account), less every payment into it. So the whole loan is the opening amount plus what was borrowed since, and it is repaid when the
   payments add up to that. */

/* { total, repaid, left, percent, payments, paymentsLeft, finished }.
   paymentsLeft is how many more EMIs it would take at the monthly amount, interest not counted (null for a loan with no fixed EMI). */
export function loanProgress(loan, transactions, emi) {
    const mine = transactions.filter(t => t.fromAccountId === loan.id || t.toAccountId === loan.id);
    const paid = mine.filter(t => t.toAccountId === loan.id);
    const borrowed = mine.filter(t => t.fromAccountId === loan.id).reduce((sum, t) => sum + Number(t.amount), 0);
    const repaid = paid.reduce((sum, t) => sum + Number(t.toAmount ?? t.amount), 0);
    const total = (Number(loan.openingBalance) || 0) + borrowed;
    const left = Math.max(Number(loan.balance) || 0, 0);
    const finished = total > 0 && left === 0;
    const percent = finished ? 100 : total > 0 ? Math.min(100, Math.max(0, Math.floor(repaid / total * 1000) / 10)) : 0;
    const paymentsLeft = emi > 0 && left > 0 ? Math.ceil(left / emi) : null;
    return { total, repaid, left, percent, payments: paid.length, paymentsLeft, finished };
}
