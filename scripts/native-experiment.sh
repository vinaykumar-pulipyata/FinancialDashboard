#!/usr/bin/env bash
# Experiment: build the app as a GraalVM native executable and smoke-test it.
#   scripts/native-experiment.sh            build (if needed) and test
#   scripts/native-experiment.sh --rebuild  build again first
# It must run in an ordinary Terminal (Gradle and the app open local sockets). Your real data is never touched: the
# executable runs from a temporary folder, on its own port, with its own database.
set -u
cd "$(dirname "$0")/.."

GRAAL="${GRAALVM_HOME:-$HOME/Library/Java/JavaVirtualMachines/graalvm-jdk-24.0.2/Contents/Home}"
[ -x "$GRAAL/bin/native-image" ] || { echo "No GraalVM with native-image at $GRAAL (set GRAALVM_HOME)"; exit 2; }
export JAVA_HOME="$GRAAL"
BIN="build/native/nativeCompile/FinancialDashboard"
PORT=8099
URL="http://127.0.0.1:$PORT"
PASS_WORD="native-test-pw-1"

if [ "${1:-}" = "--rebuild" ] || [ ! -x "$BIN" ]; then
    echo "== Building the native executable (this takes several minutes) =="
    ./gradlew nativeCompile -PjavaVersion=24 -x test -x jsTest || { echo "BUILD FAILED"; exit 1; }
fi
BIN="$(pwd)/$BIN"
ls -lh "$BIN" | awk '{print "Executable size: " $5}'

WORK="$(mktemp -d)"
cd "$WORK" || exit 2
echo "== Starting it in $WORK on port $PORT =="
"$BIN" --server.port=$PORT > app.log 2>&1 &
APP=$!
trap 'kill $APP 2>/dev/null; wait $APP 2>/dev/null' EXIT

for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$URL/" 2>/dev/null)
    [ "$code" = "200" ] && break
    kill -0 $APP 2>/dev/null || { echo "The app stopped while starting. Last log lines:"; tail -30 app.log; exit 1; }
    sleep 1
done
fails=0
check() {   # name, expected, actual
    if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1 (expected $2, got $3)"; fails=$((fails+1)); fi
}
echo "Started in about $i seconds"
H=(-H "X-Requested-With: FinancialDashboard" -H "Content-Type: application/json")
AUTH=(-u "admin:$PASS_WORD")

check "dashboard page (GET /)" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$URL/")"
check "set the password" 200 "$(curl -s -o /dev/null -w '%{http_code}' "${H[@]}" -X POST "$URL/api/auth/password" -d "{\"password\":\"$PASS_WORD\"}")"
check "a request without the login is refused" 401 "$(curl -s -o /dev/null -w '%{http_code}' "$URL/api/accounts")"
check "list accounts (database, JPA)" 200 "$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$URL/api/accounts")"
check "create an account (write to the database)" 201 "$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/accounts" -d '{"name":"Bank","type":"BANK","openingBalance":1000}')"
check "net worth (calculations)" 200 "$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$URL/api/net-worth?months=12")"
check "static script is served" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$URL/js/app.js")"
check "Excel export (Apache POI)" 200 "$(curl -s -o export.xlsx -w '%{http_code}' "${AUTH[@]}" "${H[@]}" -X POST "$URL/api/export" -d "{\"password\":\"$PASS_WORD\"}")"
check "the export is a real workbook (zip)" "PK" "$(head -c 2 export.xlsx 2>/dev/null)"
check "Excel import check (reads it back)" 200 "$(curl -s -o import.json -w '%{http_code}' "${AUTH[@]}" -H 'X-Requested-With: FinancialDashboard' -H 'Content-Type: application/octet-stream' -X POST "$URL/api/import?apply=false" --data-binary @export.xlsx)"
check "backup status" 200 "$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$URL/api/backups")"

echo
if [ $fails -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "$fails CHECK(S) FAILED. Errors in the app log:"; grep -i -m15 "exception\|error\|ClassNotFound\|NoSuchMethod" app.log | cut -c1-220; fi
echo "Full log: $WORK/app.log"
trap - EXIT; kill $APP 2>/dev/null; wait $APP 2>/dev/null
exit $fails
