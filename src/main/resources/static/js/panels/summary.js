/* Net worth as a sum, above the graph in the Net worth view:
       Assets
     - Debt
     -----------
     the total (Net worth), a bold number with no label
   Receivables (money lent) is NOT part of the sum: it is yours but not with you yet, so it is shown on its own line below,
   marked "not counted". The amounts start hidden (₹ ••••••••); the eye at the top right of the view shows or hides them
   together. Built by code and found by class. */

import { LIABILITIES } from "../core/constants.js";
import { accounts } from "../core/state.js";
import { $, balanceInr, countTo, el, rupees2, stopCount } from "../core/util.js";

const NW_TIPS = {
    assets: "Everything you own and hold: cash, bank accounts, investments, gold, property, vehicles and goal funds. Money you have lent is not in it.",
    receivables: "Money you have lent to someone else. It is yours, but not with you yet, so it is left out of net worth until they repay you.",
    debt: "Everything you owe: loans, credit card balances, and other dues. Taken away from what you own."
};
const NW_MASK = "₹ ••••••••";
const nwFigures = { total: 0, assets: 0, receivables: 0, debt: 0 };
const nwShown = { total: 0, assets: 0, receivables: 0, debt: 0 };   // what the numbers on screen last counted to
let nwRevealed = false;

/* What Assets is made of, by kind of account, in this order. Receivables is not an asset here, so it is not in it. */
const NW_PARTS = [["CASH", "Cash"], ["BANK", "Bank accounts"], ["WALLET", "Wallets"], ["GOAL_FUND", "Goal funds"], ["INVESTMENT", "Investments"],
    ["GOLD", "Gold"], ["JEWELLERY", "Jewellery"], ["PROPERTY", "Property"], ["VEHICLE", "Vehicles"], ["OTHER", "Other"]];
let nwParts = [];          // [{ label, amount }] for the kinds of account that exist; the amounts add up to Assets
let nwPartsOpen = false;   // the breakdown under Assets starts hidden; the arrow beside Assets opens it

/* Active accounts split three ways: what you own (also by kind of account), what is lent out, and what you owe. */
function splitAccounts() {
    let owned = 0, receivables = 0, debt = 0;
    const byType = new Map();
    for (const a of accounts.filter(a => a.active)) {
        const amount = balanceInr(a);   // a foreign account counts in rupees at today's rate
        if (LIABILITIES.has(a.type)) {
            debt += amount;
        } else if (a.type === "RECEIVABLE") {
            receivables += amount;
        } else {
            owned += amount;
            byType.set(a.type, (byType.get(a.type) || 0) + amount);
        }
    }
    return { owned, receivables, debt, parts: NW_PARTS.filter(([type]) => byType.has(type)).map(([type, label]) => ({ label, amount: byType.get(type) })) };
}

/* What you own by kind of account, for the allocation ring: [{ label, amount }] in the order of the fold-out under Assets. */
export function assetsByKind() { return splitAccounts().parts; }
export function totalDebt() { return splitAccounts().debt; }
/* Whether the eye has shown the amounts (the allocation ring hides its amounts with the other figures). */
export function amountsRevealed() { return nwRevealed; }

export function renderSummary() {
    const { owned, receivables, debt, parts } = splitAccounts();
    nwParts = parts;
    Object.assign(nwFigures, { total: owned - debt, assets: owned, receivables, debt });   // receivables is shown, not counted
    paintFigures();
}

/* Hidden amounts show only the mask. Shown ones count up from nothing when the eye opens, and from the old figure to the
   new one when the data changes; the real digits are never on screen while hidden. */
function paintFigures(justRevealed = false) {
    for (const key of Object.keys(nwFigures)) {
        const from = justRevealed ? 0 : nwShown[key];
        document.querySelectorAll(".js-nw-" + key).forEach(n => {
            if (nwRevealed) countTo(n, from, nwFigures[key], rupees2);
            else stopCount(n, NW_MASK);
        });
        nwShown[key] = nwFigures[key];
    }
    paintParts();
    const eye = $("nwEye");
    if (eye) {
        eye.textContent = nwRevealed ? "🙈" : "👁️";
        eye.title = nwRevealed ? "Hide the amounts" : "Show the amounts";
        eye.setAttribute("aria-pressed", String(nwRevealed));
    }
}

/* The lines under Assets: one per kind of account, indented, hidden with the amounts, folded by the arrow. */
function paintParts() {
    document.querySelectorAll(".js-nw-parts").forEach(box => {
        box.innerHTML = "";
        box.classList.toggle("hidden", !nwPartsOpen || nwParts.length === 0);
        for (const part of nwParts) {
            box.append(el("span", { className: "nw-part-label", textContent: part.label }),
                el("span", { className: "nw-part-amt", textContent: nwRevealed ? rupees2(part.amount) : NW_MASK }));
        }
    });
    document.querySelectorAll(".js-nw-toggle").forEach(button => {
        button.textContent = nwPartsOpen ? "▾" : "▸";
        button.title = nwPartsOpen ? "Hide what Assets is made of" : "Show what Assets is made of";
        button.setAttribute("aria-expanded", String(nwPartsOpen));
    });
}

/* One line of the sum: the sign, the name with its (i), and the amount, in three columns of a grid. */
function nwLine(op, key, label) {
    const tip = el("span", { className: "info-tag", tabindex: 0 }, "i", el("span", { className: "info-tooltip", textContent: NW_TIPS[key] }));
    return [el("span", { className: "nw-op", textContent: op }),
        el("span", { className: "nw-label" }, tip, ` ${label}`),
        el("span", { className: "nw-amt nw-" + key + " js-nw-" + key, textContent: NW_MASK })];
}

function buildNetWorthFigures(mount) {
    const toggle = el("button", { className: "icon-btn ledger-toggle js-nw-toggle", type: "button", textContent: "▸" });
    toggle.setAttribute("aria-label", "What Assets is made of");
    toggle.addEventListener("click", () => { nwPartsOpen = !nwPartsOpen; paintParts(); });
    const total = el("span", { className: "nw-amt net-worth js-nw-total", textContent: NW_MASK });   // the number under the rule is the total; no words on screen
    total.setAttribute("aria-label", "Net worth");
    const assets = nwLine("", "assets", "Assets");
    assets[1].append(" ", toggle);
    mount.append(el("div", { className: "nw-sum" },
        ...assets,
        el("div", { className: "nw-parts js-nw-parts" }),
        ...nwLine("−", "debt", "Debt"),
        el("div", { className: "nw-rule" }),
        total,
        ...nwLine("", "receivables", "Receivables (not counted)")));
}

export function initSummary() {
    buildNetWorthFigures($("nwFigures"));
    $("nwEye").addEventListener("click", () => { nwRevealed = !nwRevealed; paintFigures(nwRevealed); });
}
