# Operations: running, demo data, passwords, backups, logs and configuration

[← Back to the README](../README.md)

## Getting started

You need a JDK 17 (the checker `scripts/check-setup.sh` and the steps above tell you if it is missing). Start the app from the project folder with one command:

```
scripts/financial-dashboard.sh
```

That is the way to start it. The script moves to the project folder (the database `./data/financialdb` and the backups
`./backups` are relative to it), starts the optional proxy if Caddy is installed (see below), and runs the app in the same
window. Ctrl+C stops both. Run `chmod +x scripts/financial-dashboard.sh` once if it says permission denied.

| Command | What it does |
|---|---|
| `scripts/financial-dashboard.sh` | your real data |
| `scripts/financial-dashboard.sh demo` | a separate demo database (`./data/demo`, backups in `./backups-demo`) that starts empty |
| `scripts/financial-dashboard.sh --reset-password` | starts once after removing the saved password (see below) |
| `scripts/financial-dashboard.sh --restore=FILE` | starts once after putting a backup back (see below) |

The script says whether it is on the REAL or the DEMO database. Other arguments go to the app as they are, also after `demo`.
(`./gradlew bootRun` still works on its own, without the proxy, if you ever need it.)

The app logs `http://finance.localhost:8080/` when it is ready (and mentions `http://finance.localhost/` for when the proxy below is running). Names ending in `.localhost` always point at this computer, so there is nothing to set up; if your browser does not open it (some older Safari versions), use `http://localhost:8080/`. The two addresses are separate to the browser, so the theme choice and the screen-lock state are kept per address. The first time you open it:

1. It asks you to **set a password** (at least 8 characters). The browser then asks you to sign in as `admin`.
2. A **setup wizard** helps you add your accounts and opening balances. You can skip it and add accounts later.

To start again from nothing, stop the app and delete the `data/` folder.

### Without the port number (optional)

`http://finance.localhost:8080/` needs the port because a browser only assumes port 80 for a bare address. To type just
`http://finance.localhost/`, install Caddy once (`brew install caddy`). After that `scripts/financial-dashboard.sh` starts the small
proxy configured in the `Caddyfile` in the project folder in the background (its log is
`logs/caddy/<year-month>/<date>.log`) and stops it when the app stops. Without Caddy, or if the proxy cannot start, the
script says so and runs the app alone. To run the proxy by hand instead: `caddy run --config ./Caddyfile`.

It only forwards `finance.localhost` to the app on `127.0.0.1:8080`. macOS lets an ordinary program use port 80 only when it listens on all addresses, so Caddy does, and the file makes it answer 403 to any request that does not come from this computer. Nothing is installed as a
service and no file outside the project folder is changed (`admin off` and `auto_https off` in the file keep Caddy from
saving anything elsewhere). If port 80 is refused ("permission denied"), keep using the address with `:8080`. If you
already run Caddy for other local apps, add the `finance.localhost` block to your own file instead, because only one
program can listen on port 80.

### Try it with demo data

```
scripts/financial-dashboard.sh demo
python3 scripts/load-demo-data.py --years 4  # in a second terminal; four years (five is the default)
python3 scripts/load-demo-data.py --years 3
```

The script loads **five years** of history by default (`--years N`, 1 to 10), with **2 to 3 transactions on every
single day** (groceries, tea, cabs, fuel, food delivery, subscriptions, medicines, shopping, from cash, three wallets, the card and the
bank, and a small income now and then) on top of the monthly ones: salary with yearly raises, freelance income, a yearly
bonus, rent, bills, a car loan, monthly investing, the card bill, a cash top-up, wallet top-ups and a little cashback, with **sub categories** for nearly every finer name (Food › Groceries, Dining out, Delivery; Transport › Fuel, Cab, Public transport; Shopping › Clothes, Electronics, Household; Entertainment › Movies, Subscriptions, Travel; Health › Doctor, Medicines, Insurance premium; Bills › Electricity, Internet, Mobile; Housing › Rent; Salary › Base pay, Bonus; Business › Freelance, Sales; Investment Income › Interest), plus a few deliberate stand-outs for the Spending Review view (a dining-out surge and a ₹52,000 laptop this month, a ₹38,000 dental bill and an early ₹14,000 health check-up last month, so Health falls this month, and six monthly budgets (two on sub categories of Food) whose earlier months are shaped too: Entertainment close then over, Transport over, Shopping close). That is about 5,500 transactions over
five years. It also creates twelve accounts (the PhonePe, Paytm and Amazon Pay wallets, two vehicles and an apartment among them), the Emergency Fund and two goal
funds, loans to two people, a Ledger, **one or two tagged diary notes on every day**, **reminders** (two to four done
at random every month, two late, some coming up and ten more at random dates ahead) and twelve **special reminders
that repeat** (birthdays, an anniversary and an insurance renewal every year; the rent, an SIP check, the EMI and the card
bill every month; a spending review and a grocery list every week), and mutual funds and shares with a monthly value
history and half-yearly dividends, and **jewellery** (thirteen pieces of gold, silver, platinum and diamond, one bought from the bank and part of the loose gold sold). It makes roughly 8,500 API calls over five years (7,000 over four), so allow a minute or two. It asks for your dashboard
password (or reads `FINANCE_PASSWORD`); press Enter if you have not set one yet, and it sends no login at all. Use the
demo database as shown so your real data is never touched, and start from an empty one (a second run on a database
that already has data skips what is already there). The Ledger writes its entries on the day they are made, so its own
history starts today.

### Forgot the password?

Start the app once with `--reset-password`. This removes only the saved password; your data is untouched. The next time
you open the app it asks for a new one.

```
scripts/financial-dashboard.sh --reset-password
```

### Restoring a backup

Close the app and start it once with the backup file named:

```
scripts/financial-dashboard.sh --restore=./backups/backup-2026-11-01_090000.sql.zip
```

The backup is first loaded into a new database file next to the real one. Only if that works is your current database
moved aside (as `financialdb.mv.db.before-restore-<time>`, never deleted) and the new one put in its place. A missing or
damaged backup stops the start and changes nothing.

## Installing with one command

`scripts/install.sh` (macOS and Linux) and `scripts/install.ps1` (Windows) download the executable that matches the system, check its SHA-256 checksum against the one published with the release, and set up a `financial-dashboard` command. They never touch your data.

```
curl -fsSL https://raw.githubusercontent.com/vinaykumar-pulipyata/FinancialDashboard/main/scripts/install.sh | sh
```

Piping a script into `sh` runs whatever it says, so read it first if you like: `curl -fsSL <the same address> -o install.sh`, look at it, then `sh install.sh`.

- **What it does:** works out the system (Linux x64 or arm64, macOS on Apple silicon; a shell running under Rosetta counts as Apple silicon; an Intel Mac is refused with a pointer to the source version), downloads `financial-dashboard-<system>` and its `.sha256` from the **latest release** (a pre-release is only installed when you name it), refuses to install if the checksum differs, and puts the files in `~/.financial-dashboard/`:

  | Path | What |
  |---|---|
  | `~/.financial-dashboard/financial-dashboard-app` | the executable |
  | `~/.financial-dashboard/financial-dashboard` | the launcher: goes to that folder, opens the browser when the app answers, starts the app (extra options pass through) |
  | `~/.financial-dashboard/data`, `backups`, `logs` | your data, made by the app; never replaced or deleted |
  | `~/.local/bin/financial-dashboard` | a link to the launcher (if `~/.local/bin` is not on your PATH it says so and prints the line to add) |

  An earlier Java-based install in the same folder (`app.jar`) is left alone; its old launcher is saved as `financial-dashboard.previous`, and the data is shared. Running the script again is an upgrade.
- **Options** (after `sh -s --`): `--version v0.1.0-rc1` installs that release, `--dir DIR` uses another folder, `--dry-run` only says what it would do, `--uninstall` removes the program and the command (the data stays; `--purge` deletes it too, after asking: see [Uninstalling](#uninstalling)). The same through the environment: `FINANCIAL_DASHBOARD_VERSION`, `FINANCIAL_DASHBOARD_HOME`, `FINANCIAL_DASHBOARD_BIN_DIR`. Example: `curl -fsSL <address> | sh -s -- --version v0.1.0-rc1`.
- **Windows:** `irm https://raw.githubusercontent.com/vinaykumar-pulipyata/FinancialDashboard/main/scripts/install.ps1 | iex` installs into `%LOCALAPPDATA%\FinancialDashboard` (the executable, a `financial-dashboard.cmd` launcher and, later, `data\`) and adds that folder to your user PATH; open a new window and run `financial-dashboard`. Options are environment variables (`FINANCIAL_DASHBOARD_VERSION`, `FINANCIAL_DASHBOARD_HOME`, `FINANCIAL_DASHBOARD_DRY_RUN=1`, `FINANCIAL_DASHBOARD_UNINSTALL=1`). The PowerShell script has not been run on Windows yet.
- **Tested:** `scripts/test-install.sh` (run by CI) installs a fake release into a temporary home: the install, an upgrade over an older install with data in it, a wrong checksum and the uninstall. The installer has not been used against a real release yet.

## Uninstalling

macOS and Linux:

```
curl -fsSL https://raw.githubusercontent.com/vinaykumar-pulipyata/FinancialDashboard/main/scripts/install.sh | sh -s -- --uninstall
```

| Command | What it removes |
|---|---|
| `... \| sh -s -- --uninstall` | the program, the launcher and the `~/.local/bin/financial-dashboard` link. **Your data stays** in `~/.financial-dashboard` (`data/`, `backups/`, `logs/`), so installing again picks up where you left off. |
| `... \| sh -s -- --uninstall --purge` | the same, **and your data** (`data/`, `backups/`, `logs/`, and the saved `financial-dashboard.previous` launcher). It lists what it will delete with the sizes and asks you to type `delete`; anything else keeps the data. If there is no terminal to ask on, it refuses and deletes nothing. |
| `... \| sh -s -- --uninstall --purge --yes` | the same without asking, for scripts. There is no way back. |

`--dry-run` with any of them only lists what would be removed. A folder left over from an older Java-based install (`app.jar`) is not touched; the script says what is left.

**Windows** (PowerShell; set the variables, then run the installer again):

```
$env:FINANCIAL_DASHBOARD_UNINSTALL = "1"
irm https://raw.githubusercontent.com/vinaykumar-pulipyata/FinancialDashboard/main/scripts/install.ps1 | iex
```

This removes the program and the `financial-dashboard` command and takes the folder off your user PATH; your data stays. Add `$env:FINANCIAL_DASHBOARD_PURGE = "1"` to delete the data as well (it asks you to type `delete`; `FINANCIAL_DASHBOARD_YES = "1"` skips the question). The Windows script has not been run on Windows yet.

Without the scripts: delete the folder you started the program in (it holds the program and, next to it, `data/`, `backups/` and `logs/`), and on Windows take that folder off your PATH. If you made a **backup copy** somewhere else (the second backup folder in the dashboard), the uninstall does not touch it.

## Running the downloaded executable

A release ([Releases page](https://github.com/vinaykumar-pulipyata/FinancialDashboard/releases)) has one file per system, built with GraalVM, so it needs no Java:

| File | System |
|---|---|
| `financial-dashboard-linux-x64` | Linux, Intel or AMD |
| `financial-dashboard-linux-arm64` | Linux on ARM (a Raspberry Pi 4 or 5, ARM servers) |
| `financial-dashboard-macos-arm64` | Mac with Apple silicon (M1 or newer; there is no Intel Mac build) |
| `financial-dashboard-windows-x64.exe` | Windows, 64-bit |

Each file has a `.sha256` file beside it. To check a download: `shasum -a 256 -c financial-dashboard-macos-arm64.sha256` (macOS and Linux: `sha256sum -c`), or on Windows compare `certutil -hashfile financial-dashboard-windows-x64.exe SHA256` with the file's text.

1. Make a folder for it (for example `~/financial-dashboard`) and put the file there. **Start it from that folder**: like the normal version, it keeps `data/` (the database), `backups/` and `logs/` in the folder you start it from, so starting it from somewhere else begins a new, empty database.
2. Allow it to run. The files are not signed, so each system asks once:
   - **macOS:** `chmod +x financial-dashboard-macos-arm64` then `xattr -d com.apple.quarantine financial-dashboard-macos-arm64` (this removes the "downloaded from the internet" mark that makes macOS refuse an unsigned program). Or open it once with right-click, Open.
   - **Linux:** `chmod +x financial-dashboard-linux-x64` (or `-arm64`).
   - **Windows:** double-click it, and if SmartScreen says "Windows protected your PC", choose More info, then Run anyway. Or run it from a Command Prompt in the folder.
3. Run it: `./financial-dashboard-macos-arm64` (Linux and macOS) or `financial-dashboard-windows-x64.exe` (Windows). It starts in about 4 seconds; open `http://localhost:8080/` and set a password on the first run.

The same flags as the normal version work after the file name: `--reset-password`, `--restore=FILE`, `--server.port=9090` and the other [configuration](#configuration) options (as `--property=value`). The optional Caddy proxy and the `scripts/financial-dashboard.sh` launcher are for the source version; the executable needs neither. Data made by one version of the app (the source version or an executable) can be used by the other: it is the same database file, and a newer executable updates an older database by itself when it starts. Stop it with Ctrl+C in its window.

## Logs

The app writes a log to the console and to files sorted by month and day:

```
logs/
  general/            the app's own log
    2026-10/
      2026-10-22.0.log    a new file each day; .1, .2 ... when one passes 10 MB
  caddy/              the optional proxy (scripts/financial-dashboard.sh)
    2026-10/
      2026-10-22.log
```

The folders are created when the app (or `scripts/financial-dashboard.sh`) starts, so you can delete any of them, or all of `logs/`, while it is stopped. The app keeps 30 days and 300 MB of its own logs. The Caddy log is by the day the proxy started and is not trimmed. Each line starts with the date, level, thread and class; the messages look like this:

```
INFO  Account created: id=7 type=BANK level=1
INFO  Transaction recorded: id=412 kind=EXPENSE date=2026-11-03
INFO  POST /api/transactions -> 201 (14 ms)
WARN  Sign-in refused for user 'admin' from 127.0.0.1: BadCredentialsException
INFO  POST /api/accounts refused: Name is required
INFO  Backup written: ./backups/backup-2026-11-03_091500.sql.zip (app started, 5421 rows)
```

Changes are logged by id, kind and date, API calls by method, path and status, and refused sign-ins as warnings. Amounts,
note text, search text and passwords are never logged (tests check this). Set
`logging.level.com.learning.spring.financialdashboard=DEBUG` to also see every read request.

**The page logs too.** What happens in the browser after the server has answered (a script error, a drop-down that did not
update) cannot be seen from the server's lines, so the page sends its own errors and a few steps to the same file, as
`Browser <event> <details>` lines under the logger name `client`:

```
INFO  Browser page.start Mozilla/5.0 (Macintosh; ...) Chrome/...
ERROR Browser script.error TypeError: Cannot read properties of null (reading 'filter') [fill@js/panels/transaction-form.js:117:24 | ...]
ERROR Browser refresh.failed TypeError: Cannot read properties of undefined (reading 'map') [at loadAllData (js/app.js:56:20)]
WARN  Browser categories.action.failed Error [at catAction (js/panels/categories.js:21:15)]
ERROR Browser start.step-failed initNotes TypeError: Cannot read properties of null (reading 'addEventListener') [...]
ERROR Browser boot.script-failed js/panels/notes.js
```

Start-up is logged step by step, which tells a blank page from a slow one: `start.panels-loaded` and `start.ready` on the way, and
`start.step-failed <initX>` (which panel's set-up threw), `start.failed` or `panel.load-failed <file> status=N` when it stops.
A page whose files cannot be loaded at all (a missing file, a broken import) cannot log from inside them, so a small plain
script, `js/core/boot-log.js`, reports `boot.script-failed <file>`, `boot.error <message> [file:line:col]` and `boot.stalled`
(the app had not started after 10 seconds).

Errors nothing caught, a refresh that failed (`refresh.failed`) and a failed Edit categories action are logged at once; set `logging.level.client=DEBUG` to also see each time the Income / Expense form opens (with
how many categories and accounts it listed). Only codes, ids, counts and function names are sent: never amounts, notes,
category names or search text, and the message of an error that came from the server is left out. The server keeps one
line of at most 500 characters per call and drops anything past 120 a minute.

## Backups in detail

| | |
|---|---|
| **What** | A zipped file of SQL commands (`backup-YYYY-MM-DD_HHMMSS.sql.zip`) that rebuilds every table and row. It is text, so it still restores after a future H2 upgrade. |
| **When** | At start-up, at close, and every hour that passes without a good backup in the last 24 hours. **Backups > Back up now** does one at once. |
| **Checked** | Written under a temporary name, loaded into a temporary database, and every table's row count and content fingerprint compared with the live data in the same snapshot. Only a matching copy is kept. |
| **Kept** | The newest of each of the last 30 days, and the first of every month for ever. |
| **Second folder** | Set in the Backups popup. Checked for write access, and each copy is size-checked. |
| **Excel report** | The first backup of each month also saves `report-YYYY-MM.xlsx`. |
| **Not covered** | Entries made after the last backup (at most about a day). Backups are not encrypted and contain the password hash, so keep the folders private. |

## Configuration

`src/main/resources/application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `spring.datasource.url` | `jdbc:h2:file:./data/financialdb` | Where the data lives |
| `spring.flyway.baseline-on-migrate` | `true` | A database made before Flyway is marked as version 1 and left alone |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate only checks the tables; they are built and changed by Flyway (`src/main/resources/db/migration`). A change to the data model needs a new `V<n>__*.sql` file |
| `server.address` | `127.0.0.1` | Only this computer can reach the app |
| `server.port` | `8080` | |
| `app.host` | `finance.localhost` | The name in the start-up message only; the app answers to any name that reaches it |
| `app.backup.dir` | `./backups` | Where backups are written |
| `app.backup.automatic` | `true` | Start-up, close and daily backups (the tests turn it off) |
| `spring.h2.console.enabled` | `false` | |
| `app.log.dir` | `./logs/general` | Where the app's log goes, as `<year-month>/<date>.<n>.log` (new file each day or at 10 MB); also `app.log.max-file-size`, `app.log.max-history`, `app.log.total-size-cap` |
| `logging.level.com.learning.spring.financialdashboard` | `INFO` | `DEBUG` also logs every read request |
