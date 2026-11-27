/* Credit card cycles. A card's transactions grouped by its statement cycles, worked out from the statement day and the bill due day. No
   page code in here: every function takes its data as arguments, so it is tested on its own.

   A cycle runs from one statement date up to, but not including, the next one: a transaction dated on the statement day belongs to the
   NEXT cycle, the same rule as cardStatementAmount() in transactions.js and the Coming up bill. A short month uses its last day. The
   amount of a closed cycle's statement is what was owed at the start of the statement day: opening balance, plus spending and fees,
   less payments, before that day. The bill is due on the first due day after the statement day. A card with no statement day is grouped by
   calendar month (a statement day of 1). */

const pad = n => String(n).padStart(2, "0");
const daysIn = (year, month0) => new Date(year, month0 + 1, 0).getDate();

/* yyyy-mm-dd of `day` in a month (month0 counts from 0, and may run past 11 or below 0), a short month using its last day. */
function cycleDate(year, month0, day) {
    const d = new Date(year, month0, 1);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(Math.min(day, daysIn(d.getFullYear(), d.getMonth())))}`;
}

/* The first date with `dueDay` that is after the statement date. */
function dueDateAfter(statementIso, dueDay) {
    const [y, m] = statementIso.split("-").map(Number);
    const sameMonth = cycleDate(y, m - 1, dueDay);
    return sameMonth > statementIso ? sameMonth : cycleDate(y, m, dueDay);
}

const outOf = (t, card) => t.fromAccountId === card.id ? Number(t.amount) + Number(t.fee || 0) : 0;
const into = (t, card) => t.toAccountId === card.id ? Number(t.amount) : 0;

/* The cycles of a card, newest first. The first is the one running today (open); the rest are closed, back to the cycle of the card's first
   transaction. Each: { start, end (the statement date, not part of it), open, dueDate, carriedIn, spent, paid, owed (at the end: the
   statement amount once it is closed), bill, purchases, payments }. bill, for a closed cycle, is { amount, paid, left, state } where state
   is "none" (nothing to pay), "paid", "part", "due" or "overdue": the payments dated from the statement day to the due day count. */
export function cardCycles({ card, statementDay, dueDay, transactions, today }) {
    const day = Number(statementDay) > 0 ? Number(statementDay) : 1;
    const mine = transactions.filter(t => t.fromAccountId === card.id || t.toAccountId === card.id)
        .sort((a, b) => a.date.localeCompare(b.date) || a.id - b.id);
    const [ty, tm] = today.split("-").map(Number);
    const thisMonth = cycleDate(ty, tm - 1, day);
    let start = thisMonth <= today ? thisMonth : cycleDate(ty, tm - 2, day);
    let end = thisMonth <= today ? cycleDate(ty, tm, day) : thisMonth;
    const earliest = mine.length ? mine[0].date : start;
    const opening = Number(card.openingBalance) || 0;
    const owedBefore = iso => mine.reduce((owed, t) => t.date < iso ? owed + outOf(t, card) - into(t, card) : owed, opening);

    const cycles = [];
    for (let guard = 0; guard < 600; guard++) {
        const inside = mine.filter(t => t.date >= start && t.date < end);
        const purchases = inside.filter(t => outOf(t, card) > 0), payments = inside.filter(t => into(t, card) > 0);
        const carriedIn = owedBefore(start);
        const spent = purchases.reduce((s, t) => s + outOf(t, card), 0), paid = payments.reduce((s, t) => s + into(t, card), 0);
        const open = cycles.length === 0;
        cycles.push({ start, end, open, dueDate: dueDay ? dueDateAfter(end, Number(dueDay)) : null,
            carriedIn, spent, paid, owed: carriedIn + spent - paid, purchases, payments, bill: null });
        if (start <= earliest) break;
        const [y, m] = start.split("-").map(Number);
        end = start;
        start = cycleDate(y, m - 2, day);
    }
    for (const cycle of cycles) {
        if (cycle.open) continue;
        const amount = cycle.owed;
        const upTo = cycle.dueDate || "9999-12-31";
        const settled = mine.reduce((s, t) => t.date >= cycle.end && t.date <= upTo ? s + into(t, card) : s, 0);
        const left = Math.max(amount - settled, 0);
        const state = amount <= 0 ? "none" : settled >= amount ? "paid" : cycle.dueDate && today > cycle.dueDate ? "overdue" : settled > 0 ? "part" : "due";
        cycle.bill = { amount, paid: settled, left, state };
    }
    return { grouped: Number(statementDay) > 0, cycles };
}
