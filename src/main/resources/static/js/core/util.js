/* Small helpers used by every panel. */

import { CURRENCIES } from "./constants.js";

export const $ = id => document.getElementById(id);
export const rupees = n => "₹" + Number(n || 0).toLocaleString("en-IN", { maximumFractionDigits: 2 });
/* Always two decimals, 1,000.00: for figures that are read down a column (the net worth sum). */
export const rupees2 = n => "₹" + Number(n || 0).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
/* An amount in an account's own currency: rupees for INR (or when the account has none), else the symbol and the currency's decimals
   (VND has none). `balanceInr(a)` is the account's balance in rupees, which is what every total adds up. */
export function inCurrency(n, currency) {
    if (!currency || currency === "INR") return rupees(n);
    const info = CURRENCIES[currency];
    if (!info) return rupees(n);
    return info.symbol + Number(n || 0).toLocaleString("en-US", { maximumFractionDigits: info.decimals });
}
export const balanceInr = a => Number(a.balanceInr ?? a.balance);
export const isoDay = d => d.toLocaleDateString("en-CA");   // yyyy-mm-dd in local time

/* The date `count` months after `from` (before it, if negative), keeping the day of the month where the month is long
   enough and using the month's last day where it is not: 31 May minus 3 months is 28 (or 29) February, not 3 March. */
export function addMonthsClamped(from, count) {
    const first = new Date(from.getFullYear(), from.getMonth() + count, 1);
    const last = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
    return new Date(first.getFullYear(), first.getMonth(), Math.min(from.getDate(), last));
}

export function formatDate(iso) {
    return new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}

/* The "bill due on day ..." box of a credit card: empty is fine (null); otherwise a whole day from 1 to 31. Anything else is
   undefined, which the caller reports. */
export function parseDueDay(raw) {
    if (String(raw).trim() === "") return null;
    const day = Number(raw);
    return Number.isInteger(day) && day >= 1 && day <= 31 ? day : undefined;
}

/* 1 -> "1st", 22 -> "22nd", 13 -> "13th". */
export function ordinal(n) {
    const tens = n % 100, ones = n % 10;
    return n + (tens >= 11 && tens <= 13 ? "th" : ones === 1 ? "st" : ones === 2 ? "nd" : ones === 3 ? "rd" : "th");
}

/* Builds a DOM element: el("div", { className: "x" }, child, ...) */
export function el(tag, props, ...children) {
    const node = Object.assign(document.createElement(tag), props);
    node.append(...children);
    return node;
}

/* Calls the backend API. Throws an Error carrying the server's message when a call fails. */
export async function api(path, options) {
    const res = await fetch(path, {
        cache: "no-store",
        ...options,
        headers: { "Content-Type": "application/json", "X-Requested-With": "FinancialDashboard" },   // changes must say they come from here
        body: options && options.body ? JSON.stringify(options.body) : undefined
    });
    const data = res.status === 204 ? null : await res.json().catch(() => null);
    if (!res.ok) {
        throw new Error((data && data.error) || "Something went wrong (" + res.status + ")");
    }
    return data;
}

/* The page redraws after every action. A progress bar should grow in only the first time it appears, not flicker on
   each redraw, so the first call for a key says yes and every later call says no. */
const barsShown = new Set();
export function growsOnce(key) {
    if (barsShown.has(key)) return false;
    barsShown.add(key);
    return true;
}

/* A row, card or tile that is new on the page rises in (css/motion.css .rise); one already shown is left alone. */
export function riseOnce(node, key) {
    if (growsOnce(key)) node.classList.add("rise");
    return node;
}

/* A bar's width, remembered by key. When a bar is drawn again with a different value, it slides from the old width to the new
   one (css .shift reads --from) instead of jumping; a bar drawn for the first time just takes its width (and grows in). */
const barPercents = new Map();
export function setBarWidth(fill, key, pct) {
    fill.style.width = pct + "%";
    const before = barPercents.get(key);
    barPercents.set(key, pct);
    if (before !== undefined && before !== pct) {
        fill.style.setProperty?.("--from", before + "%");
        fill.classList.add("shift");
    }
}

/* A saved row is lit briefly once the page has redrawn: flashOnNext("txn-row-5") before the redraw, and the row built for that
   key takes the class .flash. A row that is new on the page rises in instead, so it does not take it (see txnRowElement). */
const flashKeys = new Set();
export function flashOnNext(key) { flashKeys.add(key); }
export function takeFlash(key) { return flashKeys.delete(key); }

/* Starts a CSS animation class again from the beginning, even if the node already has it. */
export function replay(node, className) {
    node.classList.remove(className);
    node.getBoundingClientRect?.();   // reading the layout makes the browser see the class as gone, so adding it restarts the animation (offsetWidth would not do: an svg has none)
    node.classList.add(className);
}

/* Lets the rows matching the selector collapse away, then resolves so the caller can redraw without them. Resolves at once
   when nothing matches or the person asked for less motion. */
export function collapseOut(selector, ms = 220) {
    const rows = typeof document === "undefined" ? [] : [...document.querySelectorAll(selector)];
    if (rows.length === 0 || wantsLessMotion()) return Promise.resolve();
    rows.forEach(row => row.classList.add("collapse"));
    return new Promise(resolve => setTimeout(resolve, ms));
}

/* The value part-way through a count: eased out, so it moves fast first and settles gently. progress runs 0 to 1. */
function tween(from, to, progress) {
    const p = Math.min(1, Math.max(0, progress));
    return from + (to - from) * (1 - (1 - p) ** 3);
}

const wantsLessMotion = () => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;

/* Shows `format(value)` in the node, counting from `from` to `to` over `ms`. With no animation frames (a test page) or for a
   person who asked for less motion it shows the end value at once. A new count on the same node replaces the old one. */
export function countTo(node, from, to, format, ms = 600) {
    if (node._countFrame && typeof cancelAnimationFrame === "function") cancelAnimationFrame(node._countFrame);
    node._countFrame = 0;
    if (from === to || wantsLessMotion() || typeof requestAnimationFrame !== "function") {
        node.textContent = format(to);
        return;
    }
    let start = null;
    const step = now => {
        if (start === null) start = now;
        const progress = (now - start) / ms;
        node.textContent = format(progress >= 1 ? to : tween(from, to, progress));
        node._countFrame = progress >= 1 ? 0 : requestAnimationFrame(step);
    };
    node._countFrame = requestAnimationFrame(step);
}

/* Stops a count that is running and puts `text` in the node (used when the amount is hidden again). */
export function stopCount(node, text) {
    if (node._countFrame && typeof cancelAnimationFrame === "function") cancelAnimationFrame(node._countFrame);
    node._countFrame = 0;
    node.textContent = text;
}
