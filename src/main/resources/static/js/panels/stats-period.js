/* The period of the Stats view. Stats has its own selector (Today / From / To are hidden there): Weekly, Monthly (the
   default), Annually, or Period for any From and To. The first three show one week, month or year, with arrows to the
   one before and after. The category line graph always covers at least the last 12 months ending with the period. */

import { MONTH_NAMES } from "../core/constants.js";
import { $, el, formatDate, isoDay } from "../core/util.js";
import { renderCharts } from "./charts.js";
import { monthKey } from "./period-filter.js";

let statsMode = "MONTHLY";        // WEEKLY, MONTHLY, ANNUALLY or PERIOD
let statsAnchor = new Date();     // a day inside the week, month or year on show
let statsFrom = "";               // the custom Period: either end may be empty (open)
let statsTo = "";

const STATS_MODES = [["WEEKLY", "Weekly"], ["MONTHLY", "Monthly"], ["ANNUALLY", "Annually"], ["PERIOD", "Period"]];

const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);

/* { from, to, label, phrase } for the period on show; from and to are yyyy-mm-dd, or "" when open ended. */
export function statsRange() {
    const d = statsAnchor, y = d.getFullYear(), m = d.getMonth();
    if (statsMode === "WEEKLY") {
        const monday = addDays(d, -((d.getDay() + 6) % 7)), sunday = addDays(monday, 6);
        const from = isoDay(monday), to = isoDay(sunday);
        return { from, to, label: `${formatDate(from)} – ${formatDate(to)}`, phrase: `in the week ${formatDate(from)} to ${formatDate(to)}` };
    }
    if (statsMode === "MONTHLY") {
        return { from: isoDay(new Date(y, m, 1)), to: isoDay(new Date(y, m + 1, 0)), label: `${MONTH_NAMES[m]} ${y}`, phrase: `in ${MONTH_NAMES[m]} ${y}` };
    }
    if (statsMode === "ANNUALLY") {
        return { from: `${y}-01-01`, to: `${y}-12-31`, label: String(y), phrase: `in ${y}` };
    }
    if (statsFrom && statsTo) return { from: statsFrom, to: statsTo, label: `${formatDate(statsFrom)} to ${formatDate(statsTo)}`, phrase: `in the period ${formatDate(statsFrom)} to ${formatDate(statsTo)}` };
    if (statsFrom) return { from: statsFrom, to: "", label: `from ${formatDate(statsFrom)}`, phrase: `from ${formatDate(statsFrom)}` };
    if (statsTo) return { from: "", to: statsTo, label: `up to ${formatDate(statsTo)}`, phrase: `up to ${formatDate(statsTo)}` };
    return { from: "", to: "", label: "All time", phrase: "so far" };
}

export function statsInPeriod(t) {
    const { from, to } = statsRange();
    return (!from || t.date >= from) && (!to || t.date <= to);
}

/* The period as it ends a sentence: "in October 2026", "in 2026", "so far". */
export function statsPeriodIn() {
    return statsRange().phrase;
}

/* "2026-09" moved by `delta` months. */
function shiftMonthKey(key, delta) {
    const [y, m] = key.split("-").map(Number);
    const d = new Date(y, m - 1 + delta, 1);
    return monthKey(d.getFullYear(), d.getMonth());
}

/* The months (oldest first, "YYYY-MM") of the category line graph: from the period's first month, but never fewer than
   the 12 months ending with its last month (the current month at most), and not before `firstKey`, the first month that has
   data. */
export function statsMonthKeys(firstKey, cap = 60) {
    const { from, to } = statsRange();
    const now = new Date();
    const nowKey = monthKey(now.getFullYear(), now.getMonth());
    let endKey = to ? to.slice(0, 7) : nowKey;
    if (endKey > nowKey) endKey = nowKey;
    let startKey = from ? from.slice(0, 7) : (firstKey || endKey);
    const twelve = shiftMonthKey(endKey, -11);
    if (startKey > twelve) startKey = twelve;
    if (firstKey && startKey < firstKey) startKey = firstKey;
    const keys = [];
    for (let key = startKey; key <= endKey && keys.length < 1200; key = shiftMonthKey(key, 1)) keys.push(key);
    return keys.slice(-cap);
}

/* Moves to the week, month or year before (-1) or after (+1). */
function statsStep(direction) {
    const d = statsAnchor;
    statsAnchor = statsMode === "WEEKLY" ? addDays(d, 7 * direction)
        : statsMode === "MONTHLY" ? new Date(d.getFullYear(), d.getMonth() + direction, 1)
        : new Date(d.getFullYear() + direction, 0, 1);
}

/* Draws the buttons, and under them the arrows with the period's name or the From and To boxes. */
export function renderStatsPeriod() {
    const box = $("statsPeriod");
    box.innerHTML = "";
    const modes = el("div", { className: "chips" });
    for (const [value, label] of STATS_MODES) {
        const chip = el("button", { className: "chip" + (statsMode === value ? " active" : ""), type: "button", textContent: label });
        chip.addEventListener("click", () => {
            if (value === "PERIOD" && statsMode !== "PERIOD" && !statsFrom && !statsTo) {
                const r = statsRange();                                    // start from what was on show
                statsFrom = r.from;
                statsTo = r.to > isoDay(new Date()) ? isoDay(new Date()) : r.to;
            }
            if (value !== "PERIOD" && statsMode === "PERIOD") statsAnchor = new Date();
            statsMode = value;
            renderCharts();
        });
        modes.append(chip);
    }
    box.append(modes);

    if (statsMode === "PERIOD") {
        const from = el("input", { type: "date", className: "date-input", value: statsFrom });
        const to = el("input", { type: "date", className: "date-input", value: statsTo });
        const changed = () => { statsFrom = from.value; statsTo = to.value; renderCharts(); };
        from.addEventListener("change", changed);
        to.addEventListener("change", changed);
        box.append(el("div", { className: "stats-nav" }, el("label", {}, "From", from), el("label", {}, "To", to)));
    } else {
        const prev = el("button", { className: "btn btn-secondary", type: "button", textContent: "‹", title: "Earlier" });
        const next = el("button", { className: "btn btn-secondary", type: "button", textContent: "›", title: "Later",
            disabled: statsRange().to >= isoDay(new Date()) });          // nothing after the current week, month or year
        prev.addEventListener("click", () => { statsStep(-1); renderCharts(); });
        next.addEventListener("click", () => { statsStep(1); renderCharts(); });
        box.append(el("div", { className: "stats-nav" }, prev, el("strong", { className: "stats-label", textContent: statsRange().label }), next));
    }
}
