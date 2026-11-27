#!/bin/bash
# One command to run the dashboard: starts the Caddy proxy (if Caddy is installed) so http://finance.localhost/ works with no
# port number, then runs the app in this window. Ctrl+C stops both.
#     scripts/financial-dashboard.sh                         your real data (./data/financialdb, backups in ./backups)
#     scripts/financial-dashboard.sh demo                    a separate demo database (./data/demo, backups in ./backups-demo)
#     scripts/financial-dashboard.sh moneymanager            a separate database for the Money Manager import (./data/moneymanager, backups in ./backups-moneymanager)
#     scripts/financial-dashboard.sh --reset-password        any other arguments go to the app (also after "demo" or "moneymanager")
# The demo database starts empty; fill it with `python3 scripts/load-demo-data.py` while the app is running. Its backups go to
# their own folder so they never mix with, or push out, the real ones.
# Run it from anywhere: it moves to the project folder first, because the database (./data) and backups (./backups) are
# relative to the project root.
cd "$(dirname "$0")/.." || exit 1

# Your data, backups and logs are for you alone: files and folders the app creates are readable by this user only.
umask 077
for folder in data backups backups-demo backups-moneymanager logs; do
    [ -d "$folder" ] && chmod -R go-rwx "$folder"
done

args=()
if [ "$1" = "demo" ]; then
    shift
    args=(--spring.datasource.url=jdbc:h2:file:./data/demo --app.backup.dir=./backups-demo)
    echo "DEMO database (./data/demo). Your real data is not touched."
elif [ "$1" = "moneymanager" ]; then
    shift
    args=(--spring.datasource.url=jdbc:h2:file:./data/moneymanager --app.backup.dir=./backups-moneymanager)
    echo "MONEY MANAGER import database (./data/moneymanager). Your real data is not touched."
else
    echo "REAL database (./data/financialdb)."
fi
args+=("$@")

proxy=""
if command -v caddy >/dev/null 2>&1; then
    # logs/caddy/<year-month>/<date>.log, by the day the proxy was started (like the app's logs/general/...)
    caddy_log="logs/caddy/$(date +%Y-%m)/$(date +%Y-%m-%d).log"
    mkdir -p "$(dirname "$caddy_log")"
    caddy run --config ./Caddyfile >>"$caddy_log" 2>&1 &
    proxy=$!
    sleep 1
    if kill -0 "$proxy" 2>/dev/null; then
        echo "Proxy running: open http://finance.localhost/"
    else
        proxy=""
        echo "The proxy did not start (see $caddy_log). Use http://finance.localhost:8080/ instead."
    fi
else
    echo "Caddy is not installed, so there is no proxy. Use http://finance.localhost:8080/ (brew install caddy to drop the port)."
fi

stop_proxy() { [ -n "$proxy" ] && kill "$proxy" 2>/dev/null; }
trap stop_proxy EXIT

if [ ${#args[@]} -gt 0 ]; then
    # each argument in its own quotes, so one with a space in it (--restore=/path with space/backup.zip) stays one argument
    quoted=""
    for a in "${args[@]}"; do
        quoted+="\"$a\" "
    done
    ./gradlew bootRun --args="$quoted"
else
    ./gradlew bootRun
fi
