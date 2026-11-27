# Setup

[← Back to the README](../README.md)

## Technology and what you need installed

Built with Spring Boot 4.1.1 (Spring Framework 7, Java 17), JPA on a file-based H2 database with Flyway migrations, Apache POI
for the Excel export, and a plain HTML/CSS/JavaScript front end with no build step and no npm packages. Every library is
downloaded by Gradle the first time, so **the only thing you install yourself is Java 17.** Node, Python and Caddy are optional extras.

### The short way (Mac)

1. Open the **Terminal** app and go to the project folder (`cd` followed by the folder, or drag the folder onto the Terminal window).
2. Run the checker. It installs nothing; it only tells you what is missing and the exact command for it:

   ```
   scripts/check-setup.sh
   ```

   (If it says "permission denied", run `chmod +x scripts/*.sh` once and try again.)
3. For anything marked **MISSING** under "Needed to run the app", follow its step-by-step below, then run the checker again until it
   ends with **Ready**.
4. Start the app: `scripts/financial-dashboard.sh`, then open `http://localhost:8080/`.

### Step by step, one tool at a time

**Java 17 (required)**

1. Check: `/usr/libexec/java_home -v 17`. If it prints a folder path, you have it, so skip to the next tool. (`java -version`
   alone is not enough: the build wants a JDK 17 even if a newer Java is also installed.)
2. Install, either way:
   - With Homebrew (a package manager; get it from https://brew.sh if `brew -v` says "command not found"):
     `brew install --cask temurin@17`
   - Or without Homebrew: download the **JDK 17** installer for your Mac from https://adoptium.net/temurin/releases/?version=17
     (choose macOS, and aarch64 for an Apple-silicon Mac or x64 for an Intel one), open the downloaded file and click through it.
3. Check again with the command in step 1. It should now print a path. If your terminal was open during the install, open a new
   window first.

**Gradle (required, but nothing to install)**

1. The project folder already contains Gradle's launcher (`gradlew`). The first time you start the app it downloads the right
   Gradle by itself, so you need the internet for that one time, and it takes a minute.
2. Check: `./gradlew --version` should print "Gradle 9.7.1". If it says "permission denied", run `chmod +x gradlew`.

**A web browser (required)**

Any current Chrome, Safari, Firefox or Edge. Nothing to do.

**Node.js (optional: only to run the JavaScript tests)**

1. Check: `node -v`. Version 18 or higher is fine.
2. Install: `brew install node` (or the installer from https://nodejs.org).
3. Use it: `node --test src/test/js/*.test.js`.

**Python 3 (optional: only for the demo data)**

1. Check: `python3 -V`. A Mac usually has it, or offers to install the developer tools the first time you run it (say yes).
2. Install if missing: `brew install python` (or the installer from https://www.python.org/downloads/).
3. Use it: see "Try it with demo data" below. The script needs no packages.

**Caddy (optional: only for `http://finance.localhost/` without `:8080`)**

1. Check: `caddy version`.
2. Install: `brew install caddy`. That is all: `scripts/financial-dashboard.sh` finds it and starts it for you. Without Caddy the app
   works the same at `http://localhost:8080/`.

### On Windows

I wrote and tried the project on a Mac and have not run it on Windows, so treat these steps as a guide and tell me if one fails.
The `.sh` scripts are for macOS and Linux; on Windows you start the app straight from Gradle instead. Use **PowerShell** (search
"PowerShell" in the Start menu) and go to the project folder first: `cd C:\path\to\FinancialDashboard`.

1. **Java 17 (required).** Check: `java -version`. It should say version 17 (a newer Java alone is not enough, because the build asks for a JDK 17).
   Install with the built-in Windows package manager: `winget install EclipseAdoptium.Temurin.17.JDK`, or download the Windows
   `.msi` installer from https://adoptium.net/temurin/releases/?version=17 and click through it (tick "Set JAVA_HOME" and
   "Add to PATH" if it offers them). Then close PowerShell, open a new one and check again.
2. **Gradle (required, nothing to install).** Check: `.\gradlew.bat --version` should print "Gradle 9.7.1" (the first run downloads it, so it needs the internet once).
3. **Start the app.** Run it from the project folder, because the database `.\data` and the backups `.\backups` are relative to it:

   ```
   .\gradlew.bat bootRun
   ```

   Then open `http://localhost:8080/`. Press Ctrl+C to stop. Your data stays in `.\data\financialdb`.
4. **The demo database** (instead of your real data): `.\gradlew.bat bootRun --args="--spring.datasource.url=jdbc:h2:file:./data/demo --app.backup.dir=./backups-demo"`.
   Other start-up options go in `--args` the same way, for example `--args="--reset-password"`.
5. **Demo data (optional, needs Python).** Check: `python --version` (or `py -3 --version`). Install: `winget install Python.Python.3.12`
   (tick "Add python.exe to PATH" if you use the installer from https://www.python.org/downloads/). With the app running on an
   empty database, open a second PowerShell and run `python scripts\load-demo-data.py`.
6. **JavaScript tests (optional, needs Node).** Check: `node -v` (18 or higher). Install: `winget install OpenJS.NodeJS.LTS`.
   Run: `node --test src/test/js/*.test.js`.
7. **Caddy (optional).** `winget install CaddyServer.Caddy`, then in a second PowerShell, from the project folder:
   `caddy run --config .\Caddyfile`. Without it the app works the same at `http://localhost:8080/`.

If `winget` is not found, install "App Installer" from the Microsoft Store, or use the download links above.

### On Linux

Install a JDK 17 with your package manager (for example `sudo apt install openjdk-17-jdk` on Debian or Ubuntu), then use the
same `scripts/financial-dashboard.sh` as on a Mac. `scripts/check-setup.sh` uses a Mac-only command to find Java, so on Linux
check with `java -version` and `./gradlew --version` instead.
