/* Future Expenses Ledger: one real account that holds the money saved for bills you know are coming.
   Its buckets are slices of that money. All actions are buttons at the top of the card: Fund Ledger, Add bucket,
   Edit, Pay, Transfer and Close. The bucket rows themselves have no buttons. */

import { TYPE_LABELS } from "../core/constants.js";
import { refreshData } from "../core/refresh.js";
import { accounts, ledger, portfolios, recurringDeposits } from "../core/state.js";
import { hideTooltip, showTooltip, showTooltipAt } from "../core/tooltip.js";
import { $, api, el, formatDate, growsOnce, rupees, rupees2, setBarWidth } from "../core/util.js";
import { renderAccounts } from "./accounts.js";
import { field } from "./transaction-form.js";

export const dlg = { onPrimary: null };

/* Whether the bucket list is open. It starts closed; the choice lasts until the page is reloaded. */
let ledgerExpanded = null;

/* ---------- the card ---------- */

function ledgerInfoTip() {
    return el("span", { className: "info-tag", tabIndex: 0 }, "i",
        el("span", { className: "info-tooltip",
            textContent: "Money set aside for bills you know are coming, like insurance or school fees. Each bucket gets a monthly " +
                "amount when you fund the Ledger, and you pay a bill from its bucket." }));
}

/* The Ledger counts as set up once it has a bucket. Until then the card keeps offering Set up. */
export function ledgerIsSetUp() {
    return ledger.configured && (ledger.buckets.length > 0 || ledger.closed.length > 0);
}

/* Creates the Ledger if needed, then asks for its first bucket. If no bucket is added, the card shows Set up again. */
export async function setUpLedger() {
    try {
        if (!ledger.configured) {
            await api("/api/ledger/setup", { method: "POST" });
            await refreshData();
        }
        openBucketDialog("add");
    } catch (e) {
        alert(e.message);
    }
}

export function ledgerCard() {
    const card = el("div", { className: "ef-card ledger-card" });
    const head = el("div", { className: "ef-head" },
        el("div", { className: "ef-title" }, "📒 Future Expenses Ledger", ledgerInfoTip()));
    card.append(head);

    if (!ledgerIsSetUp()) {
        const setUp = el("button", { className: "btn btn-primary", textContent: "Set up" });
        setUp.addEventListener("click", setUpLedger);
        head.append(setUp);
        card.append(el("div", { className: "ef-empty" },
            el("p", { textContent: "Set money aside for bills you know are coming, like insurance, wifi or school fees. " +
                "Move a fixed amount in each month and share it out between buckets, one for each bill. Set up asks for your first bucket." }),
            el("ul", {},
                el("li", { textContent: "A bucket per bill, with a monthly amount, a bill size and a due date" }),
                el("li", { textContent: "See which bills are funded and which are short, soonest first" }),
                el("li", { textContent: "Pay a bill straight from its bucket" }))));
        return card;
    }

    const actions = el("div", { className: "ledger-actions" });
    const open = ledger.buckets;
    const expanded = ledgerExpanded ?? false;
    const toggle = el("button", { className: "icon-btn ledger-toggle", type: "button", textContent: expanded ? "▾" : "▸",
        title: expanded ? "Collapse the buckets" : "Expand the buckets" });
    toggle.setAttribute("aria-expanded", String(expanded));
    toggle.setAttribute("aria-label", expanded ? "Collapse the buckets" : "Expand the buckets");
    toggle.addEventListener("click", () => { ledgerExpanded = !expanded; renderAccounts(); });
    head.children[0].prepend(toggle);
    const action = (label, onClick, { primary = false, needsBucket = false, disabledWhen = false, why = "" } = {}) => {
        const b = el("button", { className: "btn btn-small " + (primary ? "btn-primary" : "btn-secondary"), textContent: label });
        if (needsBucket && open.length === 0) { b.disabled = true; b.title = "Add a bucket first"; }
        if (disabledWhen) { b.disabled = true; b.title = why; }
        b.addEventListener("click", onClick);
        actions.append(b);
    };
    action("Fund Ledger", startFund, { primary: true, needsBucket: true });
    action("Add bucket", () => openBucketDialog("add"));
    action("Edit", () => openBucketDialog("edit"), { needsBucket: true });
    action("Pay", openPayDialog, { needsBucket: true });
    action("Transfer", openMoveDialog, { needsBucket: true });
    action("Credit interest", openInterestDialog);
    action("Close", () => openCloseDialog(), { disabledWhen: open.every(b => b.buffer), why: "The buffer cannot be closed" });
    head.append(actions);

    card.append(el("div", { className: "ledger-summary" },
        "Ledger total ", el("strong", { className: "lg-total", textContent: rupees2(ledger.balance) }),
        ` · Monthly plan ${rupees2(ledger.monthlyPlan)}`,
        expanded ? "" : ` · ${open.length} ${open.length === 1 ? "bucket" : "buckets"}`));

    if (!expanded) return card;      // collapsed: the header, the buttons and the summary are enough

    if (open.length === 0) {
        card.append(el("div", { className: "lg-empty", textContent: "No buckets yet. Use Add bucket to start, for example Insurance or Wifi." }));
    } else {
        open.forEach(b => card.append(ledgerRow(b)));
    }

    if (ledger.closed.length) {
        const closed = el("details", { className: "lg-closed" }, el("summary", { textContent: `Closed buckets (${ledger.closed.length})` }));
        for (const c of ledger.closed) {
            closed.append(el("div", { className: "lg-closed-row" },
                el("span", { textContent: `${c.name} · closed ${formatDate(c.closedAt)}` }),
                el("span", { textContent: `Paid in total ${rupees2(c.totalPaid)}` })));
        }
        card.append(closed);
    }
    return card;
}

/* "Every month", "Every 3 months". */
function everyMonths(months) {
    return months === 1 ? "Every month" : `Every ${months} months`;
}

/* One bucket:
     Name · how often          current balance / target
     [=========== progress bar (hover: monthly allocation) ===========]
     Previous payment                                      Next payment      */
function ledgerRow(b) {
    const monthly = `Monthly allocation: ${rupees2(b.monthlyAllocation)}`;
    const hasTarget = b.paymentAmount != null;
    const target = hasTarget ? Number(b.paymentAmount) : 0;
    let pct = hasTarget && target > 0 ? Math.max(0, Math.min(100, Number(b.balance) / target * 100)) : 0;
    let hover = monthly;
    if (b.buffer) {
        // The buffer has no bill, so its bar shows how big it is next to everything else in the Ledger:
        // buffer / (Ledger total - buffer) x 100.
        const rest = Number(ledger.balance) - Number(b.balance);
        pct = rest > 0 ? Math.max(0, Math.min(100, Number(b.balance) / rest * 100)) : (Number(b.balance) > 0 ? 100 : 0);
        hover = `${monthly} · The buffer is ${Math.floor(pct)}% of the rest of the Ledger`;
    }

    // Left: the name and how often it is paid. Right: what is saved out of the target.
    const name = el("div", { className: "lg-name", textContent: b.name });
    if (b.frequencyMonths) name.append(el("span", { className: "lg-freq", textContent: ` · ${everyMonths(b.frequencyMonths)}` }));
    if (b.buffer) name.append(el("span", { className: "lg-tag", textContent: "Buffer" }));
    if (b.endDatePassed) name.append(el("span", { className: "lg-tag warn", textContent: "End date passed" }));
    const saved = el("div", { className: "lg-saved",
        textContent: hasTarget ? `${rupees2(b.balance)} / ${rupees2(target)}` : rupees2(b.balance) });

    // The bar runs the full width below. Hovering it shows the monthly allocation. A bucket with no target has an empty bar.
    const fill = el("div", { className: "lg-fill" + (b.funded ? " funded" : "") + (growsOnce("bucket-" + b.id) ? " grow" : "") });
    setBarWidth(fill, "bucket-" + b.id, pct);
    // The visible bar is 12px, which is a small thing to point at, so it sits in a taller hit area. The tooltip shows on
    // hover and on keyboard focus.
    const tip = { value: rupees2(b.monthlyAllocation), name: "Monthly allocation",
        notes: b.buffer ? [`The buffer is ${Math.floor(pct)}% of the rest of the Ledger`] : [] };
    const bar = el("div", { className: "lg-bar-hit", tabIndex: 0 }, el("div", { className: "lg-bar" }, fill));
    bar.setAttribute("aria-label", b.buffer ? `${b.name}: ${hover}`
        : hasTarget ? `${b.name}: ${Math.floor(pct)}% saved${b.funded ? ", funded" : ""}. ${monthly}` : `${b.name}: no target. ${monthly}`);
    bar.addEventListener("pointermove", e => showTooltip(tip, e.clientX, e.clientY));
    bar.addEventListener("pointerleave", hideTooltip);
    bar.addEventListener("focus", () => showTooltipAt(bar, tip));
    bar.addEventListener("blur", hideTooltip);

    // Under the bar: the last payment on the left, the next one on the right.
    const dates = el("div", { className: "lg-dates" },
        el("span", { textContent: b.previousPaymentDate ? `Previous payment: ${formatDate(b.previousPaymentDate)}` : "No payment made yet" }),
        el("span", { textContent: b.nextPaymentDate ? `Next payment: ${formatDate(b.nextPaymentDate)}` : "No due date" }));

    return el("div", { className: "lg-row" }, el("div", { className: "lg-top" }, name, saved), bar, dates);
}

/* ---------- deleting a funding from the transaction list ---------- */

/* A transfer into the Ledger. Only this kind of Ledger transaction can be deleted from the list. */
export function isLedgerFunding(t) {
    return ledger.configured && t.kind === "TRANSFER" && t.toAccountId === ledger.accountId;
}

export function ledgerFundingDelete(t) {
    const button = el("button", { className: "icon-btn", type: "button", textContent: "🗑️", title: "Delete this funding" });
    button.setAttribute("aria-label", "Delete this funding of the Future Expenses Ledger");
    button.addEventListener("click", () => deleteLedgerFunding(t));
    return button;
}

async function deleteLedgerFunding(t) {
    if (!confirm(`Delete this funding of ${rupees(t.amount)}? The money it added to each bucket is taken back out.`)) return;
    try {
        await api(`/api/ledger/fundings/${t.id}`, { method: "DELETE" });
        await refreshData();
    } catch (e) {
        alert(e.message);   // for example: a bucket has already used the money
    }
}

/* ---------- the dialog ---------- */

export function openLedgerDialog(title, primaryLabel, build) {
    $("ledgerTitle").textContent = title;
    $("ledgerError").textContent = "";
    $("ledgerBody").innerHTML = "";
    build($("ledgerBody"));
    const primary = $("ledgerPrimary");
    primary.textContent = primaryLabel;
    primary.disabled = false;
    primary.classList.toggle("hidden", !primaryLabel);
    $("ledgerOverlay").classList.remove("hidden");
}

/* Swaps what the open dialog shows, for the "are you sure" step. */
function showLedgerStep(primaryLabel, build) {
    $("ledgerError").textContent = "";
    $("ledgerBody").innerHTML = "";
    build($("ledgerBody"));
    const primary = $("ledgerPrimary");
    primary.textContent = primaryLabel;
    primary.disabled = false;
}

function closeLedgerDialog() {
    $("ledgerOverlay").classList.add("hidden");
}

/* Runs an action, then closes the dialog and reloads. A problem is shown in the dialog instead. Returns true on success. */
export async function runLedger(action) {
    const primary = $("ledgerPrimary");
    primary.disabled = true;
    try {
        await action();
        closeLedgerDialog();
        await refreshData();
        return true;
    } catch (e) {
        $("ledgerError").textContent = e.message;
        primary.disabled = false;
        return false;
    }
}

export const ledgerFail = message => { $("ledgerError").textContent = message; };
const openBuckets = () => ledger.buckets;
const bucketById = id => ledger.buckets.find(b => b.id === Number(id));
export const numberInput = value => el("input", { type: "number", min: 0, step: "any", value: value ?? "" });
export const num = input => input.value === "" ? null : Number(input.value);
export const cents = x => Math.round(Number(x) * 100);

function bucketSelect(selectedId, list = openBuckets()) {
    const select = el("select", {}, ...list.map(b =>
        el("option", { value: b.id, textContent: `${b.name} · holds ${rupees2(b.balance)}` })));
    if (selectedId != null) select.value = selectedId;
    return select;
}

/* ---------- add / edit a bucket ---------- */

function openBucketDialog(mode, bucketId = null, after = null) {
    if (mode === "edit" && openBuckets().length === 0) return;
    const f = {};
    openLedgerDialog(mode === "add" ? "Add bucket" : "Edit bucket", mode === "add" ? "Add bucket" : "Save", body => {
        f.name = el("input", { type: "text", maxLength: 100, autocomplete: "off" });
        f.monthly = numberInput();
        f.payment = numberInput();
        f.months = el("input", { type: "number", min: 1, max: 120, step: 1 });
        f.next = el("input", { type: "date" });
        f.prev = el("input", { type: "date" });
        f.end = el("input", { type: "date" });
        f.held = numberInput();

        if (mode === "edit") {
            f.which = bucketSelect(bucketId ?? openBuckets()[0].id);
            body.append(field("Bucket", f.which));
        }
        body.append(field("Name", f.name), field("Monthly allocation (₹)", f.monthly),
            field("Bill amount, which is also the target (₹, optional)", f.payment),
            field("Repeats every (months, with the next payment date)", f.months), field("Next payment date", f.next),
            field("Previous payment date (optional)", f.prev),
            field("End date (optional)", f.end),
            field("Money held now (₹)", f.held),
            el("div", { className: "edit-hint", textContent: mode === "add"
                ? "Already have money saved for this bucket? Enter it here. It is added to the Ledger's total."
                : "Change this only to correct the balance. The Ledger's total changes by the same amount." }));

        // The buffer has no bill amount, payment dates or end date, so those fields are cleared and switched off.
        const syncBuffer = isBuffer => {
            [f.payment, f.months, f.next, f.prev, f.end].forEach(input => {
                if (isBuffer) input.value = "";
                input.disabled = isBuffer;
            });
        };
        const fill = b => {
            f.name.value = b.name;
            f.monthly.value = b.monthlyAllocation;
            f.payment.value = b.paymentAmount ?? "";
            f.months.value = b.frequencyMonths ?? "";
            f.next.value = b.nextPaymentDate ?? "";
            f.prev.value = b.previousPaymentDate ?? "";
            f.end.value = b.endDate ?? "";
            f.held.value = b.balance;
            syncBuffer(b.buffer);
        };
        if (mode === "edit") {
            fill(bucketById(f.which.value));
            f.which.addEventListener("change", () => fill(bucketById(f.which.value)));
        }
    });

    dlg.onPrimary = async () => {
        const ok = await runLedger(async () => {
            if (!f.name.value.trim()) throw new Error("Enter a name for the bucket.");
            const months = f.months.value === "" ? null : Number(f.months.value);
            if (months !== null && !(Number.isInteger(months) && months >= 1 && months <= 120)) throw new Error("Repeats every must be a whole number of months, from 1 to 120.");
            if ((months === null) !== !f.next.value) throw new Error("Enter both how many months between bills and the next payment date, or neither.");
            const body = {
                name: f.name.value.trim(), monthlyAllocation: num(f.monthly) ?? 0, paymentAmount: num(f.payment),
                frequencyMonths: months, nextPaymentDate: f.next.value || null,
                previousPaymentDate: f.prev.value || null, endDate: f.end.value || null, heldNow: num(f.held)
            };
            await api(mode === "add" ? "/api/ledger/buckets" : `/api/ledger/buckets/${f.which.value}`,
                { method: mode === "add" ? "POST" : "PUT", body });
        });
        if (ok && after) after();
    };
}

/* ---------- Fund Ledger ---------- */

function startFund() {
    const ended = openBuckets().filter(b => b.endDatePassed);
    if (ended.length) openResolveDialog(ended);
    else openFundDialog();
}

/* A bucket past its end date must be closed, or have its end date updated, before the Ledger is funded. */
function openResolveDialog(ended) {
    openLedgerDialog("Before you fund the Ledger", "", body => {
        body.append(el("p", { className: "ledger-note",
            textContent: ended.length === 1 ? "This bucket has passed its end date. Close it, or update its end date."
                : "These buckets have passed their end date. Close each one, or update its end date." }));
        for (const b of ended) {
            const close = el("button", { className: "btn btn-secondary btn-small", textContent: "Close bucket" });
            const update = el("button", { className: "btn btn-primary btn-small", textContent: "Update end date" });
            close.addEventListener("click", () => { closeLedgerDialog(); openCloseDialog(b.id, startFund); });
            update.addEventListener("click", () => { closeLedgerDialog(); openBucketDialog("edit", b.id, startFund); });
            body.append(el("div", { className: "ledger-list-row" },
                el("span", { textContent: `${b.name} ended on ${formatDate(b.endDate)}` }),
                el("span", { className: "buttons" }, close, update)));
        }
    });
    dlg.onPrimary = null;
}

/* Accounts the Ledger can be funded from. Jewellery, Mutual Funds, Shares and Recurring Deposits hold items, not spendable money: the server refuses them, so they are not offered. */
function ledgerFundingSources() {
    const itemAccountIds = [...Object.values(portfolios), recurringDeposits].filter(p => p.configured).map(p => p.accountId);
    return accounts.filter(a => a.active && a.id !== ledger.accountId && a.type !== "RECEIVABLE"
        && a.type !== "DEBT" && a.type !== "CREDIT_CARD" && a.type !== "JEWELLERY" && !itemAccountIds.includes(a.id));
}

function openFundDialog() {
    const f = {};
    const sources = ledgerFundingSources();
    openLedgerDialog("Fund Ledger", "Continue", body => {
        f.from = el("select", {}, ...sources.map(a => el("option", { value: a.id, disabled: a.ringFenced,
            textContent: `${a.name} (${TYPE_LABELS[a.type]})${a.ringFenced ? " 🔒 ring-fenced" : ""}` })));
        f.amount = numberInput();
        body.append(field("From account", f.from), field("Amount (₹)", f.amount),
            el("div", { className: "edit-hint", textContent: `Your monthly plan is ${rupees2(ledger.monthlyPlan)}.` }));
        f.amount.focus();
    });
    dlg.onPrimary = () => {
        const amount = num(f.amount);
        if (!(amount > 0)) return ledgerFail("Enter the amount to add to the Ledger.");
        if (!f.from.value) return ledgerFail("Choose the account the money comes from.");
        fundConfirm(Number(f.from.value), amount);
    };
}

/* Confirms the amount. If it is less than the plan, asks how much each bucket gets; if more, confirms the extra
   going to the buffer. */
function fundConfirm(fromId, amount) {
    const from = accounts.find(a => a.id === fromId);
    const plan = Number(ledger.monthlyPlan);
    const open = openBuckets();
    const buffer = open.find(b => b.buffer);
    const share = {};

    if (cents(amount) < cents(plan)) {
        showLedgerStep("Confirm", body => {
            body.append(el("p", { className: "ledger-note",
                textContent: `${rupees2(amount)} is less than your monthly plan of ${rupees2(plan)}. How much should each bucket get?` }));
            const total = el("div", { className: "edit-hint" });
            const update = () => {
                const sum = Object.values(share).reduce((s, i) => s + (num(i) || 0), 0);
                total.textContent = `${rupees2(sum)} of ${rupees2(amount)} shared out`;
            };
            for (const b of open) {
                share[b.id] = numberInput();
                share[b.id].addEventListener("input", update);
                body.append(el("div", { className: "ledger-list-row" }, el("span", { textContent: b.name }), share[b.id]));
            }
            body.append(total);
            update();
        });
        dlg.onPrimary = () => runLedger(async () => {
            const allocations = {};
            let sum = 0;
            for (const b of open) {
                const value = num(share[b.id]) || 0;
                if (value < 0) throw new Error("A bucket cannot get a negative amount.");
                if (value > 0) allocations[b.id] = value;
                sum += value;
            }
            if (cents(sum) !== cents(amount)) throw new Error(`The amounts add up to ${rupees2(sum)}, not ${rupees2(amount)}.`);
            await api("/api/ledger/fund", { method: "POST", body: { fromAccountId: fromId, amount, allocations } });
        });
        return;
    }

    const extra = amount - plan;
    showLedgerStep("Confirm", body => {
        body.append(el("p", { className: "ledger-note",
            textContent: `Add ${rupees2(amount)} from ${from.name} to the Ledger?` }));
        for (const b of open) {
            body.append(el("div", { className: "ledger-list-row" }, el("span", { textContent: b.name }),
                el("span", { textContent: `+ ${rupees2(b.monthlyAllocation)}` })));
        }
        if (cents(extra) > 0) {
            body.append(el("p", { className: "ledger-note",
                textContent: `That is ${rupees2(extra)} more than your plan. Move the extra ${rupees2(extra)} to ${buffer.name}?` }));
        }
    });
    dlg.onPrimary = () => runLedger(() =>
        api("/api/ledger/fund", { method: "POST", body: { fromAccountId: fromId, amount, allocations: null } }));
}

/* ---------- pay ---------- */

function openPayDialog() {
    const f = {};
    openLedgerDialog("Pay a bill", "Continue", body => {
        f.bucket = bucketSelect();
        f.amount = numberInput();
        const fillAmount = () => { f.amount.value = bucketById(f.bucket.value)?.paymentAmount ?? ""; };
        f.bucket.addEventListener("change", fillAmount);
        fillAmount();
        body.append(field("Bucket", f.bucket), field("Amount (₹)", f.amount));
        f.amount.focus();
    });
    dlg.onPrimary = () => {
        const bucket = bucketById(f.bucket.value);
        const amount = num(f.amount);
        if (!(amount > 0)) return ledgerFail("Enter the amount to pay.");
        if (cents(amount) > cents(bucket.balance)) {
            return ledgerFail(`${bucket.name} only has ${rupees2(bucket.balance)}. Use Transfer to move ${rupees2(amount - Number(bucket.balance))} into it first.`);
        }
        showLedgerStep("Pay", body => {
            body.append(el("p", { className: "ledger-note",
                textContent: `Pay ${rupees2(amount)} from ${bucket.name}? It is taken from the bucket and from the Ledger.` }));
        });
        dlg.onPrimary = () => runLedger(() =>
            api("/api/ledger/pay", { method: "POST", body: { bucketId: bucket.id, amount } }));
    };
}

/* ---------- interest ---------- */

/* Interest the bank credited to the Ledger (usually every quarter). It always goes to the buffer. */
function openInterestDialog() {
    const buffer = openBuckets().find(b => b.buffer);
    if (!buffer) return;
    const f = {};
    openLedgerDialog("Credit interest", "Credit interest", body => {
        f.amount = numberInput();
        body.append(field("Interest the bank credited (₹)", f.amount),
            el("div", { className: "edit-hint", textContent:
                `Banks usually credit interest every quarter. Enter the amount from your statement. It is added to the Ledger's total and to ${buffer.name}.` }));
        f.amount.focus();
    });
    dlg.onPrimary = () => runLedger(async () => {
        const amount = num(f.amount);
        if (!(amount > 0)) throw new Error("Enter the interest the bank credited.");
        await api("/api/ledger/interest", { method: "POST", body: { amount } });
    });
}

/* ---------- move money between buckets ---------- */

function openMoveDialog() {
    const f = {};
    const places = () => openBuckets().map(b => ({ id: String(b.id), label: `${b.name} (${rupees2(b.balance)})`, held: Number(b.balance) }));
    openLedgerDialog("Transfer between buckets", "Move money", body => {
        f.from = el("select", {}, ...places().map(p => el("option", { value: p.id, textContent: p.label })));
        f.to = el("select");
        f.amount = numberInput();
        const fillTo = () => {
            const chosen = f.to.value;
            f.to.innerHTML = "";
            places().filter(p => p.id !== f.from.value).forEach(p => f.to.append(el("option", { value: p.id, textContent: p.label })));
            if ([...f.to.options].some(o => o.value === chosen)) f.to.value = chosen;   // an unmatched value would leave it blank
        };
        f.from.addEventListener("change", fillTo);
        fillTo();
        body.append(field("From", f.from), field("To", f.to), field("Amount (₹)", f.amount));
    });
    dlg.onPrimary = () => runLedger(async () => {
        const amount = num(f.amount);
        if (!(amount > 0)) throw new Error("Enter the amount to move.");
        if (!f.to.value) throw new Error("Choose the bucket the money moves to.");
        const from = places().find(p => p.id === f.from.value);
        if (cents(amount) > cents(from.held)) throw new Error(`Only ${rupees2(from.held)} is available there.`);
        await api("/api/ledger/transfer", { method: "POST", body: {
            fromBucketId: Number(f.from.value), toBucketId: Number(f.to.value), amount } });
    });
}

/* ---------- close a bucket ---------- */

function openCloseDialog(bucketId = null, after = null) {
    const closable = openBuckets().filter(b => !b.buffer);      // the buffer cannot be closed
    if (closable.length === 0) return;
    const f = {};
    openLedgerDialog("Close a bucket", "Continue", body => {
        f.bucket = bucketSelect(bucketId ?? closable[0].id, closable);
        f.where = el("select");
        f.row = field("Move what is left, and its monthly amount, to", f.where);
        f.info = el("p", { className: "ledger-note" });
        body.append(field("Bucket", f.bucket), f.info, f.row);
        const refresh = () => {
            const bucket = bucketById(f.bucket.value);
            const left = Number(bucket.balance);
            const monthly = Number(bucket.monthlyAllocation);
            const parts = [];
            if (left > 0) parts.push(`${rupees2(left)} is left in ${bucket.name}`);
            if (monthly > 0) parts.push(`it adds ${rupees2(monthly)} a month to your plan`);
            const sentence = parts.length ? parts.join(", and ") + "." : `Nothing is left in ${bucket.name}.`;
            f.info.textContent = sentence.charAt(0).toUpperCase() + sentence.slice(1);
            f.row.classList.toggle("hidden", !(left > 0 || monthly > 0));
            f.where.innerHTML = "";
            const others = openBuckets().filter(b => b.id !== bucket.id);
            const buffer = others.find(b => b.buffer);
            others.map(b => ({ v: String(b.id), l: b.name + (b.buffer ? " (buffer)" : "") }))
                .forEach(o => f.where.append(el("option", { value: o.v, textContent: o.l })));
            f.where.value = buffer ? String(buffer.id) : (others[0] ? String(others[0].id) : "");
        };
        f.bucket.addEventListener("change", refresh);
        refresh();
    });
    dlg.onPrimary = () => {
        const bucket = bucketById(f.bucket.value);
        const left = Number(bucket.balance);
        const monthly = Number(bucket.monthlyAllocation);
        const destination = Number(f.where.value);
        const destinationName = bucketById(destination).name;
        showLedgerStep("Close bucket", body => {
            body.append(el("p", { className: "ledger-note", textContent:
                `Close ${bucket.name}?` + (left > 0 ? ` ${rupees2(left)} moves to ${destinationName}.` : "") +
                (monthly > 0 ? ` Its ${rupees2(monthly)} a month is added to ${destinationName}'s monthly allocation.` : "") +
                " It moves to Closed buckets and its history is kept." }));
        });
        dlg.onPrimary = async () => {
            const ok = await runLedger(() => api(`/api/ledger/buckets/${bucket.id}/close`,
                { method: "POST", body: { destinationBucketId: destination } }));
            if (ok && after) after();
        };
    };
}

export function initLedger() {
    $("ledgerPrimary").addEventListener("click", () => { if (dlg.onPrimary) dlg.onPrimary(); });
    $("ledgerCancel").addEventListener("click", closeLedgerDialog);
    $("ledgerClose").addEventListener("click", closeLedgerDialog);
}
