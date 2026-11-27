/* Budgets: a view with a month (‹ ›, never past the current one), the limit on all spending and a card per category limit, each with
   a bar for how much is used. A main category's budget and its sub categories' are together in one group that folds (open at first when
   one of them needs attention). The sums are in budget-calc.js. "+ Budget" and the pencil open the form, where a limit is set,
   replaced or deleted. Limits are kept by the server; the spending comes from the transactions already loaded. */

import { allCategories, budgets } from "../core/state.js";
import { rupeeTransactions } from "../core/currency.js";
import { MONTH_NAMES } from "../core/constants.js";
import { refreshData } from "../core/refresh.js";
import { $, api, el, growsOnce, isoDay, rupees, setBarWidth } from "../core/util.js";
import { budgetAlerts, budgetGroups, budgetName, budgetStatus, budgetText } from "./budget-calc.js";
import { insMonthOf, insPreviousMonth } from "./insights-calc.js";

let bgMonth = null;        // "yyyy-mm"; null means the current month
let editingBudget = null;  // the budget in the form, or null for a new one

const monthLabel = key => `${MONTH_NAMES[Number(key.slice(5)) - 1]} ${key.slice(0, 4)}`;
const nextMonth = key => { const [y, m] = key.split("-").map(Number); return m === 12 ? `${y + 1}-01` : `${y}-${String(m + 1).padStart(2, "0")}`; };

/* A budget card. `toggle` is the fold button of a group, put before the name; `name` replaces the row's name (a sub category shows just
   its own inside its group). */
function budgetCard(row, month, { toggle = null, name = row.name, note = "" } = {}) {
    const edit = el("button", { className: "bg-edit", type: "button", textContent: "✏️", title: "Change or delete this budget" });
    edit.setAttribute("aria-label", "Change the budget for " + row.name);
    edit.addEventListener("click", () => openBudgetForm(budgets.find(b => b.id === row.id)));
    const fill = el("div", { className: "usage-fill " + (row.state === "over" ? "high" : row.state === "close" ? "mid" : "low") + (growsOnce("budget-" + row.id + month) ? " grow" : "") });
    setBarWidth(fill, "budget-" + row.id, Math.min(row.pct, 100));
    const status = row.state === "over" ? `Over by ${rupees(-row.left)}` : row.state === "close" ? `Close to the limit: ${rupees(row.left)} left` : `${rupees(row.left)} left`;
    const top = el("div", { className: "bg-top" });
    if (toggle) top.append(toggle);
    top.append(el("span", { className: "bg-name", textContent: name }),
        el("span", { className: "bg-amounts", textContent: `${rupees(row.spent)} of ${rupees(row.limit)} · ${row.pct}%` }), edit);
    return el("div", { className: "bg-card" + (row.categoryId == null ? " overall" : "") },
        top, el("div", { className: "usage-bar" }, fill),
        el("div", { className: "bg-status " + row.state, textContent: status + note }));
}

/* Whether a group of budgets is unfolded: what the user chose, else open when something in it needs attention. */
const bgOpen = new Map();   // main category id -> true / false once the user has opened or folded it
const bgIsOpen = group => bgOpen.has(group.key) ? bgOpen.get(group.key) : group.state !== "ok";

/* A main category's budget with its sub categories' budgets folded under it; one with no relatives is just a card. */
function budgetGroup(group, month) {
    if (group.children.length === 0) return budgetCard(group.head, month);
    const open = bgIsOpen(group);
    const toggle = el("button", { className: "bg-toggle", type: "button", textContent: open ? "▾" : "▸" });
    toggle.setAttribute("aria-expanded", String(open));
    toggle.setAttribute("aria-label", `${open ? "Fold" : "Unfold"} the budgets under ${group.name}`);
    const note = ` · ${group.children.length} sub ${group.children.length === 1 ? "budget" : "budgets"}`;
    let header;
    if (group.head) {
        header = budgetCard(group.head, month, { toggle, note });
    } else {
        // only its sub categories have a budget: a plain header with the name and how the worst of them stands
        const worst = group.state === "over" ? "Over a limit below" : group.state === "close" ? "Close to a limit below" : "All within their limits";
        header = el("div", { className: "bg-card bg-headless" },
            el("div", { className: "bg-top" }, toggle, el("span", { className: "bg-name", textContent: group.name }),
                el("span", { className: "bg-amounts", textContent: "no budget on the whole" })),
            el("div", { className: "bg-status " + group.state, textContent: worst + note }));
    }
    const children = el("div", { className: "bg-children" + (open ? "" : " hidden") });
    group.children.forEach(r => children.append(budgetCard(r, month, { name: r.shortName })));
    toggle.addEventListener("click", () => { bgOpen.set(group.key, !bgIsOpen(group)); renderBudgets(); });
    return el("div", { className: "bg-group" }, header, children);
}

export function renderBudgets() {
    const today = isoDay(new Date());
    const nowKey = insMonthOf(today);
    if (bgMonth == null || bgMonth > nowKey) bgMonth = nowKey;
    $("bgMonthLabel").textContent = monthLabel(bgMonth) + (bgMonth === nowKey ? " (so far)" : "");
    $("bgNext").disabled = bgMonth >= nowKey;

    const { overall, rows } = budgetStatus(budgets, rupeeTransactions(), allCategories, bgMonth, today);
    const body = $("bgBody");
    body.replaceChildren();
    if (!overall && rows.length === 0) {
        body.append(el("p", { className: "bg-empty", textContent: "No budgets yet. Press + Budget to set a monthly limit on all your spending, or on a category such as Food, Travel or Shopping." }));
    } else {
        if (overall) body.append(budgetCard(overall, bgMonth));
        const list = el("div", { className: "bg-list" });
        budgetGroups(rows, allCategories).forEach(g => list.append(budgetGroup(g, bgMonth)));
        body.append(list);
    }
    // a mark on the tab while a budget of the month running needs attention
    const alerts = budgetAlerts(budgets, rupeeTransactions(), allCategories, today);
    const tab = document.querySelector('.ws-tab[data-view="budgets"]');
    if (tab) {
        tab.classList.toggle("has-alert", alerts.length > 0);
        tab.title = alerts.length ? alerts.map(budgetText).join("\n") : "";
    }
}

/* The expense categories a budget can go on (not the fixed ones), each main category followed by its sub categories, for the form's dropdown. */
function fillCategories(selected) {
    const select = $("budgetCategory");
    select.replaceChildren(el("option", { value: "", textContent: "All spending" }));
    const usable = allCategories.filter(c => c.kind === "EXPENSE" && !c.systemKey && c.active);
    for (const main of usable.filter(c => c.parentId == null)) {
        select.append(el("option", { value: String(main.id), textContent: main.name }));
        for (const sub of usable.filter(c => c.parentId === main.id)) {
            select.append(el("option", { value: String(sub.id), textContent: budgetName(sub, allCategories) }));
        }
    }
    select.value = selected == null ? "" : String(selected);
}

function openBudgetForm(budget) {
    editingBudget = budget || null;
    $("budgetHeading").textContent = budget ? "Change budget" : "New budget";
    fillCategories(budget ? budget.categoryId : null);
    $("budgetCategory").disabled = !!budget;   // a budget stays on its category; to move it, delete it and add another
    $("budgetAmount").value = budget ? budget.amount : "";
    $("budgetDelete").classList.toggle("hidden", !budget);
    $("budgetError").textContent = "";
    $("budgetSave").disabled = false;
    $("budgetOverlay").classList.remove("hidden");
    $("budgetAmount").focus();
}

function closeBudgetForm() {
    $("budgetOverlay").classList.add("hidden");
}

async function saveBudget() {
    const error = $("budgetError");
    const amount = Number($("budgetAmount").value);
    if (!(amount > 0)) { error.textContent = "Enter a monthly limit above zero."; return; }
    $("budgetSave").disabled = true;
    try {
        const categoryId = $("budgetCategory").value === "" ? null : Number($("budgetCategory").value);
        await api("/api/budgets", { method: "PUT", body: { categoryId, amount } });
        closeBudgetForm();
        await refreshData();
    } catch (e) {
        error.textContent = e.message;
        $("budgetSave").disabled = false;
    }
}

async function deleteBudget() {
    if (!editingBudget || !confirm("Delete this budget? This cannot be undone.")) return;
    $("budgetDelete").disabled = true;
    try {
        await api(`/api/budgets/${editingBudget.id}`, { method: "DELETE" });
        closeBudgetForm();
        await refreshData();
    } catch (e) {
        $("budgetError").textContent = e.message;
    } finally {
        $("budgetDelete").disabled = false;
    }
}

export function initBudgets() {
    $("bgPrev").addEventListener("click", () => { bgMonth = insPreviousMonth(bgMonth || insMonthOf(isoDay(new Date()))); renderBudgets(); });
    $("bgNext").addEventListener("click", () => { bgMonth = nextMonth(bgMonth || insMonthOf(isoDay(new Date()))); renderBudgets(); });
    $("bgAdd").addEventListener("click", () => openBudgetForm(null));
    $("budgetSave").addEventListener("click", saveBudget);
    $("budgetDelete").addEventListener("click", deleteBudget);
    $("budgetCancel").addEventListener("click", closeBudgetForm);
    $("budgetClose").addEventListener("click", closeBudgetForm);
    $("budgetAmount").addEventListener("keydown", e => { if (e.key === "Enter") saveBudget(); });
}
