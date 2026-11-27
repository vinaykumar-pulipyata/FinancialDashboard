/* Calendar: a view of its own (the first one shown). One month at a time. Each day shows the money in and out (transfers are left out, as in the charts),
   and a day with a Ledger bucket payment due carries a small marker. Click a day to see its transactions. */

import { ADJUSTMENT_KEYS, MONTH_NAMES } from "../core/constants.js";
import { rupeeTransactions } from "../core/currency.js";
import { accounts, allCategories, cardDueDays, cardStatementDays, ledger, noteDays, transactions } from "../core/state.js";
import { hideTooltip, showTooltip, showTooltipAt } from "../core/tooltip.js";
import { $, addMonthsClamped, api, el, isoDay, replay, rupees, rupees2 } from "../core/util.js";
import { HEAT_WORDS, heatSummary, heatText, monthHeat } from "./calendar-calc.js";
import { makeReminderDropTarget, movableRemindersOn, reminderChip, reminderDaysIn, reminderSection, remindersOn } from "./my-reminders.js";
import { noteCard, openNote } from "./notes.js";
import { inPeriod, periodFrom, periodLabel, periodRange, periodTo } from "./period-filter.js";
import { statementDue } from "./reminders.js";
import { openTransaction } from "./transaction-form.js";
import { cardStatementAmount, showTransactionsFor, txnRowElement } from "./transactions.js";
import { wsView } from "./workspace.js";

let calMonth = new Date(new Date().getFullYear(), new Date().getMonth(), 1);
let calSelected = null;   // yyyy-mm-dd of the chosen day
let calMode = "calendar";   // "calendar", "expense" or "income": the heatmap colours the same grid by the day's spending or income
const CAL_MODE_BUTTONS = { calendar: "calModeCalendar", expense: "calModeExpense", income: "calModeIncome" };
let calFocusIso = null;   // the day to put the keyboard focus back on after a redraw

/* The shared range (Today, or From and To) was changed: show its month and, for Today, open that day. */
export function calendarFollowPeriod() {
    if (periodFrom) {
        const d = new Date(periodFrom + "T00:00:00");
        calMonth = new Date(d.getFullYear(), d.getMonth(), 1);
    }
    calSelected = periodRange === "TODAY" ? periodFrom : null;
}

/* Payments falling in the shown month: { "yyyy-mm-dd": [{ kind, name, amount }] }, kind "ledger" or "card" (a credit card's bill
   day; its amount is what is owed now and only known for today and later). Ledger buckets: A bucket repeats every
   frequencyMonths from its next payment date until its end date; without a frequency it is a single payment. */
function calendarDue(monthStart, monthEnd) {
    const due = {};
    const todayIso = isoDay(new Date());
    for (const a of accounts) {
        if (a.type !== "CREDIT_CARD" || !a.active) continue;
        const inMonth = dom => isoDay(new Date(monthStart.getFullYear(), monthStart.getMonth(), Math.min(dom, monthEnd.getDate())));   // a short month uses its last day
        const statementDay = cardStatementDays.get(a.id), dueDay = cardDueDays.get(a.id);
        if (statementDay) {          // the bill is generated on this day every month; its amount is known once the day has come
            const iso = inMonth(statementDay);
            (due[iso] ||= []).push({ kind: "statement", name: a.name, amount: iso <= todayIso ? cardStatementAmount(a, iso) : null });
        }
        if (dueDay) {
            const iso = inMonth(dueDay);
            let amount = iso >= todayIso ? Number(a.balance) : null, statement = null;
            if (statementDay && amount != null) {      // the bill is that statement's amount less payments since, not everything owed now
                const bill = statementDue(a, statementDay, new Date(iso + "T00:00:00"));
                amount = bill ? bill.amount : null;    // null: the statement has not been generated yet
                statement = bill ? bill.statement : null;
            }
            (due[iso] ||= []).push({ kind: "card", name: a.name, amount, statement });
        }
    }
    if (!ledger.configured) return due;
    for (const b of ledger.buckets) {
        if (b.buffer || !b.nextPaymentDate) continue;
        const first = new Date(b.nextPaymentDate + "T00:00:00");
        const end = b.endDate ? new Date(b.endDate + "T00:00:00") : null;
        const every = Number(b.frequencyMonths) > 0 ? Number(b.frequencyMonths) : 0;
        for (let k = 0; k < 600; k++) {
            const day = every ? addMonthsClamped(first, k * every) : first;
            if (day > monthEnd || (end && day > end)) break;
            if (day >= monthStart) (due[isoDay(day)] ||= []).push({ kind: "ledger", name: b.name, amount: b.paymentAmount });
            if (!every) break;
        }
    }
    return due;
}

/* How a payment reads: on the day's cell, in its tooltip, and in the day's list. */
const dueLabel = p => p.kind === "statement" ? `${p.name} statement` : p.kind === "card" ? `${p.name} bill due` : `Pay ${p.name}`;
const dueTitle = p => p.kind === "statement" ? `${p.name}: statement generated${p.amount != null ? `, ${rupees(p.amount)}` : ""}`
    : p.kind === "card" ? `${p.name}: bill due${p.amount != null ? `, ${rupees(p.amount)} ${p.statement ? "on the statement" : "owed now"}` : ""}` : `Pay ${p.name}: ${rupees(p.amount)}`;
const dueSentence = p => p.kind === "statement"
    ? `Card statement generated: ${p.name}${p.amount != null ? ` · ${rupees(p.amount)} on the statement` : ""}`
    : p.kind === "card"
    ? `Card bill due: ${p.name}${p.amount != null ? ` · ${rupees(p.amount)} ${p.statement ? "on the statement" : "owed now"}` : ""}`
    : `Ledger payment due: ${p.name} · ${rupees(p.amount)}`;

const CAL_CHIPS = 2;   // reminder titles shown in a day's cell; the rest are "+N more" (the day list has them all)

/* A Balance Adjustment (an account's Reset balance, or a debt written off) only corrects a balance: it is not money in or out. */
function calIsAdjustment(t) {
    const c = t.categoryId == null ? null : allCategories.find(c => c.id === t.categoryId);
    return !!(c && ADJUSTMENT_KEYS.has(c.systemKey));
}

export function renderCalendar() {
    const year = calMonth.getFullYear(), month = calMonth.getMonth();
    const monthStart = new Date(year, month, 1), monthEnd = new Date(year, month + 1, 0);
    $("calMonthLabel").textContent = `${MONTH_NAMES[month]} ${year}`;

    // Money in and out per day, and for the whole month.
    const byDay = {};
    let totalIn = 0, totalOut = 0;
    for (const t of rupeeTransactions()) {   // the totals are in rupees: a foreign-currency transaction is left out
        if (t.kind === "TRANSFER" || calIsAdjustment(t)) continue;
        const d = new Date(t.date + "T00:00:00");
        if (d < monthStart || d > monthEnd) continue;
        const entry = (byDay[t.date] ||= { income: 0, expense: 0 });
        if (t.kind === "INCOME") { entry.income += Number(t.amount); totalIn += Number(t.amount); }
        else { entry.expense += Number(t.amount); totalOut += Number(t.amount); }
    }
    const due = calendarDue(monthStart, monthEnd);
    const openReminders = reminderDaysIn(year, month);

    // The first line always covers the month shown, so it matches the grid. With a From / To range set, a second line covers the range (Today needs none: the chosen day is listed below).
    const totalsRow = (label, tin, tout) => el("div", { className: "cal-totals-row" },
        el("strong", { className: "cal-totals-label", textContent: label }),
        el("span", { className: "in" }, "In ", el("strong", { textContent: rupees(tin) })),
        el("span", { className: "out" }, "Out ", el("strong", { textContent: rupees(tout) })),
        el("span", {}, "Net ", el("strong", { textContent: (tin - tout < 0 ? "−" : "") + rupees(Math.abs(tin - tout)) })));
    const ranged = Boolean(periodFrom || periodTo);   // highlights the days in the grid
    const totals = $("calTotals");
    totals.innerHTML = "";
    totals.append(totalsRow(`${MONTH_NAMES[month]} ${year}`, totalIn, totalOut));
    if (periodRange === "CUSTOM" && ranged) {
        let rangeIn = 0, rangeOut = 0;
        for (const t of rupeeTransactions()) {
            if (t.kind === "TRANSFER" || calIsAdjustment(t) || !inPeriod(t)) continue;
            if (t.kind === "INCOME") rangeIn += Number(t.amount); else rangeOut += Number(t.amount);
        }
        totals.append(totalsRow(periodLabel(), rangeIn, rangeOut));
    }

    const heat = calMode === "calendar" ? null : monthHeat(rupeeTransactions(), allCategories, year, month, isoDay(new Date()));
    renderHeatSummary(heat);

    const grid = $("calGrid");
    grid.innerHTML = "";
    grid.classList.toggle("heat", Boolean(heat));
    for (const name of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]) {
        grid.append(el("div", { className: "cal-dow", textContent: name }));
    }
    const lead = (monthStart.getDay() + 6) % 7;   // weeks start on Monday
    for (let i = 0; i < lead; i++) grid.append(el("div", { className: "cal-cell blank" }));
    const todayIso = isoDay(new Date());
    for (let day = 1; day <= monthEnd.getDate(); day++) {
        const iso = isoDay(new Date(year, month, day));
        const money = byDay[iso], payments = due[iso] || [];
        const heatDay = heat ? heat[day - 1] : null;
        const cell = el("button", {
            className: "cal-cell" + (heatDay ? ` heat heat-${calMode} heat-l${heatDay[calMode].level}` + (heatDay.future ? " future" : "") : "") + (ranged && inPeriod({ date: iso }) ? " in-range" : "") + (iso === todayIso ? " today" : "") + (iso === calSelected ? " selected" : ""),
            type: "button", title: payments.map(p => dueTitle(p)).join("\n")
        }, el("span", { className: "cal-num", textContent: String(day) }));
        const marks = el("span", { className: "cal-marks" });      // the notes and reminders marks, side by side at the top right
        if (noteDays.has(iso)) {
            const count = noteDays.get(iso);
            marks.append(el("span", { className: "cal-note", textContent: "📝", title: `${count} ${count === 1 ? "note" : "notes"}` }));
        }
        if (openReminders.has(iso)) {
            const count = openReminders.get(iso);
            marks.append(el("span", { className: "cal-remind", textContent: "🔔", title: `${count} ${count === 1 ? "reminder" : "reminders"} to do` }));
        }
        if (marks.children.length) cell.append(marks);
        if (heatDay) {
            if (heatDay[calMode].total > 0) cell.append(el("span", { className: "cal-amt heat-amt", textContent: rupees(heatDay[calMode].total) }));
            describeHeatDay(cell, heatDay, iso);
        } else {
            if (money && money.income) cell.append(el("span", { className: "cal-amt income", textContent: "+" + rupees(money.income) }));
            if (money && money.expense) cell.append(el("span", { className: "cal-amt expense", textContent: "−" + rupees(money.expense) }));
        }
        if (payments.length) {
            cell.append(el("span", { className: "cal-due",
                textContent: payments.length === 1 ? dueLabel(payments[0]) : `${payments.length} payments due` }));
        }
        const movable = movableRemindersOn(iso);   // open one-off reminders show their title and can be dragged to another day
        movable.slice(0, CAL_CHIPS).forEach(r => cell.append(reminderChip(r)));
        if (movable.length > CAL_CHIPS) cell.append(el("span", { className: "cal-more", textContent: `+${movable.length - CAL_CHIPS} more` }));
        makeReminderDropTarget(cell, iso);
        cell.addEventListener("click", () => { calSelected = calSelected === iso ? null : iso; calFocusIso = iso; renderCalendar(); });
        cell.dataset.iso = iso;
        grid.append(cell);
    }
    if (calFocusIso) {   // a redraw takes the focus away; the arrow keys and Enter need it back on the day
        const again = [...grid.children].find(c => c.dataset && c.dataset.iso === calFocusIso);
        calFocusIso = null;
        if (again && again.focus) again.focus();
    }

    renderCalendarDay(due);
}

/* A heatmap day: an accessible label with its figures and level (colour is never the only sign), and the tooltip on hover, focus and tap. */
function describeHeatDay(cell, heatDay, iso) {
    const label = new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { day: "numeric", month: "long", year: "numeric" });
    const text = heatText(heatDay, calMode, label);
    const tip = { value: text.lines[0], name: text.title, notes: text.lines.slice(1) };
    cell.setAttribute("aria-label", `${text.title}. ${text.lines.join(". ")}`);
    cell.addEventListener("pointermove", e => showTooltip(tip, e.clientX, e.clientY));
    cell.addEventListener("pointerleave", hideTooltip);
    cell.addEventListener("focus", () => showTooltipAt(cell, tip));
    cell.addEventListener("blur", hideTooltip);
    cell.addEventListener("click", () => showTooltipAt(cell, tip));   // a tap has no hover
}

/* The figures above the grid for the mode on show, and what each level is called (the legend). Hidden in the plain calendar. */
function renderHeatSummary(heat) {
    const box = $("calHeatSummary"), legend = $("calHeatLegend");
    box.classList.toggle("hidden", !heat);
    legend.classList.toggle("hidden", !heat);
    box.innerHTML = "";
    legend.innerHTML = "";
    if (!heat) return;
    const sum = heatSummary(heat, calMode);
    const income = calMode === "income";
    const stat = (label, value) => el("span", {}, label + " ", el("strong", { textContent: value }));
    box.append(
        stat(income ? "Total income" : "Total expenses", rupees2(sum.total)),
        stat(income ? "Income days" : "Spending days", String(sum.activeDays)),
        stat(income ? "No-income days" : "No-spend days", String(sum.quietDays)),
        stat("Highest day", sum.highest ? `${Number(sum.highest.iso.slice(8))} ${MONTH_NAMES[Number(sum.highest.iso.slice(5, 7)) - 1]} · ${rupees2(sum.highest.amount)}` : "none"),
        stat(income ? "Average per income day" : "Average per spending day", rupees2(sum.averagePerActiveDay)));
    HEAT_WORDS.forEach((word, level) => legend.append(el("span", {}, el("i", { className: `heat-${calMode} l${level}` }), word)));
}

/* The chosen day: its Ledger payments due, then its transactions with the same edit button as the main list. */
function renderCalendarDay(due) {
    const box = $("calDay");
    box.innerHTML = "";
    if (!calSelected) return;
    box.append(el("h3", { textContent: new Date(calSelected + "T00:00:00").toLocaleDateString("en-IN",
        { weekday: "long", day: "numeric", month: "long", year: "numeric" }) }));
    if (calMode !== "calendar") {   // the heatmap's day opens the ordinary transaction list, narrowed to this day and kind
        const open = el("button", { className: "txn-link", type: "button", textContent: calMode === "income" ? "Open this day's income in Transactions" : "Open this day's expenses in Transactions" });
        const day = calSelected;
        open.addEventListener("click", () => showTransactionsFor({ kind: calMode === "income" ? "INCOME" : "EXPENSE", from: day, to: day }));
        box.append(open);
    }
    for (const p of due[calSelected] || []) {
        box.append(el("div", { className: "cal-due-row", textContent: dueSentence(p) }));
    }
    const rows = transactions.filter(t => t.date === calSelected);
    if (rows.length === 0 && !(due[calSelected] || []).length && !noteDays.has(calSelected) && !remindersOn(calSelected).length) {
        box.append(el("div", { className: "empty-state", textContent: "Nothing recorded on this day." }));
    }
    for (const t of rows) box.append(txnRowElement(t));

    const day = calSelected;
    box.append(reminderSection(day));

    // The day's notes, with a button to write another.
    const add = el("button", { className: "btn btn-secondary", textContent: "+ Add note" });
    add.addEventListener("click", () => openNote(null, day));
    const notesBox = el("div", { className: "cal-notes" }, el("div", { className: "cal-notes-head" }, el("span", { textContent: "Notes" }), add));
    box.append(notesBox);
    if (noteDays.has(day)) {
        api("/api/notes?date=" + day).then(data => {
            if (calSelected === day) data.notes.forEach(n => notesBox.append(noteCard(n)));   // ignore an answer for a day no longer chosen
        }).catch(() => {});
    }
}

/* Moves between the days with the arrow keys: left and right by a day, up and down by a week. */
function moveCalendarFocus(e) {
    const step = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }[e.key];
    if (!step) return;
    const cells = [...$("calGrid").children].filter(c => c.dataset && c.dataset.iso);
    const at = cells.indexOf(e.target);
    if (at < 0 || !cells[at + step]) return;
    e.preventDefault();
    cells[at + step].focus();
}

export function initCalendar() {
    $("calGrid").addEventListener("keydown", moveCalendarFocus);
    for (const [mode, id] of Object.entries(CAL_MODE_BUTTONS)) {
        $(id).addEventListener("click", () => {
            calMode = mode;   // the month and the chosen day stay as they are
            for (const [other, otherId] of Object.entries(CAL_MODE_BUTTONS)) {
                $(otherId).classList.toggle("active", other === mode);
                $(otherId).setAttribute("aria-pressed", String(other === mode));
            }
            hideTooltip();
            renderCalendar();
        });
    }
    const move = months => { calMonth = new Date(calMonth.getFullYear(), calMonth.getMonth() + months, 1); calSelected = null; renderCalendar(); replay($("calGrid"), months > 0 ? "slide-next" : "slide-prev"); };
    $("calPrev").addEventListener("click", () => move(-1));
    $("calNext").addEventListener("click", () => move(1));

    // The + in the range row opens a new Expense (the commonest), dated on the day chosen in the calendar (today otherwise).
    // Income and Transfer are one click away inside the form.
    $("calAdd").addEventListener("click", () =>
        openTransaction("EXPENSE", null, wsView === "calendar" ? calSelected : null).catch(err => alert(err.message)));
}

/* Setters: a file that imports a variable cannot assign to it, so the files that replace it call these. */
export function setCalMonth(value) { calMonth = value; }
export function setCalSelected(value) { calSelected = value; }
