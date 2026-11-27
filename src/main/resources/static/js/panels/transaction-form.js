/* Income / Expense / Transfer popup. Also used to edit and delete an existing transaction. */

import { clientLog, describeError } from "../core/client-log.js";
import { ADJUSTMENT_KEYS, CURRENCIES, LIABILITY_PAYMENT_KEY, PERSON_CATEGORY_KEYS, TYPE_LABELS } from "../core/constants.js";
import { refreshData } from "../core/refresh.js";
import { accountCurrency, rateOn } from "../core/currency.js";
import { accounts, ledger, loans, setLoans, transactions } from "../core/state.js";
import { $, api, collapseOut, el, flashOnNext, rupees } from "../core/util.js";
import { saveLiabilityPayment } from "./liability-payment.js";
import { isJewelleryAccount } from "./jewellery.js";
import { isRecurringDepositAccount } from "./recurring-deposits.js";
import { isPortfolioAccount } from "./portfolio.js";
import { splitField } from "./split-form.js";
import { txnTagField } from "./transaction-tags.js";

export const txn = { kind: null, fields: {}, categories: [], editing: null, liability: null };   // liability: set while the Pay window (liability-payment.js) uses this form
const TXN_TITLES = { INCOME: "+  Income", EXPENSE: "−  Expense", TRANSFER: "⇄  Transfer" };   // shown when editing; a new one says "New transaction" over the kind buttons

export function field(label, ...controls) {
    return el("div", { className: "txn-field" }, el("span", { textContent: label }),
        controls.length > 1 ? el("div", { className: "txn-inline" }, ...controls) : controls[0]);
}

/* Active accounts, plus any account the transaction being edited already uses (it may have been deactivated). */
/* Which kinds of account each form offers. Money is spent from, and earned into, accounts that hold money; a card is
   also spent from (it raises what is owed). Gold, investments, property, vehicles and loans are not spent from. A
   Transfer offers every account, since moving money into an investment or gold is a normal transfer. */
export const EARN_INTO_TYPES = new Set(["BANK", "CASH", "WALLET", "GOAL_FUND"]);
const SPEND_FROM_TYPES = new Set([...EARN_INTO_TYPES, "CREDIT_CARD"]);

/* How many transactions each account has been the source (spending) or the destination (earning) of. */
function usageCounts(forSpending) {
    const counts = new Map();
    for (const t of transactions) {
        const id = forSpending ? t.fromAccountId : t.toAccountId;
        if (id != null) counts.set(id, (counts.get(id) || 0) + 1);
    }
    return counts;
}

/* Notes typed on earlier transactions in the chosen category (with its sub categories) or, once a sub category is picked, in that sub category only,
   most used first (at most NOTE_SUGGESTIONS), for the Note field. Nothing is offered until a category is chosen, so the list is never everything at once. */
const NOTE_SUGGESTIONS = 15;
function previousNotes(kind, categoryId, subCategoryId) {
    const ids = new Set();
    if (subCategoryId) ids.add(Number(subCategoryId));
    else if (categoryId) {
        ids.add(Number(categoryId));
        for (const c of txn.categories) if (Number(c.parentId) === Number(categoryId)) ids.add(c.id);
    }
    if (ids.size === 0 && kind !== "TRANSFER") return [];   // a transfer has no category, so it gets its own most used notes
    const counts = new Map();
    for (const t of transactions) {
        const n = t.kind === kind && (kind === "TRANSFER" || ids.has(t.categoryId)) && t.note ? t.note.trim() : "";
        if (n) counts.set(n, (counts.get(n) || 0) + 1);
    }
    return [...counts].sort((a, b) => b[1] - a[1]).slice(0, NOTE_SUGGESTIONS).map(([n]) => n);
}

/* Refills the Note field's suggestions for the category and sub category now chosen. */
function refreshNoteSuggestions() {
    const f = txn.fields;
    if (!f.noteChoices) return;
    f.noteChoices.innerHTML = "";
    f.noteChoices.append(...previousNotes(txn.kind, f.category && f.category.value, f.subCategory && f.subCategory.value).map(n => el("option", { value: n })));
}

/* Fills a dropdown with the accounts that can be picked. excludeId leaves one account out (the Transfer form uses
   it so "To" never lists what is chosen in "From"); the most used accounts come first; types, when given, keeps only those kinds of account. */
function fillAccountOptions(select, keepId = null, forSpending = false, excludeId = null, types = null, rupeesOnly = false) {
    select.innerHTML = "";
    const used = usageCounts(forSpending);
    // Receivables is not chosen here: it is worked out from Loans Given and Loans Collected. When editing,
    // keep the account the transaction already uses.
    const choices = accounts.filter(a => (a.active || a.id === keepId) && (a.type !== "RECEIVABLE" || a.id === keepId) && a.id !== excludeId
        && (!types || types.has(a.type) || a.id === keepId) && (!rupeesOnly || (a.currency || "INR") === "INR" || a.id === keepId) && !(ledger.configured && a.id === ledger.accountId) && !isPortfolioAccount(a.id) && !isJewelleryAccount(a.id) && !isRecurringDepositAccount(a.id));
    choices.sort((x, y) => (used.get(y.id) || 0) - (used.get(x.id) || 0));   // most used first; ties keep their order
    for (const a of choices) {
        // A ring-fenced fund takes money in but not out, so it can't be chosen as the source. When editing, keep
        // the account the transaction already uses so a note or date can still be changed.
        const locked = forSpending && a.ringFenced && a.id !== keepId;
        select.append(el("option", { value: a.id, disabled: locked,
            textContent: `${a.name} (${TYPE_LABELS[a.type]}${a.currency && a.currency !== "INR" ? " · " + a.currency : ""})${a.ringFenced ? " 🔒 ring-fenced" : ""}` }));
    }
    return select;
}

export function accountSelect(keepId = null, forSpending = false, excludeId = null, types = null, rupeesOnly = false) {
    return fillAccountOptions(el("select"), keepId, forSpending, excludeId, types, rupeesOnly);
}

/* date (yyyy-mm-dd) pre-fills a new transaction; today when empty. */
export async function openTransaction(kind, existing = null, date = null) {
    txn.kind = kind;
    txn.editing = existing;
    txn.liability = null;
    txn.fields = {};
    const f = txn.fields;
    $("txnTitle").textContent = existing ? "Edit " + TXN_TITLES[kind] : "New transaction";
    showTxnKinds(!existing, kind);
    $("txnDelete").classList.toggle("hidden", !existing);
    $("txnModal").className = "modal txn-" + kind.toLowerCase();
    $("txnError").textContent = "";
    $("txnSave").disabled = false;
    const body = $("txnBody");
    body.innerHTML = "";

    f.date = el("input", { type: "date", value: existing ? existing.date : date || new Date().toLocaleDateString("en-CA") });
    f.amount = el("input", { type: "number", min: 0, step: "any", value: existing ? existing.amount : "" });
    f.amountRow = field("Amount (₹)", f.amount);
    body.append(field("Date", f.date), f.amountRow);

    if (kind === "TRANSFER") {
        f.fee = el("input", { type: "number", min: 0, step: "any", value: existing && existing.fee ? existing.fee : "" });
        f.from = accountSelect(existing && existing.fromAccountId, true);
        if (existing) f.from.value = existing.fromAccountId;
        f.to = accountSelect(existing && existing.toAccountId, false, Number(f.from.value) || null);
        if (existing) f.to.value = existing.toAccountId;
        // Whatever is chosen in "From" is taken out of "To". The choice in "To" stays if it is still available.
        f.from.addEventListener("change", () => {
            const chosen = f.to.value;
            fillAccountOptions(f.to, null, false, Number(f.from.value) || null);
            if ([...f.to.options].some(o => o.value === chosen)) f.to.value = chosen;   // an unmatched value would leave it blank
        });
        // What arrived, asked only when the two accounts are in different currencies (pre-filled from the saved rates until typed over).
        f.received = el("input", { type: "number", min: 0, step: "any", value: existing && existing.toAmount != null ? existing.toAmount : "" });
        f.receivedEdited = !!(existing && existing.toAmount != null);
        f.received.addEventListener("input", () => { f.receivedEdited = true; });
        f.feeRow = field("Fees (optional)", f.fee);
        f.receivedRow = field("Amount received", f.received);
        f.to.addEventListener("change", syncCurrencies);
        f.from.addEventListener("change", syncCurrencies);   // after the handler above, which refills "To"
        f.date.addEventListener("change", suggestReceived);
        f.amount.addEventListener("input", suggestReceived);
        const sell = infoTip(SELL_HELP, "How to record selling an investment or gold");
        body.append(f.feeRow, field("From", f.from, sell.button), sell.line, field("To", f.to), f.receivedRow);
    } else {
        txn.categories = await api(`/api/categories?kind=${kind}`);
        body.append(...categoryFields(existing));
        const accountId = existing && (kind === "INCOME" ? existing.toAccountId : existing.fromAccountId);
        f.account = accountSelect(accountId, kind === "EXPENSE", null, kind === "EXPENSE" ? SPEND_FROM_TYPES : EARN_INTO_TYPES);
        if (existing) f.account.value = accountId;
        f.account.addEventListener("change", syncCurrencies);
        f.accountRow = field("Account", f.account);
        body.append(f.accountRow);
        if (kind === "EXPENSE") {   // an ordinary expense can be shared with people from the Receivables list, or paid by one of them
            f.paidBy = el("select", {}, el("option", { value: "", textContent: "Me (from my account)" }),
                ...loans.borrowers.map(b => el("option", { value: b.id, textContent: b.name })));
            if (existing && existing.paidByBorrowerId != null) f.paidBy.value = existing.paidByBorrowerId;
            f.myShare = el("input", { type: "number", min: 0, step: "any", value: existing && existing.ownShare != null ? existing.ownShare : "" });
            f.paidByRow = field("Paid by", f.paidBy);   // only offered once the Split box is ticked: by default every expense is the user's own
            f.myShareRow = field("Your share of the bill (₹)", f.myShare);
            f.paidByHint = el("div", { className: "edit-hint", textContent: "Nothing leaves your accounts. Your share is what you owe them, shown under their name in Receivables as \"You owe\", and it is the only part counted in Stats and Budgets." });
            f.split = splitField(existing, () => f.amount.value);
            f.amount.addEventListener("input", f.split.refresh);
            f.split.top.append(f.paidByRow, f.myShareRow, f.paidByHint);   // inside the Split box: Paid by first, then how it is shared, then the people
            body.append(f.split.node);
            // Loans Given is money handed over, not a shared bill: the split box is only for the other categories.
            const syncSplit = () => {
                const c = txn.categories.find(c => String(c.id) === f.category.value);
                f.split.node.classList.toggle("hidden", !!(c && PERSON_CATEGORY_KEYS.has(c.systemKey)));
            };
            const syncPaidBy = () => {
                const splitting = !f.split.node.classList.contains("hidden") && f.split.isOn();   // Paid by belongs to a shared expense
                if (!splitting) f.paidBy.value = "";                                              // not shared: all of it is paid by you
                const wasHidden = f.paidByRow.classList.contains("hidden");
                f.paidByRow.classList.toggle("hidden", !splitting);
                if (splitting && wasHidden && f.paidByRow.scrollIntoView) f.paidByRow.scrollIntoView({ block: "nearest" });   // the form scrolls: bring the new row into view
                const theirs = splitting && !!f.paidBy.value;   // somebody else paid: no account of yours, only your share
                f.accountRow.classList.toggle("hidden", theirs);
                f.myShareRow.classList.toggle("hidden", !theirs);
                f.paidByHint.classList.toggle("hidden", !theirs);
                f.split.showPeople(!theirs);
            };
            f.category.addEventListener("change", () => { syncSplit(); syncPaidBy(); });
            f.paidBy.addEventListener("change", syncPaidBy);
            f.split.onToggle(syncPaidBy);
            syncSplit();
            syncPaidBy();
        }
        clientLog("DEBUG", "txn.form.opened", `kind=${kind} editing=${existing ? existing.id : "no"} serverCategories=${txn.categories.length} listed=${f.category.children.length - 1} accounts=${f.account.children.length}`);
    }

    f.note = el("input", { type: "text", maxLength: 255, value: existing && existing.note ? existing.note : "", autocomplete: "off" });
    f.note.setAttribute("list", "txnNoteList");
    const suggestions = el("datalist", { id: "txnNoteList" });
    f.noteChoices = suggestions;
    body.append(suggestions);
    refreshNoteSuggestions();
    f.description = el("textarea", { rows: 2, value: existing && existing.description ? existing.description : "" });
    body.append(field("Note", f.note), field("Description", f.description));

    // Tags: worked out by the server from the note and description, then the sub category and the category; a person can change them.
    // A new transaction follows the suggestions until its tags are touched; one being edited keeps its saved tags unless Suggest is pressed.
    f.tags = txnTagField(existing && existing.tags ? existing.tags : [], { edited: !!existing, onSuggest: () => askForTags(true) });
    body.append(field("Tags", f.tags.node));
    for (const input of [f.note, f.description]) input.addEventListener("input", () => askForTags(false));
    for (const select of [f.category, f.subCategory]) if (select) select.addEventListener("change", () => { askForTags(false); refreshNoteSuggestions(); });

    const needed = kind === "TRANSFER" ? 2 : 1;
    if (accounts.filter(a => a.active).length < needed) {
        $("txnError").textContent = needed === 2 ? "You need at least two accounts to transfer." : "Add an account first.";
        $("txnSave").disabled = true;
    }
    syncCurrencies();
    $("txnOverlay").classList.remove("hidden");
    f.amount.focus();
}

/* The labels follow the currency of the account the amount belongs to (the one chosen, or "From" on a transfer); a transfer between two
   currencies also shows what arrived, in the currency of "To". */
function syncCurrencies() {
    const f = txn.fields;
    if (!f.amountRow) return;
    const own = accountCurrency(Number((txn.kind === "TRANSFER" ? f.from : f.account).value));
    f.amountRow.children[0].textContent = `Amount (${CURRENCIES[own].symbol})`;
    f.amount.step = CURRENCIES[own].decimals === 0 ? "1" : "any";
    if (txn.kind !== "TRANSFER") return;
    const to = accountCurrency(Number(f.to.value));
    f.feeRow.children[0].textContent = `Fees (optional, ${CURRENCIES[own].symbol})`;
    const cross = own !== to;
    f.receivedRow.classList.toggle("hidden", !cross);
    if (!cross) { f.receivedEdited = false; f.received.value = ""; return; }
    f.receivedRow.children[0].textContent = `Amount received (${CURRENCIES[to].symbol})`;
    f.received.step = CURRENCIES[to].decimals === 0 ? "1" : "any";
    suggestReceived();
}

/* Fills in what would arrive at the saved rates, until the person has typed the real figure (the bank's rate is rarely the saved one). */
function suggestReceived() {
    const f = txn.fields;
    if (!f.received || f.receivedEdited || f.receivedRow.classList.contains("hidden")) return;
    const from = accountCurrency(Number(f.from.value)), to = accountCurrency(Number(f.to.value));
    const amount = Number(f.amount.value), fromRate = rateOn(from, f.date.value), toRate = rateOn(to, f.date.value);
    if (!(amount > 0) || !fromRate || !toRate) { f.received.value = ""; return; }
    f.received.value = (amount * fromRate / toRate).toFixed(CURRENCIES[to].decimals);
}

/* The category the form has chosen: the sub category if there is one, else the category. */
function chosenCategoryId() {
    const f = txn.fields;
    return Number((f.subCategory && f.subCategory.value) || (f.category && f.category.value)) || null;
}

/* Asks the server which tags this note, description and category would get, a moment after the last keystroke. force = the Suggest
   button: the answer replaces the tags and counts as chosen; otherwise it only fills in a transaction nobody has tagged by hand. */
let tagTimer = null, tagAsk = 0;
function askForTags(force) {
    const f = txn.fields;
    if (!f.tags || (!force && f.tags.isEdited())) return;
    clearTimeout(tagTimer);
    const ask = ++tagAsk;
    tagTimer = setTimeout(async () => {
        try {
            const found = await api("/api/transactions/suggest-tags", { method: "POST", body: { categoryId: chosenCategoryId(), note: f.note.value, description: f.description.value } });
            if (ask !== tagAsk || !txn.fields.tags) return;   // a newer question, or the form was closed
            force ? f.tags.replaceWith(found) : f.tags.setSuggested(found);
        } catch (e) {
            clientLog("WARN", "txn.tags.suggest-failed", describeError(e));   // the server still tags the transaction when it is saved
        }
    }, force ? 0 : 350);
}

/* A small (i) button that shows or hides a line of help. Returns the button and the line; put both in the form. */
export function infoTip(text, label) {
    const button = el("button", { className: "txn-link", type: "button", textContent: "ⓘ", title: text });
    button.setAttribute("aria-label", label);
    const line = el("div", { className: "edit-hint hidden", textContent: text });
    button.addEventListener("click", () => line.classList.toggle("hidden"));
    return { button, line };
}

/* What the (i) beside From says on a Transfer: there is no Sell button, a sale is a transfer. */
const SELL_HELP = "Selling an investment or gold? Record it as a transfer: From the investment or gold account, To your bank or cash account. "
    + "If it sold for a different price than the value shown, change the account's value in Edit account first. "
    + "Mutual Funds and Shares have a Withdraw button on their own cards.";

/* What the (i) beside Category says: categories are made in the Edit categories window only. */
const NEW_CATEGORY_HELP = "To add a new category or sub category, open the ☰ menu on the left and choose Edit / Add categories under Categories.";

/* Category and sub category selectors, and (for Loans Given / Collected) the person, who can be added on the spot. */
function categoryFields(existing = null) {
    const f = txn.fields;
    f.category = el("select");
    f.subCategory = el("select");
    f.person = el("select");

    // When editing, keep the saved category selectable even if it has since been deactivated.
    const saved = existing && existing.categoryId != null ? txn.categories.find(c => c.id === existing.categoryId) : null;
    const keep = new Set(saved ? [saved.id, saved.parentId] : []);
    const listed = c => c.systemKey !== LIABILITY_PAYMENT_KEY && !ADJUSTMENT_KEYS.has(c.systemKey) && (c.active || keep.has(c.id));   // payments are made with a liability's Pay button, adjustments with an account's Reset balance

    const fillSub = () => {
        const previous = f.subCategory.value;
        f.subCategory.innerHTML = "";
        f.subCategory.append(el("option", { value: "", textContent: "Select sub category (optional)" }));
        txn.categories.filter(c => listed(c) && f.category.value && String(c.parentId) === f.category.value)
            .forEach(c => f.subCategory.append(el("option", { value: c.id, textContent: c.name })));
        f.subCategory.value = previous;
    };
    const fill = () => {
        const previous = f.category.value;
        f.category.innerHTML = "";
        f.category.append(el("option", { value: "", textContent: "Select category" }));
        txn.categories.filter(c => listed(c) && c.parentId == null)
            .forEach(c => f.category.append(el("option", { value: c.id, textContent: c.name })));
        f.category.value = previous;
        fillSub();
    };
    // Loans Given and Loans Collected take a person instead of a sub category.
    const selectedCategory = () => txn.categories.find(c => String(c.id) === f.category.value);
    const isLoan = () => !!(selectedCategory() && PERSON_CATEGORY_KEYS.has(selectedCategory().systemKey));
    const fillPerson = () => {
        const previous = f.person.value;
        f.person.innerHTML = "";
        f.person.append(el("option", { value: "", textContent: "Select person" }));
        for (const b of loans.borrowers) {
            const owed = Number(b.owed);
            f.person.append(el("option", { value: b.id,
                textContent: `${b.name} · ${owed < 0 ? "you owe " + rupees(-owed) : "owes " + rupees(owed)}` }));
        }
        f.person.value = previous;
    };
    const syncLoan = () => {
        const loan = isLoan();
        f.subRow.classList.toggle("hidden", loan);
        f.personRow.classList.toggle("hidden", !loan);
        f.personAdd.classList.add("hidden");
    };
    f.category.addEventListener("change", () => { fillSub(); syncLoan(); });
    fill();
    if (saved) {
        if (saved.parentId != null) {
            f.category.value = saved.parentId;
            fillSub();
            f.subCategory.value = saved.id;
        } else {
            f.category.value = saved.id;
            fillSub();
        }
    }

    const addPerson = inlineAdd("+ Add person", async name => {
        const created = await api("/api/borrowers", { method: "POST", body: { name, openingAmount: 0 } });
        setLoans(await api("/api/loans"));
        fillPerson();
        f.person.value = created.id;
    });
    f.subRow = field("Sub category", f.subCategory);
    f.personRow = field("Person", f.person, addPerson.button);
    f.personAdd = addPerson.form;
    fillPerson();
    if (existing && existing.borrowerId) f.person.value = existing.borrowerId;
    syncLoan();

    // New categories and sub categories are made in one place only, so there is a single list to keep right.
    const tip = infoTip(NEW_CATEGORY_HELP, "How to add a category");

    return [
        field("Category", f.category, tip.button), tip.line,
        f.subRow,
        f.personRow, f.personAdd
    ];
}


/* A "+ Add ..." link that reveals a small name box with Add and Cancel. */
function inlineAdd(label, onAdd) {
    const button = el("button", { className: "txn-link", textContent: label, type: "button" });
    const input = el("input", { type: "text", placeholder: "Name", autocomplete: "off" });
    const add = el("button", { className: "btn btn-primary", textContent: "Add", type: "button" });
    const cancel = el("button", { className: "btn btn-secondary", textContent: "Cancel", type: "button" });
    const form = el("div", { className: "txn-inline hidden" }, input, add, cancel);
    form.style.margin = "-6px 0 12px";

    const show = visible => {
        form.classList.toggle("hidden", !visible);
        if (visible) input.focus();
        else input.value = "";
    };
    const submit = async () => {
        const name = input.value.trim();
        if (!name) return;
        try {
            await onAdd(name);
            $("txnError").textContent = "";
            show(false);
        } catch (e) {
            $("txnError").textContent = e.message;
            clientLog("WARN", "form.inline-add.failed", `${label} ${describeError(e)}`);   // label is the fixed button text, e.g. "+ Add category"
        }
    };
    button.addEventListener("click", () => show(true));
    cancel.addEventListener("click", () => show(false));
    add.addEventListener("click", submit);
    input.addEventListener("keydown", e => {
        if (e.key === "Enter") { e.preventDefault(); submit(); }
        if (e.key === "Escape") show(false);
    });
    return { button, form };
}

/* The Income | Expense | Transfer buttons: only for a new transaction (an existing one keeps its kind). */
export function showTxnKinds(show, kind) {
    $("txnKinds").classList.toggle("hidden", !show);
    $("txnKinds").querySelectorAll("[data-kind]").forEach(b => b.classList.toggle("active", b.dataset.kind === kind));
}

/* Changing kind on a new transaction starts that form, keeping the date and amount already typed. */
async function switchTxnKind(kind) {
    if (txn.editing || txn.liability || kind === txn.kind) return;
    const { date, amount } = txn.fields;
    const typed = { date: date.value, amount: amount.value };
    try {
        await openTransaction(kind, null, typed.date);
        txn.fields.amount.value = typed.amount;
    } catch (e) {
        $("txnError").textContent = e.message;
    }
}

export function closeTransaction() {
    clearTimeout(tagTimer);
    tagAsk++;   // a tag suggestion still on its way is dropped
    $("txnOverlay").classList.add("hidden");
}

/* Warns when more is collected than the person owes. The user can still go ahead. */
function confirmRepayment(body, category) {
    const person = loans.borrowers.find(b => b.id === body.borrowerId);
    if (!person) return true;
    let owed = Number(person.owed);
    const e = txn.editing;
    if (e && e.borrowerId === body.borrowerId && e.categoryId === category.id) owed += Number(e.amount);   // its own earlier amount no longer counts
    if (body.amount <= owed) return true;
    return confirm(`${person.name} only owes ${rupees(Math.max(owed, 0))}. Save anyway?`);
}

async function saveTransaction() {
    if (txn.liability) return saveLiabilityPayment();
    const f = txn.fields;
    const amount = Number(f.amount.value);
    if (!amount || amount <= 0) {
        $("txnError").textContent = "Enter an amount greater than zero.";
        return;
    }
    if (!f.date.value) {
        $("txnError").textContent = "Date is required.";
        return;
    }
    const body = {
        date: f.date.value, kind: txn.kind, amount,
        note: f.note.value.trim() || null, description: f.description.value.trim() || null,
        fromAccountId: null, toAccountId: null, fee: null, categoryId: null, borrowerId: null, toAmount: null,
        tags: null, splits: null, paidByBorrowerId: null, ownShare: null
    };
    const typedTags = f.tags.tags();   // also takes in what is still typed in the box
    if (f.tags.isEdited()) body.tags = typedTags;   // otherwise the server works them out on save
    if (txn.kind === "TRANSFER") {
        body.fromAccountId = Number(f.from.value);
        body.toAccountId = Number(f.to.value);
        body.fee = f.fee.value ? Number(f.fee.value) : null;
        if (body.fromAccountId === body.toAccountId) {
            $("txnError").textContent = "From and To must be different accounts.";
            return;
        }
        if (!f.receivedRow.classList.contains("hidden")) {   // two currencies: what arrived is part of the transfer
            body.toAmount = Number(f.received.value);
            if (!(body.toAmount > 0)) {
                $("txnError").textContent = `Enter the amount that arrived in the ${accountCurrency(body.toAccountId)} account.`;
                return;
            }
        }
    } else {
        const account = Number(f.account.value);
        const paidByPerson = txn.kind === "EXPENSE" && f.paidBy && f.paidBy.value && !f.paidByRow.classList.contains("hidden") && f.split.isOn();
        if (txn.kind === "INCOME") body.toAccountId = account;
        else if (!paidByPerson) body.fromAccountId = account;
        const category = txn.categories.find(c => String(c.id) === f.category.value);
        if (category && PERSON_CATEGORY_KEYS.has(category.systemKey)) {
            // Loans Given or Loans Collected: it must say who the loan is with.
            body.categoryId = category.id;
            body.borrowerId = Number(f.person.value) || null;
            if (!body.borrowerId) {
                $("txnError").textContent = "Choose who this loan is with.";
                return;
            }
            if (txn.kind === "INCOME" && !confirmRepayment(body, category)) return;
        } else {
            body.categoryId = Number(f.subCategory.value || f.category.value) || null;
        }
        if (paidByPerson) {
            const share = Number(f.myShare.value);
            if (!(share > 0)) {
                $("txnError").textContent = "Enter your share of the bill.";
                return;
            }
            if (share > amount) {
                $("txnError").textContent = "Your share cannot be more than the bill.";
                return;
            }
            body.paidByBorrowerId = Number(f.paidBy.value);
            body.ownShare = share;
        } else if (f.split && !f.split.node.classList.contains("hidden")) {
            const split = f.split.result();
            if (split.error) {
                $("txnError").textContent = split.error;
                return;
            }
            body.splits = split.shares;
        }
    }
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
        $("txnError").textContent = e.message;
        save.disabled = false;
    }
}

async function deleteTransaction() {
    const editing = txn.editing;
    if (!editing || !confirm("Delete this transaction? This cannot be undone.")) return;
    $("txnDelete").disabled = true;
    try {
        await api(`/api/transactions/${editing.id}`, { method: "DELETE" });
        closeTransaction();
        await collapseOut(`[data-txn-id="${editing.id}"]`);   // the row folds away before the list is redrawn without it
        await refreshData();
    } catch (e) {
        $("txnError").textContent = e.message;
    } finally {
        $("txnDelete").disabled = false;
    }
}

export function initTransactionForm() {
    $("txnDelete").addEventListener("click", deleteTransaction);
    $("txnSave").addEventListener("click", saveTransaction);
    $("txnCancel").addEventListener("click", closeTransaction);
    $("txnClose").addEventListener("click", closeTransaction);
    $("txnKinds").querySelectorAll("[data-kind]").forEach(b => b.addEventListener("click", () => switchTxnKind(b.dataset.kind)));
}
