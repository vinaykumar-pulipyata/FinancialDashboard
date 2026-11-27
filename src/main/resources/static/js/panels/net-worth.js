/* Net worth over time: a line, bar or area chart with a point for each month (month-end net worth from /api/net-worth).
   One series, so one colour and no legend. Only the latest value is written on the chart; hovering or tabbing to a
   month shows a vertical guide and its value, and the table view lists everything. */

import { netWorthSeries, transactions } from "../core/state.js";
import { hideTooltip, showTooltip, showTooltipAt } from "../core/tooltip.js";
import { $, el, replay, rupees, rupees2 } from "../core/util.js";
import { svgEl } from "./charts.js";
import { periodFrom, periodLabel, periodMonthKeys, periodRange, periodTo } from "./period-filter.js";
import { wsView } from "./workspace.js";

const NW_H = 300, NW_LEFT = 62, NW_RIGHT = 20, NW_TOP = 40, NW_BOTTOM = 44;
let nwShowTable = false;
let nwChartType = "line";   // line, bar or area
let nwZoom = "1Y";          // how much history: 1Y, 3Y, 5Y or ALL
const NW_ZOOM_MONTHS = { "1Y": 12, "3Y": 36, "5Y": 60, "ALL": Infinity };
const NW_ZOOM_NAMES = { "1Y": "Last 12 months", "3Y": "Last 3 years", "5Y": "Last 5 years", "ALL": "All the history" };
const NW_MAX_POINTS = 36;   // more months than this are drawn as one point per quarter

/* ₹1,234 / ₹12.5K / ₹3.2L / ₹1.5Cr, in the Indian lakh and crore units. */
export function compactRupees(n) {
    const v = Math.abs(n), sign = n < 0 ? "−" : "";
    const fmt = (x, unit) => `${sign}₹${Math.round(x * 10) / 10}${unit}`;
    if (v >= 1e7) return fmt(v / 1e7, "Cr");
    if (v >= 1e5) return fmt(v / 1e5, "L");
    if (v >= 1e3) return fmt(v / 1e3, "K");
    return `${sign}₹${Math.round(v)}`;
}

/* Clean axis ticks. With includeZero they always reach zero (as a bar chart needs). Without it, a line whose values are
   all above zero gets a tighter scale, so its ups and downs can be seen. */
export function niceTicks(minValue, maxValue, target = 4, includeZero = true) {
    let lo = Math.min(0, minValue), hi = Math.max(0, maxValue);
    if (!includeZero) {
        const span = (maxValue - minValue) || Math.abs(maxValue) * 0.1 || 1;
        lo = minValue - span * 0.2;
        hi = maxValue + span * 0.2;
        if (minValue >= 0 && lo < 0) lo = 0;
        if (maxValue <= 0 && hi > 0) hi = 0;
    }
    const raw = ((hi - lo) || 1) / target;
    const pow = Math.pow(10, Math.floor(Math.log10(raw)));
    const step = [1, 2, 2.5, 5, 10].map(m => m * pow).find(s => s >= raw);
    const start = Math.floor(lo / step) * step, end = Math.ceil(hi / step) * step;
    const ticks = [];
    for (let t = start; t <= end + step / 2; t += step) ticks.push(Math.round(t * 100) / 100);
    return { ticks, min: start, max: end };
}

/* One point per quarter (the last month of each quarter that has data), for a long stretch of history. */
function quarterly(monthly) {
    const byQuarter = new Map();
    for (const p of monthly) {
        const [year, month] = p.month.split("-").map(Number);
        byQuarter.set(year + "-" + Math.ceil(month / 3), p);   // later months replace earlier ones
    }
    return [...byQuarter.values()];
}

/* "Last 12 months: ₹14.2L → ₹18.9L, up ₹4.7L (+33%). Best month Mar 2026 (+₹1.2L), weakest Aug 2025 (−₹30K)." */
function netWorthSummary(monthly) {
    const box = $("nwSummary");
    box.innerHTML = "";
    if (monthly.length < 2) return;
    const first = monthly[0], last = monthly[monthly.length - 1];
    const start = Number(first.netWorth), end = Number(last.netWorth), change = end - start;
    const requested = NW_ZOOM_MONTHS[nwZoom];
    const label = requested !== Infinity && monthly.length >= requested ? NW_ZOOM_NAMES[nwZoom] : "Since " + first.label;
    const pct = start !== 0 ? ` (${change >= 0 ? "+" : "−"}${Math.abs(change / start * 100).toFixed(1)}%)` : "";
    const steps = monthly.slice(1).map((p, i) => ({ label: p.label, step: Number(p.netWorth) - Number(monthly[i].netWorth) }));
    const best = steps.reduce((a, b) => b.step > a.step ? b : a);
    const worst = steps.reduce((a, b) => b.step < a.step ? b : a);
    const signed = n => (n < 0 ? "−" : "+") + rupees2(Math.abs(n));
    box.append(el("strong", { textContent: label + ": " }), `${rupees2(start)} → ${rupees2(end)}, `,
        el("span", { className: change >= 0 ? "up" : "down", textContent: `${change >= 0 ? "up" : "down"} ${rupees2(Math.abs(change))}${pct}` }),
        `. Best month ${best.label} (${signed(best.step)}), weakest ${worst.label} (${signed(worst.step)}).`);
}

export function renderNetWorth() {
    const chart = $("nwChart"), table = $("nwTable"), toggle = $("nwTableToggle"), types = $("nwTypes"), zoom = $("nwZoom");
    chart.innerHTML = "";
    table.innerHTML = "";
    $("nwSummary").innerHTML = "";
    // The period chooses the months, the zoom how many of the latest ones to show. Nothing is drawn before the first
    // month that has a transaction.
    const firstTxn = transactions.length ? transactions.reduce((min, t) => t.date < min ? t.date : min, transactions[0].date).slice(0, 7) : null;
    const months = new Set(periodMonthKeys(firstTxn, 120));
    const inPeriodMonths = netWorthSeries.filter(p => months.has(p.month));
    const wanted = NW_ZOOM_MONTHS[nwZoom];
    const series = wanted === Infinity ? inPeriodMonths : inPeriodMonths.slice(-wanted);   // the months in view
    const points = series.length > NW_MAX_POINTS ? quarterly(series) : series;              // and what is drawn
    const periodText = periodRange === "ALL" || (periodRange === "CUSTOM" && !periodFrom && !periodTo) ? "" : " for " + periodLabel();
    $("nwNote").textContent = "Month-end net worth" + periodText + ", worked out from your balances and transactions. " +
        (points !== series ? "A long stretch is drawn as one point per quarter (the quarter-end figure); choose a shorter span for every month. " : "") +
        "Changes in the value of investments, gold and property are not tracked yet, so they are not reflected here." +
        (series.length === 1 ? " Only one month falls in this period: pick a longer one to see a line." : "");

    const empty = series.length === 0 || series.every(p => Number(p.assets) === 0 && Number(p.liabilities) === 0);
    zoom.querySelectorAll("[data-zoom]").forEach(c => c.classList.toggle("active", c.dataset.zoom === nwZoom));
    if (series.length === 0 && netWorthSeries.length) {
        toggle.classList.add("hidden");
        types.classList.add("hidden");
        zoom.classList.add("hidden");
        table.classList.add("hidden");
        chart.classList.remove("hidden");
        chart.append(el("div", { className: "empty-state", textContent: "No month-end falls in this period. Pick a longer one." }));
        return;
    }
    toggle.classList.toggle("hidden", empty);
    zoom.classList.toggle("hidden", empty);
    types.classList.toggle("hidden", empty || nwShowTable);
    types.querySelectorAll("[data-type]").forEach(c => c.classList.toggle("active", c.dataset.type === nwChartType));
    if (empty) {
        table.classList.add("hidden");
        chart.classList.remove("hidden");
        chart.append(el("div", { className: "empty-state", textContent: "Add accounts and transactions to see your net worth over time." }));
        return;
    }
    netWorthSummary(series);
    toggle.textContent = nwShowTable ? "View as chart" : "View as table";
    chart.classList.toggle("hidden", nwShowTable);
    table.classList.toggle("hidden", !nwShowTable);
    table.append(netWorthTable(series));
    chart.append(netWorthSvg(points, chart.clientWidth || 720, points !== series));   // as wide as the panel
}

function netWorthSvg(series, NW_W, isQuarterly = false) {
    const values = series.map(p => Number(p.netWorth));
    const { ticks, min, max } = niceTicks(Math.min(...values), Math.max(...values), 4, nwChartType === "bar");   // bars start from zero
    const plotW = NW_W - NW_LEFT - NW_RIGHT, plotH = NW_H - NW_TOP - NW_BOTTOM;
    const y = v => NW_TOP + (max - v) / ((max - min) || 1) * plotH;
    const band = plotW / series.length;
    const xAt = i => NW_LEFT + band * i + band / 2;
    const floor = NW_TOP + plotH;                    // the bottom of the plot, where the area wash stops
    const lastIndex = series.length - 1;
    const labelEvery = Math.max(1, Math.ceil(40 / band));      // a month label needs about 40px
    const first = series[0];

    const svg = svgEl("svg", { class: "nw-chart", viewBox: `0 0 ${NW_W} ${NW_H}`, role: "img",
        "aria-label": `Net worth by month, from ${first.label} to ${series[lastIndex].label}. ` +
            `Latest ${rupees(series[lastIndex].netWorth)}.` });

    // A soft wash under the line fades from blue to nothing.
    const defs = svgEl("defs");
    const wash = svgEl("linearGradient", { id: "nwArea", x1: 0, y1: 0, x2: 0, y2: 1 });
    wash.append(svgEl("stop", { offset: "0%", style: "stop-color: var(--series)", "stop-opacity": nwChartType === "area" ? "0.45" : "0.22" }),
        svgEl("stop", { offset: "100%", style: "stop-color: var(--series)", "stop-opacity": "0" }));
    defs.append(wash);
    svg.append(defs);

    for (const tick of ticks) {
        svg.append(svgEl("line", { class: "nw-grid", x1: NW_LEFT, x2: NW_W - NW_RIGHT, y1: y(tick), y2: y(tick) }));
        svg.append(svgEl("text", { class: "nw-tick", x: NW_LEFT - 8, y: y(tick) + 4 }, compactRupees(tick)));
    }
    if (min < 0 && max > 0) {                        // the zero line, when net worth crosses it (bars always sit on it)
        svg.append(svgEl("line", { class: "nw-base", x1: NW_LEFT, x2: NW_W - NW_RIGHT, y1: y(0), y2: y(0) }));
    }

    const grow = true;   // the graph draws itself each time it is drawn: opening the view, a new zoom or chart type
    const bars = nwChartType === "bar";
    const points = values.map((v, i) => `${xAt(i)} ${y(v)}`);
    if (!bars) {
        svg.append(svgEl("path", { class: "nw-area" + (grow ? " grow" : ""),
            d: `M${xAt(0)} ${floor} L${points.join(" L")} L${xAt(lastIndex)} ${floor} Z` }));
        svg.append(svgEl("path", { class: "nw-line" + (grow ? " grow" : ""), pathLength: "1", d: `M${points.join(" L")}` }));
    }
    const barW = Math.min(band * 0.62, 64);

    series.forEach((p, i) => {
        const v = values[i];
        const col = svgEl("g", { class: "nw-col" });
        if (bars) {
            // A column from the zero line to the value, rounded at the data end, red when below zero.
            const top = Math.min(y(v), y(0)), height = Math.max(Math.abs(y(v) - y(0)), 1);
            col.append(svgEl("rect", { class: "nw-bar" + (v < 0 ? " neg" : "") + " grow", style: `--i:${i}`, x: xAt(i) - barW / 2, y: top, width: barW, height, rx: 3 }));
        } else {
            col.append(svgEl("line", { class: "nw-cross", x1: xAt(i), x2: xAt(i), y1: NW_TOP, y2: floor }));
            if (i === lastIndex) col.append(svgEl("circle", { class: "nw-halo", cx: xAt(i), cy: y(v), r: 11 }));
            // The area chart keeps only the latest dot; the line chart has one for every month.
            if (nwChartType === "line" || i === lastIndex) {
                col.append(svgEl("circle", { class: "nw-dot" + (i === lastIndex ? " latest" : "") + (grow ? " grow" : ""),
                    cx: xAt(i), cy: y(v), r: i === lastIndex ? 6 : (band < 16 ? 3 : 4.5), style: `--i:${i}` }));
            }
        }

        // A wide invisible target around each month, so it is easy to point at.
        const hit = svgEl("rect", { class: "nw-hit", x: NW_LEFT + band * i, y: NW_TOP - 8, width: band, height: plotH + 16,
            tabindex: "0", "aria-label": `${p.label}: net worth ${rupees(v)}` });
        const tip = { value: rupees(v), name: isQuarterly ? "Quarter ending " + p.label : p.label,
            notes: [`Assets ${rupees2(p.assets)} · Liabilities ${rupees2(p.liabilities)}`] };
        hit.addEventListener("pointermove", e => showTooltip(tip, e.clientX, e.clientY));
        hit.addEventListener("pointerleave", hideTooltip);
        hit.addEventListener("focus", () => showTooltipAt(hit, tip));
        hit.addEventListener("blur", hideTooltip);
        col.append(hit);
        svg.append(col);

        // A faint line where the year changes, and the year written under the first label of each year.
        const [month, year] = p.label.split(" ");
        const previousYear = i > 0 ? series[i - 1].label.split(" ")[1] : null;
        if (previousYear !== null && previousYear !== year) {
            svg.append(svgEl("line", { class: "nw-yearline", x1: xAt(i) - band / 2, x2: xAt(i) - band / 2, y1: NW_TOP, y2: floor + 6 }));
        }
        // With many points only some are labelled, always ending with the latest.
        if ((lastIndex - i) % labelEvery === 0) {
            svg.append(svgEl("text", { class: "nw-month", x: xAt(i), y: NW_H - NW_BOTTOM + 18 }, month));
            const shownBefore = i - labelEvery >= 0 ? series[i - labelEvery].label.split(" ")[1] : null;
            if (shownBefore !== year) {
                svg.append(svgEl("text", { class: "nw-year", x: xAt(i), y: NW_H - NW_BOTTOM + 31 }, year));
            }
        }
    });

    // Only the latest value is written on the chart, in a small pill above its point.
    const text = compactRupees(values[lastIndex]);
    const pillW = text.length * 7.4 + 18, pillH = 22;
    const pillX = Math.min(Math.max(xAt(lastIndex) - pillW / 2, NW_LEFT), NW_W - NW_RIGHT - pillW);
    const pillY = Math.max(y(Math.max(values[lastIndex], 0) || values[lastIndex]) - pillH - 12, 2);
    svg.append(svgEl("rect", { class: "nw-pill" + (values[lastIndex] < 0 ? " neg" : ""), x: pillX, y: pillY, width: pillW, height: pillH, rx: 11 }));
    svg.append(svgEl("text", { class: "nw-pill-text", x: pillX + pillW / 2, y: pillY + 15 }, text));
    return svg;
}

/* The months as a table, a block per year, newest first. The latest year is open; click an older one to open it. */
function netWorthTable(series) {
    const wrap = el("div", { className: "nw-years" });
    const byYear = new Map();
    for (const p of series) {
        const year = p.label.split(" ")[1];
        if (!byYear.has(year)) byYear.set(year, []);
        byYear.get(year).push(p);
    }
    let first = true;
    for (const [year, rows] of [...byYear].reverse()) {
        const section = el("details", { open: first || byYear.size === 1 },
            el("summary", {}, el("span", { textContent: year }),
                el("span", { className: "sub", textContent: `${rows.length} ${rows.length === 1 ? "month" : "months"} · ends at ${rupees(rows[rows.length - 1].netWorth)}` })));
        const table = el("table", { className: "chart-table" });
        table.append(el("thead", {}, el("tr", {}, el("th", { textContent: "Month" }),
            el("th", { className: "num", textContent: "Net worth" }), el("th", { className: "num", textContent: "Assets" }),
            el("th", { className: "num", textContent: "Liabilities" }))));
        const body = el("tbody");
        for (const p of [...rows].reverse()) {
            body.append(el("tr", {}, el("td", { textContent: p.label }), el("td", { className: "num", textContent: rupees(p.netWorth) }),
                el("td", { className: "num", textContent: rupees(p.assets) }), el("td", { className: "num", textContent: rupees(p.liabilities) })));
        }
        table.append(body);
        section.append(table);
        wrap.append(section);
        first = false;
    }
    return wrap;
}

/* The graph and the allocation donut are the two faces of one card; the two buttons above it turn it over. The face turned away is
   inert, so it cannot be clicked or tabbed to. Each face draws itself again as it comes round. */
let nwFace = "graph";
function showNetWorthFace(face) {
    if (face === nwFace) return;
    nwFace = face;
    $("nwFlip").classList.toggle("flipped", face === "allocation");
    $("nwFaceGraph").inert = face !== "graph";
    $("nwFaceAllocation").inert = face !== "allocation";
    for (const chip of document.querySelectorAll("#nwFaces [data-face]")) {
        const on = chip.dataset.face === face;
        chip.classList.toggle("active", on);
        chip.setAttribute("aria-pressed", String(on));
    }
    // Start the drawing when the face is about half way round, so it is seen from the start.
    setTimeout(() => {
        if (nwFace !== face) return;
        if (face === "allocation") document.querySelectorAll("#nwAllocation .donut").forEach(ring => replay(ring, "sweep"));
        else renderNetWorth();
    }, 250);
}

export function initNetWorth() {
    $("nwFaces").addEventListener("click", e => {
        const chip = e.target.closest("[data-face]");
        if (chip) showNetWorthFace(chip.dataset.face);
    });
    $("nwZoom").addEventListener("click", e => {
        const chip = e.target.closest("[data-zoom]");
        if (!chip) return;
        nwZoom = chip.dataset.zoom;
        renderNetWorth();
    });
    $("nwTypes").addEventListener("click", e => {
        const chip = e.target.closest("[data-type]");
        if (!chip) return;
        nwChartType = chip.dataset.type;
        renderNetWorth();
    });
    // The chart is as wide as its panel, so it is drawn again when the window changes size.
    let resizeTimer = null;
    window.addEventListener("resize", () => {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => { if (wsView === "networth") renderNetWorth(); }, 150);
    });
    $("nwTableToggle").addEventListener("click", () => { nwShowTable = !nwShowTable; renderNetWorth(); });
}
