/* Charts panel: one donut per kind (income, expense), scoped by the Stats period.
   Part-to-whole: every category is its own slice (no "Other"), biggest all-time first, in 12 colours that come round again. */

import { NOT_SPENDING_KEYS } from "../core/constants.js";
import { allCategories } from "../core/state.js";
import { rupeeTransactions } from "../core/currency.js";
import { hideTooltip, showTooltip, showTooltipAt } from "../core/tooltip.js";
import { $, el, growsOnce, rupees } from "../core/util.js";
import { compactRupees } from "./net-worth.js";
import { monthShort } from "./period-filter.js";
import { renderStatsPeriod, statsInPeriod, statsMonthKeys, statsPeriodIn, statsRange } from "./stats-period.js";
import { showTransactionsFor } from "./transactions.js";

const PALETTE_SIZE = 12;               // the colours --s0..--s11 in css/charts.css; a 13th part starts again at the first
/* The css class of the colour of the part at this place in the ranking ("s0".."s11"). */
export const paletteClass = i => "s" + (i % PALETTE_SIZE);
export const TAU = Math.PI * 2;
const SVG_NS = "http://www.w3.org/2000/svg";

/* Loans Given and Loans Collected are lending and getting money back, and a Balance Adjustment only corrects a balance: none is
   spending or earning. (Liability Payments is real spending and stays in.) */
function isLoanTransaction(t) {
    const c = allCategories.find(c => c.id === t.categoryId);
    return !!(c && NOT_SPENDING_KEYS.has(c.systemKey));
}

/* Sub categories roll up into their parent. */
function topLevelCategory(id) {
    const c = allCategories.find(c => c.id === id);
    if (!c) return null;
    const parent = c.parentId != null && allCategories.find(p => p.id === c.parentId);
    return parent || c;
}

/* Colour follows the category, not the view. Slots come from the all-time ranking, so changing the
   period never repaints a category. Every category with a transaction has a slot. */
function slotRanking(kind) {
    const totals = new Map();
    for (const t of rupeeTransactions()) {
        if (t.kind !== kind || isLoanTransaction(t)) continue;
        const c = topLevelCategory(t.categoryId);
        if (!c) continue;
        totals.set(c.id, (totals.get(c.id) || 0) + Number(t.amount));
    }
    return [...totals.entries()].sort((a, b) => b[1] - a[1] || a[0] - b[0]).map(([id]) => id);
}

/* Totals per slice for the period in effect. Slices are ordered by the amount in this period, biggest first (a tie by slot), with
   transactions that have no category ("so") last. The colour still comes from the slot, so it never changes with the period. */
function breakdown(kind) {
    const slots = slotRanking(kind);
    const groups = new Map();
    let total = 0;
    for (const t of rupeeTransactions()) {
        if (t.kind !== kind || !statsInPeriod(t) || isLoanTransaction(t)) continue;
        const amount = Number(t.amount);
        total += amount;
        const c = topLevelCategory(t.categoryId);
        const slot = c ? slots.indexOf(c.id) : -1;
        const key = slot >= 0 ? "s" + slot : "so";
        let g = groups.get(key);
        if (!g) groups.set(key, g = { key, slot, colour: slot >= 0 ? paletteClass(slot) : "so", name: slot >= 0 ? c.name : "Uncategorised", amount: 0, count: 0 });
        g.amount += amount;
        g.count++;
    }
    const slices = [...groups.values()].sort((a, b) => (a.slot < 0) - (b.slot < 0) || b.amount - a.amount || a.slot - b.slot);
    return { slices, total };
}

export function svgEl(tag, attrs = {}, text) {
    const node = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
    if (text !== undefined) node.textContent = text;
    return node;
}

/* A stroked arc along the middle of the ring, from angle a0 to a1 (radians, clockwise from 12 o'clock). */
export function arcLine(cx, cy, r, a0, a1) {
    const large = a1 - a0 > Math.PI ? 1 : 0;
    const pt = a => [cx + r * Math.sin(a), cy - r * Math.cos(a)];
    const [x0, y0] = pt(a0), [x1, y1] = pt(a1);
    return `M${x0} ${y0} A${r} ${r} 0 ${large} 1 ${x1} ${y1}`;
}

export const shareText = share => share > 0 && share < 1 ? "<1%" : Math.round(share) + "%";

/* ---------- tooltip: value first, name second, through the shared tooltip ---------- */

function sliceTip(slice, total) {
    const share = total ? slice.amount / total * 100 : 0;
    return { value: rupees(slice.amount),
        name: `${slice.name} · ${shareText(share)} · ${slice.count} ${slice.count === 1 ? "transaction" : "transactions"}`, notes: [] };
}

/* ---------- month by month for one category (opens under its table row) ---------- */

const CAT_W = 420, CAT_H = 250, CAT_TOP = 30, CAT_BOTTOM = 30;
const drilled = {};                      // kind -> slice key whose line graph replaces that donut. Survives redraws.
const drilledSub = {};                   // kind -> id of a sub category inside that slice, one level deeper.

/* Whether a transaction belongs to a slice ("s0", "s1", ..., or "so" for no category). */
function inSlice(kind, key) {
    const slots = slotRanking(kind);
    return tx => {
        const c = topLevelCategory(tx.categoryId);
        const slot = c ? slots.indexOf(c.id) : -1;
        return (slot >= 0 ? "s" + slot : "so") === key;
    };
}

/* The sub categories inside one slice for the period in effect. Transactions filed on the category itself get
   their own "Directly in ..." line. Empty for transactions with no category. */
function subBreakdown(kind, slice) {
    if (slice.slot < 0) return [];
    const parentId = slotRanking(kind)[slice.slot];
    const groups = new Map();
    for (const tx of rupeeTransactions()) {
        if (tx.kind !== kind || !statsInPeriod(tx) || isLoanTransaction(tx)) continue;
        const c = topLevelCategory(tx.categoryId);
        if (!c || c.id !== parentId) continue;
        let g = groups.get(tx.categoryId);
        if (!g) groups.set(tx.categoryId, g = { id: tx.categoryId, amount: 0,
            name: tx.categoryId === parentId ? `Directly in ${slice.name}` : allCategories.find(x => x.id === tx.categoryId).name });
        g.amount += Number(tx.amount);
    }
    return [...groups.values()].sort((a, b) => b.amount - a.amount || a.id - b.id);
}

/* The months of whatever `matches` picks, oldest first, for the period in effect. The graph starts at the first month that
   has anything (nothing is drawn before it) and ends at the period's last month. */
function categoryMonths(matches) {
    const mine = rupeeTransactions().filter(matches);
    const firstKey = mine.length ? mine.reduce((min, tx) => tx.date < min ? tx.date : min, mine[0].date).slice(0, 7) : null;
    const months = statsMonthKeys(firstKey).map(key => ({ key, label: monthShort(key), year: Number(key.slice(0, 4)), amount: 0, count: 0 }));
    const byKey = new Map(months.map(m => [m.key, m]));
    for (const tx of mine) {
        const m = byKey.get(tx.date.slice(0, 7));
        if (m) { m.amount += Number(tx.amount); m.count++; }
    }
    return months;
}

/* slice gives the colour; name and matches say what is plotted. */
function categoryBars(kind, slice, name, matches) {
    const months = categoryMonths(tx => tx.kind === kind && !isLoanTransaction(tx) && matches(tx));
    const max = months.length ? Math.max(...months.map(m => m.amount)) : 0;
    const wrap = el("div", { className: `cat-bars ${slice.colour}` });
    if (max <= 0) {
        wrap.append(el("div", { className: "empty-state", textContent: `Nothing in ${name} ${statsPeriodIn()}.` }));
        return wrap;
    }
    const grow = growsOnce(`cat-${kind}-${slice.key}-${name}-${months.length}`);
    const slot = CAT_W / months.length, labelEvery = Math.max(1, Math.ceil(34 / slot)), showValues = months.length <= 8, plotH = CAT_H - CAT_TOP - CAT_BOTTOM, base = CAT_TOP + plotH;
    const px = i => i * slot + slot / 2;
    const py = v => base - v / max * plotH;
    const svg = svgEl("svg", { class: "cat-chart", viewBox: `0 0 ${CAT_W} ${CAT_H}`, role: "img",
        "aria-label": `${name} by month. ` + months.map(m => `${m.label} ${rupees(m.amount)}`).join(", ") });
    svg.append(svgEl("line", { class: "cat-base", x1: 0, x2: CAT_W, y1: base, y2: base }));
    const points = months.map((m, i) => `${px(i)} ${py(m.amount)}`);
    svg.append(svgEl("path", { class: "cat-area" + (grow ? " grow" : ""),
        d: `M${px(0)} ${base} L${points.join(" L")} L${px(months.length - 1)} ${base} Z` }));
    svg.append(svgEl("path", { class: "cat-line" + (grow ? " grow" : ""), pathLength: "1", d: `M${points.join(" L")}` }));
    months.forEach((m, i) => {
        const tip = { value: rupees(m.amount), name: `${name} · ${m.label} ${m.year} · ${m.count} ${m.count === 1 ? "transaction" : "transactions"}`, notes: [] };
        const g = svgEl("g", { class: "cat-col", tabindex: "0", "aria-label": `${m.label} ${m.year}, ${rupees(m.amount)}` });
        g.append(svgEl("line", { class: "cat-cross", x1: px(i), x2: px(i), y1: CAT_TOP - 8, y2: base }));
        g.append(svgEl("circle", { class: "cat-dot", cx: px(i), cy: py(m.amount), r: slot < 16 ? 3 : 4.5 }));
        if (m.amount > 0 && (showValues || i === months.length - 1)) g.append(svgEl("text", { class: "cat-value", x: px(i), y: py(m.amount) - 10 }, compactRupees(m.amount)));
        if ((months.length - 1 - i) % labelEvery === 0) {
            g.append(svgEl("text", { class: "cat-month", x: px(i), y: CAT_H - 14 }, m.label));
            if (i === (months.length - 1) % labelEvery || m.label === "Jan") {
                g.append(svgEl("text", { class: "cat-year", x: px(i), y: CAT_H - 3 }, String(m.year)));
            }
        }
        g.append(svgEl("rect", { class: "cat-hit", x: i * slot, y: 0, width: slot, height: CAT_H }));   // a wide target
        g.addEventListener("pointermove", e => showTooltip(tip, e.clientX, e.clientY));
        g.addEventListener("pointerleave", hideTooltip);
        g.addEventListener("focus", () => showTooltipAt(g, tip));
        g.addEventListener("blur", hideTooltip);
        svg.append(g);
    });
    wrap.append(el("div", { className: "cat-caption", textContent: months.length === 1 ? `${name} · one month of data so far` : `${name} · by month, ${months[0].label} ${months[0].year} to ${months[months.length - 1].label} ${months[months.length - 1].year}` }), svg);
    return wrap;
}

/* ---------- one chart card ---------- */

export const RING_WIDTH = 24, RING_RADIUS = 84, RING_GAP = 4;      // a 24px ring, 4px of air between slices

function shortName(name) {
    return name.length > 18 ? name.slice(0, 17) + "…" : name;
}

function renderDonut(box, kind, title) {
    box.innerHTML = "";
    const { slices, total } = breakdown(kind);
    box.append(el("h3", { className: "chart-title", textContent: title }));
    if (total === 0) {
        box.append(el("div", { className: "empty-state", textContent: `No ${kind.toLowerCase()} in this period.` }));
        return;
    }
    if (drilled[kind] && !slices.some(s => s.key === drilled[kind])) delete drilled[kind];   // it has no data in this period
    const drill = slices.find(s => s.key === drilled[kind]);
    const subs = drill ? subBreakdown(kind, drill) : [];
    const deeper = subs.length > 1;             // a category with just one line has nothing more to show
    if (drilledSub[kind] != null && !(deeper && subs.some(x => x.id === drilledSub[kind]))) delete drilledSub[kind];
    if (!drill) delete drilledSub[kind];
    const drillSub = subs.find(x => x.id === drilledSub[kind]);
    box.append(el("div", { className: "chart-sub", textContent: `${slices.length} ${slices.length === 1 ? "category" : "categories"} · transfers and loans are not counted` }));

    const cx = 140, cy = 140;
    const outer = RING_RADIUS + RING_WIDTH / 2;
    const cap = RING_WIDTH / 2;                              // a round end sticks out by half the ring width
    const summary = slices.map(s => `${s.name} ${rupees(s.amount)}`).join(", ");
    const svg = svgEl("svg", { class: "donut sweep", viewBox: "0 0 280 280", role: "img",
        "aria-label": `${title}. Total ${rupees(total)}. ${summary}` });

    svg.append(svgEl("circle", { class: "donut-trace", cx, cy, r: RING_RADIUS, pathLength: "1" }));   // the thin circle drawn first (css/motion.css)
    const sliceEls = new Map(), rowEls = new Map();
    const centerLabel = svgEl("text", { class: "center-label", x: cx, y: cy - 6 }, `Total ${kind.toLowerCase()}`);
    const centerValue = svgEl("text", { class: "center-value", x: cx, y: cy + 20 }, rupees(total));

    // Hovering or focusing a slice or its row brings that slice forward, softens the rest and shows it in the middle.
    const activate = s => {
        svg.classList.add("has-active");
        sliceEls.forEach((slice, key) => slice.classList.toggle("active", key === s.key));
        rowEls.forEach((row, key) => row.classList.toggle("row-active", key === s.key));
        centerLabel.textContent = shortName(s.name);
        centerValue.textContent = rupees(s.amount);
    };
    const deactivate = () => {
        svg.classList.remove("has-active");
        sliceEls.forEach(slice => slice.classList.remove("active"));
        rowEls.forEach(row => row.classList.remove("row-active"));
        centerLabel.textContent = `Total ${kind.toLowerCase()}`;
        centerValue.textContent = rupees(total);
    };

    let angle = 0;
    for (const s of slices) {
        const share = s.amount / total * 100;
        const sweep = s.amount / total * TAU;
        // Round ends need room: a slice too short for them gets flat ends instead.
        const round = slices.length > 1 && sweep * RING_RADIUS > 2 * (cap + RING_GAP) + 4;
        const pad = ((round ? cap : 0) + RING_GAP / 2) / RING_RADIUS;
        const attrs = { class: `slice ${s.colour}${round ? "" : " flat"}`, style: `--i:${sliceEls.size}`, tabindex: "0",
            "aria-label": `${s.name}, ${rupees(s.amount)}, ${shareText(share)}` };
        const slice = slices.length === 1
            ? svgEl("circle", { ...attrs, cx, cy, r: RING_RADIUS })
            : svgEl("path", { ...attrs, d: arcLine(cx, cy, RING_RADIUS, angle + pad, angle + sweep - pad) });
        slice.addEventListener("pointermove", e => { activate(s); showTooltip(sliceTip(s, total), e.clientX, e.clientY); });
        slice.addEventListener("pointerleave", () => { deactivate(); hideTooltip(); });
        slice.addEventListener("focus", () => { activate(s); showTooltipAt(slice, sliceTip(s, total)); });
        slice.addEventListener("blur", () => { deactivate(); hideTooltip(); });
        sliceEls.set(s.key, slice);
        svg.append(slice);

        if (share >= 8) {   // selective direct labels: only slices big enough to carry one
            const mid = angle + sweep / 2;
            const lx = cx + (outer + 12) * Math.sin(mid), ly = cy - (outer + 12) * Math.cos(mid);
            const anchor = lx > cx + 6 ? "start" : lx < cx - 6 ? "end" : "middle";
            svg.append(svgEl("text", { class: "pct", x: lx, y: ly + 4, "text-anchor": anchor }, shareText(share)));
        }
        angle += sweep;
    }
    svg.append(centerLabel, centerValue);
    if (drill) {
        box.append(drillSub
            ? categoryBars(kind, drill, `${drill.name} › ${drillSub.name}`, tx => tx.categoryId === drillSub.id)
            : categoryBars(kind, drill, drill.name, inSlice(kind, drill.key)));
        const back = el("button", { className: "cat-back", type: "button", textContent: "← Back" });
        back.addEventListener("click", () => {
            if (drillSub) delete drilledSub[kind]; else delete drilled[kind];       // one level up
            hideTooltip();
            renderDonut(box, kind, title);
        });
        const parentId = drill.slot >= 0 ? slotRanking(kind)[drill.slot] : null;       // no category: nothing to jump to
        const category = drillSub ? (drillSub.id === parentId ? null : drillSub.id) : parentId;   // "Directly in" has no category of its own to pick
        const jump = category == null ? null : el("button", { className: "cat-back", type: "button", textContent: "🧾 View transactions" });
        if (jump) jump.addEventListener("click", () => {
            const { from, to } = statsRange();
            showTransactionsFor({ category, kind, from, to });
        });
        box.prepend(...(jump ? [back, jump] : [back]));
    } else {
        box.append(svg);
    }

    // The table is both the legend and the table view: every value is readable without hovering.
    // Inside a category that has sub categories, it lists those instead.
    const rowsOf = deeper ? subs : slices;
    const rowsTotal = deeper ? drill.amount : total;
    const table = el("table", { className: "chart-table" });
    table.append(el("thead", {}, el("tr", {},
        el("th", { textContent: deeper ? `${drill.name} · sub categories` : "Category" }), el("th", { className: "num", textContent: "Amount" }),
        el("th", { className: "num", textContent: "Share" }))));
    const body = el("tbody");
    for (const s of rowsOf) {
        const colour = deeper ? drill.colour : s.colour;
        const selected = deeper ? drillSub && drillSub.id === s.id : drill && drill.key === s.key;
        const name = el("td", {}, el("span", { className: `swatch ${colour}` }), s.name);
        const row = el("tr", { className: "cat-row" + (selected ? " selected" : ""), tabIndex: 0,
            title: "Show month by month" }, name,
            el("td", { className: "num", textContent: rupees(s.amount) }),
            el("td", { className: "num", textContent: shareText(s.amount / rowsTotal * 100) }));
        const show = () => {
            if (deeper) drilledSub[kind] = s.id; else drilled[kind] = s.key;
            hideTooltip();
            renderDonut(box, kind, title);
        };
        row.addEventListener("click", show);
        row.addEventListener("keydown", e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); show(); } });
        if (!deeper) {
            row.addEventListener("pointerenter", () => activate(s));
            row.addEventListener("pointerleave", deactivate);
            rowEls.set(s.key, row);
        }
        body.append(row);
    }
    table.append(body, el("tfoot", {}, el("tr", {}, el("td", { textContent: "Total" }),
        el("td", { className: "num", textContent: rupees(rowsTotal) }), el("td", { className: "num", textContent: "100%" }))));
    box.append(table);
}

export function renderCharts() {
    renderStatsPeriod();
    renderDonut($("incomeChart"), "INCOME", "Income by category");
    renderDonut($("expenseChart"), "EXPENSE", "Expenses by category");
}
