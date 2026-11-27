/* Edit button on an account's own row (and on the Emergency Fund card): change its name and opening balance
   (and credit limit for a credit card, how a loan is paid), reset its balance to zero, deactivate or activate it, or delete it.
   A deactivated account allows nothing until it is activated again, so its fields and Save are off and only Activate and Delete remain.
   Reset balance is for an active account with money in it and asks what happened to the money. */

import { CURRENCIES, LIABILITIES } from "../core/constants.js";
import { refreshData } from "../core/refresh.js";
import { cardDueDays, cardStatementDays, creditLimits, debtEmis, vehicles } from "../core/state.js";
import { $, api, el, inCurrency, parseDueDay } from "../core/util.js";
import { dlg, ledgerFail, openLedgerDialog, runLedger } from "./ledger.js";
import { field } from "./transaction-form.js";

let editingAccount = null;
const EDIT_INPUTS = ["editAccountName", "editOpening", "editPlate", "editYear", "editLimit", "editStatement", "editDue", "editPlan", "editEmi"];   // all off while the account is deactivated

export function openEditAccount(account) {
    if (!account) return;
    editingAccount = account;
    const liability = LIABILITIES.has(account.type);
    const isCard = account.type === "CREDIT_CARD";
    $("editAccountTitle").textContent = `Edit account · ${account.name}`;
    $("editAccountName").value = account.name;
    const symbol = (CURRENCIES[account.currency] || CURRENCIES.INR).symbol;   // the account's own currency, which cannot be changed here
    $("editOpeningLabel").textContent = (liability ? "Opening amount owed" : "Opening balance") + ` (${symbol})` + (symbol === "₹" ? "" : ` · ${account.currency}`);
    $("editOpening").value = account.openingBalance;
    const isVehicle = account.type === "VEHICLE";
    $("editPlateRow").classList.toggle("hidden", !isVehicle);
    $("editYearRow").classList.toggle("hidden", !isVehicle);
    $("editPlate").value = isVehicle ? (vehicles.get(account.id)?.numberPlate ?? "") : "";
    $("editYear").value = isVehicle ? (vehicles.get(account.id)?.yearBought ?? "") : "";
    $("editLimitRow").classList.toggle("hidden", !isCard);
    $("editLimit").value = isCard && creditLimits.get(account.id) > 0 ? creditLimits.get(account.id) : "";
    $("editStatementRow").classList.toggle("hidden", !isCard);
    $("editStatement").value = isCard ? (cardStatementDays.get(account.id) ?? "") : "";
    $("editDueRow").classList.toggle("hidden", !isCard);
    $("editDue").value = isCard ? (cardDueDays.get(account.id) ?? "") : "";
    const isDebt = account.type === "DEBT";
    $("editPlanRow").classList.toggle("hidden", !isDebt);
    $("editPlan").value = debtEmis.has(account.id) ? "FIXED" : "CUSTOM";
    $("editEmi").value = isDebt ? (debtEmis.get(account.id) ?? "") : "";
    syncEditPlan();
    $("editAccountHint").textContent = `Current ${liability ? "amount owed" : "balance"} is ${inCurrency(account.balance, account.currency)}. ` +
        "That is the opening balance plus your transactions, so changing the opening balance moves it by the same amount.";
    const active = account.active !== false;
    for (const id of EDIT_INPUTS) $(id).disabled = !active;
    $("editAccountSave").classList.toggle("hidden", !active);
    $("editAccountToggle").textContent = active ? "Deactivate" : "Activate";
    $("editAccountToggle").title = active ? "Nothing can be done with a deactivated account until you activate it again" : "Switch the account back on";
    $("editAccountReset").classList.toggle("hidden", !active || Number(account.balance) === 0);
    if (!active) {
        $("editAccountHint").textContent = "This account is deactivated: no transaction can use it and nothing about it can be changed until you activate it.";
    }
    $("editAccountError").textContent = "";
    $("editAccountSave").disabled = false;
    $("editAccountOverlay").classList.remove("hidden");
    $("editAccountName").focus();
}

/* The monthly EMI box shows only for a fixed loan. */
function syncEditPlan() {
    $("editEmiRow").classList.toggle("hidden", !(editingAccount && editingAccount.type === "DEBT" && $("editPlan").value === "FIXED"));
}

function closeEditAccount() {
    $("editAccountOverlay").classList.add("hidden");
}

async function saveEditAccount() {
    const a = editingAccount;
    if (!a) return;
    const name = $("editAccountName").value.trim();
    const openingRaw = $("editOpening").value;
    const opening = Number(openingRaw);
    const isCard = a.type === "CREDIT_CARD";
    const isVehicle = a.type === "VEHICLE";
    const isDebt = a.type === "DEBT";
    const fixedLoan = isDebt && $("editPlan").value === "FIXED";
    const emi = Number($("editEmi").value);
    const year = $("editYear").value === "" ? null : Number($("editYear").value);
    const limit = Number($("editLimit").value);
    const dueDay = parseDueDay($("editDue").value);
    const statementDay = parseDueDay($("editStatement").value);
    const error = $("editAccountError");
    if (!name) { error.textContent = "Name is required."; return; }
    if (openingRaw === "" || !Number.isFinite(opening) || opening < 0) { error.textContent = "Enter an amount of zero or more."; return; }
    if (isCard && !(limit > 0)) { error.textContent = "A credit card needs a credit limit greater than zero."; return; }
    if (isCard && dueDay === undefined) { error.textContent = "The due day must be a day of the month, from 1 to 31."; return; }
    if (isCard && statementDay === undefined) { error.textContent = "The statement day must be a day of the month, from 1 to 31."; return; }
    if (fixedLoan && !(emi > 0)) { error.textContent = "A fixed loan needs a monthly EMI greater than zero."; return; }
    if (isVehicle && year != null && !(Number.isInteger(year) && year >= 1900 && year <= new Date().getFullYear())) {
        error.textContent = `Year bought must be between 1900 and ${new Date().getFullYear()}.`;
        return;
    }

    $("editAccountSave").disabled = true;
    try {
        await api(`/api/accounts/${a.id}`, {
            method: "PUT",
            body: { name, type: a.type, level: a.level, parentId: a.parentId,
                    openingBalance: opening, targetAmount: a.targetAmount, notes: a.notes }
        });
        if (isCard) {
            await api(`/api/credit-cards/${a.id}`, { method: "PUT", body: { creditLimit: limit, dueDay, statementDay } });
        }
        if (isDebt) {
            await api(`/api/debts/${a.id}`, { method: "PUT", body: { emiAmount: fixedLoan ? emi : null } });
        }
        if (isVehicle) {
            await api(`/api/vehicles/${a.id}`, { method: "PUT", body: { numberPlate: $("editPlate").value.trim() || null, yearBought: year } });
        }
        closeEditAccount();
        await refreshData();
    } catch (e) {
        error.textContent = e.message;
        $("editAccountSave").disabled = false;
    }
}

async function deleteEditedAccount() {
    const a = editingAccount;
    if (!a || !confirm(`Delete "${a.name}"? This cannot be undone.`)) return;
    $("editAccountDelete").disabled = true;
    try {
        await api(`/api/accounts/${a.id}`, { method: "DELETE" });
        closeEditAccount();
        await refreshData();
    } catch (e) {
        $("editAccountError").textContent = e.message;   // e.g. it still has transactions or child accounts
    } finally {
        $("editAccountDelete").disabled = false;
    }
}

/* Switches the account off, or on again. */
async function toggleEditedAccount() {
    const a = editingAccount;
    if (!a) return;
    const active = a.active !== false;
    if (active && !confirm(`Deactivate "${a.name}"? Nothing can be done with it until you activate it again.`)) return;
    $("editAccountToggle").disabled = true;
    try {
        await api(`/api/accounts/${a.id}/${active ? "deactivate" : "activate"}`, { method: "POST" });
        closeEditAccount();
        await refreshData();
    } catch (e) {
        $("editAccountError").textContent = e.message;
    } finally {
        $("editAccountToggle").disabled = false;
    }
}

/* Reset balance (and Write off, for a debt you owe): asks what happened to the money, then brings the balance to zero with a Balance Adjustment
   transaction that keeps the note. */
function askWhatHappened(a, { writeOff = false } = {}) {
    const liability = LIABILITIES.has(a.type);
    const f = {};
    openLedgerDialog(writeOff ? `Write off · ${a.name}` : `Reset balance · ${a.name}`, writeOff ? "Write off" : "Reset to zero", body => {
        f.note = el("input", { type: "text", value: "", maxLength: 255, autocomplete: "off",
            placeholder: writeOff ? "The bank waived it" : "Withdrawn in cash and closed the account" });
        body.append(el("div", { className: "edit-hint", textContent:
            `${liability ? "It owes" : "It holds"} ${inCurrency(a.balance, a.currency)}. ${writeOff ? "Writing it off lets all of it go:" : "Resetting"} saves one Balance Adjustment ` +
            "transaction dated today that takes it to zero, with your note on it, so the history stays and you can see what happened. It does not count as spending or earning." }),
            field(writeOff ? "Why is it being let go?" : "What happened to the money?", f.note));
        f.note.focus();
    });
    dlg.onPrimary = () => {
        const note = f.note.value.trim();
        if (!note) return ledgerFail(writeOff ? "Say why it is being let go." : "Say what happened to the money.");
        runLedger(() => api(`/api/accounts/${a.id}/reset-balance`, { method: "POST", body: { note } }));
    };
}

function resetEditedBalance() {
    const a = editingAccount;
    if (!a) return;
    closeEditAccount();
    askWhatHappened(a);
}

/* The Write off button on a loan's or card's row: the lender lets the debt go. */
export function openWriteOffDebt(account) {
    askWhatHappened(account, { writeOff: true });
}

export function initEditAccount() {
    $("editAccountToggle").addEventListener("click", toggleEditedAccount);
    $("editAccountReset").addEventListener("click", resetEditedBalance);
    $("editAccountSave").addEventListener("click", saveEditAccount);
    $("editAccountDelete").addEventListener("click", deleteEditedAccount);
    $("editPlan").addEventListener("change", syncEditPlan);
    $("editAccountCancel").addEventListener("click", closeEditAccount);
    $("editAccountClose").addEventListener("click", closeEditAccount);
}
