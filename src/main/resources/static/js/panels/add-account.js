/* "+ Add account" on each level of Funds & Accounts. The popup shows only that level's account types,
   explains each one, then takes the name, the balance and any extras (credit limit, goal target).
   Accounts are created through the same batch endpoint the setup wizard uses. */

import { CURRENCIES, CURRENCY_TYPES, LEVEL_NAMES, TYPE_LABELS } from "../core/constants.js";
import { refreshData } from "../core/refresh.js";
import { accounts, emergencyFund } from "../core/state.js";
import { $, api, el, parseDueDay } from "../core/util.js";
import { openEmergencySetup } from "./emergency-fund.js";
import { ledgerIsSetUp, setUpLedger } from "./ledger.js";

/* What each level holds, and what to tell the user about every account type in it. */
export const LEVEL_GUIDE = {
    1: { holds: "Your spendable money: bank accounts, cash in hand and digital wallets. Everyday income and expenses move through these.",
         intro: "Money you can spend today. Pick the kind of account, then enter what is in it right now.",
         blurb: "Bank accounts and cash you can spend today.",
         types: {
            BANK: { what: "Savings and current accounts.", why: "See what is in each account and every transaction that moves money in or out." },
            CASH: { what: "Cash in hand.", why: "Keep everyday cash apart from the bank, so you can see where it goes." },
            WALLET: { what: "A digital wallet, like PhonePe, Paytm or Amazon Pay balance.", why: "See what is left in each wallet and what you spent from it. Add money with a Transfer from your bank; payments made by UPI come straight from the bank, not the wallet." } } },
    2: { holds: "Money set aside for a purpose: the Emergency Fund, the Ledger (bills and planned payments in buckets) and your own goal funds with a target. A goal fund can be ring-fenced once its target is reached.",
         intro: "Money set aside for a purpose.",
         blurb: "Money set aside for a purpose.",
         types: {
            GOAL_FUND: { label: "My own goal", what: "A fund for something you are saving towards, like a holiday or a gadget.",
                         why: "Set a target and watch the progress bar fill as you put money in." } } },
    3: { holds: "What you have invested: Mutual Funds, Shares and Jewellery, plus any other investment account. Their current value counts towards net worth.",
         intro: "Things you have invested in. Enter their current value, and update it when it changes.",
         blurb: "Mutual funds, shares and jewellery you have invested in.",
         types: {
            INVESTMENT: { what: "Mutual funds, stocks and similar.", why: "Their current value counts towards your net worth. To record a sale, make a transfer to your bank." } } },
    4: { holds: "Things you own that keep their value: property (a house, flat or land) and vehicles. Their value counts towards net worth.",
         intro: "Things you own that keep their value. Enter what they are worth today.",
         blurb: "A house, land, a car or a bike.",
         types: {
            PROPERTY: { what: "A house, flat or land.", why: "Its value counts towards your net worth." },
            VEHICLE: { what: "A car or a bike.", why: "Its value counts towards your net worth. You can note its number plate and the year you bought it." } } },
    5: { holds: "Money other people owe you. It is one Receivables total, with each borrower listed under it. It is shown but not counted in net worth until repaid.",
         intro: "Money other people owe you. It is one total, so it needs no name.",
         blurb: "Money other people owe you.",
         types: {
            RECEIVABLE: { label: "Receivables", what: "Money you have lent to others.",
                          why: "Your money, but not with you yet, so it is shown on its own and not counted in net worth until it is repaid. Once it exists, record lending with Loans Given (Expense) and repayments with Loans Collected (Income), and add the people who owe you." } } },
    6: { holds: "What you owe: loans (with an optional monthly EMI) and credit cards (with a limit and due day). The amount owed is subtracted from net worth.",
         intro: "Loans you owe and cards you owe money on.",
         blurb: "Loans and credit cards.",
         types: {
            DEBT: { what: "Loans you owe, such as a home loan.", why: "The amount you owe is subtracted from your net worth. Record each payment with the loan's Pay button." },
            CREDIT_CARD: { what: "Cards you owe money on.", why: "See how much of your credit limit you have used. Record a bill payment with the card's Pay button." } } },
    7: { holds: "Anything that does not fit the other levels. This is rare. It still counts in net worth.",
         intro: "For anything that doesn't fit the other levels. This is rare.",
         blurb: "Anything that doesn't fit the other levels.",
         types: { OTHER: { what: "Any other account.", why: "It still counts in your net worth." } } }
};

const ADD_BALANCE_LABEL = {
    BANK: "Opening balance (₹)", CASH: "Opening balance (₹)", WALLET: "Balance in the wallet (₹)", GOAL_FUND: "Current balance (₹)",
    INVESTMENT: "Current value (₹)", GOLD: "Current value (₹)", PROPERTY: "Current value (₹)", VEHICLE: "Current value (₹)",
    RECEIVABLE: "Total owed to you (₹)", DEBT: "Amount you owe (₹)", CREDIT_CARD: "Currently owed (₹)", OTHER: "Current value (₹)"
};

const addAcc = { level: null, type: null };

export function openAddAccount(level) {
    const guide = LEVEL_GUIDE[level];
    if (!guide) return;
    addAcc.level = level;
    $("addAccTitle").textContent = `Add account · ${LEVEL_NAMES[level]}`;
    $("addAccIntro").textContent = guide.intro;
    ["addAccName", "addAccBalance", "addAccLimit", "addAccStatement", "addAccDue", "addAccTarget", "addAccPlate", "addAccYear", "addAccEmi"].forEach(id => $(id).value = "");
    $("addAccPlan").value = "FIXED";
    $("addAccCurrency").value = "INR";
    $("addAccError").textContent = "";
    $("addAccSave").disabled = false;

    const types = $("addAccTypes");
    types.innerHTML = "";
    const typeKeys = Object.keys(guide.types);
    for (const key of typeKeys) {
        const info = guide.types[key];
        const card = el("button", { className: "type-card", type: "button" },
            el("h4", { textContent: info.label || TYPE_LABELS[key] }),
            el("div", { className: "what", textContent: info.what }),
            el("div", { className: "why", textContent: info.why }));
        card.addEventListener("click", () => selectAddType(key));
        card.dataset.type = key;
        types.append(card);
    }
    addExtras(level);
    selectAddType(typeKeys[0]);
    $("addAccOverlay").classList.remove("hidden");
    $(addAcc.type === "RECEIVABLE" ? "addAccBalance" : "addAccName").focus();
}

/* Goals also offers the Emergency Fund and the Ledger, which are not set up with this form. */
function addExtras(level) {
    const box = $("addAccExtras");
    box.innerHTML = "";
    if (level !== 2) return;
    const set = emergencyFund.configured;
    const emergency = el("div", { className: "extra-card" },
        el("div", {}, el("h4", { textContent: "Emergency Fund" }),
            el("div", { className: "what", textContent: set ? "Already set up. Change it with Settings on its card."
                : "A cushion for job loss, medical bills or urgent repairs. Shows your runway in months and an estimated date to reach your target." })));
    if (!set) {
        const setUp = el("button", { className: "btn btn-primary btn-small", textContent: "Set up", type: "button" });
        setUp.addEventListener("click", () => { closeAddAccount(); openEmergencySetup(); });
        emergency.append(setUp);
    }
    const ledgerSet = ledgerIsSetUp();
    const ledgerCardEl = el("div", { className: "extra-card" },
        el("div", {}, el("h4", { textContent: "Future Expenses Ledger" }),
            el("div", { className: "what", textContent: ledgerSet ? "Already set up. Use the buttons on its card."
                : "Set money aside for bills you know are coming, like insurance or school fees, with a bucket for each." })));
    if (!ledgerSet) {
        const setUpLedgerButton = el("button", { className: "btn btn-primary btn-small", textContent: "Set up", type: "button" });
        setUpLedgerButton.addEventListener("click", () => { closeAddAccount(); setUpLedger(); });
        ledgerCardEl.append(setUpLedgerButton);
    }
    box.append(emergency, ledgerCardEl);
}

function selectAddType(type) {
    addAcc.type = type;
    document.querySelectorAll("#addAccTypes .type-card").forEach(c => c.classList.toggle("selected", c.dataset.type === type));
    $("addAccNameRow").classList.toggle("hidden", type === "RECEIVABLE");   // one header account, no name needed
    $("addAccLimitRow").classList.toggle("hidden", type !== "CREDIT_CARD");
    $("addAccStatementRow").classList.toggle("hidden", type !== "CREDIT_CARD");
    $("addAccDueRow").classList.toggle("hidden", type !== "CREDIT_CARD");
    $("addAccPlateRow").classList.toggle("hidden", type !== "VEHICLE");
    $("addAccYearRow").classList.toggle("hidden", type !== "VEHICLE");
    $("addAccTargetRow").classList.toggle("hidden", type !== "GOAL_FUND");
    $("addAccPlanRow").classList.toggle("hidden", type !== "DEBT");
    syncAddPlan();
    $("addAccCurrencyRow").classList.toggle("hidden", !CURRENCY_TYPES.has(type));
    if (!CURRENCY_TYPES.has(type)) $("addAccCurrency").value = "INR";   // only some kinds of account can be foreign
    syncAddCurrency();
    $("addAccHint").textContent = type === "CREDIT_CARD"
        ? "The limit is needed so the dashboard can show how much of it you have used." : "";
}

/* The amount's label shows the chosen currency's symbol, and a dong amount takes whole numbers only. */
function syncAddCurrency() {
    const info = CURRENCIES[$("addAccCurrency").value] || CURRENCIES.INR;
    $("addAccBalanceLabel").textContent = ADD_BALANCE_LABEL[addAcc.type].replace("₹", info.symbol);
    $("addAccBalance").step = info.decimals === 0 ? "1" : "any";
}

/* A fixed loan asks for its monthly EMI; a custom one has nothing more to ask. */
function syncAddPlan() {
    $("addAccEmiRow").classList.toggle("hidden", !(addAcc.type === "DEBT" && $("addAccPlan").value === "FIXED"));
}

function closeAddAccount() {
    $("addAccOverlay").classList.add("hidden");
}

async function saveAddAccount() {
    const type = addAcc.type;
    const error = $("addAccError");
    const name = type === "RECEIVABLE" ? "Receivables" : $("addAccName").value.trim();
    const balance = $("addAccBalance").value === "" ? 0 : Number($("addAccBalance").value);
    const limit = Number($("addAccLimit").value);
    const dueDay = parseDueDay($("addAccDue").value);
    const statementDay = parseDueDay($("addAccStatement").value);
    const target = $("addAccTarget").value === "" ? null : Number($("addAccTarget").value);
    const fixedLoan = type === "DEBT" && $("addAccPlan").value === "FIXED";
    const emi = Number($("addAccEmi").value);
    const plate = $("addAccPlate").value.trim();
    const year = $("addAccYear").value === "" ? null : Number($("addAccYear").value);

    if (!name) { error.textContent = "Enter a name for the account."; return; }
    if (accounts.some(a => a.name.toLowerCase() === name.toLowerCase())) {
        error.textContent = type === "RECEIVABLE" ? "Receivables is already added." : `"${name}" already exists.`;
        return;
    }
    if (!(balance >= 0)) { error.textContent = "The amount cannot be negative."; return; }
    if (type === "CREDIT_CARD" && !(limit > 0)) { error.textContent = "A credit card needs a credit limit greater than zero."; return; }
    if (type === "CREDIT_CARD" && dueDay === undefined) { error.textContent = "The due day must be a day of the month, from 1 to 31."; return; }
    if (type === "CREDIT_CARD" && statementDay === undefined) { error.textContent = "The statement day must be a day of the month, from 1 to 31."; return; }
    if (fixedLoan && !(emi > 0)) { error.textContent = "A fixed loan needs a monthly EMI greater than zero."; return; }
    if (target != null && !(target >= 0)) { error.textContent = "The target cannot be negative."; return; }
    if (type === "VEHICLE" && year != null && !(Number.isInteger(year) && year >= 1900 && year <= new Date().getFullYear())) {
        error.textContent = `Year bought must be between 1900 and ${new Date().getFullYear()}.`;
        return;
    }

    $("addAccSave").disabled = true;
    try {
        await api("/api/setup/accounts", { method: "POST", body: [{
            name, type, openingBalance: balance, currency: $("addAccCurrency").value,
            creditLimit: type === "CREDIT_CARD" ? limit : null,
            dueDay: type === "CREDIT_CARD" ? dueDay : null,
            statementDay: type === "CREDIT_CARD" ? statementDay : null,
            targetAmount: type === "GOAL_FUND" ? target : null,
            emiAmount: fixedLoan ? emi : null,
            numberPlate: type === "VEHICLE" && plate ? plate : null,
            yearBought: type === "VEHICLE" ? year : null
        }] });
        closeAddAccount();
        await refreshData();
    } catch (e) {
        error.textContent = e.message;
        $("addAccSave").disabled = false;
    }
}

export function initAddAccount() {
    $("addAccSave").addEventListener("click", saveAddAccount);
    $("addAccPlan").addEventListener("change", syncAddPlan);
    for (const [code, info] of Object.entries(CURRENCIES)) $("addAccCurrency").append(el("option", { value: code, textContent: info.label }));
    $("addAccCurrency").addEventListener("change", syncAddCurrency);
    $("addAccCancel").addEventListener("click", closeAddAccount);
    $("addAccClose").addEventListener("click", closeAddAccount);
    ["addAccName", "addAccBalance", "addAccLimit", "addAccStatement", "addAccDue", "addAccTarget", "addAccEmi"].forEach(id =>
        $(id).addEventListener("keydown", e => { if (e.key === "Enter") saveAddAccount(); }));
}
