/* Emergency Fund: the card at the top of the Goals level, and the popup that sets it up or changes it. */

import { refreshData } from "../core/refresh.js";
import { accounts, emergencyFund, ledger } from "../core/state.js";
import { $, api, el, growsOnce, rupees2, setBarWidth } from "../core/util.js";
import { openEditAccount } from "./edit-account.js";
import { fenceBanner } from "./ring-fence.js";

export const monthYear = iso => new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { month: "short", year: "numeric" });

/* ---------- the card ---------- */

export function efTile(label, value, sub) {
    const valueEl = el("div", { className: "value" });
    if (value instanceof Node) valueEl.append(value);
    else valueEl.textContent = value;
    const tile = el("div", { className: "ef-tile" }, el("div", { className: "label", textContent: label }), valueEl);
    if (sub) tile.append(el("div", { className: "sub", textContent: sub }));
    return tile;
}

function efInfoTip() {
    return el("span", { className: "info-tag", tabIndex: 0 }, "i",
        el("span", { className: "info-tooltip",
            textContent: "A cushion for job loss, medical bills or urgent repairs. Essential expenses are what you must pay " +
                "even with no income. Runway is how many months the fund would cover them." }));
}

/* Runway status: always a word beside the colour, never colour alone. */
function efStatus(f) {
    if (f.targetReached) return { cls: "good", text: "Target reached" };
    if (f.runwayMonths == null) return { cls: "", text: "Add your monthly essentials" };
    return Number(f.runwayMonths) >= 3 ? { cls: "mid", text: "Building" } : { cls: "low", text: "Low" };
}

export function emergencyCard() {
    const f = emergencyFund;
    const card = el("div", { className: "ef-card" });
    const head = el("div", { className: "ef-head" },
        el("div", { className: "ef-title" }, "🛟 Emergency Fund", efInfoTip()));
    const open = el("button", { className: "btn " + (f.configured ? "btn-secondary" : "btn-primary"),
        textContent: f.configured ? "Settings" : "Set up" });
    open.addEventListener("click", openEmergencySetup);
    if (f.configured && f.ringFenced) {
        // Ring-fenced: nothing to change or look at until it is released, so no buttons here.
    } else if (f.configured) {
        const edit = el("button", { className: "icon-btn row-edit", textContent: "✏️" });
        edit.title = "Edit the account's name and balance";
        edit.setAttribute("aria-label", "Edit the Emergency Fund account");
        edit.addEventListener("click", () => openEditAccount(accounts.find(a => a.id === f.accountId)));
        head.firstElementChild.append(edit);   // the pencil sits on the left, beside the title; Settings stays on the right
        head.append(open);
    } else {
        head.append(open);
    }
    card.append(head);

    if (!f.configured) {
        card.append(el("div", { className: "ef-empty" },
            el("p", { textContent: "A cushion for the unexpected, like job loss, medical bills or urgent repairs. " +
                "Tell us your monthly essential expenses and we'll show how long your fund would last." }),
            el("ul", {},
                el("li", { textContent: "Progress towards a target in months of expenses, or a fixed amount" }),
                el("li", { textContent: "Runway: how many months your fund would cover" }),
                el("li", { textContent: "An estimate of when you will reach your target" }))));
        return card;
    }

    const banner = fenceBanner({ id: f.accountId, name: f.name, reached: f.targetReached, fenced: f.ringFenced });

    const status = efStatus(f);
    const runway = f.runwayMonths == null ? "Not set"
        : el("span", {}, `${Number(f.runwayMonths).toFixed(1)} months`);
    const runwayTile = efTile("Runway", runway);
    runwayTile.append(el("div", { className: "sub ef-status" }, el("span", { className: "ef-dot " + status.cls }), status.text));

    if (f.ringFenced) {
        // Ring-fenced: out of sight, out of mind. Only the runway shows until the fund is released.
        card.append(banner, el("div", { className: "ef-grid" }, runwayTile));
        return card;
    }

    const pct = Number(f.progressPercent);
    const fill = el("div", { className: "ef-fill" + (f.targetReached ? " reached" : "") + (growsOnce("emergency-fund") ? " grow" : "") });
    setBarWidth(fill, "emergency-fund", pct);
    card.append(el("div", { className: "ef-bar" }, fill),
        el("div", { className: "ef-progress-text", textContent: `${pct.toFixed(1)}% of ${rupees2(f.target)}` }));
    if (banner) card.append(banner);

    const targetSub = f.targetMode === "MONTHS" ? `${f.targetMonths} months × ${rupees2(f.monthlyEssentialExpenses)}` : "Fixed amount";
    const contribution = f.contributionSource === "PLANNED"
        ? efTile("Monthly contribution", rupees2(f.plannedMonthlyContribution), "Planned")
        : f.contributionSource === "AVERAGE"
            ? efTile("Monthly contribution", rupees2(f.averageMonthlyContribution), "Average of the last 3 months")
            : efTile("Monthly contribution", "Not set", "Plan one in Settings");
    const estimate = f.targetReached ? efTile("Estimated date", "Reached", "You have hit your target")
        : f.estimatedDate ? efTile("Estimated date", monthYear(f.estimatedDate), "At the contribution shown")
        : efTile("Estimated date", "No estimate yet", "Add a monthly contribution to see one");

    card.append(el("div", { className: "ef-grid" },
        efTile("Balance", rupees2(f.balance)),
        efTile("Target", rupees2(f.target), targetSub),
        efTile("Remaining", rupees2(f.remaining)),
        runwayTile, contribution, estimate));
    return card;
}

/* ---------- setup / settings popup ---------- */

const efMode = () => document.querySelector('input[name="efMode"]:checked').value;

function efShowMode() {
    const months = efMode() === "MONTHS";
    $("efMonthsRow").classList.toggle("hidden", !months);
    $("efAmountRow").classList.toggle("hidden", months);
}

function efShowOpening() {
    // Only a brand new account asks for a balance. An existing goal fund keeps its own.
    $("efOpeningRow").classList.toggle("hidden", emergencyFund.configured || $("efAccount").value !== "");
}

export function openEmergencySetup() {
    const f = emergencyFund;
    const select = $("efAccount");
    select.innerHTML = "";
    if (f.configured) {
        select.append(el("option", { value: f.accountId, textContent: f.name }));
        select.disabled = true;
    } else {
        select.disabled = false;
        select.append(el("option", { value: "", textContent: "Create a new account called Emergency Fund" }));
        for (const a of accounts.filter(a => a.type === "GOAL_FUND" && a.active && !(ledger.configured && a.id === ledger.accountId))) {
            select.append(el("option", { value: a.id, textContent: `Use my goal fund: ${a.name}` }));
        }
    }
    $("efTitle").textContent = f.configured ? "Emergency Fund settings" : "Set up your Emergency Fund";
    $("efOpening").value = "";
    document.querySelector(`input[name="efMode"][value="${f.configured ? f.targetMode : "MONTHS"}"]`).checked = true;
    $("efMonths").value = f.configured && f.targetMonths ? f.targetMonths : 6;
    $("efAmount").value = f.configured && f.targetMode === "AMOUNT" ? f.target : "";
    $("efBurn").value = f.configured && f.monthlyEssentialExpenses ? f.monthlyEssentialExpenses : "";
    $("efPlanned").value = f.configured && f.plannedMonthlyContribution ? f.plannedMonthlyContribution : "";
    $("efError").textContent = "";
    $("efSave").disabled = false;
    efShowMode();
    efShowOpening();
    $("efOverlay").classList.remove("hidden");
}

function closeEmergencySetup() {
    $("efOverlay").classList.add("hidden");
}

const efNumber = id => $(id).value === "" ? null : Number($(id).value);

async function saveEmergencySetup() {
    const mode = efMode();
    const body = {
        accountId: $("efAccount").value === "" ? null : Number($("efAccount").value),
        openingBalance: efNumber("efOpening"),
        targetMode: mode,
        targetMonths: mode === "MONTHS" ? efNumber("efMonths") : null,
        targetAmount: mode === "AMOUNT" ? efNumber("efAmount") : null,
        monthlyEssentialExpenses: efNumber("efBurn"),
        plannedMonthlyContribution: efNumber("efPlanned")
    };
    const error = $("efError");
    if (body.openingBalance != null && body.openingBalance < 0) { error.textContent = "The balance cannot be negative."; return; }
    if (mode === "MONTHS" && !(body.targetMonths > 0)) { error.textContent = "Enter the number of months."; return; }
    if (mode === "MONTHS" && !(body.monthlyEssentialExpenses > 0)) { error.textContent = "Enter your monthly essential expenses to set a target in months."; return; }
    if (mode === "AMOUNT" && !(body.targetAmount > 0)) { error.textContent = "Enter a target amount."; return; }
    if (body.monthlyEssentialExpenses != null && body.monthlyEssentialExpenses < 0) { error.textContent = "Monthly essential expenses cannot be negative."; return; }
    if (body.plannedMonthlyContribution != null && body.plannedMonthlyContribution < 0) { error.textContent = "The planned contribution cannot be negative."; return; }

    $("efSave").disabled = true;
    try {
        await api("/api/emergency-fund", { method: "PUT", body });
        closeEmergencySetup();
        await refreshData();
    } catch (e) {
        error.textContent = e.message;
        $("efSave").disabled = false;
    }
}

export function initEmergencyFund() {
    document.querySelectorAll('input[name="efMode"]').forEach(r => r.addEventListener("change", efShowMode));
    $("efAccount").addEventListener("change", efShowOpening);
    $("efSave").addEventListener("click", saveEmergencySetup);
    $("efCancel").addEventListener("click", closeEmergencySetup);
    $("efClose").addEventListener("click", closeEmergencySetup);
}
