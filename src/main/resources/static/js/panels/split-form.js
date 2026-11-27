/* "Split with people" part of the Expense form: tick people from the Receivables list (or add a new one), choose how to share the bill
   (equally, by amount or by percent) and see each share and the user's own. The maths is split-calc.js; this file only draws and reads the boxes.
   The expense keeps its full amount: only what the others owe is saved with it (their shares appear under their names in Receivables). */

import { splitShares } from "../core/currency.js";
import { loans, setLoans } from "../core/state.js";
import { SPLIT_AMOUNT, SPLIT_EQUAL, SPLIT_PERCENT, computeShares } from "../core/split-calc.js";
import { api, el, rupees } from "../core/util.js";

const MODES = [[SPLIT_EQUAL, "Equally"], [SPLIT_AMOUNT, "By amount"], [SPLIT_PERCENT, "By percent"]];

/* existing is the expense being edited (or null); getAmount() gives the amount typed in the form so far.
   Returns { node, top, refresh, isOn, onToggle, showPeople, result } (top is an empty holder under the tick box for the form's Paid by row): refresh() after the amount changes; result() is { shares, error }, shares null when not split. */
export function splitField(existing, getAmount) {
    const saved = existing ? splitShares(existing) : [];
    let on = saved.length > 0 || !!(existing && existing.paidByBorrowerId != null);   // a bill somebody else paid is also a shared one
    let panelShown = true;           // the form hides the people while somebody else paid (their share is typed in the form instead)
    const listeners = [];            // called when the box is ticked or unticked
    let mode = saved.length ? SPLIT_AMOUNT : SPLIT_EQUAL;   // a saved split is shown as the amounts it was saved with
    const chosen = new Map(saved.map(s => [s.personId, String(s.share)]));   // person id -> the typed amount or percent
    let adding = false;

    const node = el("div", { className: "split-box" });
    const toggle = el("input", { type: "checkbox", checked: on });
    const head = el("label", { className: "split-toggle" }, toggle, " Split this expense with people");
    const top = el("div", { className: "split-top" });   // the form puts Paid by (and, when somebody else paid, Your share) here: first under the box
    const panel = el("div", { className: "split-panel" });
    node.append(head, top, panel);

    const members = () => [...chosen].map(([id, value]) => ({ id, value }));
    const calc = () => computeShares(Number(getAmount()), mode, members());

    let figures = new Map();   // person id -> the node that shows their share
    let mineNode = null, noteNode = null;

    function paintFigures() {
        const r = calc();
        for (const [id, node] of figures) node.textContent = r.shares[id] == null ? "" : rupees(r.shares[id]);
        if (mineNode) mineNode.textContent = r.error ? "" : rupees(r.mine);
        if (noteNode) { noteNode.textContent = r.error || ""; noteNode.classList.toggle("hidden", !r.error); }
    }

    function draw() {
        panel.classList.toggle("hidden", !on || !panelShown);
        panel.replaceChildren();
        if (!on) return;
        const modeSelect = el("select", {}, ...MODES.map(([value, label]) => el("option", { value, textContent: label, selected: value === mode })));
        modeSelect.addEventListener("change", () => { mode = modeSelect.value; draw(); });
        panel.append(el("div", { className: "split-mode" }, el("span", { textContent: "Share" }), modeSelect));

        const list = el("div", { className: "split-people" });
        figures = new Map();
        mineNode = el("span", { className: "split-fig split-mine" });
        list.append(el("div", { className: "split-person me" }, el("span", { className: "split-name", textContent: "You" }), mineNode));
        for (const b of loans.borrowers) {
            const tick = el("input", { type: "checkbox", checked: chosen.has(b.id) });
            tick.addEventListener("change", () => { tick.checked ? chosen.set(b.id, chosen.get(b.id) || "") : chosen.delete(b.id); draw(); });
            const row = el("div", { className: "split-person" }, el("label", { className: "split-name" }, tick, " " + b.name));
            if (chosen.has(b.id) && mode !== SPLIT_EQUAL) {
                const input = el("input", { type: "number", min: 0, step: "any", value: chosen.get(b.id), className: "split-input",
                    placeholder: mode === SPLIT_PERCENT ? "%" : "₹" });
                input.setAttribute("aria-label", `${b.name}'s ${mode === SPLIT_PERCENT ? "percent" : "amount"}`);
                input.addEventListener("input", () => { chosen.set(b.id, input.value); paintFigures(); });
                row.append(input, el("span", { className: "split-unit", textContent: mode === SPLIT_PERCENT ? "%" : "₹" }));
            }
            const fig = el("span", { className: "split-fig" });
            figures.set(String(b.id), fig);
            row.append(fig);
            list.append(row);
        }
        noteNode = el("div", { className: "split-note hidden" });
        panel.append(list, addPersonRow(), noteNode);
        paintFigures();
    }

    /* "+ Add person": a name box that saves a new person to the Receivables list and ticks them. */
    function addPersonRow() {
        const row = el("div", { className: "split-add" });
        if (!adding) {
            const open = el("button", { className: "txn-link", type: "button", textContent: "+ Add person" });
            open.addEventListener("click", () => { adding = true; draw(); });
            row.append(open);
            return row;
        }
        const input = el("input", { type: "text", placeholder: "Name", autocomplete: "off" });
        const add = el("button", { className: "btn btn-primary", type: "button", textContent: "Add" });
        const cancel = el("button", { className: "btn btn-secondary", type: "button", textContent: "Cancel" });
        const error = el("span", { className: "split-add-error" });
        const submit = async () => {
            const name = input.value.trim();
            if (!name) return;
            try {
                const created = await api("/api/borrowers", { method: "POST", body: { name, openingAmount: 0 } });
                setLoans(await api("/api/loans"));
                chosen.set(created.id, "");
                adding = false;
                draw();
            } catch (e) {
                error.textContent = e.message;
            }
        };
        add.addEventListener("click", submit);
        cancel.addEventListener("click", () => { adding = false; draw(); });
        input.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); submit(); } if (e.key === "Escape") { adding = false; draw(); } });
        row.append(input, add, cancel, error);
        setTimeout(() => input.focus(), 0);
        return row;
    }

    toggle.addEventListener("change", () => { on = toggle.checked; draw(); listeners.forEach(fn => fn()); });
    draw();

    return {
        node,
        top,
        refresh: () => { if (on) paintFigures(); },
        isOn: () => on,
        onToggle: fn => listeners.push(fn),
        showPeople: visible => { panelShown = visible; panel.classList.toggle("hidden", !on || !visible); },
        result() {
            if (!on) return { shares: null, error: null };
            const r = calc();
            return { shares: r.error ? null : r.shares, error: r.error };
        },
    };
}
