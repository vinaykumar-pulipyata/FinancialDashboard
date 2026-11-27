/* Exchange rates: a popup (menu > Data > Exchange rates) where the rupee value of one unit of each foreign currency is entered by
   hand, by day. The server uses the newest rate on or before a day to count a foreign account in the totals and net worth. */

import { CURRENCIES } from "../core/constants.js";
import { refreshData } from "../core/refresh.js";
import { $, api, el, formatDate, isoDay } from "../core/util.js";

/* A rate as typed: up to 8 decimals, no trailing zeros ("0.0033", never "0.00330000"). */
const rateText = rate => String(Number(rate));

/* The foreign currencies, in the order of CURRENCIES (INR is the base and has no rate). */
const foreignCurrencies = () => Object.keys(CURRENCIES).filter(code => code !== "INR");

async function loadRates() {
    const rates = await api("/api/exchange-rates");
    const list = $("ratesList");
    list.innerHTML = "";
    if (rates.length === 0) {
        list.append(el("div", { className: "empty-state", textContent: "No rates yet. Add one above to hold an account in a foreign currency." }));
        return;
    }
    for (const code of foreignCurrencies()) {
        const rows = rates.filter(r => r.currency === code);   // the server sends newest first within a currency
        if (rows.length === 0) continue;
        const group = el("div", { className: "rates-group" }, el("h3", { textContent: CURRENCIES[code].label }));
        for (const r of rows) {
            const remove = el("button", { className: "rates-delete", type: "button", textContent: "🗑", title: "Delete this rate" });
            remove.setAttribute("aria-label", `Delete the ${code} rate of ${formatDate(r.date)}`);
            remove.addEventListener("click", () => deleteRate(r.id));
            group.append(el("div", { className: "rates-row" },
                el("span", { className: "rates-date", textContent: formatDate(r.date) }),
                el("span", { className: "rates-value", textContent: `₹${rateText(r.rate)} per 1 ${code}` }), remove));
        }
        list.append(group);
    }
}

async function deleteRate(id) {
    $("ratesError").textContent = "";
    try {
        await api(`/api/exchange-rates/${id}`, { method: "DELETE" });
        await loadRates();
        await refreshData();
    } catch (e) {
        $("ratesError").textContent = e.message;
    }
}

async function saveRate() {
    const error = $("ratesError");
    error.textContent = "";
    const rate = Number($("ratesRate").value);
    if ($("ratesRate").value === "" || !(rate > 0)) { error.textContent = "Enter how many rupees one unit is worth."; return; }
    if (!$("ratesDate").value) { error.textContent = "Choose the date of the rate."; return; }
    $("ratesSave").disabled = true;
    try {
        await api("/api/exchange-rates", { method: "PUT", body: { currency: $("ratesCurrency").value, date: $("ratesDate").value, rate } });
        $("ratesRate").value = "";
        await loadRates();
        await refreshData();   // the totals and net worth use the new rate
    } catch (e) {
        error.textContent = e.message;
    } finally {
        $("ratesSave").disabled = false;
    }
}

function openRates() {
    $("ratesError").textContent = "";
    $("ratesDate").value = isoDay(new Date());
    $("ratesOverlay").classList.remove("hidden");
    loadRates().catch(e => { $("ratesError").textContent = e.message; });
}

function closeRates() {
    $("ratesOverlay").classList.add("hidden");
}

export function initExchangeRates() {
    for (const code of foreignCurrencies()) $("ratesCurrency").append(el("option", { value: code, textContent: CURRENCIES[code].label }));
    $("ratesCurrency").addEventListener("change", () => { $("ratesRateLabel").textContent = `Rupees per 1 ${$("ratesCurrency").value}`; });
    $("ratesRateLabel").textContent = `Rupees per 1 ${$("ratesCurrency").value}`;
    $("exchangeRatesButton").addEventListener("click", openRates);
    $("ratesSave").addEventListener("click", saveRate);
    $("ratesClose").addEventListener("click", closeRates);
    $("ratesDone").addEventListener("click", closeRates);
    $("ratesRate").addEventListener("keydown", e => { if (e.key === "Enter") saveRate(); });
}
