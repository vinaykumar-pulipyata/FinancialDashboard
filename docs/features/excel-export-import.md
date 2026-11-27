# Excel export and import

Getting everything out into a workbook, and loading such a workbook into a new, empty database.

[← Back to the README](../../README.md) · [All features](../FEATURES.txt)

- **Excel export.** One button (it asks for your password first, because the file holds everything, your diary included, and a wrong password is refused and counted like a wrong sign-in) downloads a workbook with a sheet for each area (accounts, every transaction, income and
  expense by category, monthly totals, net worth, the Ledger and its entries, the Emergency Fund, Mutual Funds,
  Shares, loans, categories, budgets, notes, reminders and the value history of each holding) and native Excel charts. The Accounts
  sheet also has each account's goal target, credit limit and bill day, monthly EMI and vehicle details, and its **currency** and its
  **balance in ₹** (the amount the totals count; the Balance column stays in the account's own currency). When you have entered exchange
  rates, a last **Exchange rates** sheet lists them. The Transactions sheet has the **currency** of each amount and, for a transfer between two currencies, what arrived and in which currency. The net worth sheet is in rupees. It is for reading; the backups are for restoring.

- **Import from Excel** (menu > Data > Import from Excel): choose a file made by Export to Excel and press **Check the file**, which goes through everything exactly as the import would and shows what it found without saving anything; then press **Import**. A bar shows what it is doing and how far it has got. When everything loads without a problem the **Calendar** opens; if some rows had problems the page stays and lists them (with an **Open the calendar** button), and any error stays on the page. It only works on a **new, empty database**. It loads, in the order the data depends on itself: exchange rates, categories, accounts (credit cards, loans with their EMI, vehicles, goal funds, foreign currency), people and the Receivables total, all ordinary transactions, the Emergency Fund settings, the Future Expenses Ledger (buckets, history and balance), Recurring Deposits, Mutual Funds and Shares (holdings, Invest, Withdraw, dividends, value history), Jewellery (items and their transfers), write-offs, Balance Adjustment rows, inactive accounts and categories, budgets, note folders and notes, reminders and ticked days. The Balance Adjustment rows from Reset balance and Write off come back on their own days. Ring-fenced goal funds are ring-fenced again once everything is loaded. **What it does not bring back:** only the sheets that just report (Net worth, Monthly, Income & Expenses, Bills), which are built from the rest. The page lists anything it left out or could not load.

- **More sheets in the Excel export**: Balance adjustments, Write-offs, Bills (cards and Ledger bills), Note folders and Reminder ticks (the ticked days of repeating reminders).
