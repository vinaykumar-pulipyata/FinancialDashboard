/* Stats for Mutual Funds and Shares: a line graph of the whole panel or of one fund, from the day its first value was
   recorded (nothing is drawn before that). Two lines: Current value, and Invested as a dashed grey line. Hovering or
   tabbing to a point shows its date, value and gain; "View as table" lists the same numbers. */

import { portfolios, portfolioStats, transactions } from "../core/state.js";
import { hideTooltip, showTooltip, showTooltipAt } from "../core/tooltip.js";
import { el, growsOnce, rupees, rupees2 } from "../core/util.js";
import { renderAccounts } from "./accounts.js";
import { svgEl } from "./charts.js";
import { compactRupees, niceTicks } from "./net-worth.js";
import { gainClass, PORTFOLIO_KINDS, signedRupees } from "./portfolio.js";

const PS_LEFT = 58, PS_RIGHT = 26, PS_TOP = 34, PS_BOTTOM = 42;

/* The graphs fill the width of the card. An svg scales to its box, so it is drawn on a canvas about as wide as the box will be (the
   page's width, less the margins and the card's padding): the text stays its own size instead of growing with the box, and the
   height follows the width. Without a window (a test page) it is the old 720 by 230. */
function psWidth() {
    const bar = typeof document !== "undefined" ? document.documentElement.dataset.sidebar : undefined;   // the sidebar's width (css/header-menu.css) is not part of the page
    const page = typeof window !== "undefined" && window.innerWidth ? window.innerWidth - (bar === "collapsed" ? 56 : bar === "open" ? 224 : 0) : 0;
    return page ? Math.max(520, Math.min(1280, Math.round(Math.min(1400, page * 0.94) - 110))) : 720;
}
const psHeight = width => Math.max(230, Math.min(340, Math.round(width * 0.25)));
export const psView = { MUTUAL_FUNDS: "list", SHARES: "list" };        // "list", "stats" or "allocation"
const psPick = { MUTUAL_FUNDS: "all", SHARES: "all" };          // "all" or a holding id
const psTable = { MUTUAL_FUNDS: false, SHARES: false };

/* The graph's own date range (not the shared one, which the Accounts view hides): a preset counted back from today, or a From / To the person typed. */
const PS_RANGES = [["6M", "6 months", 6], ["1Y", "1 year", 12], ["3Y", "3 years", 36], ["5Y", "5 years", 60], ["ALL", "All", 0]];
const psRange = { MUTUAL_FUNDS: { preset: "ALL", from: "", to: "" }, SHARES: { preset: "ALL", from: "", to: "" } };

const psIso = d => d.toLocaleDateString("en-CA");

/* The first and last day (either may be "" = no limit) of a range choice. */
function psBounds(range, today = new Date()) {
    if (range.preset === "CUSTOM") return { from: range.from || "", to: range.to || "" };
    const months = (PS_RANGES.find(r => r[0] === range.preset) || [])[2];
    if (!months) return { from: "", to: "" };
    const from = new Date(today.getFullYear(), today.getMonth() - months, today.getDate());
    if (from.getDate() !== today.getDate()) from.setDate(0);   // a short month: its last day
    return { from: psIso(from), to: "" };
}

const psInRange = (kind, t) => {
    const { from, to } = psBounds(psRange[kind]);
    return (!from || t.date >= from) && (!to || t.date <= to);
};

/* "in the last 6 months", "so far", "from 3 Jan 2024 to 9 Feb 2025": ends a sentence about what the graph covers. */
function psRangeText(kind) {
    const r = psRange[kind];
    if (r.preset === "CUSTOM") {
        if (r.from && r.to) return `from ${psFullDay(r.from)} to ${psFullDay(r.to)}`;
        if (r.from) return `from ${psFullDay(r.from)}`;
        if (r.to) return `up to ${psFullDay(r.to)}`;
        return "so far";
    }
    const named = PS_RANGES.find(x => x[0] === r.preset);
    return !named || !named[2] ? "so far" : "in the last " + named[1];
}

const psDay = iso => new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { day: "numeric", month: "short" });
const psFullDay = iso => new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });

/* Everything recorded for the current choice: the whole panel, or one fund. Empty when nothing is recorded. */
function psRecorded(kind) {
    const stats = portfolioStats[kind];
    if (psPick[kind] === "all") return stats.overall;
    const one = stats.holdings.find(h => String(h.id) === String(psPick[kind]));
    return one ? one.points : [];
}

/* The points inside the range chosen above the graph. */
function psPoints(kind) {
    return psRecorded(kind).filter(p => psInRange(kind, p));
}

function psName(kind) {
    if (psPick[kind] === "all") return `All ${PORTFOLIO_KINDS[kind].label.toLowerCase()}`;
    const one = portfolioStats[kind].holdings.find(h => String(h.id) === String(psPick[kind]));
    return one ? one.name : "";
}

/* Dividends for the current choice (all holdings of the card, or one), inside the period, oldest first. Each is one
   income transaction linked to the holding that paid it. */
function psDividends(kind) {
    const names = new Map(portfolios[kind].holdings.map(h => [h.id, h.name]));
    return transactions
        .filter(t => t.kind === "INCOME" && t.holdingId != null && names.has(t.holdingId) && psInRange(kind, t)
            && (psPick[kind] === "all" || String(t.holdingId) === String(psPick[kind])))
        .map(t => ({ date: t.date, amount: Number(t.amount), name: names.get(t.holdingId) }))
        .sort((a, b) => a.date < b.date ? -1 : a.date > b.date ? 1 : 0);
}

/* The whole stats view for a card: the picker, the legend, then the graph or the table. */
export function portfolioStatsView(kind) {
    const cfg = PORTFOLIO_KINDS[kind];
    if (psPick[kind] !== "all" && !portfolioStats[kind].holdings.some(h => String(h.id) === String(psPick[kind]))) psPick[kind] = "all";
    const box = el("div", { className: "ps-view" });

    const select = el("select", { className: "ps-select" },
        el("option", { value: "all", textContent: `All ${cfg.label.toLowerCase()}` }),
        ...portfolioStats[kind].holdings.map(h => el("option", { value: h.id, textContent: h.name })));
    select.value = psPick[kind];
    select.setAttribute("aria-label", `Choose ${cfg.one}`);
    select.addEventListener("change", () => { psPick[kind] = select.value; renderAccounts(); });

    const points = psPoints(kind);
    const controls = el("div", { className: "ps-controls" }, select);
    if (points.length) {
        const toggle = el("button", { className: "btn btn-secondary btn-small", type: "button",
            textContent: psTable[kind] ? "View as chart" : "View as table" });
        toggle.addEventListener("click", () => { psTable[kind] = !psTable[kind]; renderAccounts(); });
        controls.append(toggle);
    }
    box.append(controls, psRangeBar(kind));

    const dividends = PORTFOLIO_KINDS[kind].dividends ? psDividends(kind) : null;
    if (!points.length) {
        box.append(el("div", { className: "lg-empty", textContent: psRecorded(kind).length
            ? `No value was recorded ${psRangeText(kind)}. Pick a longer range.`
            : "No values recorded yet. The graph starts when a value is first saved." }));
        if (dividends) box.append(psDividendView(kind, dividends));
        return box;
    }
    if (psTable[kind]) {
        box.append(psTableFor(points));
        if (dividends) box.append(psDividendTable(kind, dividends));
        return box;
    }
    box.append(el("div", { className: "ps-legend" },
        el("span", {}, el("i", { className: "ps-key ps-key-value" }), "Current value"),
        el("span", {}, el("i", { className: "ps-key ps-key-invested" }), "Invested")));
    box.append(psChart(kind, points));
    const first = points[0].date;
    box.append(el("div", { className: "ps-note", textContent: points.length === 1
        ? `Only one value is recorded ${psRangeText(kind)} (${psFullDay(first)}). The line needs at least two.`
        : `Graph ${psRangeText(kind)}: starts ${psFullDay(first)}, the first value recorded${psPick[kind] === "all" ? "" : " for this " + cfg.one}. Every update adds a point.` }));
    if (dividends) box.append(psDividendView(kind, dividends));
    return box;
}

/* The range buttons (6 months, 1 year, 3 years, 5 years, All) and the From / To boxes; typing a date makes the range custom. */
function psRangeBar(kind) {
    const r = psRange[kind];
    const bar = el("div", { className: "ps-range" });
    for (const [key, label] of PS_RANGES) {
        const b = el("button", { className: "chip" + (r.preset === key ? " active" : ""), type: "button", textContent: key === "ALL" ? "All" : key });
        b.title = key === "ALL" ? "Everything recorded" : "The last " + label;
        b.addEventListener("click", () => { r.preset = key; r.from = ""; r.to = ""; renderAccounts(); });
        bar.append(b);
    }
    const bounds = psBounds(r);
    const from = el("input", { type: "date", value: bounds.from, className: "ps-date-input" });
    const to = el("input", { type: "date", value: bounds.to, className: "ps-date-input" });
    from.setAttribute("aria-label", "From");
    to.setAttribute("aria-label", "To");
    const custom = () => {
        r.preset = "CUSTOM";
        r.from = from.value;
        r.to = to.value;
        if (r.from && r.to && r.from > r.to) {   // the date just typed wins and the other one moves to meet it
            if (document.activeElement === from) r.to = r.from; else r.from = r.to;
        }
        renderAccounts();
    };
    from.addEventListener("change", custom);
    to.addEventListener("change", custom);
    bar.append(el("span", { className: "ps-range-dates" }, "From ", from, " To ", to));
    return bar;
}

/* Dividends received, as their own small graph: a running total that steps up with each dividend. It has its own scale
   because dividends are small next to what the shares are worth. */
function psDividendView(kind, dividends) {
    const PS_W = psWidth(), PD_H = Math.round(psHeight(PS_W) * 0.8);
    const box = el("div", { className: "ps-dividends" }, el("h4", { className: "ps-subtitle", textContent: "Dividends received" }));
    if (!dividends.length) {
        box.append(el("div", { className: "ps-note", textContent: `No dividends recorded ${psRangeText(kind)}.` }));
        return box;
    }
    let running = 0;
    const byDay = new Map();
    for (const d of dividends) {
        running += d.amount;
        const entry = byDay.get(d.date) || { date: d.date, paid: 0, total: 0, names: [] };
        entry.paid += d.amount;
        entry.total = running;
        if (!entry.names.includes(d.name)) entry.names.push(d.name);
        byDay.set(d.date, entry);
    }
    const points = [...byDay.values()];
    const { ticks, max } = niceTicks(0, running, 2, true);
    const plotW = PS_W - PS_LEFT - PS_RIGHT, plotH = PD_H - PS_TOP - PS_BOTTOM;
    const y = v => PS_TOP + (max - v) / (max || 1) * plotH;
    const t0 = new Date(points[0].date + "T00:00:00").getTime();
    const t1 = new Date(points[points.length - 1].date + "T00:00:00").getTime();
    const x = p => points.length === 1 ? PS_LEFT + plotW / 2
        : PS_LEFT + (new Date(p.date + "T00:00:00").getTime() - t0) / (t1 - t0) * plotW;
    const last = points.length - 1;
    const svg = svgEl("svg", { class: "ps-chart", viewBox: `0 0 ${PS_W} ${PD_H}`, role: "img",
        "aria-label": `Dividends received, ${rupees(running)} in total, from ${psFullDay(points[0].date)}.` });
    for (const tick of ticks) {
        svg.append(svgEl("line", { class: "ps-grid", x1: PS_LEFT, x2: PS_W - PS_RIGHT, y1: y(tick), y2: y(tick) }));
        svg.append(svgEl("text", { class: "ps-tick", x: PS_LEFT - 8, y: y(tick) + 4 }, compactRupees(tick)));
    }
    if (points.length > 1) {
        svg.append(svgEl("path", { class: "ps-div-line", d: "M" + points.map(p => `${x(p)} ${y(p.total)}`).join(" L") }));
    }
    let lastLabelX = -Infinity;
    points.forEach((p, i) => {
        const px = x(p);
        if (i === 0 || i === last || (px - lastLabelX >= 64 && x(points[last]) - px >= 64)) {
            svg.append(svgEl("text", { class: "ps-date", x: px, y: PD_H - 22 }, psDay(p.date)));
            svg.append(svgEl("text", { class: "ps-year", x: px, y: PD_H - 8 }, String(new Date(p.date + "T00:00:00").getFullYear())));
            lastLabelX = px;
        }
    });
    const slot = points.length === 1 ? plotW : plotW / Math.max(points.length - 1, 1);
    points.forEach((p, i) => {
        const g = svgEl("g", { class: "ps-col", tabindex: "0", "aria-label": `${psFullDay(p.date)}, ${rupees(p.paid)} paid, ${rupees(p.total)} received in total` });
        const tip = { value: rupees(p.total), name: `Dividends received to ${psFullDay(p.date)}`,
            notes: [`${rupees(p.paid)} paid on ${psDay(p.date)} by ${p.names.join(", ")}`] };
        g.append(svgEl("line", { class: "ps-cross", x1: x(p), x2: x(p), y1: PS_TOP - 8, y2: PS_TOP + plotH }));
        g.append(svgEl("circle", { class: "ps-div-dot", cx: x(p), cy: y(p.total), r: i === last ? 5.5 : 4.5 }));
        if (i === last) g.append(svgEl("text", { class: "ps-last", x: x(p), y: y(p.total) - 12 }, compactRupees(p.total)));
        const left = Math.max(x(p) - slot / 2, 0), width = Math.min(slot, PS_W - left);
        g.append(svgEl("rect", { class: "ps-hit", x: left, y: 0, width, height: PD_H }));
        g.addEventListener("pointermove", e => showTooltip(tip, e.clientX, e.clientY));
        g.addEventListener("pointerleave", hideTooltip);
        g.addEventListener("focus", () => showTooltipAt(g, tip));
        g.addEventListener("blur", hideTooltip);
        svg.append(g);
    });
    box.append(el("div", { className: "ps-wrap" }, svg),
        el("div", { className: "ps-note", textContent: points.length === 1
            ? `One dividend ${psRangeText(kind)} (${psFullDay(points[0].date)}). The line needs at least two.`
            : `${dividends.length} dividends ${psRangeText(kind)}, ${rupees2(running)} in total. The line steps up with each one.` }));
    return box;
}

function psDividendTable(kind, dividends) {
    const box = el("div", { className: "ps-dividends" }, el("h4", { className: "ps-subtitle", textContent: "Dividends received" }));
    if (!dividends.length) {
        box.append(el("div", { className: "ps-note", textContent: `No dividends recorded ${psRangeText(kind)}.` }));
        return box;
    }
    const table = el("table", { className: "chart-table ps-table" });
    table.append(el("thead", {}, el("tr", {}, el("th", { textContent: "Date" }), el("th", { textContent: "Paid by" }),
        el("th", { className: "num", textContent: "Amount" }))));
    const body = el("tbody");
    for (const d of [...dividends].reverse()) {
        body.append(el("tr", {}, el("td", { textContent: psFullDay(d.date) }), el("td", { textContent: d.name }),
            el("td", { className: "num", textContent: rupees2(d.amount) })));
    }
    table.append(body);
    box.append(table);
    return box;
}

function psTableFor(points) {
    const table = el("table", { className: "chart-table ps-table" });
    table.append(el("thead", {}, el("tr", {}, el("th", { textContent: "Date" }),
        el("th", { className: "num", textContent: "Invested" }), el("th", { className: "num", textContent: "Current value" }),
        el("th", { className: "num", textContent: "Gain" }))));
    const body = el("tbody");
    for (const p of [...points].reverse()) {
        const gain = Number(p.currentValue) - Number(p.investedAmount);
        body.append(el("tr", {}, el("td", { textContent: psFullDay(p.date) }),
            el("td", { className: "num", textContent: rupees2(p.investedAmount) }),
            el("td", { className: "num", textContent: rupees2(p.currentValue) }),
            el("td", { className: "num gain " + gainClass(gain), textContent: signedRupees(gain) })));
    }
    table.append(body);
    return table;
}

function psChart(kind, points) {
    const PS_W = psWidth(), PS_H = psHeight(PS_W);
    const name = psName(kind);
    const values = points.flatMap(p => [Number(p.currentValue), Number(p.investedAmount)]);
    const { ticks, min, max } = niceTicks(Math.min(...values), Math.max(...values), 3, false);
    const plotW = PS_W - PS_LEFT - PS_RIGHT, plotH = PS_H - PS_TOP - PS_BOTTOM;
    const y = v => PS_TOP + (max - v) / ((max - min) || 1) * plotH;
    const t0 = new Date(points[0].date + "T00:00:00").getTime();
    const t1 = new Date(points[points.length - 1].date + "T00:00:00").getTime();
    const x = p => points.length === 1 ? PS_LEFT + plotW / 2
        : PS_LEFT + (new Date(p.date + "T00:00:00").getTime() - t0) / (t1 - t0) * plotW;
    const last = points.length - 1;
    const grow = growsOnce(`ps-${kind}-${psPick[kind]}`);

    const svg = svgEl("svg", { class: "ps-chart", viewBox: `0 0 ${PS_W} ${PS_H}`, role: "img",
        "aria-label": `${name}: current value and invested amount from ${psFullDay(points[0].date)}. ` +
            `Latest ${rupees(points[last].currentValue)} against ${rupees(points[last].investedAmount)} invested.` });
    for (const tick of ticks) {
        svg.append(svgEl("line", { class: "ps-grid", x1: PS_LEFT, x2: PS_W - PS_RIGHT, y1: y(tick), y2: y(tick) }));
        svg.append(svgEl("text", { class: "ps-tick", x: PS_LEFT - 8, y: y(tick) + 4 }, compactRupees(tick)));
    }
    const line = key => points.map(p => `${x(p)} ${y(Number(p[key]))}`);
    if (points.length > 1) {
        svg.append(svgEl("path", { class: "ps-invested", d: `M${line("investedAmount").join(" L")}` }));   // no pathLength: it would stretch the dashes into one solid line
        svg.append(svgEl("path", { class: "ps-line" + (grow ? " grow" : ""), pathLength: "1", d: `M${line("currentValue").join(" L")}` }));
    }

    // Date labels: the first and last always, and the ones between only where there is room.
    let lastLabelX = -Infinity;
    points.forEach((p, i) => {
        const px = x(p);
        const show = i === 0 || i === last || (px - lastLabelX >= 64 && x(points[last]) - px >= 64);
        if (show) {
            svg.append(svgEl("text", { class: "ps-date", x: px, y: PS_H - 22 }, psDay(p.date)));
            if (i === 0 || new Date(p.date + "T00:00:00").getFullYear() !== new Date(points[Math.max(0, i - 1)].date + "T00:00:00").getFullYear()) {
                svg.append(svgEl("text", { class: "ps-year", x: px, y: PS_H - 8 }, String(new Date(p.date + "T00:00:00").getFullYear())));
            }
            lastLabelX = px;
        }
    });

    const slot = points.length === 1 ? plotW : plotW / Math.max(points.length - 1, 1);
    points.forEach((p, i) => {
        const g = svgEl("g", { class: "ps-col", tabindex: "0",
            "aria-label": `${psFullDay(p.date)}, worth ${rupees(p.currentValue)}, invested ${rupees(p.investedAmount)}` });
        const gain = Number(p.currentValue) - Number(p.investedAmount);
        const tip = { value: rupees(p.currentValue), name: `${name} · ${psFullDay(p.date)}`,
            notes: [`Invested ${rupees(p.investedAmount)} · ${gain < 0 ? "Loss" : "Gain"} ${rupees(Math.abs(gain))}`] };
        g.append(svgEl("line", { class: "ps-cross", x1: x(p), x2: x(p), y1: PS_TOP - 8, y2: PS_TOP + plotH }));
        g.append(svgEl("circle", { class: "ps-dot" + (i === last ? " latest" : ""), cx: x(p), cy: y(Number(p.currentValue)), r: i === last ? 5.5 : 4.5 }));
        g.append(svgEl("circle", { class: "ps-dot-invested", cx: x(p), cy: y(Number(p.investedAmount)), r: 3 }));
        if (i === last) {
            g.append(svgEl("text", { class: "ps-last", x: x(p), y: y(Number(p.currentValue)) - 12 }, compactRupees(Number(p.currentValue))));
        }
        const left = Math.max(x(p) - slot / 2, 0), width = Math.min(slot, PS_W - left);
        g.append(svgEl("rect", { class: "ps-hit", x: left, y: 0, width, height: PS_H }));
        g.addEventListener("pointermove", e => showTooltip(tip, e.clientX, e.clientY));
        g.addEventListener("pointerleave", hideTooltip);
        g.addEventListener("focus", () => showTooltipAt(g, tip));
        g.addEventListener("blur", hideTooltip);
        svg.append(g);
    });
    return el("div", { className: "ps-wrap" }, svg);
}
