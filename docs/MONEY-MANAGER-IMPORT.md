# Importing from Money Manager

[← Back to the README](../README.md)

`scripts/import-money-manager.py` moves an export of the Money Manager app (an .xlsx with one sheet) into a **new, empty** database, so
your real data is never touched. Start the app on its own database with `scripts/financial-dashboard.sh moneymanager` (`./data/moneymanager`;
do not open the page yet), then:

1. `python3 scripts/import-money-manager.py plan "Money Manager.xlsx"` writes `import-review/`: `account-map.csv` (the name, type and
   currency each Money Manager account gets), `category-map.csv` (each category and sub category), `rates.csv` (exchange rates worked out
   from the rows in VND and USD) and `plan.csv` (every row as it will be saved). Edit the two maps and run `plan` again until they are right.
   A Money Manager account can be marked in `account-map.csv` as a deposit of the Recurring Deposits card (`HOLDING_RECURRING_DEPOSIT`) or as a fund
   inside Mutual Funds (`HOLDING_MUTUAL_FUNDS`); their transfers are saved with the card's own Deposit, Close and Invest on their dates.
2. `FINANCE_PASSWORD=... python3 scripts/import-money-manager.py apply` sets the rates, creates the accounts and the missing categories and
   saves the rows oldest first. It stops with REFUSED if the database already has accounts or transactions. A new database has no
   password yet, so the one you give is set.

Every opening balance ends at 0, so each account's balance is what the rows add up to: edit an account to give it the real balance of
1 January. Negative expenses (money given back) are saved as income under Refund; Money Manager's balance corrections are kept under
Balance correction; a transfer between two currencies keeps both amounts.
