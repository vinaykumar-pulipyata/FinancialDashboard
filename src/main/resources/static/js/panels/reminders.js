/* "Coming up": what is due within the next week, or already late, at the top of the dashboard, in up to three panels:
   Bills (credit card bills and Ledger payments), Reminders (your own, with a tick box) and Recurring (your repeating ones),
   plus Budgets (a budget of this month that is 80% used or over, opening the Budgets view) and Done recently: the reminders you ticked in the last week (a day of a repeating reminder too), newest first, still with their tick box so one ticked by
   mistake can be unticked and comes back.
   A card is listed only when it has a due day and owes something; a Ledger bucket when its next payment date is near. A
   panel with nothing in it is left out, and the whole section when all are empty. Clicking an item opens that day in the
   Calendar. */

import { rupeeTransactions } from "../core/currency.js";
import { accounts, allCategories, budgets, cardDueDays, cardStatementDays, ledger, transactions, userReminders } from "../core/state.js";
import { $, el, isoDay, riseOnce, rupees } from "../core/util.js";
import { budgetAlerts, budgetText } from "./budget-calc.js";
import { renderCalendar, setCalMonth, setCalSelected } from "./calendar.js";
import { cardStatementAmount } from "./transactions.js";
import { isRepeating, moveReminder, nextOccurrence, occurrenceDone, reminderCheckbox, startReminderDrag, tomorrowIso, yearsText } from "./my-reminders.js";
import { showWorkspaceView, wsView } from "./workspace.js";

const REMIND_DAYS = 7;        // how far ahead to look
const DONE_DAYS = 7;          // how long a ticked reminder stays in "Done recently"
const DONE_SHOWN = 5;         // how many of them are listed; the rest are found in the Calendar
const REMIND_LATE_DAYS = 30;  // how long a Ledger bill stays on the list after its date has passed without being paid

const dayNumber = d => Math.round(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000);

/* The next date a bill due on `dueDay` falls on, counting today. A month that is too short uses its last day. */
function nextDueDate(dueDay, from = new Date()) {
    const inMonth = (year, month) => new Date(year, month, Math.min(dueDay, new Date(year, month + 1, 0).getDate()));
    const today = new Date(from.getFullYear(), from.getMonth(), from.getDate());
    const thisMonth = inMonth(from.getFullYear(), from.getMonth());
    return thisMonth >= today ? thisMonth : inMonth(from.getFullYear(), from.getMonth() + 1);
}

const dayIn = (day, year, month) => new Date(year, month, Math.min(day, new Date(year, month + 1, 0).getDate()));   // a short month uses its last day

/* The latest statement date strictly before `before`: the statement that a bill falling due on `before` is for. */
function statementBefore(statementDay, before) {
    const sameMonth = dayIn(statementDay, before.getFullYear(), before.getMonth());
    return sameMonth < before ? sameMonth : dayIn(statementDay, before.getFullYear(), before.getMonth() - 1);
}

/* What is really due on a card's next due date, once the card has a statement day. The statement date and the due date are two
   different days: the bill is for what was owed when the statement was generated, less what has been paid since, and purchases made
   after the statement are not due yet. Returns { amount, statement } (the statement date as yyyy-mm-dd), or null when that
   statement has not been generated yet (nothing is due for it). */
export function statementDue(card, statementDay, dueDate, now = new Date()) {
    const statement = statementBefore(statementDay, dueDate);
    if (dayNumber(statement) > dayNumber(now)) return null;
    const statementIso = isoDay(statement), todayIso = isoDay(now);
    let owed = Number(card.openingBalance) || 0, paid = 0;
    for (const t of transactions) {
        if (t.fromAccountId === card.id && t.date < statementIso) owed += Number(t.amount) + Number(t.fee || 0);   // spending on the card, fees included
        if (t.toAccountId === card.id) {                                                                            // a payment into the card
            if (t.date < statementIso) owed -= Number(t.amount);
            else if (t.date <= todayIso) paid += Number(t.amount);
        }
    }
    return { amount: Math.min(Math.max(owed - paid, 0), Number(card.balance)), statement: statementIso };
}

/* Everything due soon, soonest first: { kind, name, date, days, amount } (a card with a statement day also has `statement`). days is negative for a Ledger bill that is late. */
function upcomingDues(now = new Date()) {
    const today = dayNumber(now);
    const items = [];
    for (const a of accounts) {
        const dueDay = cardDueDays.get(a.id);
        if (a.type !== "CREDIT_CARD" || !a.active || !dueDay || !(Number(a.balance) > 0)) continue;
        const date = nextDueDate(dueDay, now);
        const statementDay = cardStatementDays.get(a.id);
        let amount = Number(a.balance), statement = null;
        if (statementDay) {          // with a statement day the bill is that statement's amount, not everything owed now
            const due = statementDue(a, statementDay, date, now);
            if (!due || due.amount <= 0) continue;
            amount = due.amount;
            statement = due.statement;
        }
        items.push({ kind: "card", name: a.name, date: isoDay(date), days: dayNumber(date) - today, amount, statement });
    }
    for (const a of accounts) {        // the day the bank generates the bill, every month; it is a reminder, never late
        const statementDay = cardStatementDays.get(a.id);
        if (a.type !== "CREDIT_CARD" || !a.active || !statementDay) continue;
        const date = nextDueDate(statementDay, now);
        const days = dayNumber(date) - today;
        items.push({ kind: "statement", name: a.name, date: isoDay(date), days, amount: days === 0 ? cardStatementAmount(a, isoDay(date)) : Number(a.balance) });
    }
    if (ledger.configured) {
        for (const b of ledger.buckets) {
            if (b.buffer || !b.nextPaymentDate) continue;
            items.push({ kind: "ledger", name: b.name, date: b.nextPaymentDate,
                days: dayNumber(new Date(b.nextPaymentDate + "T00:00:00")) - today,
                amount: b.paymentAmount == null ? null : Number(b.paymentAmount) });
        }
    }
    for (const r of userReminders) {
        if (isRepeating(r)) {              // never late: it shows from a week before until its day, and once that day is ticked the next one takes over
            let date = nextOccurrence(r, now);
            if (occurrenceDone(r, date)) {
                const [y, m, d] = date.split("-").map(Number);
                date = nextOccurrence(r, new Date(y, m - 1, d + 1));
            }
            items.push({ kind: "reminder", repeating: true, id: r.id, name: r.title + yearsText(r, date), date, days: dayNumber(new Date(date + "T00:00:00")) - today, amount: null });
        } else if (!r.done) {
            items.push({ kind: "reminder", id: r.id, name: r.title, date: r.date, days: dayNumber(new Date(r.date + "T00:00:00")) - today, amount: null });
        }
    }
    return items.filter(i => i.days <= REMIND_DAYS && (i.kind === "reminder" || i.days >= -REMIND_LATE_DAYS))   // your own reminders stay until dealt with
        .sort((a, b) => a.days - b.days || a.name.localeCompare(b.name));
}

function whenText(days) {
    if (days < 0) return `${-days} ${-days === 1 ? "day" : "days"} late`;
    return days === 0 ? "today" : days === 1 ? "tomorrow" : `in ${days} days`;
}

const shortDay = iso => new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { day: "numeric", month: "short" });

function reminderText(item) {
    const day = new Date(item.date + "T00:00:00").toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short" });
    if (item.kind === "reminder") return `${item.repeating ? "🔁" : "🔔"} ${item.name} (${day}, ${whenText(item.days)})`;
    if (item.kind === "statement") return `🧾 ${item.name} statement is generated ${day} (${whenText(item.days)}): ${rupees(item.amount)} ${item.days === 0 ? "on the statement" : "outstanding so far"}`;
    return item.kind === "card"
        ? `💳 ${item.name}: ${rupees(item.amount)} ${item.statement ? `on the statement of ${shortDay(item.statement)}` : "owed"}, bill due ${day} (${whenText(item.days)})`
        : `📦 ${item.name}${item.amount != null ? ": " + rupees(item.amount) : ""} due ${day} (${whenText(item.days)})`;
}

/* Opens the Calendar on a day. */
export function showReminderDay(iso) {
    const day = new Date(iso + "T00:00:00");
    setCalMonth(new Date(day.getFullYear(), day.getMonth(), 1));
    setCalSelected(iso);
    if (wsView !== "calendar") showWorkspaceView("calendar");
    renderCalendar();
}

/* Your reminders from the last DONE_DAYS days that are ticked (a one-off one, or a day of a repeating one), the latest first. */
function recentlyDone(now = new Date()) {
    const today = dayNumber(now);
    const recent = iso => today - dayNumber(new Date(iso + "T00:00:00")) < DONE_DAYS;
    const days = userReminders.filter(r => isRepeating(r)).flatMap(r => Object.entries(r.completions || {})
        .filter(([occurrence, doneOn]) => recent(occurrence) && recent(doneOn))
        .map(([occurrence, doneOn]) => ({ id: r.id, title: r.title, date: occurrence, doneOn, occurrence })));
    return userReminders
        .filter(r => r.done && !isRepeating(r) && r.doneOn
            && today - dayNumber(new Date(r.doneOn + "T00:00:00")) < DONE_DAYS    // ticked within the week, and
            && today - dayNumber(new Date(r.date + "T00:00:00")) < DONE_DAYS)     // the reminder itself is from that week too (not an old one ticked in bulk)
        .concat(days)
        .sort((a, b) => b.doneOn.localeCompare(a.doneOn) || b.id - a.id || b.date.localeCompare(a.date));
}

function doneText(doneOn, now = new Date()) {
    const ago = dayNumber(now) - dayNumber(new Date(doneOn + "T00:00:00"));
    return ago <= 0 ? "done today" : ago === 1 ? "done yesterday" : `done ${ago} days ago`;
}

/* A ticked reminder: its tick box (ticked; unticking brings it back), its title struck through and when it was ticked. */
function doneRow(r) {
    const day = new Date(r.date + "T00:00:00").toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short" });
    const row = el("button", { className: "reminder-item done", type: "button", title: "Show this day in the Calendar" },
        el("span", { className: "ri-main", textContent: r.title }),
        el("span", { className: "ri-detail", textContent: "was for " + day }),
        el("span", { className: "ri-when", textContent: doneText(r.doneOn) }));
    row.setAttribute("aria-label", `${r.title}, ${doneText(r.doneOn)}`);
    row.addEventListener("click", () => showReminderDay(r.date));
    return el("div", { className: "reminder-line" }, reminderCheckbox({ id: r.id, title: r.title, done: true }, r.occurrence || null), row);
}

/* The panels for what is coming up, in the order they are shown, and which items go in each. */
const PANELS = [
    { key: "bills", title: "Bills", has: item => item.kind === "card" || item.kind === "ledger" || item.kind === "statement" },
    { key: "reminders", title: "Reminders", has: item => item.kind === "reminder" && !item.repeating },
    { key: "recurring", title: "Recurring", has: item => item.kind === "reminder" && item.repeating }
];

/* What a row shows: a name with its icon, a line under it (the amount and due day), and when it falls, on the right. */
function rowParts(item) {
    const day = new Date(item.date + "T00:00:00").toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short" });
    if (item.kind === "statement") return { main: `🧾 ${item.name} statement`, detail: `${rupees(item.amount)} ${item.days === 0 ? "on the statement" : "outstanding so far"} · ${day}` };
    if (item.kind === "card") return { main: `💳 ${item.name}`, detail: `${rupees(item.amount)} ${item.statement ? `on the ${shortDay(item.statement)} statement` : "owed"} · due ${day}` };
    if (item.kind === "ledger") return { main: `📦 ${item.name}`, detail: `${item.amount != null ? rupees(item.amount) + " · " : ""}due ${day}` };
    return { main: item.repeating ? `🔁 ${item.name}` : item.name, detail: day };
}

function reminderRow(item) {
    const { main, detail } = rowParts(item);
    const row = el("button", { className: "reminder-item" + (item.days < 0 ? " late" : item.days === 0 ? " today" : ""), type: "button",
        title: "Show this day in the Calendar" },
        el("span", { className: "ri-main", textContent: main }),
        el("span", { className: "ri-detail", textContent: detail }),
        el("span", { className: "ri-when", textContent: whenText(item.days) }));
    row.setAttribute("aria-label", reminderText(item));
    row.addEventListener("click", () => showReminderDay(item.date));
    if (item.kind !== "reminder") return row;
    // A one-off reminder can be dragged from here onto a day of the Calendar (when it is on show) to move it.
    if (!item.repeating) {
        row.draggable = true;
        row.addEventListener("dragstart", e => startReminderDrag(e, { id: item.id }));
    }
    // Your own reminders can be ticked here (a repeating one for this day only), and a late one moved to tomorrow.
    const line = el("div", { className: "reminder-line" }, reminderCheckbox({ id: item.id, title: item.name, done: false }, item.repeating ? item.date : null), row);
    if (item.days < 0) {
        const move = el("button", { className: "reminder-move", type: "button", textContent: "Tomorrow", title: "Move this reminder to tomorrow" });
        move.addEventListener("click", () => moveReminder(item.id, tomorrowIso()).catch(e => alert(e.message)));
        line.append(move);
    }
    return line;
}

export function renderReminders() {
    const bar = $("remindersBar");
    bar.innerHTML = "";
    const items = upcomingDues();
    const done = recentlyDone();
    const alerts = budgetAlerts(budgets, rupeeTransactions(), allCategories, isoDay(new Date()));
    bar.classList.toggle("hidden", items.length === 0 && done.length === 0 && alerts.length === 0);
    for (const panel of PANELS) {
        const mine = items.filter(panel.has);
        if (mine.length === 0) continue;
        const box = el("section", { className: "reminder-panel reminder-panel-" + panel.key },
            el("h3", { className: "reminder-head" }, panel.title, el("span", { className: "reminder-count", textContent: String(mine.length) })));
        mine.forEach(item => box.append(reminderRow(item)));
        bar.append(riseOnce(box, "reminder-panel-" + panel.key));
    }
    if (alerts.length > 0) {
        // budgets of the month running that are close to or over their limit; a row opens the Budgets view
        const box = el("section", { className: "reminder-panel reminder-panel-budgets" },
            el("h3", { className: "reminder-head" }, "Budgets", el("span", { className: "reminder-count", textContent: String(alerts.length) })));
        for (const a of alerts) {
            const row = el("button", { className: "reminder-item " + a.state, type: "button", title: "Open Budgets" },
                el("span", { className: "ri-main", textContent: "🎯 " + a.name }),
                el("span", { className: "ri-detail", textContent: `${rupees(a.spent)} of ${rupees(a.limit)}` }),
                el("span", { className: "ri-when", textContent: a.state === "over" ? `over by ${rupees(-a.left)}` : `${a.pct}% used` }));
            row.setAttribute("aria-label", budgetText(a));
            row.addEventListener("click", () => showWorkspaceView("budgets"));
            box.append(row);
        }
        bar.append(riseOnce(box, "reminder-panel-budgets"));
    }
    if (done.length > 0) {
        const box = el("section", { className: "reminder-panel reminder-panel-done" },
            el("h3", { className: "reminder-head" }, "Done recently", el("span", { className: "reminder-count", textContent: String(done.length) })));
        done.slice(0, DONE_SHOWN).forEach(r => box.append(doneRow(r)));
        if (done.length > DONE_SHOWN) {
            box.append(el("div", { className: "reminder-more", textContent: `+ ${done.length - DONE_SHOWN} more: find them under Reminders` }));
        }
        bar.append(riseOnce(box, "reminder-panel-done"));
    }
}
