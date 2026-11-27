/* The Categories page (menu > Categories > Edit / Add categories): add, rename, deactivate/activate and delete categories and sub
   categories, income and expense apart, as a list: a row per category with its sub categories indented under it, the buttons on the right of each row, and how many transactions use each.
   The server enforces the rules (two levels only, no delete while a category has sub categories or transactions) and its message is
   shown as is. */

import { clientLog, describeError } from "../core/client-log.js";
import { refreshData } from "../core/refresh.js";
import { allCategories, transactions } from "../core/state.js";
import { $, api, el } from "../core/util.js";

const catView = { kind: "EXPENSE", editing: null, addingSubFor: null };

/* Called when the page is shown: it starts from a clean list (nothing being renamed). */
export function showCategories() {
    catView.editing = null;
    catView.addingSubFor = null;
    $("categoriesError").textContent = "";
    renderCategories();
}

/* Runs a change, then reloads everything so the dashboard and the forms see it too. */
async function catAction(change) {
    $("categoriesError").textContent = "";
    try {
        await change();
        catView.editing = null;
        catView.addingSubFor = null;
        await refreshData();
    } catch (e) {
        $("categoriesError").textContent = e.message;
        clientLog("WARN", "categories.action.failed", describeError(e));
    }
    renderCategories();
}

/* How many transactions use each category (by id). */
function useCounts() {
    const counts = new Map();
    for (const t of transactions) if (t.categoryId != null) counts.set(t.categoryId, (counts.get(t.categoryId) || 0) + 1);
    return counts;
}

export function renderCategories() {
    const tabs = $("catTabs");
    tabs.innerHTML = "";
    for (const [kind, label] of [["EXPENSE", "Expense"], ["INCOME", "Income"]]) {
        const count = allCategories.filter(c => c.kind === kind && c.parentId == null).length;
        const tab = el("button", { className: "chip" + (catView.kind === kind ? " active" : ""), textContent: `${label} (${count})` });
        tab.addEventListener("click", () => {
            catView.kind = kind;
            catView.editing = null;
            catView.addingSubFor = null;
            $("categoriesError").textContent = "";
            renderCategories();
        });
        tabs.append(tab);
    }

    const byName = (a, b) => a.name.localeCompare(b.name);
    const counts = useCounts();
    const tops = allCategories.filter(c => c.kind === catView.kind && c.parentId == null).sort(byName);
    const list = $("catList");
    list.innerHTML = "";
    if (tops.length === 0) {
        list.append(el("div", { className: "cat-empty", textContent: "No categories yet. Add one above." }));
    }
    for (const top of tops) {
        const subs = allCategories.filter(c => c.parentId === top.id).sort(byName);
        const used = (counts.get(top.id) || 0) + subs.reduce((sum, c) => sum + (counts.get(c.id) || 0), 0);
        const card = el("div", { className: "cat-card" });   // groups a category with its sub categories; it draws no box
        card.append(categoryRow(top, false, used, subs.length));
        for (const sub of subs) card.append(categoryRow(sub, true, counts.get(sub.id) || 0, 0));
        if (catView.addingSubFor === top.id) card.append(subCategoryForm(top));
        list.append(card);
    }
    $("catNewName").placeholder = `New ${catView.kind.toLowerCase()} category name`;
}

/* The name with how many transactions use it right after it, like "Bank expenses (1)". */
function nameWithCount(name, used, subs) {
    const title = "Transactions that use it" + (subs ? `; ${subs} sub ${subs === 1 ? "category" : "categories"}` : "");
    return el("span", { className: "cat-name", title, textContent: `${name} (${used})` });
}

/* used is how many transactions use it (a category: with its sub categories' too), subs how many sub categories it has. */
function categoryRow(c, isChild, used, subs) {
    const row = el("div", { className: "cat-row " + (isChild ? "child" : "top") + (c.active ? "" : " inactive") });

    if (c.systemKey) {   // the fixed ones cannot be renamed, deactivated or deleted
        row.append(nameWithCount(c.name, used, 0),
            el("span", { className: "cat-tag", textContent: c.systemKey === "LIABILITY_PAYMENT" ? "🔒 Fixed · made with the Pay button on a loan or card"
                : c.systemKey.startsWith("BALANCE_ADJUSTMENT") ? "🔒 Fixed · made with an account's Reset balance button"
                : "🔒 Fixed · people are managed under Receivables" }));
        return row;
    }

    if (catView.editing === c.id) {
        const input = el("input", { type: "text", value: c.name, maxLength: 100, autocomplete: "off" });
        const save = el("button", { className: "btn btn-primary", textContent: "Save" });
        const cancel = el("button", { className: "btn btn-secondary", textContent: "Cancel" });
        const submit = () => catAction(() => api(`/api/categories/${c.id}`,
            { method: "PUT", body: { name: input.value.trim(), kind: c.kind, parentId: c.parentId } }));
        save.addEventListener("click", submit);
        cancel.addEventListener("click", () => { catView.editing = null; renderCategories(); });
        input.addEventListener("keydown", e => {
            if (e.key === "Enter") submit();
            if (e.key === "Escape") { catView.editing = null; renderCategories(); }
        });
        row.append(input, save, cancel);
        setTimeout(() => input.focus(), 0);
        return row;
    }

    row.append(nameWithCount(c.name, used, subs));
    if (!c.active) row.append(el("span", { className: "cat-tag", textContent: "inactive" }));

    const actions = el("div", { className: "cat-actions" });
    const button = (label, onClick, extra = "") => {
        const b = el("button", { className: "cat-btn " + extra, textContent: label });
        b.addEventListener("click", onClick);
        actions.append(b);
    };
    if (!isChild) {
        button("+ Sub category", () => { catView.addingSubFor = c.id; catView.editing = null; renderCategories(); });
    }
    button("Rename", () => { catView.editing = c.id; catView.addingSubFor = null; renderCategories(); });
    button(c.active ? "Deactivate" : "Activate",
        () => catAction(() => api(`/api/categories/${c.id}/${c.active ? "deactivate" : "activate"}`, { method: "POST" })));
    button("Delete", () => {
        if (confirm(`Delete "${c.name}"? This cannot be undone.`)) {
            catAction(() => api(`/api/categories/${c.id}`, { method: "DELETE" }));
        }
    }, "danger");
    row.append(actions);
    return row;
}

function subCategoryForm(parent) {
    const input = el("input", { type: "text", placeholder: `New sub category under ${parent.name}`, maxLength: 100, autocomplete: "off" });
    const add = el("button", { className: "btn btn-primary", textContent: "Add" });
    const cancel = el("button", { className: "btn btn-secondary", textContent: "Cancel" });
    const submit = () => {
        const name = input.value.trim();
        if (name) catAction(() => api("/api/categories", { method: "POST", body: { name, kind: parent.kind, parentId: parent.id } }));
    };
    add.addEventListener("click", submit);
    cancel.addEventListener("click", () => { catView.addingSubFor = null; renderCategories(); });
    input.addEventListener("keydown", e => {
        if (e.key === "Enter") submit();
        if (e.key === "Escape") { catView.addingSubFor = null; renderCategories(); }
    });
    setTimeout(() => input.focus(), 0);
    return el("div", { className: "cat-row child" }, input, add, cancel);
}

function addTopCategory() {
    const name = $("catNewName").value.trim();
    if (!name) return;
    catAction(async () => {
        await api("/api/categories", { method: "POST", body: { name, kind: catView.kind, parentId: null } });
        $("catNewName").value = "";
    });
}

export function initCategories() {
    $("catAddButton").addEventListener("click", addTopCategory);
    $("catNewName").addEventListener("keydown", e => { if (e.key === "Enter") addTopCategory(); });
}
