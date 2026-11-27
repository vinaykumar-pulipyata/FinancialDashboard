/* Shared month and spending helpers (used by Budgets). The Spending Review view itself is a blank page.

   What counts as spending is the same as in Stats: expenses, without Loans Given (lending), sub categories rolled into their parent. */

import { NOT_SPENDING_KEYS } from "../core/constants.js";

export const insMonthOf = iso => iso.slice(0, 7);

export function insPreviousMonth(key) {
    const [y, m] = key.split("-").map(Number);
    return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, "0")}`;
}

/* The category a transaction counts under: its top-level category's name ("Uncategorised" if it has none). */
function insCategoryName(t, categories) {
    const c = categories.find(c => c.id === t.categoryId);
    if (!c) return "Uncategorised";
    const parent = c.parentId != null && categories.find(p => p.id === c.parentId);
    return (parent || c).name;
}

/* The spending transactions, each with the name of the category it counts under. */
export function insSpending(transactions, categories) {
    return transactions
        .filter(t => t.kind === "EXPENSE" && !NOT_SPENDING_KEYS.has((categories.find(c => c.id === t.categoryId) || {}).systemKey))
        .map(t => ({ ...t, amount: Number(t.amount), category: insCategoryName(t, categories) }));
}

/* The largest expense of a month ("yyyy-mm") out of insSpending's list, or null. Ties go to the later day. */
export function biggestExpense(spending, month) {
    let top = null;
    for (const t of spending) {
        if (insMonthOf(t.date) !== month) continue;
        if (!top || t.amount > top.amount || (t.amount === top.amount && t.date > top.date)) top = t;
    }
    return top;
}

const INS_MIN_PCT = 20;          // a category is "up" or "down" when it moved by at least this many percent...
const INS_MIN_RISE = 500;        // ...and by at least this many rupees (so Rs 30 to Rs 45 is not news)
const INS_NEW_MIN = 1000;        // a category with no spending in the month before is "new" from this amount
export const INS_SMALL_MAX = 500;       // an expense under this is "small"
const INS_SMALL_COUNT = 5;       // a category with at least this many small expenses in a month is "frequent"
const INS_MAX_SMALL = 3;         // at most this many categories are listed

/* Spending observations for a month ("yyyy-mm") out of insSpending's list; today is "yyyy-mm-dd". The month still running is
   compared with the same days of the month before. Returns { running, previousMonth, increased, decreased, frequentSmall }:
   increased and decreased rows are { category, now, before, pct } (pct null when nothing was spent before), biggest change first;
   frequentSmall rows are { category, count, total }, biggest total first. */
export function spendingObservations(spending, month, today) {
    const previousMonth = insPreviousMonth(month);
    const running = month === insMonthOf(today);
    const lastDay = running ? today.slice(8) : "99";
    const now = new Map(), before = new Map(), small = new Map();
    for (const t of spending) {
        const m = insMonthOf(t.date);
        if (m === month) {
            now.set(t.category, (now.get(t.category) || 0) + t.amount);
            if (t.amount < INS_SMALL_MAX) {
                const s = small.get(t.category) || { category: t.category, count: 0, total: 0 };
                s.count++;
                s.total += t.amount;
                small.set(t.category, s);
            }
        } else if (m === previousMonth && t.date.slice(8) <= lastDay) {
            before.set(t.category, (before.get(t.category) || 0) + t.amount);
        }
    }
    const rows = [];
    for (const category of new Set([...now.keys(), ...before.keys()])) {
        const n = now.get(category) || 0, b = before.get(category) || 0;
        rows.push({ category, now: n, before: b, pct: b > 0 ? Math.round((n - b) / b * 100) : null });
    }
    const increased = rows
        .filter(r => r.now - r.before >= INS_MIN_RISE && (r.before > 0 ? r.pct >= INS_MIN_PCT : r.now >= INS_NEW_MIN))
        .sort((a, b) => (b.now - b.before) - (a.now - a.before));
    const decreased = rows
        .filter(r => r.before - r.now >= INS_MIN_RISE && r.pct <= -INS_MIN_PCT)
        .sort((a, b) => (b.before - b.now) - (a.before - a.now));
    const frequentSmall = [...small.values()].filter(s => s.count >= INS_SMALL_COUNT)
        .sort((a, b) => b.total - a.total).slice(0, INS_MAX_SMALL);
    return { running, previousMonth, increased, decreased, frequentSmall };
}

/* The counts behind the Transactions panel for a month ("yyyy-mm"): total, per kind, the day with the most (later day wins a tie)
   and the number of days that had any. */
export function monthTransactionStats(transactions, month) {
    const r = { total: 0, expense: 0, income: 0, transfer: 0, busiestDay: null, busiestCount: 0, activeDays: 0 };
    const perDay = new Map();
    for (const t of transactions) {
        if (insMonthOf(t.date) !== month) continue;
        r.total++;
        if (t.kind === "EXPENSE") r.expense++;
        else if (t.kind === "INCOME") r.income++;
        else r.transfer++;
        perDay.set(t.date, (perDay.get(t.date) || 0) + 1);
    }
    r.activeDays = perDay.size;
    for (const [day, n] of perDay) if (n > r.busiestCount || (n === r.busiestCount && day > r.busiestDay)) { r.busiestDay = day; r.busiestCount = n; }
    return r;
}
