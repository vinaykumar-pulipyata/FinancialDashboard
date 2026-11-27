/* Budgets: how much of each monthly limit has been used. No page code in here; every function takes its data as arguments, so it is
   tested on its own. The limits are kept by the server (/api/budgets), the spending is worked out here from the transactions with
   the same rules as Stats and Spending Review (expenses, Loans Given left out). A limit on a main category covers its sub
   categories; a limit on a sub category covers only that sub category's own spending, so a main category and its sub category can
   both have one; the limit with no category covers all spending. */

import { insMonthOf, insSpending } from "./insights-calc.js";
import { rupees } from "../core/util.js";

const BUDGET_WARN_PCT = 80;   // from this share used a budget is "close"; above 100% it is "over"

/* Where a budget stands: "ok", "close" (80% or more used) or "over" (more than all of it). */
function budgetState(spent, limit) {
    if (spent > limit) return "over";
    return limit > 0 && spent / limit * 100 >= BUDGET_WARN_PCT ? "close" : "ok";
}

function budgetRow(budget, name, spent, shortName) {
    const limit = Number(budget.amount);
    return {
        id: budget.id, categoryId: budget.categoryId, name, shortName: shortName || name, limit, spent,
        left: limit - spent, pct: limit > 0 ? Math.round(spent / limit * 100) : 0, state: budgetState(spent, limit),
    };
}

/* "Food" for a main category, "Food › Dining out" for a sub category. */
export function budgetName(category, categories) {
    const parent = category.parentId != null && categories.find(c => c.id === category.parentId);
    return parent ? `${parent.name} › ${category.name}` : category.name;
}

const STATE_ORDER = { over: 0, close: 1, ok: 2 };

/* month is "yyyy-mm"; today is "yyyy-mm-dd" (the month still running counts only up to today). Returns { overall, rows }: overall is the
   limit on all spending or null, rows are the category limits, the ones needing attention first. */
export function budgetStatus(budgets, transactions, categories, month, today) {
    const lastDay = month === insMonthOf(today) ? Number(today.slice(8)) : 31;
    const spentOn = new Map();    // category id -> what was spent on it itself
    let total = 0;
    for (const t of insSpending(transactions, categories)) {
        if (insMonthOf(t.date) !== month || Number(t.date.slice(8)) > lastDay) continue;
        total += t.amount;
        spentOn.set(t.categoryId, (spentOn.get(t.categoryId) || 0) + t.amount);
    }
    // a category's own spending, plus its sub categories' when it is a main category
    const spentIn = id => [...spentOn.entries()].reduce((sum, [categoryId, amount]) =>
        sum + (categoryId === id || (categories.find(c => c.id === categoryId) || {}).parentId === id ? amount : 0), 0);
    let overall = null;
    const rows = [];
    for (const b of budgets) {
        if (b.categoryId == null) {
            overall = budgetRow(b, "All spending", total);
            continue;
        }
        const category = categories.find(c => c.id === b.categoryId);
        if (category) rows.push(budgetRow(b, budgetName(category, categories), spentIn(category.id), category.name));   // a limit whose category is gone is left out
    }
    rows.sort((a, b) => STATE_ORDER[a.state] - STATE_ORDER[b.state] || b.pct - a.pct || a.name.localeCompare(b.name));
    return { overall, rows };
}

/* The rows put together by main category: a main category's own budget (head, or null when only its sub categories have one) with the
   budgets of its sub categories (children). Needing attention is carried up (state is the worst of them), so the groups that do come
   first. A budget with no relatives is a group of its own with no children. */
export function budgetGroups(rows, categories) {
    const groups = new Map();
    for (const r of rows) {
        const category = categories.find(c => c.id === r.categoryId);
        const mainId = category.parentId != null ? category.parentId : category.id;
        if (!groups.has(mainId)) {
            const main = categories.find(c => c.id === mainId);
            groups.set(mainId, { key: mainId, name: main ? main.name : r.name, head: null, children: [], state: "ok" });
        }
        const g = groups.get(mainId);
        if (category.parentId != null) g.children.push(r); else g.head = r;
        if (STATE_ORDER[r.state] < STATE_ORDER[g.state]) g.state = r.state;
    }
    const peak = g => Math.max(g.head ? g.head.pct : 0, ...g.children.map(r => r.pct));
    return [...groups.values()].sort((a, b) => STATE_ORDER[a.state] - STATE_ORDER[b.state] || peak(b) - peak(a) || a.name.localeCompare(b.name));
}

/* The budgets of the month running that are close to or over their limit, the limit on everything first. */
export function budgetAlerts(budgets, transactions, categories, today) {
    const { overall, rows } = budgetStatus(budgets, transactions, categories, insMonthOf(today), today);
    return [overall, ...rows].filter(r => r && r.state !== "ok");
}

/* "Food: 92% used, ₹800 left" or "Food: over by ₹1,200". */
export function budgetText(row) {
    return row.state === "over"
        ? `${row.name}: over by ${rupees(-row.left)} (${rupees(row.spent)} of ${rupees(row.limit)})`
        : `${row.name}: ${row.pct}% used, ${rupees(row.left)} left (${rupees(row.spent)} of ${rupees(row.limit)})`;
}
