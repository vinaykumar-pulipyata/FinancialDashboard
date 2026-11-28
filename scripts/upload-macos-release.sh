#!/usr/bin/env bash
# Builds the macOS (Apple silicon) executable on THIS Mac, smoke-tests it and attaches it, with its checksum, to a GitHub Release.
# For when GitHub's small macOS runner (7 GB) cannot build it: the release workflow makes the Linux and Windows files, this adds the Mac one.
#   scripts/upload-macos-release.sh v0.1.0          (the release must already exist: push the tag first and let the workflow create it)
#   scripts/upload-macos-release.sh v0.1.0-rc3 --no-build    use the executable already built
# Needs: an Apple-silicon Mac, GraalVM 25 (see scripts/native-experiment.sh), and `gh auth login` for github.com. Run it in an ordinary terminal.
set -eu
cd "$(dirname "$0")/.."
TAG="${1:-}"
[ -n "$TAG" ] || { echo "usage: $0 TAG [--no-build]   e.g. $0 v0.1.0"; exit 2; }
[ "$(uname -s)-$(uname -m)" = "Darwin-arm64" ] || { echo "This builds the macOS arm64 file, so it has to run on an Apple-silicon Mac."; exit 2; }
command -v gh >/dev/null || { echo "The GitHub CLI (gh) is needed."; exit 2; }
gh release view "$TAG" >/dev/null 2>&1 || { echo "There is no release $TAG yet. Push the tag first (git tag $TAG && git push origin $TAG) and wait for the workflow to create it."; exit 2; }

if [ "${2:-}" != "--no-build" ]; then
    scripts/native-experiment.sh --rebuild       # builds and runs the smoke test; stops here if anything fails
else
    scripts/native-experiment.sh
fi
BIN="build/native/nativeCompile/FinancialDashboard"
[ -x "$BIN" ] || { echo "No executable at $BIN"; exit 1; }

DIST="$(mktemp -d)"
ASSET="financial-dashboard-macos-arm64"
cp "$BIN" "$DIST/$ASSET"
( cd "$DIST" && shasum -a 256 "$ASSET" > "$ASSET.sha256" && cat "$ASSET.sha256" )
gh release upload "$TAG" "$DIST/$ASSET" "$DIST/$ASSET.sha256" --clobber
rm -rf "$DIST"
echo "Attached $ASSET to $TAG: $(gh release view "$TAG" --json url -q .url)"
