/* Period filter: one date range for the whole dashboard, set from the row at the top of the view card (Today, From, To).
   It scopes the calendar's range, the pie charts, the transactions list, and every line
   graph (net worth over time, a category month by month, and the Mutual Funds and Shares stats). */

import { MONTH_NAMES } from "../core/constants.js";
import { $, el, formatDate, isoDay } from "../core/util.js";
import { renderAccounts } from "./accounts.js";
import { calendarFollowPeriod, renderCalendar } from "./calendar.js";
import { renderCharts } from "./charts.js";
import { renderNetWorth } from "./net-worth.js";
import { renderTransactions, setTxnPage } from "./transactions.js";

export let periodRange = "ALL";      // ALL, TODAY or CUSTOM
export let periodFrom = "";
export let periodTo = "";

const PERIOD_LABELS = [["ALL", "All time"], ["TODAY", "Today"]];

export function inPeriod(t) {
    return (!periodFrom || t.date >= periodFrom) && (!periodTo || t.date <= periodTo);
}

export const pad2 = n => String(n).padStart(2, "0");
export const monthKey = (year, month0) => `${year}-${pad2(month0 + 1)}`;

/* From after To would show nothing, so the date just typed wins and the other one moves to meet it. Returns [from, to]. */
function rangeAfterEdit(changed, from, to) {
    if (from && to && from > to) return changed === "from" ? [from, from] : [to, to];
    return [from, to];
}

/* The months (oldest first, as "YYYY-MM") a line graph covers for the period in effect. firstKey is the first month that
   has any data: nothing is drawn before it. All time runs from firstKey to this month. */
export function periodMonthKeys(firstKey, cap = 60) {
    const now = new Date();
    const nowKey = monthKey(now.getFullYear(), now.getMonth());
    let from = periodFrom ? periodFrom.slice(0, 7) : (firstKey || nowKey);
    if (firstKey && from < firstKey) from = firstKey;
    let to = periodTo ? periodTo.slice(0, 7) : nowKey;
    if (to > nowKey) to = nowKey;
    const keys = [];
    let [y, m] = from.split("-").map(Number);
    m -= 1;
    while (monthKey(y, m) <= to && keys.length < 1200) {
        keys.push(monthKey(y, m));
        if (++m > 11) { m = 0; y++; }
    }
    return keys.slice(-cap);
}

/* "Sep" from "2026-09". */
export const monthShort = key => MONTH_NAMES[Number(key.slice(5)) - 1].slice(0, 3);

/* Readable description of the range in effect, for chart captions. */
export function periodLabel() {
    if (periodRange === "CUSTOM") {
        if (periodFrom && periodTo) return `${formatDate(periodFrom)} to ${formatDate(periodTo)}`;
        if (periodFrom) return `from ${formatDate(periodFrom)}`;
        if (periodTo) return `up to ${formatDate(periodTo)}`;
        return "All time";
    }
    return PERIOD_LABELS.find(([value]) => value === periodRange)[1];
}

/* The period as it reads in a sentence: "all time", "today", "the period 01 Jul 2026 to 30 Sept 2026". */
function periodPhrase() {
    return periodRange === "CUSTOM" && (periodFrom || periodTo) ? "the period " + periodLabel() : periodLabel().toLowerCase();
}

/* The period as it ends a sentence: "so far" for all time, "today", "in this month", "in the period 01 Jul 2026 to ...". */
export function periodIn() {
    if (periodRange === "ALL" || (periodRange === "CUSTOM" && !periodFrom && !periodTo)) return "so far";
    if (periodRange === "TODAY") return "today";
    return "in " + periodPhrase();
}

/* Today, or all time (Clear). The From and To boxes always show the range in effect. */
function applyRange(range) {
    periodRange = range;
    periodFrom = periodTo = range === "TODAY" ? isoDay(new Date()) : "";
    periodChanged();
}

/* Sets the shared range to exactly from..to (either may be "" for open ended). Both empty is all time. */
export function setSharedRange(from, to) {
    periodRange = from || to ? "CUSTOM" : "ALL";
    periodFrom = from || "";
    periodTo = to || "";
    periodChanged();
}

/* Any change of range: the calendar jumps to it, every view redraws. */
function periodChanged() {
    setTxnPage(1);
    calendarFollowPeriod();
    renderPeriod();
}

/* The row at the top of the view card: Today, From, To and (once a range is set) Clear. Built once, then kept in step. */
export function initPeriodFilter() {
    const bar = $("wsRange");
    const today = el("button", { className: "chip", id: "periodToday", textContent: "Today" });
    const from = el("input", { type: "date", className: "date-input", id: "periodFromInput" });
    const to = el("input", { type: "date", className: "date-input", id: "periodToInput" });
    const clear = el("button", { className: "chip", id: "periodClear", textContent: "Clear", title: "Back to all time" });
    today.addEventListener("click", () => applyRange("TODAY"));
    clear.addEventListener("click", () => applyRange("ALL"));
    const custom = changed => {
        [from.value, to.value] = rangeAfterEdit(changed, from.value, to.value);
        periodRange = "CUSTOM"; periodFrom = from.value; periodTo = to.value; periodChanged();
    };
    from.addEventListener("change", () => custom("from"));
    to.addEventListener("change", () => custom("to"));
    bar.prepend(today, el("label", {}, "From", from), el("label", {}, "To", to), clear);   // the + (in the markup) stays last, on the right
}

function renderPeriodFilter() {
    $("periodToday").classList.toggle("active", periodRange === "TODAY");
    $("periodFromInput").value = periodFrom;
    $("periodToInput").value = periodTo;
    $("periodClear").classList.toggle("hidden", !periodFrom && !periodTo);
}

/* Everything that follows the period re-renders against the same slice. */
export function renderPeriod() {
    renderPeriodFilter();
    renderCalendar();
    renderNetWorth();
    renderAccounts();      // the Mutual Funds and Shares stats
    renderCharts();
    renderTransactions();
}
