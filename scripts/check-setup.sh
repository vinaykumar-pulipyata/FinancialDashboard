#!/bin/bash
# Checks which tools the dashboard needs are installed and prints the install command for any that are missing.
# Nothing is installed or changed. Run it from anywhere:  scripts/check-setup.sh
cd "$(dirname "$0")/.." || exit 1

missing_required=0
ok()   { printf '  [ok]       %-8s %s\n' "$1" "$2"; }
miss() { printf '  [MISSING]  %-8s %s\n' "$1" "$2"; }

echo "Needed to run the app:"
if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    ok "Java 17" "$(/usr/libexec/java_home -v 17)"
elif command -v java >/dev/null 2>&1; then
    miss "Java 17" "no JDK 17 found (the build needs one). Found: $(java -version 2>&1 | head -1). Install:  brew install --cask temurin@17"
    missing_required=1
else
    miss "Java 17" "not installed.  Install:  brew install --cask temurin@17"
    missing_required=1
fi
if [ -x ./gradlew ]; then
    ok "Gradle" "the project's own wrapper (./gradlew) downloads it on first run, internet needed once"
else
    miss "Gradle" "./gradlew is missing or not executable.  Fix:  chmod +x gradlew"
    missing_required=1
fi

echo "Optional:"
command -v node    >/dev/null 2>&1 && ok "Node.js" "$(node -v)  (JavaScript tests)"        || miss "Node.js" "only for the JavaScript tests.  Install:  brew install node"
command -v python3 >/dev/null 2>&1 && ok "Python 3" "$(python3 -V 2>&1)  (demo data)"       || miss "Python 3" "only for the demo data.  Install:  brew install python"
command -v caddy   >/dev/null 2>&1 && ok "Caddy" "$(caddy version 2>&1 | head -1)  (finance.localhost without a port)" || miss "Caddy" "only for http://finance.localhost/ without :8080.  Install:  brew install caddy"

echo
if [ "$missing_required" = 1 ]; then
    echo "Install what is marked MISSING under \"Needed to run the app\", then run this again."
    exit 1
fi
echo "Ready. Start the app with:  scripts/financial-dashboard.sh"
