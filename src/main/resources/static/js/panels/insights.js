/* Spending Review: a month with ‹ › (never past the current one, never before the first expense) and the panels for it.
   Pure helpers are in insights-calc.js; this file only draws. Panels: the biggest expense of the month and the spending observations. */

import { MONTH_NAMES } from "../core/constants.js";
import { allCategories, transactions } from "../core/state.js";
import { rupeeTransactions } from "../core/currency.js";
import { $, el, formatDate, isoDay, rupees } from "../core/util.js";
import { biggestExpense, insMonthOf, monthTransactionStats, insPreviousMonth, insSpending, spendingObservations, INS_SMALL_MAX } from "./insights-calc.js";

let insMonth = null;   // "yyyy-mm"; null means the current month

const insMonthLabel = key => `${MONTH_NAMES[Number(key.slice(5)) - 1]} ${key.slice(0, 4)}`;

function insList(title, items, empty, kind) {
    const box = el("div", { className: "ins-sub ins-sub-" + kind }, el("h4", { textContent: title }));
    if (!items.length) {
        box.append(el("p", { className: "ins-empty", textContent: empty }));
        return box;
    }
    const list = el("ul", { className: "ins-list" });
    for (const text of items) list.append(el("li", { className: "ins-item " + kind, textContent: text }));
    box.append(list);
    return box;
}

function changeText(row, running, prev) {
    const versus = running ? `the same days of ${insMonthLabel(prev)}` : insMonthLabel(prev);
    const pct = row.pct == null ? "new this month" : `${Math.abs(row.pct)}%`;
    return `${row.category}: ${rupees(row.now)} against ${rupees(row.before)} in ${versus} (${pct})`;
}

function renderObservations(spending, today) {
    const r = spendingObservations(spending, insMonth, today);
    $("insObservations").replaceChildren(
        insList("Spending increased", r.increased.map(x => changeText(x, r.running, r.previousMonth)), "No category rose noticeably.", "up"),
        insList("Spending decreased", r.decreased.map(x => changeText(x, r.running, r.previousMonth)), "No category fell noticeably.", "down"),
        insList("Insight: frequent small expenses",
            r.frequentSmall.map(s => `${s.category}: ${s.count} expenses under ${rupees(INS_SMALL_MAX)} added up to ${rupees(s.total)}`),
            "No category has many small expenses this month.", "tip"));
}

export function renderInsights() {
    const nowKey = insMonthOf(isoDay(new Date()));
    const spending = insSpending(rupeeTransactions(), allCategories);
    const first = spending.length ? spending.map(t => insMonthOf(t.date)).sort()[0] : nowKey;
    if (insMonth == null || insMonth > nowKey) insMonth = nowKey;
    if (insMonth < first) insMonth = first;

    $("insMonthLabel").textContent = insMonthLabel(insMonth);
    $("insPrev").disabled = insMonth <= first;
    $("insNext").disabled = insMonth >= nowKey;

    const stats = monthTransactionStats(transactions, insMonth);
    $("insCount").textContent = String(stats.total);
    $("insCountDetail").replaceChildren(
        ...[["Expenses", stats.expense, "out"], ["Income", stats.income, "in"], ["Transfers", stats.transfer, "move"]]
            .map(([label, n, kind]) => el("span", { className: "ins-chip " + kind, textContent: `${label} ${n}` })),
        el("div", { className: "ins-count-line", textContent: stats.total
            ? `Busiest day: ${formatDate(stats.busiestDay)} (${stats.busiestCount}) \u00b7 ${stats.activeDays} active day${stats.activeDays === 1 ? "" : "s"}`
            : "Nothing recorded in this month." }));
    renderObservations(spending, isoDay(new Date()));
    const box = $("insBiggest");
    const top = biggestExpense(spending, insMonth);
    if (!top) {
        box.replaceChildren(el("p", { className: "ins-empty", textContent: "No expenses in this month." }));
        return;
    }
    const what = top.note || top.description || top.category;
    box.replaceChildren(el("div", { className: "ins-big" },
        el("div", { className: "ins-big-amount", textContent: rupees(top.amount) }),
        el("div", { className: "ins-big-what", textContent: what === top.category ? what : `${what} · ${top.category}` }),
        el("div", { className: "ins-big-when", textContent: formatDate(top.date) })));
}

export function initInsights() {
    $("insPrev").addEventListener("click", () => { insMonth = insPreviousMonth(insMonth || insMonthOf(isoDay(new Date()))); renderInsights(); });
    $("insNext").addEventListener("click", () => {
        const [y, m] = (insMonth || insMonthOf(isoDay(new Date()))).split("-").map(Number);
        insMonth = m === 12 ? `${y + 1}-01` : `${y}-${String(m + 1).padStart(2, "0")}`;
        renderInsights();
    });
}
