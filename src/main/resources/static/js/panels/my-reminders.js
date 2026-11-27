/* Your own reminders: a title, a day and a done tick. They are listed under a day in the Calendar, marked on its cell, and
   shown in the "Coming up" bar (reminders.js) until they are ticked. A late one stays until it is ticked, moved or deleted. */

import { refreshData } from "../core/refresh.js";
import { userReminders } from "../core/state.js";
import { $, api, el, formatDate, isoDay } from "../core/util.js";
import { pad2 } from "./period-filter.js";

let editingReminder = null;   // the reminder being edited, or null for a new one

/* yyyy-mm-dd of the day after today. */
export const tomorrowIso = () => { const d = new Date(); d.setDate(d.getDate() + 1); return isoDay(d); };

/* A reminder can repeat: every week, every month or every year. Its date is the first day. A repeating reminder is an occasion,
   not a task: it is never ticked or late, and it is shown in the Calendar on each of its days from the first day on. */
const REPEAT_LABELS = { WEEKLY: "every week", MONTHLY: "every month", YEARLY: "every year" };
const repeatOf = r => r.repeat || "NONE";
export const isRepeating = r => repeatOf(r) !== "NONE";
const anchorYear = r => Number(r.date.slice(0, 4));
const anchorDay = r => Number(r.date.slice(8, 10));
const daysInMonth = (year, month0) => new Date(year, month0 + 1, 0).getDate();
const isoDayNumber = iso => Date.parse(iso + "T00:00:00Z") / 86400000;     // whole days, so a week is exactly 7

/* The day a monthly reminder falls on in a month: its day of the month, or the last day of a shorter month. */
function monthlyDay(r, year, month0) {
    return isoDay(new Date(year, month0, Math.min(anchorDay(r), daysInMonth(year, month0))));
}

/* The day a yearly reminder falls on in a year: its month and day, with 29 February moved to the 28th in a year that has no 29th. */
function yearlyDay(r, year) {
    const month0 = Number(r.date.slice(5, 7)) - 1;
    return isoDay(new Date(year, month0, Math.min(anchorDay(r), daysInMonth(year, month0))));
}

/* Does the reminder fall on this day? */
function reminderFallsOn(r, iso) {
    const repeat = repeatOf(r);
    if (repeat === "NONE") return r.date === iso;
    if (iso < r.date) return false;                                   // nothing before the first day
    if (repeat === "WEEKLY") return (isoDayNumber(iso) - isoDayNumber(r.date)) % 7 === 0;
    const year = Number(iso.slice(0, 4)), month0 = Number(iso.slice(5, 7)) - 1;
    return iso === (repeat === "MONTHLY" ? monthlyDay(r, year, month0) : yearlyDay(r, year));
}

/* The reminders of one day, in the order added. */
export const remindersOn = iso => userReminders.filter(r => reminderFallsOn(r, iso));

/* " · 35 years" on a yearly reminder in a later year than its first; "" otherwise. */
export function yearsText(r, iso) {
    const years = Number(iso.slice(0, 4)) - anchorYear(r);
    return repeatOf(r) === "YEARLY" && years > 0 ? ` · ${years} ${years === 1 ? "year" : "years"}` : "";
}

/* The next day a repeating reminder falls on, today included (its first day if that is still to come). */
export function nextOccurrence(r, now = new Date()) {
    const today = isoDay(now);
    if (r.date >= today) return r.date;
    const repeat = repeatOf(r);
    if (repeat === "WEEKLY") {
        const weeks = Math.ceil((isoDayNumber(today) - isoDayNumber(r.date)) / 7);
        const [y, m, d] = r.date.split("-").map(Number);
        return isoDay(new Date(y, m - 1, d + weeks * 7));
    }
    if (repeat === "MONTHLY") {
        const thisMonth = monthlyDay(r, now.getFullYear(), now.getMonth());
        return thisMonth >= today ? thisMonth : monthlyDay(r, now.getFullYear(), now.getMonth() + 1);
    }
    const thisYear = yearlyDay(r, now.getFullYear());
    return thisYear >= today ? thisYear : yearlyDay(r, now.getFullYear() + 1);
}

/* Has this occurrence of a repeating reminder been ticked? (r.completions: occurrence day -> the day it was ticked.) */
export const occurrenceDone = (r, iso) => Boolean(r.completions && r.completions[iso]);

/* The days of a month that have a reminder still to do, or a repeating one not yet ticked for that day: yyyy-mm-dd -> how many. */
export function reminderDaysIn(year, month0) {
    const days = new Map();
    const add = iso => days.set(iso, (days.get(iso) || 0) + 1);
    const prefix = `${year}-${pad2(month0 + 1)}`;
    for (let day = 1; day <= daysInMonth(year, month0); day++) {
        const iso = `${prefix}-${pad2(day)}`;
        for (const r of userReminders) if (reminderFallsOn(r, iso) && !r.done && !occurrenceDone(r, iso)) add(iso);      // a repeating one is never done as a whole, but each day of it can be ticked
    }
    return days;
}

async function afterReminderChange() {
    await refreshData();
}

async function setReminderDone(id, done) {
    await api(`/api/reminders/${id}/done`, { method: "PUT", body: { done } });
    await afterReminderChange();
}

async function setOccurrenceDone(id, iso, done) {
    await api(`/api/reminders/${id}/occurrences/${iso}/done`, { method: "PUT", body: { done } });
    await afterReminderChange();
}

/* Moves a reminder to another day (Tomorrow button, drag and drop). */
export async function moveReminder(id, iso) {
    const r = userReminders.find(x => x.id === id);
    if (!r || isRepeating(r) || r.date === iso) return;
    await api(`/api/reminders/${id}`, { method: "PUT", body: { date: iso, title: r.title } });
    await afterReminderChange();
}

/* The tick box shared by the day list and the bar. For one day of a repeating reminder pass that day as `occurrence` (r.done is then
   whether that day is ticked); it ticks that day only. */
export function reminderCheckbox(r, occurrence = null) {
    const box = el("input", { type: "checkbox", className: "reminder-check", checked: r.done,
        title: r.done ? "Mark as not done" : "Mark as done" });
    box.setAttribute("aria-label", (r.done ? "Not done: " : "Done: ") + r.title);
    box.addEventListener("click", e => e.stopPropagation());
    box.addEventListener("change", () => (occurrence ? setOccurrenceDone(r.id, occurrence, box.checked) : setReminderDone(r.id, box.checked)).catch(err => { alert(err.message); box.checked = !box.checked; }));
    return box;
}

/* The "Reminders" part of a chosen day in the Calendar. */
export function reminderSection(day) {
    const add = el("button", { className: "btn btn-secondary", textContent: "+ Add reminder" });
    add.addEventListener("click", () => openReminderForm(null, day));
    const box = el("div", { className: "cal-reminders" }, el("div", { className: "cal-notes-head" }, el("span", { textContent: "Reminders" }), add));
    for (const r of remindersOn(day)) {
        const text = el("button", { className: "reminder-text", type: "button", textContent: r.title + yearsText(r, day), title: "Edit this reminder" });
        text.addEventListener("click", () => openReminderForm(r));
        // A repeating reminder is never done as a whole, but this day of it can be ticked.
        const ticked = isRepeating(r) && occurrenceDone(r, day);
        const lead = isRepeating(r) ? reminderCheckbox({ id: r.id, title: r.title, done: ticked }, day) : reminderCheckbox(r);
        const row = el("div", { className: "reminder-row" + (r.done || ticked ? " done" : "") + (isRepeating(r) ? " repeating" : "") }, lead, text);
        if (isRepeating(r)) row.append(el("span", { className: "reminder-when", textContent: "🔁 " + REPEAT_LABELS[r.repeat] }));
        if (ticked) row.append(el("span", { className: "reminder-when", textContent: "done " + formatDate(r.completions[day]) }));
        if (r.done && r.doneOn) row.append(el("span", { className: "reminder-when", textContent: "done " + formatDate(r.doneOn) }));
        const remove = el("button", { className: "reminder-delete", type: "button", textContent: "🗑️", title: "Delete this reminder" });
        remove.setAttribute("aria-label", "Delete " + r.title);
        remove.addEventListener("click", () => removeReminder(r.id).catch(err => alert(err.message)));
        row.append(remove);
        if (!r.done && !isRepeating(r)) {
            row.draggable = true;
            row.title = "Drag onto another day to move it";
            row.addEventListener("dragstart", e => startReminderDrag(e, r));
        }
        box.append(row);
    }
    return box;
}

/* A reminder is dragged with its own data type, so selected text or a file dropped on a day never moves one. */
const DRAG_TYPE = "application/x-financial-reminder";

/* dragstart of anything that stands for the open one-off reminder r (a row, a chip, a Coming up item). */
export function startReminderDrag(e, r) {
    e.dataTransfer.setData(DRAG_TYPE, String(r.id));
    e.dataTransfer.effectAllowed = "move";
}

const carriesReminder = e => !!(e.dataTransfer && e.dataTransfer.types && [...e.dataTransfer.types].includes(DRAG_TYPE));

/* The reminders on a day that can be dragged to another: open and not repeating. */
export const movableRemindersOn = iso => remindersOn(iso).filter(r => !r.done && !isRepeating(r));

/* A small draggable title inside a calendar cell, so a reminder can be taken straight from its day to another. */
export function reminderChip(r) {
    const chip = el("span", { className: "cal-chip", textContent: r.title, title: r.title + "\nDrag onto another day to move it" });
    chip.draggable = true;
    chip.addEventListener("dragstart", e => { chip.classList.add("dragging"); startReminderDrag(e, r); });
    chip.addEventListener("dragend", () => chip.classList.remove("dragging"));
    return chip;
}

/* A calendar cell accepts a dragged reminder. */
export function makeReminderDropTarget(cell, iso) {
    cell.addEventListener("dragover", e => { if (!carriesReminder(e)) return; e.preventDefault(); cell.classList.add("drop"); });
    cell.addEventListener("dragleave", () => cell.classList.remove("drop"));
    cell.addEventListener("drop", e => {
        if (!carriesReminder(e)) return;
        e.preventDefault();
        cell.classList.remove("drop");
        const id = Number(e.dataTransfer.getData(DRAG_TYPE));
        if (id) moveReminder(id, iso).catch(err => alert(err.message));
    });
}

function openReminderForm(reminder, day) {
    editingReminder = reminder || null;
    $("reminderHeading").textContent = reminder ? "Edit reminder" : "New reminder";
    $("reminderDate").value = reminder ? reminder.date : (day || isoDay(new Date()));
    $("reminderRepeat").value = reminder ? repeatOf(reminder) : "NONE";
    $("reminderText").value = reminder ? reminder.title : "";
    $("reminderDelete").classList.toggle("hidden", !reminder);
    $("reminderError").textContent = "";
    $("reminderSave").disabled = false;
    $("reminderOverlay").classList.remove("hidden");
    $("reminderText").focus();
}

function closeReminderForm() {
    $("reminderOverlay").classList.add("hidden");
}

async function saveReminder() {
    const error = $("reminderError");
    if (!$("reminderDate").value) { error.textContent = "Choose the day."; return; }
    if (!$("reminderText").value.trim()) { error.textContent = "Write what to remember."; return; }
    $("reminderSave").disabled = true;
    try {
        const body = { date: $("reminderDate").value, title: $("reminderText").value, repeat: $("reminderRepeat").value };
        await api(editingReminder ? `/api/reminders/${editingReminder.id}` : "/api/reminders", { method: editingReminder ? "PUT" : "POST", body });
        closeReminderForm();
        await afterReminderChange();
    } catch (e) {
        error.textContent = e.message;
        $("reminderSave").disabled = false;
    }
}

/* Deletes a reminder after asking; true when it was deleted. */
export async function removeReminder(id) {
    if (!confirm("Delete this reminder? This cannot be undone.")) return false;
    await api(`/api/reminders/${id}`, { method: "DELETE" });
    await afterReminderChange();
    return true;
}

async function deleteReminder() {
    if (!editingReminder) return;
    $("reminderDelete").disabled = true;
    try {
        if (await removeReminder(editingReminder.id)) closeReminderForm();
    } catch (e) {
        $("reminderError").textContent = e.message;
    } finally {
        $("reminderDelete").disabled = false;
    }
}

export function initReminderForm() {
    $("reminderSave").addEventListener("click", saveReminder);
    $("reminderDelete").addEventListener("click", deleteReminder);
    $("reminderCancel").addEventListener("click", closeReminderForm);
    $("reminderClose").addEventListener("click", closeReminderForm);
    $("reminderText").addEventListener("keydown", e => { if (e.key === "Enter") saveReminder(); });
}
