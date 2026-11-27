/* Reminders: a view after Notes. A summary strip (done this week, month and year, and how many were on time),
   then every ticked one-off reminder grouped by the month it was ticked in, newest first, 25 rows to a page (10, 25, 50 or 100). The newest month starts open, the
   others folded; a search or a chosen year narrows the list and opens every month. Unticking one brings it back to the
   Coming up bar. */

import { MONTH_NAMES } from "../core/constants.js";
import { userReminders } from "../core/state.js";
import { $, el, formatDate } from "../core/util.js";
import { isRepeating, removeReminder, reminderCheckbox } from "./my-reminders.js";
import { showReminderDay } from "./reminders.js";

const isoDayNo = iso => Math.round(Date.parse(iso + "T00:00:00Z") / 86400000);
const toisoDayNo = () => { const d = new Date(); return Math.round(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000); };
let rvPage = 1, rvPageSize = 25;
const opened = new Map();   // month key -> true/false once the user has opened or folded it

/* Days after its day that a reminder was ticked; 0 or less means on time (or early). */
const daysLate = r => isoDayNo(r.doneOn) - isoDayNo(r.date);

function remindersViewRow(r) {
    const title = el("button", { className: "reminder-text", type: "button", textContent: (r.occurrence ? "🔁 " : "") + r.title, title: "Show the day it was for in the Calendar" });
    title.addEventListener("click", () => showReminderDay(r.date));
    const remove = el("button", { className: "reminder-delete", type: "button", textContent: "🗑️", title: "Delete this reminder" });
    remove.setAttribute("aria-label", "Delete " + r.title);
    remove.addEventListener("click", () => removeReminder(r.id).catch(err => alert(err.message)));
    const late = daysLate(r);
    const row = el("div", { className: "reminder-row rv-row done" }, reminderCheckbox({ id: r.id, title: r.title, done: true }, r.occurrence || null), title);
    if (late > 0) row.append(el("span", { className: "rv-late", textContent: late === 1 ? "1 day late" : `${late} days late` }));
    row.append(el("span", { className: "rv-for", textContent: "was for " + formatDate(r.date) }),
        el("span", { className: "reminder-when", textContent: "done " + formatDate(r.doneOn) }));
    if (!r.occurrence) row.append(remove);   // the 🗑️ would delete the whole repeating reminder; one day of it is unticked with its box
    return row;
}

function summaryFigure(label, value, className = "") {
    return el("span", { className }, label, el("strong", { textContent: String(value) }));
}

function renderSummary(done) {
    const today = toisoDayNo(), thisMonth = new Date().toLocaleDateString("en-CA").slice(0, 7), thisYear = thisMonth.slice(0, 4);
    const box = $("rvSummary");
    box.innerHTML = "";
    const late = done.filter(r => daysLate(r) > 0).length;
    box.append(summaryFigure("This week", done.filter(r => today - isoDayNo(r.doneOn) < 7).length),
        summaryFigure("This month", done.filter(r => r.doneOn.startsWith(thisMonth)).length),
        summaryFigure("This year", done.filter(r => r.doneOn.startsWith(thisYear)).length),
        summaryFigure("On time", done.length - late, "ontime"), summaryFigure("Late", late, "late"));
}

function fillYears(done) {
    const select = $("rvYear"), chosen = select.value;
    const years = [...new Set(done.map(r => r.doneOn.slice(0, 4)))].sort().reverse();
    select.innerHTML = "";
    select.append(el("option", { value: "", textContent: "All years" }), ...years.map(y => el("option", { value: y, textContent: y })));
    select.value = years.includes(chosen) ? chosen : "";
}

export function renderRemindersView() {
    const ticked = userReminders.filter(r => isRepeating(r)).flatMap(r => Object.entries(r.completions || {})
        .map(([occurrence, doneOn]) => ({ id: r.id, title: r.title, date: occurrence, doneOn, occurrence })));
    const all = userReminders.filter(r => r.done && !isRepeating(r) && r.doneOn).concat(ticked)
        .sort((a, b) => b.doneOn.localeCompare(a.doneOn) || b.id - a.id);
    renderSummary(all);
    fillYears(all);
    const words = $("rvSearch").value.trim().toLowerCase(), year = $("rvYear").value;
    const shown = all.filter(r => (!year || r.doneOn.startsWith(year)) && (!words || r.title.toLowerCase().includes(words)));
    const list = $("rvList"), pager = $("rvPager");
    list.innerHTML = "";
    pager.innerHTML = "";
    pager.classList.add("hidden");
    if (shown.length === 0) {
        list.append(el("div", { className: "rv-empty", textContent: all.length === 0
            ? "Nothing completed yet. Tick a reminder and it appears here." : "No completed reminder matches." }));
        return;
    }
    const pages = Math.ceil(shown.length / rvPageSize);
    rvPage = Math.min(Math.max(rvPage, 1), pages);
    const start = (rvPage - 1) * rvPageSize;
    const monthTotals = new Map();   // a month's count is its whole count, even when its rows run over two pages
    for (const r of shown) monthTotals.set(r.doneOn.slice(0, 7), (monthTotals.get(r.doneOn.slice(0, 7)) || 0) + 1);
    const groups = new Map();
    for (const r of shown.slice(start, start + rvPageSize)) {
        const key = r.doneOn.slice(0, 7);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(r);
    }
    let first = true;
    for (const [key, rows] of groups) {
        const filtering = Boolean(words || year);
        const group = el("details", { className: "rv-month" },
            el("summary", {}, `${MONTH_NAMES[Number(key.slice(5)) - 1]} ${key.slice(0, 4)}`, el("span", { className: "rv-count", textContent: String(monthTotals.get(key)) })));
        const byDefault = filtering || first;
        group.open = opened.has(key) ? opened.get(key) : byDefault;
        group.addEventListener("toggle", () => { if (group.open !== byDefault || opened.has(key)) opened.set(key, group.open); });   // only a choice the user made is kept
        rows.forEach(r => group.append(remindersViewRow(r)));
        list.append(group);
        first = false;
    }
    renderRemindersViewPager(pager, shown.length, pages, start);
}

function renderRemindersViewPager(pager, total, pages, start) {
    pager.classList.remove("hidden");
    const prev = el("button", { className: "btn btn-secondary", textContent: "‹ Previous", disabled: rvPage <= 1 });
    const next = el("button", { className: "btn btn-secondary", textContent: "Next ›", disabled: rvPage >= pages });
    prev.addEventListener("click", () => { rvPage--; renderRemindersView(); });
    next.addEventListener("click", () => { rvPage++; renderRemindersView(); });
    const size = el("select", { className: "page-size" },
        ...[10, 25, 50, 100].map(n => el("option", { value: n, textContent: `${n} per page`, selected: n === rvPageSize })));
    size.addEventListener("change", () => { rvPageSize = Number(size.value); rvPage = 1; renderRemindersView(); });
    pager.append(el("span", { textContent: `Showing ${start + 1}–${Math.min(start + rvPageSize, total)} of ${total}` }),
        el("div", { className: "pager-controls" }, size, prev, el("span", { textContent: `Page ${rvPage} of ${pages}` }), next));
}

export function initRemindersView() {
    $("rvSearch").addEventListener("input", () => { rvPage = 1; renderRemindersView(); });
    $("rvYear").addEventListener("change", () => { rvPage = 1; renderRemindersView(); });
}
