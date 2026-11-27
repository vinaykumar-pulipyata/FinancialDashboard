/* Allocation donuts: a ring that splits a total into its parts, with a table beside it. Every part is its own slice, biggest
   first, and nothing is folded into an "Other"; the colours come round again after PALETTE_SIZE (12) parts. Used for
     - "Where your assets are": everything you own by kind of account (Cash, Bank accounts, Goal funds, Investments, Gold,
       Property, Vehicles), on the back of the net worth graph (the Graph / Allocation buttons turn the card over). Debt and money
       lent out are not in it (the debt is written below). Its amounts follow the eye at the top of the view: hidden until it is
       pressed, though the shares always show;
     - how a Mutual Funds or Shares card is split between its holdings, by current value (portfolio.js, the card's Allocation
       button).
   drawAllocation does the drawing for both. The ring draws itself in the same way as the Stats rings (.donut.sweep). */

import { hideTooltip, showTooltip, showTooltipAt } from "../core/tooltip.js";
import { $, el, rupees } from "../core/util.js";
import { arcLine, paletteClass, RING_GAP, RING_RADIUS, RING_WIDTH, shareText, svgEl, TAU } from "./charts.js";
import { amountsRevealed, assetsByKind, totalDebt } from "./summary.js";

const MASK = "₹ ••••••••";

/* The parts with something in them, biggest first, one slice each. key is unique ("s0", "s1", ...); colour is the css class of the
   slice's colour (the 12 colours are used in turn). total is their sum. */
export function allocationSlices(parts) {
    const kinds = parts.filter(p => p.amount > 0).sort((a, b) => b.amount - a.amount || a.label.localeCompare(b.label));
    const total = kinds.reduce((sum, k) => sum + k.amount, 0);
    const slices = kinds.map((k, i) => ({ key: "s" + i, colour: paletteClass(i), name: k.label, amount: k.amount }));
    return { slices, total };
}

/* Draws the ring and its table into `box` (an element with the class nw-allocation, which carries the slice colours and the layout).
   spec: { title, subtitle, totalLabel, headLabel, slices, total, money, empty, note }
     money(amount) writes an amount (it may mask it); empty is what to say when total is 0; note is an optional line under the table. */
export function drawAllocation(box, spec) {
    const { title, subtitle, totalLabel, headLabel, slices, total, money, empty, note } = spec;
    box.innerHTML = "";
    box.classList.remove("hidden");
    if (total <= 0) {
        box.append(el("div", { className: "empty-state", textContent: empty }));
        return;
    }
    box.append(el("h3", { className: "chart-title", textContent: title }), el("div", { className: "chart-sub", textContent: subtitle }));

    const cx = 140, cy = 140;
    const outer = RING_RADIUS + RING_WIDTH / 2, cap = RING_WIDTH / 2;
    const svg = svgEl("svg", { class: "donut sweep", viewBox: "0 0 280 280", width: "280", height: "280", role: "img",   // a size of its own, so it cannot collapse to nothing
        "aria-label": `${title}: ` + slices.map(s => `${s.name} ${shareText(s.amount / total * 100)}`).join(", ") });
    svg.append(svgEl("circle", { class: "donut-trace", cx, cy, r: RING_RADIUS, pathLength: "1" }));
    const sliceEls = new Map(), rowEls = new Map();
    const centerLabel = svgEl("text", { class: "center-label", x: cx, y: cy - 6 }, totalLabel);
    const centerValue = svgEl("text", { class: "center-value", x: cx, y: cy + 20 }, money(total));
    const activate = s => {
        svg.classList.add("has-active");
        sliceEls.forEach((slice, key) => slice.classList.toggle("active", key === s.key));
        rowEls.forEach((row, key) => row.classList.toggle("row-active", key === s.key));
        centerLabel.textContent = s.name.length > 18 ? s.name.slice(0, 17) + "…" : s.name;
        centerValue.textContent = money(s.amount);
    };
    const deactivate = () => {
        svg.classList.remove("has-active");
        sliceEls.forEach(slice => slice.classList.remove("active"));
        rowEls.forEach(row => row.classList.remove("row-active"));
        centerLabel.textContent = totalLabel;
        centerValue.textContent = money(total);
    };

    let angle = 0;
    for (const s of slices) {
        const share = s.amount / total * 100;
        const sweep = s.amount / total * TAU;
        const round = slices.length > 1 && sweep * RING_RADIUS > 2 * (cap + RING_GAP) + 4;   // a slice too short for round ends gets flat ones
        const pad = ((round ? cap : 0) + RING_GAP / 2) / RING_RADIUS;
        const attrs = { class: `slice ${s.colour}${round ? "" : " flat"}`, style: `--i:${sliceEls.size}`, tabindex: "0",
            "aria-label": `${s.name}, ${money(s.amount)}, ${shareText(share)}` };
        const slice = slices.length === 1
            ? svgEl("circle", { ...attrs, cx, cy, r: RING_RADIUS })
            : svgEl("path", { ...attrs, d: arcLine(cx, cy, RING_RADIUS, angle + pad, angle + sweep - pad) });
        const tip = () => ({ value: money(s.amount), name: `${s.name} · ${shareText(share)}`, notes: [] });
        slice.addEventListener("pointermove", e => { activate(s); showTooltip(tip(), e.clientX, e.clientY); });
        slice.addEventListener("pointerleave", () => { deactivate(); hideTooltip(); });
        slice.addEventListener("focus", () => { activate(s); showTooltipAt(slice, tip()); });
        slice.addEventListener("blur", () => { deactivate(); hideTooltip(); });
        sliceEls.set(s.key, slice);
        svg.append(slice);
        if (share >= 8) {   // only a slice big enough to carry its share written beside it
            const mid = angle + sweep / 2;
            const lx = cx + (outer + 12) * Math.sin(mid), ly = cy - (outer + 12) * Math.cos(mid);
            svg.append(svgEl("text", { class: "pct", x: lx, y: ly + 4, "text-anchor": lx > cx + 6 ? "start" : lx < cx - 6 ? "end" : "middle" }, shareText(share)));
        }
        angle += sweep;
    }
    svg.append(centerLabel, centerValue);

    const table = el("table", { className: "chart-table" });
    table.append(el("thead", {}, el("tr", {}, el("th", { textContent: headLabel }),
        el("th", { className: "num", textContent: "Amount" }), el("th", { className: "num", textContent: "Share" }))));
    const body = el("tbody");
    for (const s of slices) {
        const row = el("tr", { className: "cat-row", tabIndex: 0 },
            el("td", {}, el("span", { className: `swatch ${s.colour}` }), s.name),
            el("td", { className: "num", textContent: money(s.amount) }),
            el("td", { className: "num", textContent: shareText(s.amount / total * 100) }));
        row.addEventListener("pointerenter", () => activate(s));
        row.addEventListener("pointerleave", deactivate);
        rowEls.set(s.key, row);
        body.append(row);
    }
    table.append(body, el("tfoot", {}, el("tr", {}, el("td", { textContent: totalLabel }),
        el("td", { className: "num", textContent: money(total) }), el("td", { className: "num", textContent: "100%" }))));

    const side = el("div", { className: "alloc-side" }, table);
    if (note) side.append(el("div", { className: "chart-note alloc-debt", textContent: note }));
    box.append(el("div", { className: "alloc-body" }, el("div", { className: "alloc-ring" }, svg), side));   // the box gives the svg a fixed square shape: a bare svg in a grid can collapse in Safari
}

const money = amount => amountsRevealed() ? rupees(amount) : MASK;

export function renderAllocation() {
    const { slices, total } = allocationSlices(assetsByKind());
    const debt = totalDebt();
    drawAllocation($("nwAllocation"), {
        title: "Where your assets are",
        subtitle: "Share of everything you own today, by kind of account, at its current value. Debt and money lent out are not in the ring.",
        totalLabel: "Total assets", headLabel: "Kind of account", slices, total, money,
        empty: "Nothing to show yet: add an account with a balance and it appears here.",
        note: debt > 0 ? `You owe ${money(debt)}, which is taken off these assets to give your net worth.` : null });
}

export function initAllocation() {
    // The eye shows or hides every amount in the view together, this ring's too. summary.js has already flipped it by now.
    $("nwEye").addEventListener("click", () => renderAllocation());
}
