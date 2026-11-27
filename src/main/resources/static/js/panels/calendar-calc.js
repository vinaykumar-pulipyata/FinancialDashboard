/* The Calendar's heatmaps, worked out from the transactions already loaded. No page code in here: every function takes its data as
   arguments and returns new values (nothing it is given is changed), so it is tested on its own.

   Expenses and income are counted apart and never netted. What counts is what Stats counts: income and expenses without transfers,
   without Loans Given / Loans Collected and without Balance Adjustments. The amounts are expected in rupees (rupeeTransactions() in
   core/currency.js converts a foreign one at the rate of its day and leaves out one with no rate). */

import { NOT_SPENDING_KEYS } from "../core/constants.js";
import { rupees2 } from "../core/util.js";

export const HEAT_WORDS = ["none", "very low", "low", "high", "very high"];   // what each level 0..4 is called, so colour is never the only sign

/* The income or expense transactions that count, as { date, kind, amount } (a new list). */
function heatSpending(transactions, categories) {
    const keyOf = new Map(categories.map(c => [c.id, c.systemKey]));
    return transactions
        .filter(t => (t.kind === "INCOME" || t.kind === "EXPENSE") && !NOT_SPENDING_KEYS.has(keyOf.get(t.categoryId)))
        .map(t => ({ date: t.date, kind: t.kind, amount: Number(t.amount) }));
}

/* A function from a day's total to its level, 0 to 4, from the month's own days: 0 for no activity, and 1 to 4 for the days that had activity,
   by a score that is half the day's rank and half its size against the month's biggest day. The rank (the share of active days below it, a
   tie counting half) keeps one huge day from making every other day look like nothing; the size keeps a small day from sharing a level with a
   big one just because both are among the busiest few (Rs 699, Rs 30,000 and Rs 60,000 are three different levels). No rupee threshold is
   written down. One active day, or days that all match, are in the middle of themselves (rank only) and read as level 3. */
function heatLevelOf(totals) {
    const active = totals.filter(v => v > 0).sort((a, b) => a - b);
    const biggest = active.length ? active[active.length - 1] : 0;
    const alike = active.length > 0 && active[0] === biggest;
    return amount => {
        if (!(amount > 0)) return 0;
        const below = active.filter(v => v < amount).length, equal = active.filter(v => v === amount).length;
        const rank = (below + equal / 2) / active.length;
        const score = alike ? rank : (rank + amount / biggest) / 2;
        return 1 + Math.min(3, Math.floor(score * 4));
    };
}

const emptySide = () => ({ total: 0, count: 0, average: 0, level: 0 });

/* Every day of a month: { iso, day, future, expense, income }, each side with total, count, average and level. A day after today is future
   (neutral, level 0, and not a quiet day). month0 counts from 0. */
export function monthHeat(transactions, categories, year, month0, todayIso) {
    const counted = heatSpending(transactions, categories);
    const length = new Date(year, month0 + 1, 0).getDate();
    const key = `${year}-${String(month0 + 1).padStart(2, "0")}-`;
    const days = [];
    for (let day = 1; day <= length; day++) {
        days.push({ iso: key + String(day).padStart(2, "0"), day, future: key + String(day).padStart(2, "0") > todayIso, expense: emptySide(), income: emptySide() });
    }
    const byIso = new Map(days.map(d => [d.iso, d]));
    for (const t of counted) {
        const d = byIso.get(t.date);
        if (!d) continue;
        const side = t.kind === "INCOME" ? d.income : d.expense;
        side.total += t.amount;
        side.count++;
    }
    for (const mode of ["expense", "income"]) {
        const levelOf = heatLevelOf(days.map(d => d[mode].total));
        for (const d of days) {
            d[mode].average = d[mode].count ? d[mode].total / d[mode].count : 0;
            d[mode].level = d.future ? 0 : levelOf(d[mode].total);
        }
    }
    return days;
}

/* The compact figures above the grid for one side ("expense" or "income"): the month's total, days with and without activity (a future day is
   neither), the highest day, and the average per active day. */
export function heatSummary(days, mode) {
    const past = days.filter(d => !d.future);
    const active = past.filter(d => d[mode].total > 0);
    const total = active.reduce((sum, d) => sum + d[mode].total, 0);
    const highest = active.reduce((best, d) => !best || d[mode].total > best[mode].total ? d : best, null);
    return {
        total, activeDays: active.length, quietDays: past.length - active.length,
        highest: highest ? { iso: highest.iso, amount: highest[mode].total } : null,
        averagePerActiveDay: active.length ? total / active.length : 0
    };
}

/* The words for a day's tooltip and accessible label: { title, lines }. */
export function heatText(day, mode, dateLabel) {
    const side = day[mode], word = mode === "income" ? "income" : "expenses";
    if (day.future) return { title: dateLabel, lines: ["Not yet"] };
    if (!side.count) return { title: dateLabel, lines: [mode === "income" ? "No income" : "No expenses"] };
    return {
        title: dateLabel,
        lines: [`Total ${word}: ${rupees2(side.total)}`, `Transactions: ${side.count}`, `Average transaction: ${rupees2(side.average)}`, `Level: ${HEAT_WORDS[side.level]}`]
    };
}
