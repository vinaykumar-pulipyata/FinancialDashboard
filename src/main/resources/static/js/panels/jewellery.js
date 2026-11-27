/* Jewellery: gold, silver and other metals you own, as items inside one Jewellery account (Level 3). Each item has a name, a type
   (ornament, coin, bar, loose gold, other), a weight in grams and the price paid. There is no current value and no profit or loss
   while you hold it, because bought jewellery is rarely sold. Selling asks for the price it sold for: what it cost moves to your
   bank or cash account, and the difference is recorded there as profit (income) or loss (expense). The dialog is the Ledger's, reused. */

import { refreshData } from "../core/refresh.js";
import { accounts, jewellery } from "../core/state.js";
import { api, el, rupees2 } from "../core/util.js";
import { dlg, ledgerFail, num, numberInput, openLedgerDialog, runLedger } from "./ledger.js";
import { field } from "./transaction-form.js";

const FORM_LABELS = { ORNAMENT: "Ornament", COIN: "Coin", BAR: "Bar", LOOSE: "Loose", OTHER: "Other" };
const ABOUT = "Your gold, silver and other jewellery in one place. Each piece has a name, a type, a weight and the price you paid. " +
    "It counts towards your net worth at what it cost. Selling records the money coming in and the profit or loss.";

/* The colour class of a kind: gold, silver, platinum and diamond have their own; any other kind keeps the plain look. */
const KIND_CLASSES = { gold: "jw-gold", silver: "jw-silver", platinum: "jw-platinum", diamond: "jw-diamond" };
const kindClass = metal => KIND_CLASSES[String(metal).trim().toLowerCase()] || "jw-other";
const KINDS = ["Gold", "Silver", "Platinum", "Diamond"];   // offered in the Kind list; anything else is typed in

let jewelleryExpanded = false;   // the list starts closed; the choice lasts until the page is reloaded

/* Whether an account is the Jewellery account. Ordinary transactions cannot use it. */
export function isJewelleryAccount(id) {
    return jewellery.configured && jewellery.accountId === id;
}

const weightText = n => Number(n).toLocaleString("en-IN", { maximumFractionDigits: 3 }) + " g";
const jewelleryApi = (path, body, method = "POST") => api(`/api/jewellery${path}`, { method, body });

/* Bank and cash accounts that can send or receive money. */
function moneySelect(withNone) {
    const list = accounts.filter(a => a.active && (a.type === "BANK" || a.type === "CASH"));
    const select = el("select", {}, ...(withNone ? [el("option", { value: "", textContent: "Already owned (no money moves)" })] : []),
        ...list.map(a => el("option", { value: a.id, textContent: `${a.name} · ${rupees2(a.balance)}` })));
    return select;
}

function itemRow(item) {
    const perGram = Number(item.weight) > 0 ? ` · ${rupees2(Number(item.boughtPrice) / Number(item.weight))}/g` : "";
    const sell = el("button", { className: "btn btn-small btn-secondary", type: "button", textContent: "Sell", title: "Sell " + item.name });
    sell.addEventListener("click", () => openSellDialog(item));
    const edit = el("button", { className: "icon-btn", type: "button", textContent: "✏️", title: "Edit " + item.name });
    edit.setAttribute("aria-label", "Edit " + item.name);
    edit.addEventListener("click", () => openItemDialog(item));
    const remove = el("button", { className: "icon-btn", type: "button", textContent: "🗑️", title: "Remove " + item.name + (item.paidByAccount ? " (puts the money back)" : " (no money moves)") });
    remove.setAttribute("aria-label", "Remove " + item.name);
    remove.addEventListener("click", () => removeItem(item));
    return el("div", { className: "jw-item" },
        el("div", { className: "jw-left" },
            el("div", { className: "lg-name", textContent: item.name }),
            el("div", { className: "jw-sub", textContent: `${FORM_LABELS[item.form] || item.form} · ${weightText(item.weight)}${perGram}` })),
        el("div", { className: "jw-right" },
            el("div", { className: "jw-price", textContent: rupees2(item.boughtPrice) }),
            el("div", { className: "jw-actions" }, sell, edit, remove)));
}

export function jewelleryCard() {
    const card = el("div", { className: "ef-card jewellery-card" });
    const head = el("div", { className: "ef-head" },
        el("div", { className: "ef-title" }, "💍 Jewellery",
            el("span", { className: "info-tag", tabIndex: 0 }, "i", el("span", { className: "info-tooltip", textContent: ABOUT }))));
    card.append(head);

    if (!jewellery.configured || jewellery.metals.length === 0) {
        const setUp = el("button", { className: "btn btn-primary", textContent: jewellery.configured ? "Add item" : "Set up" });
        setUp.addEventListener("click", async () => {
            try {
                if (!jewellery.configured) {
                    await jewelleryApi("/setup");
                    await refreshData();
                }
                openItemDialog(null);
            } catch (e) {
                alert(e.message);
            }
        });
        head.append(setUp);
        card.append(el("div", { className: "ef-empty" },
            el("p", { textContent: "Keep your gold and silver together. For each piece note its name, type (ornament, coin, bar or loose), weight and the price you paid. Platinum and diamond have their own colours; other kinds can be added too." }),
            el("ul", {},
                el("li", { textContent: "Grouped by Gold, Silver, Platinum, Diamond and your own kinds, with total weight and what it cost" }),
                el("li", { textContent: "Sell a piece, or part of its weight: the money, and the profit or loss, are recorded for you" }))));
        return card;
    }

    const expanded = jewelleryExpanded;
    const toggle = el("button", { className: "icon-btn ledger-toggle", type: "button", textContent: expanded ? "▾" : "▸" });
    const label = `${expanded ? "Collapse" : "Expand"} Jewellery`;
    toggle.title = label;
    toggle.setAttribute("aria-label", label);
    toggle.setAttribute("aria-expanded", String(expanded));
    toggle.addEventListener("click", () => { jewelleryExpanded = !expanded; refreshData(); });
    const titleBox = head.children[0];
    titleBox.prepend(toggle);
    titleBox.append(el("span", { className: "pf-total", title: "What the pieces cost" },
        "Bought for ", el("strong", { textContent: rupees2(jewellery.boughtPrice) })));
    const add = el("button", { className: "btn btn-small btn-primary", textContent: "Add item" });
    add.addEventListener("click", () => openItemDialog(null));
    head.append(el("div", { className: "ledger-actions" }, add));

    card.append(el("div", { className: "ledger-summary" },
        ...jewellery.metals.flatMap((m, i) => [i ? " · " : "", el("span", { className: `jw-dot ${kindClass(m.metal)}` }), `${m.metal} `, el("strong", { textContent: weightText(m.weight) })])));
    if (expanded) {
        for (const metal of jewellery.metals) {
            const kind = el("div", { className: `jw-kind ${kindClass(metal.metal)}` });
            kind.append(el("div", { className: "jw-group" },
                el("span", { className: "jw-metal", textContent: metal.metal }),
                el("span", { className: "jw-total", textContent: `${weightText(metal.weight)} · ${rupees2(metal.boughtPrice)}` })));
            metal.items.forEach(item => kind.append(itemRow(item)));
            card.append(kind);
        }
    }
    return card;
}

/* ---------- dialogs ---------- */

/* The kind of metal: Gold, Silver, a kind already in use, or a new one typed in. */
function metalField(f, current) {
    const known = [...KINDS, ...jewellery.metals.map(m => m.metal).filter(m => !KINDS.includes(m))];
    const select = el("select", {}, ...known.map(m => el("option", { value: m, textContent: m })), el("option", { value: "", textContent: "Another kind…" }));
    f.metalChoice = select;
    f.metalOther = el("input", { type: "text", maxLength: 40, autocomplete: "off", placeholder: "Palladium" });
    const sync = () => f.metalOther.classList.toggle("hidden", select.value !== "");
    select.addEventListener("change", sync);
    if (current) select.value = known.includes(current) ? current : "";
    if (current && !known.includes(current)) f.metalOther.value = current;
    sync();
    return [field("Kind", select), field("Name of the kind", f.metalOther)];
}

const chosenMetal = f => (f.metalChoice.value || f.metalOther.value).trim();

function openItemDialog(item) {
    const f = {};
    openLedgerDialog(item ? "Edit item" : "Add an item", item ? "Save" : "Add", body => {
        body.append(...metalField(f, item && item.metal));
        f.name = el("input", { type: "text", maxLength: 100, autocomplete: "off", placeholder: "Wedding necklace", value: item ? item.name : "" });
        f.form = el("select", {}, ...Object.entries(FORM_LABELS).map(([value, text]) => el("option", { value, textContent: text })));
        if (item) f.form.value = item.form;
        f.weight = numberInput(item ? item.weight : "");
        f.weight.step = "0.001";
        f.price = numberInput(item ? item.boughtPrice : "");
        body.append(field("Name", f.name), field("Type", f.form), field("Weight (grams)", f.weight), field("Bought price (₹)", f.price));
        if (!item) {
            f.paidFrom = moneySelect(true);
            body.append(field("Paid from", f.paidFrom));
        }
        body.append(el("div", { className: "edit-hint", textContent: item
            ? "Correcting the price keeps your total equal to what the pieces cost."
            : "Choose a bank or cash account if you paid for it now: the price moves out of it. Leave it on Already owned for a piece you had before; nothing is taken from your accounts." }));
        f.name.focus();
    });
    dlg.onPrimary = () => {
        const weight = num(f.weight), price = num(f.price);
        if (!chosenMetal(f)) return ledgerFail("Choose the kind, or type another one.");
        if (!f.name.value.trim()) return ledgerFail("Enter a name.");
        if (!(weight > 0)) return ledgerFail("Enter the weight in grams.");
        if (price == null || price < 0) return ledgerFail("Enter the price paid.");
        const body = { metal: chosenMetal(f), name: f.name.value.trim(), form: f.form.value, weight, boughtPrice: price };
        if (item) runLedger(() => jewelleryApi(`/items/${item.id}`, body, "PUT"));
        else runLedger(() => jewelleryApi("/items", { ...body, paidFromAccountId: f.paidFrom.value ? Number(f.paidFrom.value) : null }));
    };
}

function openSellDialog(item) {
    const f = {};
    openLedgerDialog("Sell " + item.name, "Sell", body => {
        f.weight = numberInput(item.weight);
        f.weight.step = "0.001";
        f.price = numberInput();
        f.to = moneySelect(false);
        body.append(field("Weight sold (grams)", f.weight), field("Sold for (₹)", f.price), field("Money goes to", f.to),
            el("div", { className: "edit-hint", textContent:
                `It cost ${rupees2(item.boughtPrice)} for ${weightText(item.weight)}. What it cost moves to the account you choose, and the difference to the price you sold it for is recorded there as profit (income) or loss (expense). Selling less than the full weight leaves the rest in the list.` }));
        f.price.focus();
    });
    dlg.onPrimary = () => {
        const weight = num(f.weight), price = num(f.price);
        if (!f.to.value) return ledgerFail("Add a bank or cash account first.");
        if (!(weight > 0) || weight > Number(item.weight)) return ledgerFail(`Enter a weight up to ${weightText(item.weight)}.`);
        if (price == null || price < 0) return ledgerFail("Enter the price it sold for.");
        runLedger(() => jewelleryApi(`/items/${item.id}/sell`, { toAccountId: Number(f.to.value), salePrice: price, weight }));
    };
}

async function removeItem(item) {
    const message = item.paidByAccount
        ? `Remove ${item.name} and put ${rupees2(item.boughtPrice)} back in the account it was paid from? Use Sell if you sold it or lost it, so the loss is recorded.`
        : `Remove ${item.name} from the list? No money moves. Use Sell if you sold it.`;
    if (!confirm(message)) return;
    try {
        await jewelleryApi(`/items/${item.id}`, null, "DELETE");
        await refreshData();
    } catch (e) {
        alert(e.message);
    }
}
