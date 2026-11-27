/* The Pay button on a loan or credit card: an expense, paid from a bank or cash account, that lowers what is owed. It is
   recorded as an ordinary transaction in the fixed Liability Payments category, with the loan or card as its To account.
   The window is the transaction window (#txnModal); saveTransaction hands over to saveLiabilityPayment while txn.liability
   is set. A fixed loan starts with its EMI as the amount; a custom one starts empty. */

import { LIABILITIES, LIABILITY_PAYMENT_KEY } from "../core/constants.js";
import { refreshData } from "../core/refresh.js";
import { accounts, allCategories, debtEmis } from "../core/state.js";
import { $, api, el, flashOnNext, rupees } from "../core/util.js";
import { accountSelect, closeTransaction, EARN_INTO_TYPES, field, showTxnKinds, txn } from "./transaction-form.js";

/* An expense that names a To account is a payment towards a liability. */
export function isLiabilityPayment(t) {
    return t.kind === "EXPENSE" && t.toAccountId != null;
}

export async function openLiabilityPayment(liability, existing = null) {
    if (!liability) throw new Error("That loan or card could not be found.");
    txn.kind = "EXPENSE";
    txn.editing = existing;
    txn.liability = liability;
    txn.fields = {};
    const f = txn.fields;
    $("txnTitle").textContent = (existing ? "Edit payment · " : "Pay · ") + liability.name;
    $("txnDelete").classList.toggle("hidden", !existing);
    showTxnKinds(false, "EXPENSE");   // a payment is not switched to another kind
    $("txnModal").className = "modal txn-expense";
    $("txnError").textContent = "";
    $("txnSave").disabled = false;
    const body = $("txnBody");
    body.innerHTML = "";

    const emi = debtEmis.get(liability.id);
    f.date = el("input", { type: "date", value: existing ? existing.date : new Date().toLocaleDateString("en-CA") });
    f.amount = el("input", { type: "number", min: 0, step: "any", value: existing ? existing.amount : emi || "" });
    f.account = accountSelect(existing && existing.fromAccountId, true, null, EARN_INTO_TYPES, true);   // a loan or card is paid in rupees
    if (existing) f.account.value = existing.fromAccountId;
    f.note = el("input", { type: "text", maxLength: 255, value: existing && existing.note ? existing.note : "" });
    f.description = el("textarea", { rows: 2, value: existing && existing.description ? existing.description : "" });
    const owed = `${rupees(liability.balance)} owed now` + (emi ? ` · monthly EMI ${rupees(emi)}` : "");
    body.append(el("div", { className: "edit-hint", textContent: owed }),
        field("Date", f.date), field("Amount (₹)", f.amount), field("Pay from", f.account),
        field("Note", f.note), field("Description", f.description));

    if (accounts.filter(a => a.active && !LIABILITIES.has(a.type)).length < 1) {
        $("txnError").textContent = "Add a bank or cash account to pay from.";
        $("txnSave").disabled = true;
    }
    $("txnOverlay").classList.remove("hidden");
    f.amount.focus();
}

/* Warns when more is paid than is owed. The user can still go ahead. */
function confirmOverpayment(liability, amount) {
    let owed = Number(liability.balance);
    const e = txn.editing;
    if (e && e.toAccountId === liability.id) owed += Number(e.amount);   // its own earlier amount no longer counts
    if (amount <= owed) return true;
    return confirm(`${liability.name} only has ${rupees(Math.max(owed, 0))} owed. Save anyway?`);
}

export async function saveLiabilityPayment() {
    const f = txn.fields, liability = txn.liability;
    const error = $("txnError");
    const amount = Number(f.amount.value);
    if (!amount || amount <= 0) { error.textContent = "Enter an amount greater than zero."; return; }
    if (!f.date.value) { error.textContent = "Date is required."; return; }
    if (!f.account.value) { error.textContent = "Choose the account you pay from."; return; }
    const category = allCategories.find(c => c.systemKey === LIABILITY_PAYMENT_KEY);
    if (!category) { error.textContent = "The Liability Payments category is missing. Reload the page."; return; }
    if (!confirmOverpayment(liability, amount)) return;
    const body = {
        date: f.date.value, kind: "EXPENSE", amount, fee: null, categoryId: category.id, borrowerId: null,
        fromAccountId: Number(f.account.value), toAccountId: liability.id,
        note: f.note.value.trim() || null, description: f.description.value.trim() || null
    };
    const save = $("txnSave");
    save.disabled = true;
    try {
        const editing = txn.editing;
        const saved = await api(editing ? `/api/transactions/${editing.id}` : "/api/transactions",
            { method: editing ? "PUT" : "POST", body });
        if (saved && saved.id != null) flashOnNext("txn-row-" + saved.id);
        closeTransaction();
        await refreshData();
    } catch (e) {
        error.textContent = e.message;
        save.disabled = false;
    }
}
