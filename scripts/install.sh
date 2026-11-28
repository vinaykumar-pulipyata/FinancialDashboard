#!/bin/sh
# Installs Financial Dashboard: finds out the system, downloads the matching executable from the latest GitHub release,
# checks its checksum and sets up a `financial-dashboard` command. For macOS and Linux; Windows has scripts/install.ps1.
#
#   curl -fsSL https://raw.githubusercontent.com/vinaykumar-pulipyata/FinancialDashboard/main/scripts/install.sh | sh
#
# With options (after `sh -s --`):
#   ... | sh -s -- --version v0.1.0-rc1     install that release (a pre-release is only installed when named)
#   ... | sh -s -- --dir "$HOME/finance"     install and keep the data somewhere else (default ~/.financial-dashboard)
#   ... | sh -s -- --dry-run                 only say what would happen
#   ... | sh -s -- --uninstall               remove the program and the command (your data is kept)
#   ... | sh -s -- --uninstall --purge       also delete your data (data/, backups/, logs/); asks you to type "delete" first
#   ... | sh -s -- --uninstall --purge --yes   the same without asking (for scripts: there is no way back)
#
# Where things go (nothing outside these is touched):
#   ~/.financial-dashboard/financial-dashboard-app   the executable
#   ~/.financial-dashboard/financial-dashboard       the launcher: opens the browser and starts the app from this folder
#   ~/.financial-dashboard/data, backups, logs       your data, made by the app on its first start; never deleted or replaced
#   ~/.local/bin/financial-dashboard                 a link to the launcher
set -eu

REPO="vinaykumar-pulipyata/FinancialDashboard"
VERSION="${FINANCIAL_DASHBOARD_VERSION:-latest}"
HOME_DIR="${FINANCIAL_DASHBOARD_HOME:-$HOME/.financial-dashboard}"
BIN_DIR="${FINANCIAL_DASHBOARD_BIN_DIR:-$HOME/.local/bin}"
BASE_URL="${FINANCIAL_DASHBOARD_BASE_URL:-}"      # for tests: a folder or address that holds the files
DRY_RUN=0
UNINSTALL=0
PURGE=0
YES=0

say() { printf '%s\n' "$*"; }
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --version) [ $# -ge 2 ] || fail "--version needs a value, for example v0.1.0"; VERSION="$2"; shift 2 ;;
        --dir) [ $# -ge 2 ] || fail "--dir needs a folder"; HOME_DIR="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --uninstall) UNINSTALL=1; shift ;;
        --purge) PURGE=1; shift ;;
        --yes) YES=1; shift ;;
        -h|--help) sed -n '2,18p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//' || true; exit 0 ;;
        *) fail "Unknown option: $1 (try --help)" ;;
    esac
done

[ "$PURGE" = 0 ] || [ "$UNINSTALL" = 1 ] || fail "--purge only goes with --uninstall."
[ "$YES" = 0 ] || [ "$PURGE" = 1 ] || fail "--yes only goes with --purge."

# ---------------------------------------------------------------------------------------------- uninstall
if [ "$UNINSTALL" = 1 ]; then
    say "Removing the program and the command. Your data (data/, backups/, logs/ in $HOME_DIR) is left alone."
    for f in "$HOME_DIR/financial-dashboard-app" "$HOME_DIR/financial-dashboard"; do
        if [ -e "$f" ] || [ -L "$f" ]; then
            if [ "$DRY_RUN" = 1 ]; then say "  would remove $f"; else rm -f "$f"; say "  removed $f"; fi
        fi
    done
    link="$BIN_DIR/financial-dashboard"
    if [ -L "$link" ]; then
        if [ "$DRY_RUN" = 1 ]; then say "  would remove $link"; else rm -f "$link"; say "  removed $link"; fi
    fi

    if [ "$PURGE" = 1 ]; then
        # Your data: the app keeps data/, backups/ and logs/ in its folder. This is the one thing the installer can destroy, so it asks.
        targets=""
        for d in data backups logs; do [ -e "$HOME_DIR/$d" ] && targets="$targets $HOME_DIR/$d"; done
        [ -e "$HOME_DIR/financial-dashboard.previous" ] && targets="$targets $HOME_DIR/financial-dashboard.previous"
        say ""
        if [ -z "$targets" ]; then
            say "No data to delete in $HOME_DIR."
        else
            say "--purge deletes your data. This cannot be undone:"
            for t in $targets; do say "  $t  ($(du -sh "$t" 2>/dev/null | cut -f1))"; done
            if [ "$DRY_RUN" = 1 ]; then
                say "(dry run: nothing was deleted)"; exit 0
            fi
            if [ "$YES" = 1 ]; then
                :
            elif [ -z "${FINANCIAL_DASHBOARD_NO_TTY:-}" ] && ( : < /dev/tty ) 2>/dev/null; then   # (the variable lets the tests pretend there is no terminal)
                printf 'Type "delete" to delete it, anything else keeps it: ' > /dev/tty
                read -r answer < /dev/tty || answer=""
                [ "$answer" = delete ] || { say "Kept. Nothing was deleted."; exit 0; }
            else
                fail "Cannot ask for confirmation here (there is no terminal). Nothing was deleted. Run it in a terminal, or add --yes if you are sure."
            fi
            for t in $targets; do rm -rf "$t"; say "  deleted $t"; done
        fi
        rmdir "$HOME_DIR" 2>/dev/null && say "  removed the empty folder $HOME_DIR" || say "Left in $HOME_DIR: $(ls -A "$HOME_DIR" 2>/dev/null | tr '\n' ' ')"
    fi
    exit 0
fi

# ---------------------------------------------------------------------------------------------- which system is this?
os_name="$(uname -s)"
arch_name="$(uname -m)"
case "$os_name" in
    Linux)  os=linux ;;
    Darwin) os=macos ;;
    MINGW*|MSYS*|CYGWIN*) fail "This looks like Windows. Use the PowerShell installer instead:
  irm https://raw.githubusercontent.com/$REPO/main/scripts/install.ps1 | iex" ;;
    *) fail "Unsupported system: $os_name. Executables are built for Linux, macOS (Apple silicon) and Windows." ;;
esac
case "$arch_name" in
    x86_64|amd64) arch=x64 ;;
    arm64|aarch64) arch=arm64 ;;
    *) fail "Unsupported processor: $arch_name. Executables are built for x64 and arm64." ;;
esac
if [ "$os" = macos ] && [ "$arch" = x64 ]; then
    # a shell running under Rosetta reports x86_64 on an Apple-silicon Mac
    if [ "$(sysctl -n hw.optional.arm64 2>/dev/null || echo 0)" = 1 ]; then
        arch=arm64
    else
        fail "This is an Intel Mac, and there is no build for it. Run it from the source instead (see the README: Quick start)."
    fi
fi
if [ "$os" = macos ] && [ "$arch" != arm64 ]; then fail "macOS builds are for Apple silicon only."; fi
asset="financial-dashboard-$os-$arch"

if [ -n "$BASE_URL" ]; then
    base="${BASE_URL%/}"
elif [ "$VERSION" = latest ]; then
    base="https://github.com/$REPO/releases/latest/download"
else
    base="https://github.com/$REPO/releases/download/$VERSION"
fi

say "System:      $os $arch"
say "Release:     $VERSION"
say "Download:    $base/$asset"
say "Install in:  $HOME_DIR  (command: $BIN_DIR/financial-dashboard)"
if [ "$DRY_RUN" = 1 ]; then say "(dry run: nothing was downloaded or changed)"; exit 0; fi

# ---------------------------------------------------------------------------------------------- download and check
if command -v curl >/dev/null 2>&1; then
    fetch() { curl -fsSL --retry 3 -o "$2" "$1"; }
elif command -v wget >/dev/null 2>&1; then
    fetch() { wget -q -O "$2" "$1"; }
else
    fail "Neither curl nor wget is installed."
fi
if command -v sha256sum >/dev/null 2>&1; then
    sha() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
    sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
    fail "Neither sha256sum nor shasum is installed, so the download cannot be checked."
fi

tmp="$(mktemp -d 2>/dev/null || mktemp -d -t fdinstall)"
trap 'rm -rf "$tmp"' EXIT INT TERM
say "Downloading ..."
fetch "$base/$asset" "$tmp/$asset" || fail "Could not download $base/$asset
  (Is there a release yet? See https://github.com/$REPO/releases . A pre-release is only installed with --version.)"
fetch "$base/$asset.sha256" "$tmp/$asset.sha256" || fail "Could not download the checksum file for $asset."
expected="$(cut -d' ' -f1 "$tmp/$asset.sha256" | head -1)"
actual="$(sha "$tmp/$asset")"
[ -n "$expected" ] && [ "$expected" = "$actual" ] || fail "The checksum does not match (expected $expected, got $actual). Nothing was installed."
say "Checksum OK."

# ---------------------------------------------------------------------------------------------- install
mkdir -p "$HOME_DIR" "$BIN_DIR"
if [ -f "$HOME_DIR/app.jar" ]; then
    say "Found an earlier Java-based install here (app.jar). It is left in place; the new launcher replaces the old one, and your data is kept."
fi
if [ -f "$HOME_DIR/financial-dashboard" ] && ! grep -q "financial-dashboard-app" "$HOME_DIR/financial-dashboard" 2>/dev/null; then
    cp "$HOME_DIR/financial-dashboard" "$HOME_DIR/financial-dashboard.previous"
    say "The old launcher was saved as $HOME_DIR/financial-dashboard.previous"
fi
cp "$tmp/$asset" "$HOME_DIR/financial-dashboard-app.new"
chmod +x "$HOME_DIR/financial-dashboard-app.new"
if [ "$os" = macos ]; then xattr -d com.apple.quarantine "$HOME_DIR/financial-dashboard-app.new" 2>/dev/null || true; fi
mv -f "$HOME_DIR/financial-dashboard-app.new" "$HOME_DIR/financial-dashboard-app"

# The launcher runs the app from this folder, because the app keeps data/, backups/ and logs/ in the folder it is started from.
cat > "$HOME_DIR/financial-dashboard" <<EOF
#!/bin/sh
# Starts Financial Dashboard from its own folder and opens the browser once it answers. Extra options go to the app
# (for example --reset-password, --restore=FILE, --server.port=9090).
cd "$HOME_DIR" || exit 1
URL="http://localhost:8080/"
if [ "\$(uname)" = "Darwin" ]; then OPEN=open; else OPEN=xdg-open; fi
( i=0; while [ \$i -lt 60 ]; do
    if curl -s -o /dev/null "\$URL"; then "\$OPEN" "\$URL" >/dev/null 2>&1; break; fi
    i=\$((i+1)); sleep 1
  done ) &
exec "$HOME_DIR/financial-dashboard-app" "\$@"
EOF
chmod +x "$HOME_DIR/financial-dashboard"
ln -sf "$HOME_DIR/financial-dashboard" "$BIN_DIR/financial-dashboard"

say ""
say "Installed. Start it with:  financial-dashboard"
say "It opens http://localhost:8080/ ; on the first run it asks you to set a password."
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) say ""; say "Note: $BIN_DIR is not on your PATH. Add it (then open a new terminal):"
       say "  echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> ~/.profile" ;;
esac
say "Your data lives in $HOME_DIR/data (and backups/, logs/). To remove the program later: re-run this script with --uninstall."