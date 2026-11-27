/* One floating tooltip for every chart and bar: the value first, then what it is, then any notes.
   Names come from the data, so only textContent is used. */

import { el } from "./util.js";

let tooltipEl = null;

export function showTooltip({ value, name, notes = [] }, x, y) {
    if (!tooltipEl) {
        tooltipEl = el("div", { className: "chart-tip" });
        tooltipEl.style.display = "none";
        document.body.append(tooltipEl);
    }
    tooltipEl.innerHTML = "";
    tooltipEl.append(el("div", { className: "tip-value", textContent: value }),
        el("div", { className: "tip-name", textContent: name }));
    notes.forEach(note => tooltipEl.append(el("div", { className: "tip-name", textContent: note })));
    tooltipEl.style.display = "block";
    tooltipEl.style.left = Math.min(x + 14, window.innerWidth - 250) + "px";
    tooltipEl.style.top = y + 14 + "px";
}

export function hideTooltip() {
    if (tooltipEl) tooltipEl.style.display = "none";
}

/* The same tooltip for keyboard focus as for hover: shown at the middle of the element. */
export function showTooltipAt(element, content) {
    const r = element.getBoundingClientRect();
    showTooltip(content, r.left + r.width / 2, r.top + r.height / 2);
}
