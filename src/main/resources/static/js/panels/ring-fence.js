/* Ring-fencing for goal funds (the Emergency Fund and your own goals, not the Ledger).
   Offered only once a goal fund has reached its target. While ring-fenced, money can still go in, but nothing can
   be spent or transferred out until you release it. The server enforces this; this file shows the offer and the lock,
   and keeps the amount of a fund that has reached its target behind an eye, so it is out of sight until you look. */

import { refreshData } from "../core/refresh.js";
import { api, el } from "../core/util.js";

/* A goal of your own whose target has been reached. */
export function goalTargetReached(account) {
    return account.type === "GOAL_FUND" && Number(account.targetAmount) > 0
        && Number(account.balance) >= Number(account.targetAmount);
}

async function ringFence(accountId) {
    try {
        await api(`/api/ring-fences/${accountId}`, { method: "POST" });
        await refreshData();
    } catch (e) {
        alert(e.message);
    }
}

async function releaseRingFence(accountId, name) {
    if (!confirm(`Release "${name}"? You will be able to spend from it again.`)) return;
    try {
        await api(`/api/ring-fences/${accountId}`, { method: "DELETE" });
        await refreshData();
    } catch (e) {
        alert(e.message);
    }
}

/* The one message shown for a goal that has reached its target, or is ring-fenced. The same wording is used on the
   Emergency Fund card and on every goal of your own. Returns null when neither applies. */
export function fenceBanner({ id, name, reached, fenced }) {
    if (fenced) {
        const release = el("button", { className: "btn btn-secondary btn-small", textContent: "Release" });
        release.addEventListener("click", () => releaseRingFence(id, name));
        return el("div", { className: "fence-banner locked" },
            el("div", { className: "fence-text" }, el("strong", { textContent: "🔒 Ring-fenced. " }),
                "You can add money, but nothing can be spent or transferred out until you release it."),
            release);
    }
    if (reached) {
        const fence = el("button", { className: "btn btn-primary btn-small", textContent: "Ring-fence it" });
        fence.addEventListener("click", () => ringFence(id));
        return el("div", { className: "fence-banner" },
            el("div", { className: "fence-text" }, el("strong", { textContent: "🎯 Target reached. " }),
                "Ring-fence the fund so it can't be spent by accident. You can add to it, and release it when you need it."),
            fence);
    }
    return null;
}

/* An amount hidden behind an eye, like the Assets figure at the top. Stays shown across redraws once you peek,
   until the page is reloaded. */
const peeked = new Set();

export function peekAmount(text, key) {
    const value = el("span", { className: "masked-value" });
    const eye = el("button", { className: "reveal-toggle", title: "Show or hide the amount", type: "button", textContent: "👁️" });
    const paint = () => { value.textContent = peeked.has(key) ? text : "₹ ••••••••"; };
    eye.addEventListener("click", () => {
        if (peeked.has(key)) peeked.delete(key); else peeked.add(key);
        paint();
    });
    paint();
    return el("span", {}, value, eye);
}
