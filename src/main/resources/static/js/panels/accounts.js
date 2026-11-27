/* Funds & Accounts: coloured tiles per level; a chosen level shows its accounts, ranked by amount. */

import { LEVEL_NAMES, LIABILITIES, TYPE_LABELS } from "../core/constants.js";
import {
    accounts, cardDueDays, cardStatementDays, creditLimits, debtEmis, emergencyFund, ledger, loans, portfolios, transactions, vehicles,
} from "../core/state.js";
import { $, addMonthsClamped, balanceInr, countTo, el, growsOnce, inCurrency, isoDay, ordinal, riseOnce, rupees, rupees2, setBarWidth } from "../core/util.js";
import { LEVEL_GUIDE, openAddAccount } from "./add-account.js";
import { openEditAccount, openWriteOffDebt } from "./edit-account.js";
import { efTile, emergencyCard, monthYear } from "./emergency-fund.js";
import { cardCyclesBlock } from "./card-cycles.js";
import { loanTransactionsBlock } from "./loan-block.js";
import { loanProgress } from "./loan-calc.js";
import { ledgerCard } from "./ledger.js";
import { openLiabilityPayment } from "./liability-payment.js";
import { openPerson } from "./person.js";
import { hasOpenBalance, personToggle, personTransactionsBlock, settleButton } from "./split-block.js";
import { isJewelleryAccount, jewelleryCard } from "./jewellery.js";
import { isRecurringDepositAccount, recurringDepositsCard } from "./recurring-deposits.js";
import { isPortfolioAccount, portfolioCard } from "./portfolio.js";
import { fenceBanner, goalTargetReached, peekAmount } from "./ring-fence.js";
import { infoTip } from "./transaction-form.js";
import { showTransactionsFor } from "./transactions.js";
import { showWorkspaceView } from "./workspace.js";

const tileTotals = new Map();   // level -> the total its tile last counted to
let openLevel = null;   // the level whose accounts are on show in the level view

/* The Accounts view is a wall of coloured tiles, one per level. A tile shows the level's name, its total and how many
   accounts it holds; clicking it turns the card over to that level's accounts (renderLevelBody). */
export function renderAccounts() {
    const list = $("accountList");
    list.innerHTML = "";
    if (accounts.length === 0) {
        list.append(el("div", { className: "empty-state", textContent: 'No accounts yet. Open a level and use "+" to add one.' }));
    }
    const byId = new Map(accounts.map(a => [a.id, a]));
    const display = levelAccounts();
    const levels = accountLevels(display);
    const tiles = el("div", { className: "level-tiles" });
    for (const level of levels) {
        const all = accounts.filter(a => a.active && a.level === level);   // the Emergency Fund, Ledger and portfolios count too
        const total = all.filter(a => !a.ringFenced).reduce((sum, a) => sum + balanceInr(a), 0);   // foreign accounts count in rupees; Mutual Funds and Shares at what they cost; ring-fenced money is not added here, only in net worth
        const market = levelMarket(all, total);
        const [num, name] = (LEVEL_NAMES[level] || "Level " + level).split(" · ");
        const tile = el("button", { className: `level-tile level-${level}`, type: "button" },
            el("span", { className: "lt-num", textContent: num }),
            el("span", { className: "lt-name", textContent: name || "" }),
            el("span", { className: "lt-total", textContent: rupees2(tileTotals.get(level) ?? total), title: market ? "Total invested" : "" }),
            ...(market ? [el("span", { className: "lt-sub", textContent: marketText(market) })] : []),
            el("span", { className: "lt-count", textContent: `${all.length} ${all.length === 1 ? "account" : "accounts"}` }));
        if (LEVEL_GUIDE[level]?.holds) {   // an (i) in the corner; hovering it (or focusing the tile) shows what the level holds
            const info = el("span", { className: "lt-info", textContent: "ⓘ" },
                el("span", { className: "lt-tip", role: "tooltip", textContent: LEVEL_GUIDE[level].holds }));
            tile.append(info);
        }
        tile.addEventListener("click", () => { openLevel = level; renderAccounts(); showWorkspaceView("level"); });
        countTo(tile.querySelector(".lt-total"), tileTotals.get(level) ?? total, total, rupees2);   // counts only when the total changed
        tileTotals.set(level, total);
        tiles.append(riseOnce(tile, "level-tile-" + level));
    }
    list.append(tiles);
    renderLevelBody(display, byId);
}

/* The portfolio (Mutual Funds or Shares) that an account is, or null. */
function portfolioOf(a) {
    return Object.values(portfolios).find(p => p.configured && p.accountId === a.id) || null;
}

/* What the level's investments are worth today against what they cost (the tile and the level count them at cost): null when the level has
   no Mutual Funds or Shares account. A holding's market value comes from its portfolio, every other account counts at its balance. */
function levelMarket(all, total) {
    let current = total, any = false;
    for (const a of all) {
        const p = portfolioOf(a);
        if (p) {
            any = true;
            current += Number(p.currentValue) - balanceInr(a);
        }
    }
    return any ? { invested: total, current } : null;
}

/* "Current value ₹1,32,785.68 · ▲ +3.78%": the small line under the big total invested. */
function marketText(m) {
    const pct = m.invested !== 0 ? (m.current - m.invested) / Math.abs(m.invested) * 100 : null;
    return `Current value ${rupees2(m.current)}` + (pct == null ? "" : ` · ${pct >= 0 ? "▲ +" : "▼ −"}${Math.abs(pct).toFixed(2)}%`);
}

/* The accounts that show as ordinary rows: the Emergency Fund, the Ledger and the portfolios have cards of their own. */
function levelAccounts() {
    const fundId = emergencyFund.configured ? emergencyFund.accountId : null;
    const ledgerId = ledger.configured ? ledger.accountId : null;
    return displayAccounts().filter(a => a.id !== fundId && a.id !== ledgerId && !isPortfolioAccount(a.id) && !isJewelleryAccount(a.id) && !isRecurringDepositAccount(a.id));
}

/* Every level is always listed, even when empty, so each one explains itself and offers its own Add button. */
function accountLevels(display) {
    return [...new Set([...Object.keys(LEVEL_GUIDE).map(Number), ...display.map(a => a.level)])].sort((x, y) => x - y);
}

/* The chosen level: its head with the Add button, then its cards and account rows. */
function renderLevelBody(display, byId) {
    const body = $("levelBody");
    body.innerHTML = "";
    const level = openLevel;
    if (level == null) return;
    const group = el("div", { className: `level-group level-${level}` });
    const head = el("div", { className: "level-head" },
        el("div", { className: "flow-level-label", textContent: LEVEL_NAMES[level] || "Level " + level }));
    if (LEVEL_GUIDE[level]) {
        const add = el("button", { className: "btn btn-secondary level-add", textContent: "+", title: "Add account" });
        add.setAttribute("aria-label", "Add account");
        add.addEventListener("click", () => openAddAccount(level));
        head.append(add);
    }
    group.append(head);
    const here = accounts.filter(a => a.active && a.level === level);
    const fenced = here.filter(a => a.ringFenced).reduce((sum, a) => sum + balanceInr(a), 0);
    if (fenced !== 0) {   // not in the level's total above, still in net worth
        group.append(el("div", { className: "level-fenced", textContent: `🔒 ${rupees2(fenced)} is ring-fenced: not added to this level's total, still counted in net worth.` }));
    }
    const counted = here.filter(a => !a.ringFenced);
    const market = levelMarket(counted, counted.reduce((sum, a) => sum + balanceInr(a), 0));
    if (market) {   // the Investments level: the total invested in big type, what it is worth today and the gain or loss in small type
        group.append(el("div", { className: "level-market" },
            el("span", { className: "level-market-big", title: "Total invested", textContent: "Invested " + rupees2(market.invested) }),
            el("span", { className: "level-market-small", textContent: marketText(market) })));
    }
    if (level === 2) group.append(emergencyCard(), ledgerCard());   // pinned above the other goals
    if (level === 3) group.append(portfolioCard("MUTUAL_FUNDS"), portfolioCard("SHARES"), recurringDepositsCard(), jewelleryCard());   // above other investments
    const inLevel = display.filter(a => a.level === level)
        .sort((x, y) => (y.active === false ? 0 : 1) - (x.active === false ? 0 : 1) || Number(y.balance) - Number(x.balance));   // deactivated ones go last
    if (inLevel.length === 0 && level !== 2 && level !== 3 && LEVEL_GUIDE[level]) {
        group.append(el("div", { className: "level-empty", textContent: `Nothing here yet. ${LEVEL_GUIDE[level].blurb}` }));
    }
    let offShown = false;
    for (const a of inLevel) {
        if (a.active === false && !offShown) {   // a heading before the first switched-off account
            offShown = true;
            group.append(el("div", { className: "level-off", textContent: `Deactivated (${inLevel.filter(x => x.active === false).length}) · nothing can be done with these until you activate them` }));
        }
        group.append(accountRow(a, byId));
    }
    if (level === 5 && receivablesExpanded) receivablesPeople().forEach(node => group.append(node));
    body.append(group);
}

/* All receivables collapse into one "Receivables" row showing the total. Each person is listed under it. */
function displayAccounts() {
    const receivables = accounts.filter(a => a.type === "RECEIVABLE" && a.active);
    const others = accounts.filter(a => a.type !== "RECEIVABLE" || !a.active);
    if (receivables.length <= 1) return accounts;
    const total = receivables.reduce((sum, a) => sum + Number(a.balance), 0);
    return [...others, {
        name: "Receivables", type: "RECEIVABLE", level: Math.min(...receivables.map(a => a.level)),
        balance: total, active: true, parentId: null,
        summaryLabel: `${receivables.length} ${receivables.length === 1 ? "entry" : "entries"}`
    }];
}

/* What the (i) on an investment or gold row says: there is no Sell button, a sale is a transfer. */
const SELL_ROW_HELP = "Sold some or all of this? Record it as a transfer from this account to your bank or cash account: use the red + and choose Transfer. "
    + "If it sold for a different price than the value shown, change the value with the \u270F\uFE0F first.";

/* An account's row, with an Edit button at its end, and the ring-fence message under a goal fund that needs one. */
function accountRow(a, byId) {
    const row = buildAccountRow(a, byId);
    let sellTip = null;
    if (a.id != null) {
        const edit = document.createElement("button");
        edit.className = "icon-btn row-edit";
        edit.textContent = "✏️";
        edit.title = "Edit this account";
        edit.setAttribute("aria-label", "Edit " + a.name);
        edit.addEventListener("click", () => openEditAccount(a));
        const view = document.createElement("button");
        view.className = "icon-btn row-edit";
        view.textContent = "🧾";
        view.title = "Show this account's transactions";
        view.setAttribute("aria-label", "Transactions of " + a.name);
        view.addEventListener("click", () => showTransactionsFor({ account: a.id }));
        row.querySelector(".name").append(" ", edit, " ", view);
        if ((a.type === "INVESTMENT" || a.type === "GOLD") && a.active) {
            sellTip = infoTip(SELL_ROW_HELP, "How to record selling " + a.name);
            row.querySelector(".name").append(" ", sellTip.button);
        }
        if (LIABILITIES.has(a.type) && a.active && !loanClosed(a)) {   // the only place a payment towards a loan or card is made
            const pay = el("button", { className: "btn btn-primary btn-small", type: "button", textContent: "Pay", title: "Pay " + a.name });
            pay.setAttribute("aria-label", "Pay " + a.name);
            pay.addEventListener("click", () => openLiabilityPayment(a).catch(e => alert(e.message)));
            row.querySelector(".name").append(" ", pay);
        }
        if (LIABILITIES.has(a.type) && a.active && Number(a.balance) > 0) {   // a debt you owe that the lender lets go of
            const wipe = el("button", { className: "btn btn-secondary btn-small", type: "button", textContent: "Write off", title: "Let the debt go: take what is owed to zero, with a note" });
            wipe.setAttribute("aria-label", "Write off " + a.name);
            wipe.addEventListener("click", () => openWriteOffDebt(a));
            row.querySelector(".name").append(" ", wipe);
        }
    }
    if (a.type === "RECEIVABLE" && hasReceivablesDetail()) {
        const toggle = el("button", { className: "icon-btn ledger-toggle", type: "button", textContent: receivablesExpanded ? "▾" : "▸" });
        const label = `${receivablesExpanded ? "Collapse" : "Expand"} the people behind Receivables`;
        toggle.title = label;
        toggle.setAttribute("aria-label", label);
        toggle.setAttribute("aria-expanded", String(receivablesExpanded));
        toggle.addEventListener("click", () => { receivablesExpanded = !receivablesExpanded; renderAccounts(); });
        row.querySelector(".name").prepend(toggle);
    }
    const banner = a.id != null && a.type === "GOAL_FUND"
        ? fenceBanner({ id: a.id, name: a.name, reached: goalTargetReached(a), fenced: a.ringFenced }) : null;
    const stats = goalStats(a);
    const reminder = vehicleReminder(a);
    const cycles = a.type === "CREDIT_CARD" && a.id != null ? cardCyclesBlock(a, renderAccounts)   // the card's statement cycles, folded under its row
        : a.type === "DEBT" && a.id != null ? loanTransactionsBlock(a, renderAccounts) : null;       // a loan's transactions, folded the same way
    if (!banner && !stats && !reminder && !sellTip && !cycles) return row;
    return el("div", { className: "account-block" }, row, ...[sellTip && sellTip.line, stats, banner, reminder, cycles].filter(Boolean));
}

/* A vehicle loses value every year, but the app only knows the value you typed in. After this many months a note asks
   you to check it, so net worth does not drift above what the vehicle would really sell for. */
const VEHICLE_STALE_MONTHS = 6;

/* Whole months from an ISO date until today. */
function monthsSince(iso) {
    const [y, m, d] = iso.split("-").map(Number), now = new Date();
    let months = (now.getFullYear() - y) * 12 + (now.getMonth() + 1 - m);
    if (now.getDate() < d) months -= 1;
    return Math.max(0, months);
}

function monthsAgoText(months) {
    if (months < 12) return months === 1 ? "1 month ago" : `${months} months ago`;
    const years = Math.floor(months / 12);
    return years === 1 ? "1 year ago" : `${years} years ago`;
}

/* The note under a vehicle whose value has not been set for a while (or whose date is not known). */
function vehicleReminder(a) {
    if (a.type !== "VEHICLE" || a.id == null || !a.active) return null;
    const updated = vehicles.get(a.id)?.valueUpdatedOn;
    let text;
    if (!updated) {
        text = "The date this value was set is not known. Check that it is what the vehicle would sell for today.";
    } else {
        const months = monthsSince(updated);
        if (months < VEHICLE_STALE_MONTHS) return null;
        text = `Value last updated ${monthsAgoText(months)}. Vehicles lose value every year, so update it to keep your net worth honest.`;
    }
    const update = el("button", { className: "txn-link", type: "button", textContent: "Update value" });
    update.addEventListener("click", () => openEditAccount(a));
    return el("div", { className: "value-reminder", role: "note" }, el("span", { textContent: "⚠ " + text }), update);
}

/* The money that came into a goal fund over the last 3 months, per month. Same rule as the Emergency Fund's average. */
function goalMonthlyContribution(accountId) {
    const from = addMonthsClamped(new Date(), -3);
    const since = isoDay(from), today = isoDay(new Date());
    return transactions.filter(t => t.toAccountId === accountId && t.date >= since && t.date <= today)
        .reduce((sum, t) => sum + Number(t.amount), 0) / 3;
}

/* Balance, target, remaining, monthly contribution and estimated date under a goal fund that has a target.
   A ring-fenced fund shows none of it (out of sight until released). */
function goalStats(a) {
    if (a.type !== "GOAL_FUND" || a.id == null || a.ringFenced || !(Number(a.targetAmount) > 0)) return null;
    const balance = Number(a.balance), target = Number(a.targetAmount);
    const remaining = Math.max(0, target - balance);
    const monthly = goalMonthlyContribution(a.id);
    let estimate;
    if (remaining === 0) estimate = efTile("Estimated date", "Reached", "You have hit your target");
    else if (monthly > 0) {
        const when = addMonthsClamped(new Date(), Math.ceil(remaining / monthly));
        estimate = efTile("Estimated date", monthYear(isoDay(when)), "At the contribution shown");
    } else estimate = efTile("Estimated date", "No estimate yet", "Add money to this fund to see one");
    return el("div", { className: "goal-stats" },
        efTile("Balance", rupees2(balance)),
        efTile("Target", rupees2(target)),
        efTile("Remaining", rupees2(remaining)),
        efTile("Monthly contribution", monthly > 0 ? rupees2(monthly) : "None yet", "Average of the last 3 months"),
        estimate);
}

/* A loan that was owed something and now owes nothing is closed: it keeps its history but no more payments are made to it. (A card at
   zero is simply unused, not closed.) */
function loanClosed(a) {
    return a.type === "DEBT" && a.id != null && loanProgress(a, transactions, null).finished;
}

function buildAccountRow(a, byId) {
    const row = document.createElement("div");
    row.className = "account-row" + (a.parentId && byId.has(a.parentId) ? " child" : "") + (a.active ? "" : " inactive") + (loanClosed(a) ? " closed" : "");
    if (a.id != null) riseOnce(row, "account-row-" + a.id);
    const left = document.createElement("div");
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = a.name;
    const type = document.createElement("span");
    type.className = "type";
    const parent = a.parentId && byId.get(a.parentId);
    const vehicle = a.type === "VEHICLE" && a.id != null ? vehicles.get(a.id) : null;
    type.textContent = (a.summaryLabel || (a.type === "RECEIVABLE" ? "Total owed to you" : TYPE_LABELS[a.type]))
        + (vehicle && vehicle.numberPlate ? " · " + vehicle.numberPlate : "")
        + (vehicle && vehicle.yearBought ? " · bought " + vehicle.yearBought : "")
        + (vehicle && vehicle.valueUpdatedOn ? " · value set " + monthYear(vehicle.valueUpdatedOn) : "")
        + (loanClosed(a) ? " · closed" : a.type === "DEBT" && a.id != null ? (debtEmis.has(a.id) ? ` · EMI ${rupees(debtEmis.get(a.id))} a month` : " · pay any amount") : "")
        + (parent ? " · under " + parent.name : "");
    if (!a.active) name.append(" ", el("span", { className: "off-badge", textContent: "Deactivated" }));
    left.append(name, type);
    const amount = document.createElement("span");
    const liability = LIABILITIES.has(a.type);
    amount.className = "amount" + (liability ? " liability" : "");
    if (a.id != null && a.ringFenced) {
        amount.append(peekAmount(rupees(a.balance), "acct-" + a.id));   // ring-fenced: out of sight until you look
    } else {
        amount.textContent = loanClosed(a) ? "Closed" : inCurrency(a.balance, a.currency) + (a.type === "CREDIT_CARD" ? " withdrawal" : liability ? " owed" : "")
            + (a.currency && a.currency !== "INR" ? ` · about ${rupees(a.balanceInr)}` : "");
    }
    if (a.type === "CREDIT_CARD" && a.id != null) {
        const limit = creditLimits.get(a.id);
        const right = document.createElement("div");
        right.className = "card-usage";
        const summary = document.createElement("div");
        summary.className = "usage-text";
        right.append(amount);
        if (limit > 0) {
            const used = Number(a.balance) / limit * 100;
            summary.textContent = `${used.toFixed(1)}% of ${rupees(limit)} limit used · ${rupees(Math.max(limit - Number(a.balance), 0))} available` + (cardStatementDays.has(a.id) ? ` · statement on the ${ordinal(cardStatementDays.get(a.id))}` : "") + (cardDueDays.has(a.id) ? ` · bill due on the ${ordinal(cardDueDays.get(a.id))}` : "");
            const bar = document.createElement("div");
            bar.className = "usage-bar";
            const fill = document.createElement("div");
            fill.className = "usage-fill " + (used >= 70 ? "high" : used >= 30 ? "mid" : "low") + (growsOnce("card-" + a.id) ? " grow" : "");
            setBarWidth(fill, "card-" + a.id, Math.min(used, 100));
            bar.appendChild(fill);
            right.append(summary, bar);
        } else {
            summary.textContent = "No credit limit set. Use Edit to add one.";
            right.append(summary);
        }
        row.append(left, right);
        return row;
    }
    if (a.type === "DEBT" && a.id != null) {   // a loan: how much of it has been paid back, as a bar like the card's
        const loan = loanProgress(a, transactions, debtEmis.get(a.id));
        const right = document.createElement("div");
        right.className = "card-usage";
        right.append(amount);
        if (loan.total > 0) {
            const summary = document.createElement("div");
            summary.className = "usage-text";
            summary.textContent = loan.finished ? `Fully repaid · ${rupees(loan.repaid)} paid in ${loan.payments} ${loan.payments === 1 ? "payment" : "payments"}`
                : `${loan.percent.toFixed(1)}% repaid · ${rupees(loan.repaid)} of ${rupees(loan.total)} paid · ${rupees(loan.left)} left`
                    + (loan.paymentsLeft ? ` · about ${loan.paymentsLeft} ${loan.paymentsLeft === 1 ? "EMI" : "EMIs"} to go` : "");
            const bar = document.createElement("div");
            bar.className = "usage-bar";
            bar.setAttribute("role", "progressbar");
            bar.setAttribute("aria-valuenow", String(Math.round(loan.percent)));
            bar.setAttribute("aria-valuemin", "0");
            bar.setAttribute("aria-valuemax", "100");
            bar.setAttribute("aria-label", `${a.name}: ${loan.percent.toFixed(1)}% repaid`);
            const fill = document.createElement("div");
            fill.className = "usage-fill " + (loan.finished ? "low" : "goal") + (growsOnce("loan-" + a.id) ? " grow" : "");
            setBarWidth(fill, "loan-" + a.id, loan.percent);
            bar.append(fill);
            right.append(summary, bar);
        }
        row.append(left, right);
        return row;
    }
    if (a.type === "GOAL_FUND" && a.id != null && Number(a.targetAmount) > 0 && !a.ringFenced) {
        const target = Number(a.targetAmount);
        const pct = Math.max(0, Math.min(100, Number(a.balance) / target * 100));
        const right = document.createElement("div");
        right.className = "card-usage";
        const summary = document.createElement("div");
        summary.className = "usage-text";
        summary.textContent = `${pct.toFixed(1)}% of ${rupees(target)}`;
        const bar = document.createElement("div");
        bar.className = "usage-bar";
        const fill = document.createElement("div");
        fill.className = "usage-fill goal" + (growsOnce("goal-" + a.id) ? " grow" : "");
        setBarWidth(fill, "goal-" + a.id, pct);
        bar.appendChild(fill);
        right.append(amount, summary, bar);
        row.append(left, right);
        return row;
    }
    row.append(left, amount);
    return row;
}

/* Under the Receivables total: who owes what, from Loans Given and Loans Collected. Whatever part of the opening
   total has not been given to a person yet shows as "Earlier loans (no name)". */
let receivablesExpanded = false;      // the people under Receivables start closed; the choice lasts until the page is reloaded

function hasReceivablesDetail() {
    return !!loans.receivablesAccountId || loans.borrowers.length > 0;
}

function receivablesPeople() {
    if (!hasReceivablesDetail()) return [];
    const rows = [];
    if (Number(loans.unnamed) > 0) rows.push(personRow({ name: "Earlier loans (no name)", owed: loans.unnamed }, "earlier"));
    for (const b of loans.borrowers) rows.push(personRow(b, "person"));
    const add = el("button", { className: "btn btn-secondary btn-small", textContent: "+ Add person" });
    add.addEventListener("click", () => openPerson(null));
    rows.push(el("div", { className: "person-add" }, add));
    return rows;
}

/* kind is "person" for someone on the list, or "earlier" for the unnamed part of the opening total. */
function personRow(b, kind) {
    const editable = kind === "person";
    const balance = Number(b.owed) - (editable ? Number(b.youOwe) || 0 : 0);   // what they owe you less what you owe them
    const owed = balance;
    const row = el("div", { className: "account-row child" });
    const name = el("span", { className: "name", textContent: b.name });
    if (editable) {
        const edit = el("button", { className: "icon-btn row-edit", textContent: "✏️", title: "Edit this person" });
        edit.setAttribute("aria-label", "Edit " + b.name);
        edit.addEventListener("click", () => openPerson(b));
        name.prepend(personToggle(b, renderAccounts));   // the name folds out their transactions
        name.append(" ", edit);
        if (hasOpenBalance(b)) name.append(" ", settleButton(b));   // one Settle for the whole balance, beside the name
    }
    const letGo = (Number(b.writtenOff) !== 0 ? ` · ${rupees(Math.abs(b.writtenOff))} written off` : "");
    const left = el("div", {}, name,
        el("span", { className: "type", textContent: (editable ? (owed < 0 ? "You owe them" : "Owes you") : "Not yet given to a person") + letGo }));
    if (!editable) {
        // The unnamed earlier loans: a plain link to give all or part of them a name.
        const give = el("button", { className: "txn-link", type: "button", textContent: "Give this a name" });
        give.addEventListener("click", () => openPerson(null, { fromEarlier: true }));
        left.append(el("div", { className: "earlier-link" }, give));
    }
    row.append(left);
    row.append(el("span", { className: "amount" + (owed < 0 ? " liability" : ""), textContent: (owed < 0 ? "−" : "") + rupees(Math.abs(owed)) }));   // a negative amount: you owe them
    if (editable) {   // their loans, the expenses shared with them and the bills they paid for you, folded under the name (an empty fold says so)
        const fold = personTransactionsBlock(b);
        return fold ? el("div", { className: "account-block" }, row, fold) : row;
    }
    return row;
}
