/* App bootstrap: loads the panel HTML, wires each panel, fetches the data and renders. */

import { clientLog, describeError } from "./core/client-log.js";
import { MONTH_NAMES } from "./core/constants.js";
import {
    accounts, setAccounts, setAllCategories, setBudgets, setCardDueDays, setCardStatementDays, setCreditLimits, setDebtEmis, setEmergencyFund, setExchangeRates,
    setJewellery, setLedger, setLoans, setNetWorthSeries, setNoteDays, setPortfolios, setPortfolioStats, setRecurringDeposits, setSplitSettlements, setTransactions, setWriteOffs,
    setUserReminders, setVehicles,
} from "./core/state.js";
import { renderForeignNotes } from "./core/currency.js";
import { setRefreshHandler } from "./core/refresh.js";
import { $, api } from "./core/util.js";
import { initAddAccount } from "./panels/add-account.js";
import { initBackup, loadBackupStatus } from "./panels/backup.js";
import { initExchangeRates } from "./panels/exchange-rates.js";
import { initBudgets, renderBudgets } from "./panels/budgets.js";
import { initInsights, renderInsights } from "./panels/insights.js";
import { initRemindersView, renderRemindersView } from "./panels/reminders-view.js";
import { initCalendar } from "./panels/calendar.js";
import { initCategories, renderCategories } from "./panels/categories.js";
import { initExport } from "./panels/export.js";
import { initImport } from "./panels/import.js";
import { initEditAccount } from "./panels/edit-account.js";
import { initEmergencyFund } from "./panels/emergency-fund.js";
import { initHeaderMenu } from "./panels/header-menu.js";
import { initLedger } from "./panels/ledger.js";
import { initReminderForm } from "./panels/my-reminders.js";
import { initNetWorth } from "./panels/net-worth.js";
import { initNotes } from "./panels/notes.js";
import { askForFirstPassword, initPassword, startScreenLock } from "./panels/password.js";
import { initPeriodFilter, renderPeriod } from "./panels/period-filter.js";
import { initPerson } from "./panels/person.js";
import { renderReminders } from "./panels/reminders.js";
import { initSetupWizard, openSetupWizard, showDashboard } from "./panels/setup-wizard.js";
import { initSummary, renderSummary } from "./panels/summary.js";
import { initAllocation, renderAllocation } from "./panels/allocation.js";
import { initTransactionForm } from "./panels/transaction-form.js";
import { initTransactionSearch } from "./panels/transactions.js";
import { initWorkspace } from "./panels/workspace.js";

/* Replaces every <div data-include="..."> with the HTML file it points at. A panel can include
   other panels, so keep going until no slots are left. */
async function loadPanels() {
    let slots = [...document.querySelectorAll("[data-include]")];
    while (slots.length) {
        await Promise.all(slots.map(async slot => {
            const res = await fetch(slot.dataset.include, { cache: "no-store" });
            if (!res.ok) {
                clientLog("ERROR", "panel.load-failed", `${slot.dataset.include} status=${res.status}`);   // a file name and a code, no data
                throw new Error(`Could not load ${slot.dataset.include}`);
            }
            slot.outerHTML = await res.text();
        }));
        slots = [...document.querySelectorAll("[data-include]")];
    }
}

async function loadDashboard() {
    const auth = await api("/api/auth/status");
    if (!auth.passwordSet) await askForFirstPassword();   // first run: no dashboard until a password exists
    startScreenLock();   // the page opens locked (unless this tab was already unlocked); the dashboard loads behind the cover
    const now = new Date();
    $("dashboardDate").textContent = `Financial position · ${MONTH_NAMES[now.getMonth()]}-${now.getFullYear()}`;
    await loadAllData();
    loadBackupStatus().catch(() => {});   // the warning bar must never stop the dashboard from opening
    if (accounts.length === 0) {
        openSetupWizard();   // first run: show only the wizard, the dashboard appears once it is closed
    } else {
        showDashboard();
    }
}


async function loadAllData() {
    try {
        const [accountList, cards, txns, cats, fund, loanSummary, ledgerView, netWorth, mutualFunds, shares, mutualFundStats, shareStats, vehicleList, noteCounts, reminderList, debtList, jewelleryView, budgetList, rateList, depositView, splitList, writeOffList] = await Promise.all([
            api("/api/accounts"), api("/api/credit-cards"), api("/api/transactions"), api("/api/categories"),
            api("/api/emergency-fund"), api("/api/loans"), api("/api/ledger"), api("/api/net-worth?months=120"),
            api("/api/portfolios/MUTUAL_FUNDS"), api("/api/portfolios/SHARES"),
            api("/api/portfolios/MUTUAL_FUNDS/stats"), api("/api/portfolios/SHARES/stats"), api("/api/vehicles"), api("/api/notes/days"), api("/api/reminders"), api("/api/debts"), api("/api/jewellery"), api("/api/budgets"), api("/api/exchange-rates"), api("/api/recurring-deposits"), api("/api/splits"), api("/api/loans/write-offs")]);
        setAccounts(accountList);
        setExchangeRates(rateList);
        setRecurringDeposits(depositView);
        setNetWorthSeries(netWorth);
        setLedger(ledgerView);
        setPortfolios({ MUTUAL_FUNDS: mutualFunds, SHARES: shares });
        setJewellery(jewelleryView);
        setPortfolioStats({ MUTUAL_FUNDS: mutualFundStats, SHARES: shareStats });
        setVehicles(new Map(vehicleList.map(v => [v.accountId, v])));
        setUserReminders(reminderList);
        setNoteDays(new Map(noteCounts.map(d => [d.date, d.count])));
        setLoans(loanSummary);
        setSplitSettlements(splitList);
        setWriteOffs(writeOffList);
        setEmergencyFund(fund);
        setTransactions(txns);
        setAllCategories(cats);
        setBudgets(budgetList);
        setCreditLimits(new Map(cards.map(c => [c.accountId, Number(c.creditLimit)])));
        setCardDueDays(new Map(cards.filter(c => c.dueDay).map(c => [c.accountId, c.dueDay])));
        setCardStatementDays(new Map(cards.filter(c => c.statementDay).map(c => [c.accountId, c.statementDay])));
        setDebtEmis(new Map(debtList.map(d => [d.accountId, Number(d.emiAmount)])));
        render();
    } catch (e) {
        clientLog("ERROR", "refresh.failed", describeError(e));   // a failed fetch or a panel that threw while drawing
        throw e;
    }
}

function render() {
    renderSummary();
    renderAllocation();
    renderReminders();
    renderRemindersView();
    renderInsights();
    renderBudgets();
    renderCategories();
    renderForeignNotes();
    renderPeriod();   // one date range for the calendar, the net worth line, the accounts' stats, the charts and the transactions
}

/* Runs one start-up call. If it throws, the log names it (a panel's initX), then the error goes on to the catch below. */
function step(init) {
    try {
        init();
    } catch (e) {
        clientLog("ERROR", "start.step-failed", `${init.name} ${describeError(e)}`);
        throw e;
    }
}

async function startApp() {
    setRefreshHandler(loadAllData);   // what refreshData() means for the panels, which cannot import this file
    window.dashboardStarted = true;   // boot-log.js stops watching: the modules loaded and linked
    clientLog("INFO", "page.start", typeof navigator === "undefined" ? "" : String(navigator.userAgent).slice(0, 150));
    await loadPanels();
    clientLog("INFO", "start.panels-loaded");
    step(initSummary);
    step(initAllocation);   // after initSummary: both listen to the eye
    step(initWorkspace);
    step(initPeriodFilter);
    step(initCalendar);
    step(initAddAccount);
    step(initSetupWizard);
    step(initTransactionForm);
    step(initEditAccount);
    step(initEmergencyFund);
    step(initPerson);
    step(initPassword);
    step(initTheme);
    step(initTransactionSearch);
    step(initBackup);
    step(initExchangeRates);
    step(initNotes);
    step(initRemindersView);
    step(initInsights);
    step(initBudgets);
    step(initReminderForm);
    step(initHeaderMenu);
    step(initLedger);
    step(initNetWorth);
    step(initCategories);
    step(initImport);
    step(initExport);
    await loadDashboard();
    clientLog("INFO", "start.ready");
}

startApp().catch(e => {
    clientLog("ERROR", "start.failed", describeError(e));   // first, so it is logged even if the lines below throw too
    showDashboard();
    $("dashboardDate").textContent = "Could not reach the server: " + e.message;
});
