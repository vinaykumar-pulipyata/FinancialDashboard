/* Data loaded from the backend, shared by the panels. app.js refreshes it, then calls render(). */

export let accounts = [];
export let transactions = [];
export let allCategories = [];
export let budgets = [];   // monthly spending limits: { id, categoryId (null = all spending), amount }
export let creditLimits = new Map();   // accountId -> credit limit
export let cardStatementDays = new Map();   // accountId -> the day of the month its statement is generated (only cards that have one)
export let cardDueDays = new Map();   // accountId -> the day of the month its bill is due (only cards that have one)
export let debtEmis = new Map();   // accountId -> the monthly EMI of a fixed loan (a loan that is not here is custom: any amounts)
export let emergencyFund = { configured: false };   // settings and calculated figures, or not set up yet
export let loans = { receivablesAccountId: null, unnamed: 0, borrowers: [] };   // people behind Receivables
export let ledger = { configured: false };   // the Future Expenses Ledger with its buckets, or not set up yet
export let portfolios = { MUTUAL_FUNDS: { configured: false, holdings: [] }, SHARES: { configured: false, holdings: [] } };   // Mutual Funds and Shares, each with its holdings
export let recurringDeposits = { configured: false, open: [], closed: [], deposited: 0 };   // the Recurring Deposits account with its open and closed deposits, or not set up yet
export let jewellery = { configured: false, metals: [] };   // the Jewellery account: items grouped by kind (Gold, Silver, ...), { configured, accountId, boughtPrice, metals: [{ metal, weight, boughtPrice, items }] }
export let portfolioStats = { MUTUAL_FUNDS: { overall: [], holdings: [] }, SHARES: { overall: [], holdings: [] } };   // value history for the stats graphs
export let vehicles = new Map();   // accountId -> { numberPlate, yearBought } for vehicle accounts
export let netWorthSeries = [];   // month-end net worth, oldest first (the page asks for 120 months)
export let userReminders = [];   // the user's own reminders: { id, date, title, done, doneOn }
export let exchangeRates = [];   // rupees per 1 unit of a foreign currency on a day: { id, currency, date, rate }
export let splitSettlements = [];   // which payments cleared which shared expenses: { id, borrowerId, expenseTransactionId, incomeTransactionId, amount }
export let writeOffs = [];   // debts let go of: { id, borrowerId, amount, writtenOn, note }
export let noteDays = new Map();   // yyyy-mm-dd -> how many notes that day has, for the marks on the calendar

/* Setters: a file that imports a variable cannot assign to it, so the files that replace it call these. */
export function setAccounts(value) { accounts = value; }
export function setAllCategories(value) { allCategories = value; }
export function setBudgets(value) { budgets = value; }
export function setCardDueDays(value) { cardDueDays = value; }
export function setCardStatementDays(value) { cardStatementDays = value; }
export function setCreditLimits(value) { creditLimits = value; }
export function setDebtEmis(value) { debtEmis = value; }
export function setExchangeRates(value) { exchangeRates = value; }
export function setEmergencyFund(value) { emergencyFund = value; }
export function setLedger(value) { ledger = value; }
export function setLoans(value) { loans = value; }
export function setNetWorthSeries(value) { netWorthSeries = value; }
export function setNoteDays(value) { noteDays = value; }
export function setPortfolioStats(value) { portfolioStats = value; }
export function setRecurringDeposits(value) { recurringDeposits = value; }
export function setJewellery(value) { jewellery = value; }
export function setPortfolios(value) { portfolios = value; }
export function setSplitSettlements(value) { splitSettlements = value; }
export function setWriteOffs(value) { writeOffs = value; }
export function setTransactions(value) { transactions = value; }
export function setUserReminders(value) { userReminders = value; }
export function setVehicles(value) { vehicles = value; }
