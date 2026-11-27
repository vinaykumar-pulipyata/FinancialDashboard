/* Tags on transactions. The server works them out from the note and description, then the sub category and the category
   (POST /api/transactions/suggest-tags), and keeps what is saved. This file has the tag box of the transaction form and the small
   pills in the list. A tag is in the same form as a diary tag: lower case, no #, spaces as hyphens, letters, numbers, - and _,
   at most 30 characters, at most 10 on a transaction. */

import { el } from "../core/util.js";

const TXN_TAG_MAX = 30;
const TXN_TAGS_MAX = 10;

/* A tag as the server stores it. */
function normalizeTxnTag(raw) {
    return raw.trim().replace(/^#+/, "").trim().toLowerCase().replace(/\s+/g, "-");
}

/* The tags of a transaction as small pills; choosing one calls onPick(tag). */
export function txnTagPills(tags, onPick) {
    const row = el("div", { className: "tag-row txn-tags" });
    for (const tag of tags) {
        const pill = el("span", { className: "tag-pill", role: "button", tabIndex: 0, textContent: "#" + tag, title: "Show the transactions tagged " + tag });
        const pick = e => { e.preventDefault(); e.stopPropagation(); onPick(tag); };
        pill.addEventListener("click", pick);
        pill.addEventListener("keydown", e => { if (e.key === "Enter" || e.key === " ") pick(e); });
        row.append(pill);
    }
    return row;
}

/* The tag box of the form: the pills (each with a x), a box to type more, and a button that asks for the suggestions again.
   tags() is what to save, isEdited() whether a person has chosen the tags (adding, removing or pressing Suggest); until then
   setSuggested() may replace them as the note changes, and a new transaction is saved with no list at all so the server works them out. */
export function txnTagField(initial, { onSuggest = null, edited = false } = {}) {
    let list = [...initial];
    let changedByPerson = edited;
    const pills = el("span", { className: "txn-tag-pills" });
    const input = el("input", { type: "text", maxLength: TXN_TAG_MAX + 1, autocomplete: "off", placeholder: "add a tag" });
    const error = el("div", { className: "txn-tag-error" });
    const suggest = el("button", { className: "txn-link", type: "button", textContent: "↻ Suggest", title: "Work the tags out again from the note, the description and the category" });
    const node = el("div", { className: "txn-tagbox" }, el("div", { className: "tag-input" }, pills, input, suggest), error);

    const render = () => {
        pills.replaceChildren();
        for (const tag of list) {
            const remove = el("button", { className: "tag-x", type: "button", textContent: "×", title: "Remove the tag " + tag });
            remove.setAttribute("aria-label", "Remove the tag " + tag);
            remove.addEventListener("click", () => { list = list.filter(t => t !== tag); changedByPerson = true; render(); input.focus(); });
            pills.append(el("span", { className: "tag-pill" }, "#" + tag, remove), " ");
        }
    };

    /* What has been typed (one word, or several separated by commas) becomes tags. False when one is not allowed. */
    const commit = () => {
        for (const part of (input.value || "").split(",")) {
            const tag = normalizeTxnTag(part);
            if (!tag) continue;
            if (tag.length > TXN_TAG_MAX) { error.textContent = `A tag can be at most ${TXN_TAG_MAX} characters.`; return false; }
            if (![...tag].every(ch => /[\p{L}\p{M}\p{N}_-]/u.test(ch))) { error.textContent = "A tag can only have letters, numbers, - and _."; return false; }
            if (list.includes(tag)) continue;
            if (list.length >= TXN_TAGS_MAX) { error.textContent = `A transaction can have at most ${TXN_TAGS_MAX} tags.`; return false; }
            list.push(tag);
            changedByPerson = true;
        }
        input.value = "";
        error.textContent = "";
        render();
        return true;
    };
    input.addEventListener("keydown", e => {
        if (e.key === "Enter" || e.key === ",") { e.preventDefault(); e.stopPropagation(); commit(); }
        else if (e.key === "Backspace" && !input.value && list.length) { list = list.slice(0, -1); changedByPerson = true; render(); }
    });
    input.addEventListener("blur", commit);
    suggest.addEventListener("click", () => { if (onSuggest) onSuggest(); });
    render();

    return {
        node,
        tags: () => (commit(), [...list]),
        isEdited: () => changedByPerson,
        /* The server's suggestion for a transaction nobody has tagged by hand yet. */
        setSuggested(next) {
            if (changedByPerson) return;
            list = [...next];
            render();
        },
        /* The Suggest button: replace the tags with the suggestion and treat them as chosen. */
        replaceWith(next) {
            list = [...next];
            changedByPerson = true;
            render();
        },
    };
}
