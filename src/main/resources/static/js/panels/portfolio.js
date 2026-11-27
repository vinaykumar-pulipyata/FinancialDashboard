/* Mutual Funds and Shares: each is one account at Level 3 whose funds (or shares) are its rows. A holding has an invested
   amount and a current value (typed in by hand); the gain or loss is worked out. All actions are buttons at the top of
   the card: Add, Invest and Withdraw; each row has a pencil to edit it (name, quantity, invested, current value) or remove it. The dialog is the Ledger's, reused. */

import { refreshData } from "../core/refresh.js";
import { accounts, portfolios, transactions } from "../core/state.js";
import { api, el, isoDay, rupees2 } from "../core/util.js";
import { renderAccounts } from "./accounts.js";
import { allocationSlices, drawAllocation } from "./allocation.js";
import { cents, dlg, ledgerFail, num, numberInput, openLedgerDialog, runLedger } from "./ledger.js";
import { inPeriod, periodIn } from "./period-filter.js";
import { portfolioStatsView, psView } from "./portfolio-stats.js";
import { field } from "./transaction-form.js";

export const PORTFOLIO_KINDS = {
    MUTUAL_FUNDS: { label: "Mutual Funds", icon: "📈", one: "fund", add: "Add fund", qty: "Units",
        about: "Your mutual funds in one place. Type in what each fund is worth now and the gain or loss is worked out " +
            "from what you put in. Its total counts towards your net worth.",
        intro: "Keep your mutual funds together. For each fund, note what you put in and what it is worth now, " +
            "and see your gain or loss. Set up asks for your first fund.",
        placeholder: "Nifty 50 index fund" },
    SHARES: { label: "Shares", icon: "💹", one: "share", add: "Add share", qty: "Qty", dividends: true,
        about: "Your shares in one place. Type in what each holding is worth now and the gain or loss is worked out " +
            "from what you put in. Its total counts towards your net worth.",
        intro: "Keep your shares together. For each company, note what you put in and what it is worth now, " +
            "and see your gain or loss. Set up asks for your first share.",
        placeholder: "Infosys" }
};

/* Whether a card's list of holdings is open. It starts closed; the choice lasts until the page is reloaded. */
const portfolioExpanded = { MUTUAL_FUNDS: null, SHARES: null };

/* Whether an account is the Mutual Funds or Shares account. Ordinary transactions cannot use it. */
export function isPortfolioAccount(id) {
    return Object.values(portfolios).some(p => p.configured && p.accountId === id);
}

/* Dividends received from a holding (or from all of a card's holdings) inside the period in effect. They are ordinary
   income transactions linked to the holding that paid them. */
function dividendsFor(kind, holdingId = null) {
    const ids = new Set(portfolios[kind].holdings.map(h => h.id));
    return transactions
        .filter(t => t.kind === "INCOME" && t.holdingId != null && (holdingId == null ? ids.has(t.holdingId) : t.holdingId === holdingId) && inPeriod(t))
        .reduce((sum, t) => sum + Number(t.amount), 0);
}

export const signedRupees = n => (Number(n) < 0 ? "−" : "+") + rupees2(Math.abs(Number(n)));
const signedPercent = p => p == null ? "" : (Number(p) < 0 ? "−" : "+") + Math.abs(Number(p)).toFixed(1) + "%";

/* "+₹2,500 (+25.0%)", with an arrow so the direction never depends on colour alone. */
function gainText(gain, percent) {
    const g = Number(gain);
    const arrow = g > 0 ? "▲ " : g < 0 ? "▼ " : "";
    return arrow + signedRupees(g) + (percent == null ? "" : ` (${signedPercent(percent)})`);
}

export const gainClass = gain => Number(gain) > 0 ? "up" : Number(gain) < 0 ? "down" : "";

function portfolioInfoTip(kind) {
    return el("span", { className: "info-tag", tabIndex: 0 }, "i",
        el("span", { className: "info-tooltip", textContent: PORTFOLIO_KINDS[kind].about }));
}

/* Counts as set up once it has a holding. Until then the card keeps offering Set up. */
function portfolioIsSetUp(kind) {
    const p = portfolios[kind];
    return p.configured && p.holdings.length > 0;
}

async function setUpPortfolio(kind) {
    try {
        if (!portfolios[kind].configured) {
            await api(`/api/portfolios/${kind}/setup`, { method: "POST" });
            await refreshData();
        }
        openHoldingDialog(kind);
    } catch (e) {
        alert(e.message);
    }
}

export function portfolioCard(kind) {
    const p = portfolios[kind], cfg = PORTFOLIO_KINDS[kind];
    const card = el("div", { className: "ef-card portfolio-card" });
    const head = el("div", { className: "ef-head" },
        el("div", { className: "ef-title" }, `${cfg.icon} ${cfg.label}`, portfolioInfoTip(kind)));
    card.append(head);
    // Total invested sits beside the heading once the card is set up.
    const titleBox = head.children[0];

    if (!portfolioIsSetUp(kind)) {
        const setUp = el("button", { className: "btn btn-primary", textContent: "Set up" });
        setUp.addEventListener("click", () => setUpPortfolio(kind));
        head.append(setUp);
        card.append(el("div", { className: "ef-empty" },
            el("p", { textContent: cfg.intro }),
            el("ul", {},
                el("li", { textContent: `Invested amount and current value for every ${cfg.one}` }),
                el("li", { textContent: "Gain or loss in rupees and percent, worked out for you" }),
                el("li", { textContent: "Invest and withdraw straight from your bank or cash account" }))));
        return card;
    }

    const expanded = portfolioExpanded[kind] ?? false;
    const toggle = el("button", { className: "icon-btn ledger-toggle", type: "button", textContent: expanded ? "▾" : "▸" });
    const toggleLabel = `${expanded ? "Collapse" : "Expand"} ${cfg.label}`;
    toggle.title = toggleLabel;
    toggle.setAttribute("aria-label", toggleLabel);
    toggle.setAttribute("aria-expanded", String(expanded));
    toggle.addEventListener("click", () => { portfolioExpanded[kind] = !expanded; renderAccounts(); });
    titleBox.prepend(toggle);
    titleBox.append(el("span", { className: "pf-total", title: "What you have put in and not taken out yet, at cost. Invest adds to it and a withdrawal takes the cost of what it sold off it." },
        "Total invested ", el("strong", { textContent: rupees2(p.totalInvested) })));
    titleBox.append(el("span", { className: "pf-total", title: "Total invested minus Invested" },
        "Difference ", el("strong", { textContent: rupees2(Number(p.totalInvested) - Number(p.investedAmount)) })));
    const sold = Number(p.realizedGain || 0);
    if (sold !== 0) {   // once something has been sold: what the sales made, kept after the fund's own gain is gone
        titleBox.append(el("span", { className: "pf-total", title: "What the sales made: what each withdrawal paid above (or below) the cost of what it took out" },
            sold > 0 ? "Profit from sales " : "Loss from sales ", el("strong", { className: gainClass(sold), textContent: signedRupees(sold) })));
    }
    if (cfg.dividends) {
        titleBox.append(el("span", { className: "pf-total", title: `Dividends received ${periodIn()}` },
            "Dividends ", el("strong", { textContent: rupees2(dividendsFor(kind)) })));
    }

    const actions = el("div", { className: "ledger-actions" });
    const action = (label, onClick, primary = false) => {
        const b = el("button", { className: "btn btn-small " + (primary ? "btn-primary" : "btn-secondary"), textContent: label });
        b.addEventListener("click", onClick);
        actions.append(b);
    };
    action(cfg.add, () => openHoldingDialog(kind), true);
    action("Invest", () => openInvestDialog(kind));
    action("Withdraw", () => openWithdrawDialog(kind));
    if (cfg.dividends) action("Dividend", () => openDividendDialog(kind));
    // Stats and Allocation each turn the card to that view and back to the list; the button of the view on show is named after the list.
    const view = psView[kind];
    const toggleView = name => () => { psView[kind] = view === name ? "list" : name; portfolioExpanded[kind] = true; renderAccounts(); };
    action(view === "stats" ? cfg.label : "Stats", toggleView("stats"));
    action(view === "allocation" ? cfg.label : "Allocation", toggleView("allocation"));
    head.append(actions);

    card.append(el("div", { className: "ledger-summary" },
        "Invested ", el("strong", { textContent: rupees2(p.investedAmount) }),
        " · Current value ", el("strong", { textContent: rupees2(p.currentValue) }),
        " · Gain ", el("strong", { className: "gain " + gainClass(p.gain), textContent: gainText(p.gain, p.gainPercent) }),
        expanded ? "" : ` · ${p.holdings.length} ${p.holdings.length === 1 ? cfg.one : cfg.one + "s"}`));
    if (expanded) {   // collapsed: the header, buttons and totals are enough
        if (view === "stats") card.append(portfolioStatsView(kind));
        else if (view === "allocation") card.append(portfolioAllocationView(kind));
        else byName(p.holdings).forEach(h => card.append(holdingRow(h, kind)));
    }
    return card;
}

/* How the card's money is split between its holdings, by current value: a donut and a table (allocation.js draws both). */
function portfolioAllocationView(kind) {
    const cfg = PORTFOLIO_KINDS[kind];
    const { slices, total } = allocationSlices(portfolios[kind].holdings.map(h => ({ label: h.name, amount: Number(h.currentValue) })));
    const box = el("div", { className: "nw-allocation pf-allocation" });   // nw-allocation carries the slice colours and the layout
    drawAllocation(box, {
        title: `How your ${cfg.label.toLowerCase()} are split`,
        subtitle: `Share of the current value of each ${cfg.one}. Edit a row to keep it up to date.`,
        totalLabel: "Total value", headLabel: cfg.one[0].toUpperCase() + cfg.one.slice(1), slices, total, money: rupees2,
        empty: `Nothing to show yet: use "${cfg.add}" and it appears here.` });
    return box;
}

/* One holding, in two columns, every figure to the paisa:
     Name - Qty 10, Avg ₹1,500.00                 ▲ +₹2,500.00      (profit or loss: current value - invested)
     ₹17,500.00 (current value, green or red)        +16.7%         (that as a percentage of what was invested)
     Invested ₹15,000.00
   Quantity and average price (invested / quantity) appear once a quantity has been entered. */
const signedPrecise = n => (Number(n) < 0 ? "−" : "+") + rupees2(Math.abs(Number(n)));
function holdingRow(h, kind) {
    const cfg = PORTFOLIO_KINDS[kind];
    const dividends = cfg.dividends ? dividendsFor(kind, h.id) : 0;
    const quantity = h.quantity == null ? null : Number(h.quantity);
    const heading = quantity > 0
        ? `${h.name} - ${cfg.qty} ${quantity.toLocaleString("en-IN", { maximumFractionDigits: 4 })}, Avg ${rupees2(Number(h.investedAmount) / quantity)}`
        : h.name;
    const gain = Number(h.gain), trend = gainClass(h.gain);
    const arrow = gain > 0 ? "▲ " : gain < 0 ? "▼ " : "";
    const pencil = el("button", { className: "icon-btn", type: "button", textContent: "✏️", title: `Edit this ${cfg.one}` });
    pencil.setAttribute("aria-label", `Edit ${h.name}`);
    pencil.addEventListener("click", () => openEditHoldingDialog(kind, h.id));
    return el("div", { className: "holding-row pf-holding" },
        el("div", { className: "pf-left" },
            el("div", { className: "pf-title" }, el("div", { className: "lg-name", textContent: heading }), pencil),
            el("div", { className: "pf-current gain " + trend, textContent: rupees2(h.currentValue) }),
            el("div", { className: "pf-invested", textContent: `Invested ${rupees2(h.investedAmount)}` + (dividends > 0 ? ` · Dividends ${rupees2(dividends)}` : "") })),
        el("div", { className: "pf-right" },
            el("div", { className: "pf-gain gain " + trend, textContent: arrow + signedPrecise(gain) }),
            el("div", { className: "pf-percent gain " + trend, textContent: h.gainPercent == null ? "" : signedPercent(h.gainPercent) })));
}

/* ---------- dialogs ---------- */

/* Holdings by name, A to Z, ignoring capitals (a copy: the list in the page state keeps its order). */
export const byName = list => [...list].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base", numeric: true }));

const holdingById = (kind, id) => portfolios[kind].holdings.find(h => h.id === Number(id));

function holdingSelect(kind, selectedId) {
    const label = PORTFOLIO_KINDS[kind].one;
    const select = el("select", {}, ...byName(portfolios[kind].holdings).map(h =>
        el("option", { value: h.id, textContent: `${h.name} · worth ${rupees2(h.currentValue)}` })));
    if (selectedId != null) select.value = selectedId;
    select.setAttribute("aria-label", label);
    return select;
}

/* Bank and cash accounts that can send or receive money. */
function moneySourceSelect(forSpending) {
    const list = accounts.filter(a => a.active && (a.type === "BANK" || a.type === "CASH") && !(forSpending && a.ringFenced));
    return el("select", {}, ...list.map(a => el("option", { value: a.id, textContent: `${a.name} · ${rupees2(a.balance)}` })));
}

const portfolioApi = (kind, path, body, method = "POST") =>
    api(`/api/portfolios/${kind}/holdings${path}`, { method, body });

function openHoldingDialog(kind) {
    const cfg = PORTFOLIO_KINDS[kind], f = {};
    openLedgerDialog(`Add a ${cfg.one}`, cfg.add, body => {
        f.name = el("input", { type: "text", maxLength: 100, autocomplete: "off", placeholder: cfg.placeholder });
        f.invested = numberInput();
        f.value = numberInput();
        f.quantity = numberInput();
        body.append(field("Name", f.name), field(`${cfg.qty === "Qty" ? "Quantity" : cfg.qty} (optional)`, f.quantity), field("Invested amount (₹)", f.invested), field("Current value (₹)", f.value),
            el("div", { className: "edit-hint", textContent:
                `What you have put into this ${cfg.one} so far, and what it is worth today. Nothing is taken from your accounts.` }));
        f.name.focus();
    });
    dlg.onPrimary = () => {
        const invested = num(f.invested), value = num(f.value);
        if (!f.name.value.trim()) return ledgerFail(`Enter the ${cfg.one}'s name.`);
        if (invested == null || invested < 0) return ledgerFail("Enter the invested amount.");
        if (value == null || value < 0) return ledgerFail("Enter the current value.");
        const quantity = num(f.quantity);
        if (quantity != null && quantity < 0) return ledgerFail("The quantity cannot be negative.");
        runLedger(() => portfolioApi(kind, "", { name: f.name.value.trim(), investedAmount: invested, currentValue: value, quantity }));
    };
}

/* A transfer into a Mutual Funds or Shares account is an Invest; it can be deleted from the Transactions list. */
function investedKind(t) {
    if (t.kind !== "TRANSFER") return null;
    return Object.keys(portfolios).find(k => portfolios[k] && portfolios[k].configured && portfolios[k].accountId === t.toAccountId) || null;
}

export function isInvestment(t) {
    return investedKind(t) != null;
}

export function investmentDelete(t) {
    const button = el("button", { className: "icon-btn", type: "button", textContent: "🗑️", title: "Delete this investment and return the money" });
    button.setAttribute("aria-label", "Delete this investment and return the money to the account it came from");
    button.addEventListener("click", () => {
        const kind = investedKind(t);
        if (t.holdingId == null && portfolios[kind].holdings.length) openOldInvestmentDelete(kind, t);   // it never recorded its fund
        else confirmInvestmentDelete(kind, t);
    });
    return button;
}

async function confirmInvestmentDelete(kind, t, fundId) {
    if (!confirm(`Delete this investment of ${rupees2(t.amount)}? The money goes back to the account it came from, and Total invested and the fund's invested amount come down by the same amount.`)) return;
    try {
        await api(`/api/portfolios/${kind}/investments/${t.id}` + (fundId ? `?fundId=${fundId}` : ""), { method: "DELETE" });
        await refreshData();
    } catch (e) {
        alert(e.message);
    }
}

/* An investment made before funds were chosen does not know where it went: ask, so that fund's invested amount comes down too. */
function openOldInvestmentDelete(kind, t) {
    const cfg = PORTFOLIO_KINDS[kind], f = {};
    openLedgerDialog("Delete this investment", "Delete", body => {
        f.fund = el("select", { "aria-label": cfg.one },
            ...byName(portfolios[kind].holdings).map(h =>
                el("option", { value: h.id, textContent: `${h.name} · invested ${rupees2(h.investedAmount)}` })),
            el("option", { value: "", textContent: `None: only Total invested comes down` }));
        body.append(field(`Which ${cfg.one} did ${rupees2(t.amount)} go into?`, f.fund),
            el("div", { className: "edit-hint", textContent:
                "The money goes back to the account it came from. Total invested and that fund's invested amount come down by the same amount." }));
    });
    dlg.onPrimary = () => runLedger(() => api(`/api/portfolios/${kind}/investments/${t.id}` + (f.fund.value ? `?fundId=${f.fund.value}` : ""), { method: "DELETE" }));
}

function openInvestDialog(kind) {
    const cfg = PORTFOLIO_KINDS[kind], f = {};
    const NEW = "new";
    openLedgerDialog("Invest", "Invest", body => {
        f.from = moneySourceSelect(true);
        f.amount = numberInput();
        f.fund = el("select", { "aria-label": cfg.one },
            ...byName(portfolios[kind].holdings).map(h =>
                el("option", { value: h.id, textContent: `${h.name} · invested ${rupees2(h.investedAmount)}` })),
            el("option", { value: NEW, textContent: `+ Add new ${cfg.one}` }));
        f.name = el("input", { type: "text", maxLength: 80, placeholder: `Name of the new ${cfg.one}` });
        const nameField = field("Name", f.name);
        const showName = () => nameField.classList.toggle("hidden", f.fund.value !== NEW);
        f.fund.addEventListener("change", showName);
        showName();
        body.append(field("From", f.from), field("Amount (₹)", f.amount), field(cfg.one[0].toUpperCase() + cfg.one.slice(1), f.fund), nameField,
            el("div", { className: "edit-hint", textContent:
                `The money leaves the account you choose and is added to Total invested and to the ${cfg.one}'s invested amount. ` +
                "Its current value is not changed: update it yourself with the pencil on its row. A new one starts worth what you put in." }));
        f.amount.focus();
    });
    dlg.onPrimary = () => {
        const amount = num(f.amount);
        if (!f.from.value) return ledgerFail("Add a bank or cash account first.");
        if (!(amount > 0)) return ledgerFail("Enter the amount to invest.");
        const body = { fromAccountId: Number(f.from.value), amount };
        if (f.fund.value === NEW) {
            if (!f.name.value.trim()) return ledgerFail(`Enter a name for the new ${cfg.one}.`);
            body.newName = f.name.value.trim();
        } else body.holdingId = Number(f.fund.value);
        runLedger(() => api(`/api/portfolios/${kind}/invest`, { method: "POST", body }));
    };
}

function openDividendDialog(kind) {
    const cfg = PORTFOLIO_KINDS[kind], f = {};
    const today = isoDay(new Date());
    openLedgerDialog("Record a dividend", "Save", body => {
        f.fund = holdingSelect(kind);
        f.to = moneySourceSelect(false);
        f.amount = numberInput();
        f.date = el("input", { type: "date", value: today, max: today });
        body.append(field(cfg.one[0].toUpperCase() + cfg.one.slice(1), f.fund), field("Received in", f.to),
            field("Amount (₹)", f.amount), field("Date", f.date),
            el("div", { className: "edit-hint", textContent:
                `Recorded as income under Investment Income, Dividends, and linked to this ${cfg.one}. It does not change what you invested or what the ${cfg.one} is worth.` }));
        f.amount.focus();
    });
    dlg.onPrimary = () => {
        const amount = num(f.amount);
        if (!f.to.value) return ledgerFail("Add a bank or cash account first.");
        if (!(amount > 0)) return ledgerFail("Enter the dividend amount.");
        if (!f.date.value || f.date.value > today) return ledgerFail("Choose a date that is not in the future.");
        runLedger(() => portfolioApi(kind, `/${f.fund.value}/dividend`, { toAccountId: Number(f.to.value), amount, date: f.date.value }));
    };
}

/* What a sale made, said in words: the current value of what is sold less its invested amount. */
function saleResultText(invested, received) {
    const difference = Math.round((Number(received) - Number(invested)) * 100) / 100;
    if (difference === 0) return "No profit or loss on this sale.";
    const percent = Number(invested) > 0 ? ` (${difference > 0 ? "+" : "−"}${(Math.abs(difference) / Number(invested) * 100).toFixed(1)}%)` : "";
    return `${difference > 0 ? "Profit" : "Loss"} on this sale: ${rupees2(Math.abs(difference))}${percent}`;
}

/* Withdraw: pick the fund, type the invested amount you are selling and the current value of it (what you receive). The difference is the
   profit, shown as you type, and it is added to the card's Profit from sales. Both boxes start with the whole fund. */
function openWithdrawDialog(kind) {
    const cfg = PORTFOLIO_KINDS[kind], f = {};
    openLedgerDialog("Withdraw", "Withdraw", body => {
        f.fund = holdingSelect(kind);
        if (!f.fund.value && f.fund.children.length) f.fund.value = f.fund.children[0].value;   // the first one is chosen to start with
        f.to = moneySourceSelect(false);
        f.invested = numberInput();
        f.received = numberInput();
        f.result = el("div", { className: "edit-hint" });
        const sum = () => {
            const cost = num(f.invested), got = num(f.received);
            f.result.textContent = cost == null || got == null ? "" : saleResultText(cost, got);
        };
        const fill = () => {   // the whole fund to start with
            const h = holdingById(kind, f.fund.value);
            f.invested.value = h ? h.investedAmount : "";
            f.received.value = h ? h.currentValue : "";
            sum();
        };
        f.fund.addEventListener("change", fill);
        f.invested.addEventListener("input", sum);
        f.received.addEventListener("input", sum);
        body.append(field(cfg.one[0].toUpperCase() + cfg.one.slice(1), f.fund), field("Money goes to", f.to),
            field("Invested amount you are withdrawing (₹)", f.invested), field("Current value of it (₹): what you receive", f.received), f.result,
            el("div", { className: "edit-hint", textContent:
                "The invested amount comes off Total invested and the fund's invested amount; the current value comes off what the fund is worth. " +
                "The difference is your profit (or loss), kept under Profit from sales. For part of a fund, type only the part you sold." }));
        fill();
        f.invested.focus();
    });
    dlg.onPrimary = () => {
        const cost = num(f.invested), received = num(f.received), fund = holdingById(kind, f.fund.value);
        if (!f.to.value) return ledgerFail("Add a bank or cash account first.");
        if (cost == null || cost < 0) return ledgerFail("Enter the invested amount you are withdrawing.");
        if (!(received > 0)) return ledgerFail("Enter the current value of it, which is what you receive.");
        if (cents(cost) > cents(fund.investedAmount)) return ledgerFail(`${fund.name} has only ${rupees2(fund.investedAmount)} invested.`);
        runLedger(() => portfolioApi(kind, `/${fund.id}/withdraw`, { toAccountId: Number(f.to.value), amount: received, investedAmount: cost }));
    };
}

function openEditHoldingDialog(kind, holdingId) {
    const cfg = PORTFOLIO_KINDS[kind], f = {};
    const h = holdingById(kind, holdingId);
    openLedgerDialog(`Edit ${h.name}`, "Save", body => {
        f.name = el("input", { type: "text", maxLength: 100, autocomplete: "off", value: h.name });
        f.quantity = numberInput();
        f.invested = numberInput();
        f.value = numberInput();
        f.quantity.value = h.quantity == null ? "" : h.quantity;
        f.invested.value = h.investedAmount;
        f.value.value = h.currentValue;
        const empty = Number(h.currentValue) === 0;
        f.remove = el("button", { className: "btn btn-secondary btn-small", type: "button", textContent: `Remove this ${cfg.one}`, disabled: !empty });
        body.append(field("Name", f.name), field(`${cfg.qty === "Qty" ? "Quantity" : cfg.qty} (optional)`, f.quantity),
            field("Invested value (₹)", f.invested), field("Current value (₹)", f.value),
            el("div", { className: "edit-hint", textContent:
                `Type what you have invested and what the ${cfg.one} is worth now, as your app or statement shows. No money moves, and Total invested is not changed.` }),
            f.remove,
            el("div", { className: "edit-hint", textContent: empty ? `This ${cfg.one} is worth nothing, so it can be removed.`
                : `To remove a ${cfg.one}, withdraw everything from it first.` }));
        f.value.focus();
    });
    dlg.onPrimary = () => {
        const name = f.name.value.trim(), invested = num(f.invested), value = num(f.value), quantity = num(f.quantity);
        if (!name) return ledgerFail(`Enter the ${cfg.one}'s name.`);
        if (invested == null || invested < 0) return ledgerFail("Enter the invested value.");
        if (value == null || value < 0) return ledgerFail("Enter the current value.");
        if (quantity != null && quantity < 0) return ledgerFail("The quantity cannot be negative.");
        runLedger(async () => {
            if (name !== h.name) await portfolioApi(kind, `/${h.id}`, { name }, "PUT");
            await portfolioApi(kind, `/${h.id}/value`, { investedAmount: invested, currentValue: value, quantity });
        });
    };
    f.remove.addEventListener("click", () => runLedger(() => portfolioApi(kind, `/${h.id}`, undefined, "DELETE")));
}
