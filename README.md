# Financial Dashboard

A personal finance app for one person. Everything is counted in Indian rupees (INR); an account can also be held in USD, EUR, GBP or VND. It runs on your own computer, keeps its data in a local file database and shows everything on one web page at `http://finance.localhost:8080/` (`http://localhost:8080/` works too). No cloud, no account to create elsewhere and no live price feeds: you enter your own numbers.

Built with Spring Boot 4.1.1 (Java 17), JPA on a file-based H2 database with Flyway migrations, and a plain HTML/CSS/JavaScript front end with no build step. The only thing you install yourself is **Java 17**; Gradle fetches the rest.

## Download (no Java needed)

**One command** (macOS on Apple silicon and Linux; it finds out your system, downloads the right file, checks its checksum and sets up a `financial-dashboard` command):

```
curl -fsSL https://raw.githubusercontent.com/vinaykumar-pulipyata/FinancialDashboard/main/scripts/install.sh | sh
```

On Windows, in PowerShell: `irm https://raw.githubusercontent.com/vinaykumar-pulipyata/FinancialDashboard/main/scripts/install.ps1 | iex`. Details and options: [Installing with one command](docs/OPERATIONS.md#installing-with-one-command). To remove it again (your data stays unless you ask): [Uninstalling](docs/OPERATIONS.md#uninstalling).

Or download by hand: each release on the [Releases page](https://github.com/vinaykumar-pulipyata/FinancialDashboard/releases) has a self-contained executable for **Linux (x64 and arm64), macOS (Apple silicon) and Windows (x64)**, each with a `.sha256` checksum file. There is no build for Intel Macs. Put the file in a folder of its own (it keeps `data/`, `backups/` and `logs/` next to where you start it) and run it, then open `http://localhost:8080/`. How to open it on each system, and checking the download: [Running the downloaded executable](docs/OPERATIONS.md#running-the-downloaded-executable). The first release (`v0.1.0`) has not been published yet.

## Quick start (from the source)

```
scripts/check-setup.sh             # says what is missing and how to install it (changes nothing)
scripts/financial-dashboard.sh     # starts the app; open http://localhost:8080/
```

The first time, the app asks you to **set a password** (8 characters or more) and then offers a setup wizard for your accounts. Details, Windows and Linux: [Setup](docs/SETUP.md).

Try it with demo data, without touching your real data (two terminals):

```
scripts/financial-dashboard.sh demo
python3 scripts/load-demo-data.py --years 4
```

## Features

Each line links to a page with the details.

- **[Views](docs/features/views.md)**: Calendar, Transactions, Budgets, Spending Review, Stats, Accounts, Net worth, Notes and Reminders on one card; a shared date range.
- **[Accounts](docs/features/accounts.md)**: cash, bank, wallets, cards, loans you owe, foreign currencies, deactivating and resetting a balance.
- **[Transactions](docs/features/transactions.md)**: income, expense and transfer, search, tags and the balance left after each row.
- **[People and split expenses](docs/features/people-and-splits.md)**: share a bill (or have someone pay it for you), one balance per person, Settle, loans and write-offs.
- **[Funds and investments](docs/features/funds-and-investments.md)**: Emergency Fund, goal funds, the Future Expenses Ledger, Mutual Funds, Shares, Recurring Deposits, Jewellery and net worth history.
- **[Budgets, Spending Review and heatmaps](docs/features/budgets-and-review.md)**: monthly limits, the biggest expense and what changed, and the Calendar heatmaps.
- **[Notes and reminders](docs/features/notes-and-reminders.md)**: a private, encrypted diary, note folders, your own reminders and the Coming up bar.
- **[Look and feel](docs/features/look-and-feel.md)**: side menu, Light, Dark and Color themes, motion.
- **[Security and backups](docs/features/security-and-backups.md)**: one password, screen lock and automatic checked backups.
- **[Excel export and import](docs/features/excel-export-import.md)** and **[importing from Money Manager](docs/MONEY-MANAGER-IMPORT.md)**.

A plain-text overview of everything is in [`docs/FEATURES.txt`](docs/FEATURES.txt).

## More

| Page | What is in it |
|---|---|
| [Setup](docs/SETUP.md) | Installing Java and the optional tools, step by step, for Mac, Windows and Linux |
| [Operations](docs/OPERATIONS.md) | Starting, demo data, forgot the password, restoring a backup, logs, backups in detail, configuration |
| [Development](docs/DEVELOPMENT.md) | Project layout, REST API, tests, CI and releases, the native executable, known limits |
| [Specification](docs/SPEC.md) | Every rule, the data model and a dated list of every decision |

## Tests

```
./gradlew test                      # all tests, plus the JaCoCo report in build/reports/jacoco/test/html
node --test src/test/js/*.test.js   # only the JavaScript tests (Node 18 or newer, nothing to install)
```
