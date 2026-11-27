#!/bin/sh
# Tests scripts/install.sh without the internet: a fake "release" (a small script standing in for the executable, plus its checksum) in a
# temporary folder, a temporary home folder, and the installer pointed at them. Checks the install, an upgrade over an older Java-style
# install (its data must survive), a wrong checksum (nothing may be installed) and the uninstall.
#   sh scripts/test-install.sh
set -u
cd "$(dirname "$0")/.."
SCRIPT="$(pwd)/scripts/install.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fails=0
ok() { echo "PASS  $1"; }
bad() { echo "FAIL  $1"; fails=$((fails+1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected '$2', got '$3')"; fi; }

# which file this system downloads: the last part of the address the dry run prints
asset="$(HOME="$TMP/h0" sh "$SCRIPT" --dry-run | sed -n 's/^Download: *.*\/\(financial-dashboard-[a-z0-9.-]*\)$/\1/p')"
case "$asset" in financial-dashboard-linux-x64|financial-dashboard-linux-arm64|financial-dashboard-macos-arm64) ok "this system picks $asset" ;; *) bad "unexpected file for this system: '$asset'"; exit 1 ;; esac

REL="$TMP/release"; mkdir -p "$REL"
printf '#!/bin/sh\necho "fake app in $(pwd) with: $*"\n' > "$REL/$asset"
if command -v sha256sum >/dev/null 2>&1; then ( cd "$REL" && sha256sum "$asset" > "$asset.sha256" ); else ( cd "$REL" && shasum -a 256 "$asset" > "$asset.sha256" ); fi
export FINANCIAL_DASHBOARD_BASE_URL="file://$REL"

# an older Java-style install with data in it
H="$TMP/home"; D="$H/.financial-dashboard"
mkdir -p "$D/data"; echo jar > "$D/app.jar"; echo "my data" > "$D/data/financialdb.mv.db"
printf '#!/bin/sh\nexec java -jar app.jar\n' > "$D/financial-dashboard"

HOME="$H" sh "$SCRIPT" >/dev/null 2>&1; check "the installer succeeds" 0 $?
[ -x "$D/financial-dashboard-app" ] && ok "the executable is installed" || bad "the executable is missing"
[ -x "$D/financial-dashboard" ] && grep -q financial-dashboard-app "$D/financial-dashboard" && ok "the launcher runs it" || bad "the launcher is wrong"
[ -L "$H/.local/bin/financial-dashboard" ] && ok "the command is linked" || bad "the command link is missing"
check "the old launcher is kept" "yes" "$([ -f "$D/financial-dashboard.previous" ] && echo yes)"
check "the old data is untouched" "my data" "$(cat "$D/data/financialdb.mv.db")"
check "the old jar is untouched" "jar" "$(cat "$D/app.jar")"
out="$(HOME="$H" "$H/.local/bin/financial-dashboard" --server.port=1 2>&1 | head -1)"
case "$out" in "fake app in $D with: --server.port=1") ok "the command starts the app from its own folder with the options" ;; *) bad "the command printed: $out" ;; esac

HOME="$H" sh "$SCRIPT" >/dev/null 2>&1; check "installing again (an upgrade) succeeds" 0 $?
check "the data survives an upgrade" "my data" "$(cat "$D/data/financialdb.mv.db")"

# a wrong checksum: nothing may be installed
echo "deadbeef  x" > "$REL/$asset.sha256"
HOME="$TMP/h2" sh "$SCRIPT" >/dev/null 2>&1; rc=$?
[ $rc -ne 0 ] && ok "a wrong checksum is refused" || bad "a wrong checksum was accepted"
[ ! -e "$TMP/h2/.financial-dashboard/financial-dashboard-app" ] && ok "and nothing was installed" || bad "something was installed"

HOME="$H" sh "$SCRIPT" --uninstall >/dev/null 2>&1
[ ! -e "$D/financial-dashboard-app" ] && [ ! -e "$H/.local/bin/financial-dashboard" ] && ok "uninstall removes the program and the command" || bad "uninstall left files"
check "uninstall keeps the data" "my data" "$(cat "$D/data/financialdb.mv.db")"

echo; [ $fails -eq 0 ] && echo "ALL CHECKS PASSED" || echo "$fails CHECK(S) FAILED"
exit $fails
