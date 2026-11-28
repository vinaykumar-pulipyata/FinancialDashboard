# Development: project layout, REST API, tests and limits

[← Back to the README](../README.md)

## Project layout

```
src/main/java/com/learning/spring/financialdashboard/
  controller/   thin REST controllers under /api
  service/      all the rules (accounts, transactions, Ledger, portfolios, jewellery, loans, net worth, budgets, notes, reminders, backups, export)
  repository/   Spring Data repositories
  model/        JPA entities
  config/       security (sign-in, the sign-in throttle, the allowed host, the cross-site header), request logging, the H2 shutdown option, scheduling
  startup/      start-up runners (category seeding, Ledger repair, backups)
  exception/    ValidationException (400) and NotFoundException (404), returned as {"error": "..."}
src/main/resources/static/
  Dashboard.html       the page shell
  panels/*.html        one fragment per view or popup, loaded into the shell
  css/ js/core js/panels   styles and scripts (ES modules; js/app.js imports the rest), one file per panel
src/main/resources/db/migration   the Flyway migrations that build and change the database
src/test/java            the Java tests (in-memory H2, never your real data)
src/test/js              the JavaScript tests (Node's built-in runner, nothing to install)
scripts/load-demo-data.py
docs/SPEC.md           the living specification and dated change log
docs/FEATURES.txt      a plain list of every feature, by heading
docs/features/*.md     the details of each feature (the README links to them)
docs/SETUP.md, docs/OPERATIONS.md, docs/DEVELOPMENT.md, docs/MONEY-MANAGER-IMPORT.md   setup, running and backups, this page, the Money Manager import
```

The front end has no framework and no build step. `js/app.js` loads each fragment into its slot and then wires every
panel. The scripts are ES modules: `Dashboard.html` loads `js/app.js` as the one module and each file imports what it uses.

## REST API

Everything is under `/api` and needs the sign-in once a password is set. Refusals (400) and missing things (404) come back as `{"error": "message"}`; an unexpected failure or malformed JSON gets Spring Boot's own error body.

| Area | Endpoints |
|---|---|
| Auth | `GET /auth/status`, `POST /auth/password` (first run only), `PUT /auth/password`, `POST /auth/verify` |
| Accounts | `/accounts` (list, get, create, update, delete, `/{id}/deactivate`, `/{id}/activate`), `POST /setup/accounts` |
| Transactions | `/transactions` (list, create, update, delete; `tags` is optional in the body: left out = worked out by the app, a list = exactly those; `POST /transactions/suggest-tags` with `{categoryId, note, description}` returns the tags a transaction would get and saves nothing) |
| Categories | `/categories` (list with `?kind=`, create, update, delete, `/{id}/deactivate`, `/{id}/activate`) |
| Credit cards, vehicles, fixed loans | `/credit-cards`, `/vehicles`, `/debts` (list; `PUT /debts/{accountId}` with `{emiAmount}`, left out = custom) |
| Emergency Fund | `GET`/`PUT /emergency-fund` |
| Ring-fence | `POST`/`DELETE /ring-fences/{accountId}` |
| Ledger | `/ledger` (get, `setup`, `buckets`, `fund`, `pay`, `interest`, `transfer`, `buckets/{id}/close`, `fundings/{transactionId}`) |
| Investments | `/portfolios/{MUTUAL_FUNDS or SHARES}` (get, `setup`, `holdings`, `invest`, `stats`, `holdings/{id}` and its `value`, `withdraw`, `dividend`) |
| Loans | `GET /loans`, `/borrowers` (create, update, delete) |
| Jewellery | `/jewellery` (get, `setup`, `items` (create, `PUT`/`DELETE` on `items/{id}`), `items/{id}/sell`) |
| Net worth | `GET /net-worth?months=` |
| Budgets | `GET /budgets` (all), `PUT /budgets` (`{categoryId, amount}`: sets or replaces the monthly limit of an expense category, main or sub, or of all spending when `categoryId` is left out), `DELETE /budgets/{id}` |
| Reminders | `GET /reminders` (all, oldest day first), create, update (`{date, title, repeat}`), `PUT /reminders/{id}/done` (`{done, doneOn?}`, a one-off reminder; `doneOn` is an optional past day), `PUT /reminders/{id}/occurrences/{yyyy-mm-dd}/done` (same body, one day of a repeating reminder; the list carries each reminder's `completions`), delete |
| Notes | `GET /notes` (`?date=`, `?tag=`, `?q=`, `?page=` from 0 and `?size=`; answers `{notes, total, page, size, pages}`), `GET /notes/days`, `GET /notes/tags`, create, update, delete (`tags` left out = worked out from the title and text, a list = exactly those); `POST /notes/suggest-tags` with `{title, body}` returns the tags a note would get and saves nothing |
| Backups | `GET /backups`, `POST /backups` (back up now), `PUT /backups/settings` |
| Export | `GET /export` (the Excel workbook) |

## Tests

```
./gradlew test                      # all tests, plus the JaCoCo report in build/reports/jacoco/test/html
./gradlew test --tests '*LedgerServiceTest'
node --test src/test/js/*.test.js   # only the JavaScript tests (Node 18 or newer, nothing to install)
```

There are about 451 Java tests (with an in-memory H2 database, never the real file) and 442 JavaScript tests (JavaScript counted on 2026-11-25; Java on 2026-10-29 with a manual JUnit run for Java; run `./gradlew test` for the exact Java count). The Java ones are: service tests for every rule, `*ValidationTest`
classes for the refusals, `ApiTest` driving every endpoint through Spring Security and the JSON layer without opening a
port, backup and restore tests (including restoring a real backup into a fresh database and comparing every table),
export tests that open the generated workbook, and `DemoDataTest`, which runs the demo-data script itself (four years, about
5,600 API calls) against the application and checks the result (including 2 or more transactions on every day), the speed of net worth, the Excel export and a backup
on that much data. It needs `python3` and is skipped without it. Measured coverage is about 99% of lines and 93% of branches.

The JavaScript tests (`src/test/js`, run by `./gradlew test` as well) load the dashboard's own scripts into a stand-in
page and check the period maths, the Stats buttons, the net worth graph and its zoom, the calendar, notes and tags,
the figures, the view switching and `api()`, and that the page is wired together: the one module that loads the rest, every import matching an export, every id defined, no markup built from data.

## Continuous integration and releases

Two GitHub Actions workflows in `.github/workflows/`:

| Workflow | When | What it does |
|---|---|---|
| `ci.yml` (CI) | every push and pull request | one Linux job: sets up Java 17, Node 24 and Python 3, runs `./gradlew build` (Java tests, then the JavaScript tests, then the jar), keeps the test reports and the jar for 14 days. A newer push to the same branch cancels the older run. |
| `release.yml` (Release) | a pushed tag starting with `v` | first the same tests in one job (`verify`); then four jobs in parallel build the native executable with GraalVM 25 and run the smoke test on it: `ubuntu-latest` (Linux x64), `ubuntu-24.04-arm` (Linux arm64), `macos-15` (Apple silicon; no Intel Mac) and `windows-latest`. Each attaches `financial-dashboard-<system>` and a `.sha256` file to a GitHub Release (generated release notes; a tag with a hyphen such as `v0.1.0-rc1` is a pre-release). One system failing does not stop the others. |

To release: set `version` in **`gradle.properties`** (the one place for the version; Gradle uses it as the project version and in the jar name), commit and push, then `git tag v0.1.0 && git push origin v0.1.0`. The workflow's first job refuses a tag whose number differs from the file (`v0.1.0-rc3` is for version `0.1.0`). Try a pre-release tag (`v0.1.0-rc1`) first.

**macOS needs more memory than GraalVM takes by default.** GitHub's Apple-silicon runner has only 7 GB and 3 cores, and with GraalVM's default limit (5.7 GB) the analysis phase thrashes and is aborted after about 35 minutes (every try, with and without trimming the recorded metadata). The macOS entry of the release matrix therefore passes `-J-Xmx6500m -H:DeadlockWatchdogInterval=0` (through `-PnativeExtraArgs`): with that the build and the smoke test pass, in about 50 minutes (hence the 100-minute job limit). If it ever fails again, `scripts/upload-macos-release.sh v0.1.0` builds the Mac file on an Apple-silicon Mac and attaches it to the same release.

## The native executable (GraalVM)

The release files are GraalVM native images of the Spring Boot app (about 190 MB, start in about 4 seconds, no Java needed). A native image can only be built on the system it is for, which is why the release workflow has a job per system.

- **Build it yourself:** it needs GraalVM **25** (Spring Boot 4 writes its native metadata in the GraalVM 25 format; GraalVM 24 stops with "Missing attribute(s) [glob]"). With `JAVA_HOME` on that GraalVM: `./gradlew nativeCompile -PjavaVersion=25`. The result is `build/native/nativeCompile/FinancialDashboard`. It takes about 5 minutes and about 4 GB of memory. The normal build is unchanged and still uses Java 17 (`javaVersion` defaults to 17).
- **Test it:** `scripts/smoke-test.sh build/native/nativeCompile/FinancialDashboard` starts it from a temporary folder on port 8099 (own database, your data is not touched), runs about 30 calls (pages, password, accounts, transactions, the encrypted diary, backups, Excel export and import) and stops it. `scripts/native-experiment.sh` builds and tests in one go (`--rebuild` builds first) and finds a GraalVM 25 in `~/Library/Java/JavaVirtualMachines` or `GRAALVM_HOME`. Run both in an ordinary terminal: Gradle and the app open local sockets.
- **Why it needs more than Spring's own help:** a native image drops everything the program only reaches by name at run time. Three things make this app work:
  - `config/NativeImageHints.java` (imported with `@ImportRuntimeHints`, used only when the image is built) registers Hibernate's `*_$logger` classes, which JBoss Logging finds by name.
  - `src/main/resources/META-INF/native-image/com.learning.spring/FinancialDashboard-agent/reachability-metadata.json` is a **recording**: `scripts/native-experiment.sh --agent` runs the normal jar on GraalVM with the tracing agent, makes the smoke-test calls, and saves every class and resource used by name (Hibernate helper classes, Apache POI, security and so on). **If a new feature fails only in the native executable with a "MissingReflectionRegistrationError" or a missing resource, add a call that uses it to `scripts/smoke-test.sh` and record again.** The script takes the ByteBuddy byte-code provider out of the recording again (Hibernate 7.4 picks its provider only through the Java service lookup, and the native image must not see ByteBuddy).
  - `build.gradle` has `-H:+AddAllCharsets` for the native build (Apache POI asks for Windows-1252).
- **Not covered by the smoke test:** the screens in a browser, and `--restore=FILE` and `--reset-password` on the native executable. The Java tests run on the normal JVM, not on the native image.

## Limits and choices

Known rough edges in the back end (some refusal messages with names reaching the log, a percent-encoded `/api` path skipping the header check, an unescaped `%` in note search, a Ledger bill date drifting after a month end, and similar) are written down in `docs/SPEC.md` section 8b.

Small known rough edges in the screens (a double Enter in a form, a tiny slice in the Stats ring, dialogs without labels for screen readers and similar; none loses data) are written down in `docs/SPEC.md` section 8a.

- Anything that changes data (POST, PUT, PATCH, DELETE under `/api/`) must carry the header
  `X-Requested-With: FinancialDashboard`. The dashboard and the demo script send it; a web page on another site cannot,
  which stops it from using your browser's remembered sign-in against this app. Reads need nothing. A client of your own
  must send it too.
- Tags (on transactions and notes) are worked out on your computer with simple rules and your own history, not by an AI service. They will sometimes pick a word you would not; remove it or press Suggest. Transactions that existed before tags were added were tagged once; old notes were left alone.
- One user, totals in INR (accounts can be held in USD, EUR, GBP or VND at rates you enter), no live prices (values of investments, gold and property are entered by hand).
- The net worth history is worked out from balances and transactions; changes in the value of investments, gold and
  property between months are not reflected until you record them.
- The transactions list and the Excel export load everything at once, which is fine for tens of thousands of
  transactions and slows down toward hundreds of thousands.
- Notes are plain text, up to 10,000 characters each: no formatting, pictures or attachments. A note can have up to 10
  tags of up to 30 characters; renaming a tag everywhere is done by editing the notes.
- Importing from Excel works only into a new, empty database (menu > Data > Import from Excel; see [Excel export and import](features/excel-export-import.md)). For a database that is in use, the backups are what you restore from.

The full specification, every rule and a dated list of every decision is in [`SPEC.md`](SPEC.md).
