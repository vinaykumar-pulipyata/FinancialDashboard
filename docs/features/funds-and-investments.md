# Funds, the Ledger and investments

Emergency Fund, goal funds, the Future Expenses Ledger, Mutual Funds and Shares, Recurring Deposits, Jewellery and net worth history.

[← Back to the README](../../README.md) · [All features](../FEATURES.txt)

- **Emergency Fund** with a target (a number of months of essential expenses, or an amount), progress, runway in
  months and an estimated date to reach the target.

- **Goal funds** with targets and progress. Once a target is reached a fund can be **ring-fenced**: money can still
  go in but nothing can come out until you release it.

- **Future Expenses Ledger**: one pot of money split into **buckets** for bills you know are coming (insurance, wifi,
  subscriptions). Fund it from a bank account, pay a bill from a bucket, credit bank interest, move money between
  buckets, edit a bucket, and close a bucket. A default Buffer bucket absorbs extra money, so nothing is ever unallocated. Every
  change is kept as history.

- **Mutual Funds and Shares**: holdings with an optional quantity (units or shares), invested amount, current value, gain and gain percent. Every figure is shown to the paisa, never rounded to whole rupees. Holdings are listed by name, A to Z (capitals ignored), on the card and in the Update value, Withdraw and Edit lists. A holding row has two columns: on the left "Name - Qty 10, Avg ₹1,500.00" (the average price is invested ÷ quantity, shown once a quantity is entered), the current value in green for a profit or red for a loss, and the invested value; on the right the profit or loss (current value − invested) and its percentage. Invest from a
  bank or cash account, withdraw, update a value by hand, and record **dividends** on shares. Each day's figures are
  saved to draw value-over-time graphs. The **Stats** button shows those graphs (full width, drawn to suit the width of your window; the current value is an orange line and what you put in is a blue dashed line), and **Allocation** shows a donut of how
  the card's money is split between its holdings by current value (the five biggest, then Other) with a table of
  amounts and shares.

- **Stats range for Mutual Funds and Shares**: above the graph, buttons 6M, 1Y, 3Y, 5Y and All pick how far back it goes (All is the default and starts at the first recorded value), and From / To boxes pick any two days. The dividends graph and the table follow the same range.

- **Investments total**: the Investments tile, level and net worth count Mutual Funds and Shares at what was invested in the holdings (their cost), in big type; a small line under it gives the current value and the profit or loss in percent. Money figures everywhere show two decimals.

- **Recurring Deposits**: a fixed investment in Level 3 beside Mutual Funds and Shares (menu: Accounts › Investments). **Set up** creates one account, Recurring Deposits; **Open a deposit** adds each deposit with its start day, its term in months and, if you like, its monthly amount and yearly interest rate (and what was already paid in if it began before you used the page). A **progress bar** shows how far through its term each deposit is ("Month 7 of 36 · 19%", "Matured on ..."), with how many instalments went in, what it should pay at maturity (instalments compounded every quarter) and a **Transactions** fold under it, closed to begin with, that lists that deposit's transfers. **Deposit** puts an instalment in from a bank or cash account on the day you choose; **Close** (at maturity or early) takes everything paid in back to a bank or cash account and saves what came back above it as interest income. A closed deposit is kept, in a **Closed** list with its figures and transactions, never deleted, so the history stays; a mistaken deposit into an open one can be removed with the bin on its row. An open deposit counts towards net worth at what you have paid in, and the interest arrives as income when you close it. Like Mutual Funds, the account takes no ordinary income, expense or transfer.

- **Jewellery**: gold, silver and other items you own, kept as items (name, type: ornament, coin, bar, loose or other, weight in grams and the price paid) in one Jewellery account on Level 3. No current value is kept, so the account shows what the items cost. Add an item you already own, or one paid for from a bank or cash account (a transfer); Sell all or part of the weight into a bank or cash account (the difference to what it cost is recorded as income or an expense); Remove an item with no money moving. Ordinary transactions on the account are refused. It counts in Assets, the allocation ring and the Excel export.

- **Net worth history** replays every transaction by month for up to five years (60 months).
