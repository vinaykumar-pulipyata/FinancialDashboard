/* People behind Receivables. Add one, rename one, set what they already owed before you started, or remove one.
   Record lending and repayments with Loans Given (Expense) and Loans Collected (Income). */

import { refreshData } from "../core/refresh.js";
import { loans } from "../core/state.js";
import { $, api, el, rupees } from "../core/util.js";
import { dlg, ledgerFail, openLedgerDialog, runLedger } from "./ledger.js";
import { field } from "./transaction-form.js";

let editingPerson = null;

/* person is null to add someone. fromEarlier opens it from the "Earlier loans (no name)" row: the whole earlier amount
   is filled in, so only a name is needed, and a smaller amount gives just part of it. */
export function openPerson(person, { fromEarlier = false } = {}) {
    editingPerson = person;
    $("personTitle").textContent = person ? `Edit person · ${person.name}` : fromEarlier ? "Who owes you the earlier loans?" : "Add person";
    $("personName").value = person ? person.name : "";
    $("personOpening").value = person ? (Number(person.openingAmount) > 0 ? person.openingAmount : "")
        : fromEarlier && Number(loans.unnamed) > 0 ? loans.unnamed : "";
    const room = Number(loans.unnamed) + (person ? Number(person.openingAmount) : 0);
    $("personHint").textContent = fromEarlier
        ? `Enter the name. The amount is what this person owes from your earlier loans (up to ${rupees(room)}). Lower it to give just part, and the rest stays as "Earlier loans (no name)".`
        : room > 0
        ? `Up to ${rupees(room)} of your earlier loans can be given to a person. It comes out of "Earlier loans (no name)".`
        : "You have no earlier loans to give to a person, so leave this blank.";
    $("personDelete").classList.toggle("hidden", !person);
    $("personWriteOff").classList.toggle("hidden", !(person && Number(person.owed) !== 0));
    $("personError").textContent = "";
    $("personSave").disabled = false;
    $("personOverlay").classList.remove("hidden");
    $("personName").focus();
}

/* Letting go of what a person owes you: all of it, with a note of why. Their owed amount and the Receivables total come down; no account is touched. */
function openWriteOffPerson(person) {
    const f = {};
    openLedgerDialog(`Write off · ${person.name}`, "Write off", body => {
        f.note = el("input", { type: "text", value: "", maxLength: 255, autocomplete: "off", placeholder: "He lost his job, so I let it go" });
        body.append(el("div", { className: "edit-hint", textContent:
            (Number(person.owed) < 0
                ? `You owe ${person.name} ${rupees(Math.abs(person.owed))} (they paid back more than they owed). Writing it off lets all of it go: it comes down to zero and the Receivables total goes up by that amount, `
                : `${person.name} owes you ${rupees(person.owed)}. Writing it off lets all of it go: what they owe and the Receivables total come down by that amount, `) +
            "with today's date and your note kept. No bank account changes and it is not income or spending." }),
            field("Why are you letting it go?", f.note));
        f.note.focus();
    });
    dlg.onPrimary = () => {
        const note = f.note.value.trim();
        if (!note) return ledgerFail("Say why you are letting it go.");
        runLedger(() => api(`/api/borrowers/${person.id}/write-off`, { method: "POST", body: { note } }));
    };
}

function writeOffEditedPerson() {
    const person = editingPerson;
    if (!person) return;
    closePerson();
    openWriteOffPerson(person);
}

function closePerson() {
    $("personOverlay").classList.add("hidden");
}

async function savePerson() {
    const name = $("personName").value.trim();
    const raw = $("personOpening").value;
    const error = $("personError");
    if (!name) { error.textContent = "Enter the person's name."; return; }
    if (raw !== "" && !(Number(raw) >= 0)) { error.textContent = "The amount cannot be negative."; return; }
    $("personSave").disabled = true;
    try {
        const body = { name, openingAmount: raw === "" ? 0 : Number(raw) };
        await api(editingPerson ? `/api/borrowers/${editingPerson.id}` : "/api/borrowers",
            { method: editingPerson ? "PUT" : "POST", body });
        closePerson();
        await refreshData();
    } catch (e) {
        error.textContent = e.message;
        $("personSave").disabled = false;
    }
}

async function deletePerson() {
    const person = editingPerson;
    if (!person || !confirm(`Remove ${person.name}? Anything they already owed goes back into "Earlier loans (no name)".`)) return;
    $("personDelete").disabled = true;
    try {
        await api(`/api/borrowers/${person.id}`, { method: "DELETE" });
        closePerson();
        await refreshData();
    } catch (e) {
        $("personError").textContent = e.message;   // e.g. they still have loan transactions
    } finally {
        $("personDelete").disabled = false;
    }
}

export function initPerson() {
    $("personSave").addEventListener("click", savePerson);
    $("personDelete").addEventListener("click", deletePerson);
    $("personWriteOff").addEventListener("click", writeOffEditedPerson);
    $("personCancel").addEventListener("click", closePerson);
    $("personClose").addEventListener("click", closePerson);
    $("personName").addEventListener("keydown", e => { if (e.key === "Enter") savePerson(); });
}
