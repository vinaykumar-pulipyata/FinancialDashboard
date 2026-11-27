#!/usr/bin/env bash
# Smoke test of the running application: starts the given command, waits for the dashboard page, runs about thirty calls against it
# (pages, password, accounts, transactions, the encrypted diary, backups, Excel export and import) and stops it again.
#   scripts/smoke-test.sh COMMAND [ARGS...]        e.g. scripts/smoke-test.sh build/native/nativeCompile/FinancialDashboard
# "--server.port=PORT" (PORT from the environment, default 8099) is added to the command. The command runs from a temporary folder with
# its own database, so your real data is never touched. Exit status: the number of failed checks (0 = all passed), 1 if it did not start.
# Needs bash, curl and Python 3; it works in Git Bash on Windows, on macOS (bash 3.2) and on Linux.
set -u
[ $# -ge 1 ] || { echo "usage: $0 COMMAND [ARGS...]"; exit 2; }
PORT="${PORT:-8099}"
URL="http://127.0.0.1:$PORT"
PASS_WORD="smoke-test-pw-1"
PY="$(command -v python3 || command -v python)"
[ -n "$PY" ] || { echo "Python 3 is needed for the smoke test"; exit 2; }

# ---------------------------------------------------------------------------------------------------- the checks
fails=0
failed() {   # shows what the server answered to the call that just failed
    echo "FAIL  $1 ($2)"; fails=$((fails+1))
    [ -s last.body ] && echo "      reply: $(head -c 300 last.body | tr '\n' ' ')"
}
check() {   # name, expected, actual
    if [ "$2" = "$3" ]; then echo "PASS  $1"; else failed "$1" "expected $2, got $3"; fi
}
checkok() {   # name, actual: any success code (200, 201 or 204) will do
    case "$2" in 200|201|204) echo "PASS  $1" ;; *) failed "$1" "got $2" ;; esac
}
H=(-H "X-Requested-With: FinancialDashboard" -H "Content-Type: application/json")
AUTH=(-u "admin:$PASS_WORD")
code() { curl -s -o last.body -w '%{http_code}' "$@"; }
json() { "$PY" -c "import sys,json; print(json.load(sys.stdin)$1)" 2>/dev/null; }

smoke_checks() {
    checkok "dashboard page (GET /)" "$(code "$URL/")"
    checkok "a panel fragment is served" "$(code "$URL/panels/workspace.html")"
    checkok "static script is served" "$(code "$URL/js/app.js")"
    checkok "set the password (BCrypt)" "$(code "${H[@]}" -X POST "$URL/api/auth/password" -d "{\"password\":\"$PASS_WORD\"}")"
    check "a request without the login is refused" 401 "$(code "$URL/api/accounts")"
    checkok "verify the password" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/auth/verify" -d "{\"password\":\"$PASS_WORD\"}")"
    # an Excel file of the still empty database, read back by the import check (the import only works on an empty database)
    checkok "Excel export of the empty database (Apache POI)" "$(curl -s -o empty.xlsx -w '%{http_code}' "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/export" -d "{\"password\":\"$PASS_WORD\"}")"
    checkok "Excel import check of it (reads the workbook)" "$(curl -s -o last.body -w '%{http_code}' "${AUTH[@]}" -H 'X-Requested-With: FinancialDashboard' -H 'Content-Type: application/octet-stream' -X POST "$URL/api/import?apply=false" --data-binary @empty.xlsx)"
    checkok "list accounts (database, JPA)" "$(code "${AUTH[@]}" "$URL/api/accounts")"
    acc="$(curl -s "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/accounts" -d '{"name":"Bank","type":"BANK","openingBalance":1000}' | json "['id']")"
    check "create an account (write to the database)" "yes" "$([ -n "$acc" ] && echo yes)"
    # an ordinary top-level category (not one of the built-in fixed ones such as Balance Adjustment, which the app refuses)
    cat="$(curl -s "${AUTH[@]}" "$URL/api/categories?kind=EXPENSE" | "$PY" -c "import sys,json; print(next(c['id'] for c in json.load(sys.stdin) if not c.get('systemKey') and c.get('parentId') is None))" 2>/dev/null)"
    check "categories" "yes" "$([ -n "$cat" ] && echo yes)"
    # The bodies are built here, outside the $( ) of the checks: macOS's old bash (3.2) splits an escaped-quote JSON at its commas inside $( ).
    body_expense="{\"date\":\"2026-01-05\",\"kind\":\"EXPENSE\",\"fromAccountId\":$acc,\"amount\":250,\"categoryId\":$cat,\"note\":\"Dinner with friends\"}"
    body_income="{\"date\":\"2026-01-06\",\"kind\":\"INCOME\",\"toAccountId\":$acc,\"amount\":900,\"note\":\"Salary\"}"
    checkok "create an expense (tags, lookups)" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/transactions" -d "$body_expense")"
    checkok "create an income" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/transactions" -d "$body_income")"
    checkok "suggest tags" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/transactions/suggest-tags" -d '{"note":"Coffee at Starbucks","description":null,"categoryId":null}')"
    checkok "list transactions" "$(code "${AUTH[@]}" "$URL/api/transactions")"
    checkok "net worth (calculations)" "$(code "${AUTH[@]}" "$URL/api/net-worth?months=12")"
    checkok "a note in the diary (AES-GCM, PBKDF2)" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/notes" -d '{"date":"2026-01-05","title":"Hello","body":"A diary note","tags":null}')"
    checkok "read the notes back (decrypted)" "$(code "${AUTH[@]}" "$URL/api/notes")"
    checkok "a reminder" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/reminders" -d '{"date":"2026-12-01","title":"Pay rent","repeat":"MONTHLY"}')"
    checkok "a budget" "$(code "${AUTH[@]}" "${H[@]}" -X PUT "$URL/api/budgets" -d '{"categoryId":null,"amount":5000}')"
    checkok "a person (loans)" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/borrowers" -d '{"name":"Ravi","openingAmount":0}')"
    checkok "loans summary" "$(code "${AUTH[@]}" "$URL/api/loans")"
    checkok "set up the Ledger" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/ledger/setup")"
    checkok "the Ledger" "$(code "${AUTH[@]}" "$URL/api/ledger")"
    checkok "set up Shares" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/portfolios/SHARES/setup")"
    checkok "exchange rates" "$(code "${AUTH[@]}" "$URL/api/exchange-rates")"
    checkok "emergency fund" "$(code "${AUTH[@]}" "$URL/api/emergency-fund")"
    checkok "write a backup now (SQL script, zip, verify)" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/backups")"
    checkok "backup status" "$(code "${AUTH[@]}" "$URL/api/backups")"
    checkok "client log" "$(code "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/client-log" -d '{"level":"INFO","event":"smoke","detail":"x"}')"
    checkok "Excel export with data (Apache POI)" "$(curl -s -o export.xlsx -w '%{http_code}' "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/export" -d "{\"password\":\"$PASS_WORD\"}")"
    check "the export is a real workbook (zip)" "PK" "$(head -c 2 export.xlsx 2>/dev/null)"
}

start_app() {   # runs the given command in the background from a temporary folder, waits until the page answers
    WORK="$(mktemp -d)"; cd "$WORK" || exit 2
    echo "== Starting it in $WORK on port $PORT =="
    "$@" > app.log 2>&1 &
    APP=$!
    trap 'kill $APP 2>/dev/null; wait $APP 2>/dev/null' EXIT
    for i in $(seq 1 90); do
        [ "$(code "$URL/")" = "200" ] && break
        kill -0 $APP 2>/dev/null || { echo "The app stopped while starting. Last log lines:"; tail -40 app.log; echo "Full log: $WORK/app.log"; exit 1; }
        sleep 1
    done
    echo "Started in about $i seconds"
}
stop_app() { trap - EXIT; kill $APP 2>/dev/null; wait $APP 2>/dev/null; }
report() {
    echo
    if [ $fails -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "$fails CHECK(S) FAILED. Errors in the app log:"; grep -i -m20 "exception\|error\|ClassNotFound\|NoSuchMethod\|MissingReflection" app.log | cut -c1-240; fi
    echo "Full log: $WORK/app.log"
}


# ---------------------------------------------------------------------------------------------------- start, check, stop
CMD=("$@")
WORK="$(mktemp -d)"
case "${CMD[0]}" in /*|?:*) ;; *) CMD[0]="$(cd "$(dirname "${CMD[0]}")" && pwd)/$(basename "${CMD[0]}")" ;; esac   # an absolute path, since it runs from another folder
cd "$WORK" || exit 2
echo "== Starting it in $WORK on port $PORT =="
"${CMD[@]}" --server.port=$PORT > app.log 2>&1 &
APP=$!
trap 'kill $APP 2>/dev/null; wait $APP 2>/dev/null' EXIT
started=0
for i in $(seq 1 120); do
    [ "$(code "$URL/")" = "200" ] && { started=1; break; }
    kill -0 $APP 2>/dev/null || { echo "The app stopped while starting. Last log lines:"; tail -40 app.log; echo "Full log: $WORK/app.log"; exit 1; }
    sleep 1
done
[ $started = 1 ] || { echo "The app did not answer within two minutes. Last log lines:"; tail -40 app.log; exit 1; }
echo "Started in about $i seconds"

smoke_checks

trap - EXIT
kill $APP 2>/dev/null; wait $APP 2>/dev/null
echo
if [ $fails -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "$fails CHECK(S) FAILED. Errors in the app log:"; grep -i -m20 "exception\|error\|ClassNotFound\|NoSuchMethod\|MissingReflection" app.log | cut -c1-240; fi
echo "Full log: $WORK/app.log"
exit $fails
