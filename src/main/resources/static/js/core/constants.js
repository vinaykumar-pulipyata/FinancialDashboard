/* Labels and lookup tables shared by every panel. */

export const TYPE_LABELS = {
    CASH: "Cash", BANK: "Bank", WALLET: "Wallet", GOAL_FUND: "Goal fund", INVESTMENT: "Investment", GOLD: "Gold", JEWELLERY: "Jewellery",
    PROPERTY: "Property", VEHICLE: "Vehicle", RECEIVABLE: "Receivables", DEBT: "Debt",
    CREDIT_CARD: "Credit card", OTHER: "Other"
};
export const LIABILITIES = new Set(["DEBT", "CREDIT_CARD"]);
/* Fixed categories (category.systemKey). Loans Given and Loans Collected name a person; Liability Payments names the
   loan or card it pays (the transaction's To account) and is only made with a liability's Pay button. */
export const PERSON_CATEGORY_KEYS = new Set(["LOANS_GIVEN", "LOANS_COLLECTED"]);
export const LIABILITY_PAYMENT_KEY = "LIABILITY_PAYMENT";
/* Balance Adjustment is what an account's Reset balance button writes (an expense, or an income): it is not spending or earning, so Stats,
   Spending Review and Budgets leave it out like a loan, and the transaction form does not offer it. */
export const ADJUSTMENT_KEYS = new Set(["BALANCE_ADJUSTMENT_EXPENSE", "BALANCE_ADJUSTMENT_INCOME"]);
export const NOT_SPENDING_KEYS = new Set([...PERSON_CATEGORY_KEYS, ...ADJUSTMENT_KEYS]);
export const LEVEL_NAMES = {
    1: "Level 1 · Liquid money", 2: "Level 2 · Goals", 3: "Level 3 · Investments",
    4: "Level 4 · Fixed assets", 5: "Level 5 · Money owed to you",
    6: "Level 6 · Liabilities", 7: "Level 7 · Other"
};

export const MONTH_NAMES = ["January", "February", "March", "April", "May", "June", "July",
    "August", "September", "October", "November", "December"];

export const KIND_LABELS = { INCOME: "Income", EXPENSE: "Expense", TRANSFER: "Transfer" };

/* The currencies an account can be held in, the same list as the server's Currency enum (change both together). INR is the base:
   totals and net worth are in rupees. `decimals` is how many decimal places an amount has (VND has none). Only the asset types in
   CURRENCY_TYPES can be foreign; the rest are rupees only for now. */
export const CURRENCIES = {
    INR: { symbol: "₹", decimals: 2, label: "INR · Indian rupee" },
    USD: { symbol: "$", decimals: 2, label: "USD · US dollar" },
    EUR: { symbol: "€", decimals: 2, label: "EUR · Euro" },
    GBP: { symbol: "£", decimals: 2, label: "GBP · Pound sterling" },
    VND: { symbol: "₫", decimals: 0, label: "VND · Vietnamese dong" }
};
export const CURRENCY_TYPES = new Set(["BANK", "CASH", "WALLET", "INVESTMENT", "PROPERTY", "VEHICLE", "OTHER"]);
