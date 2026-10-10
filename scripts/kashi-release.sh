#!/usr/bin/env bash
# Create a Kashi in-app-updatable release. The body ALWAYS starts with the app-id line the updater requires,
# and the script fails if the published release body does not start with it.
# usage: scripts/kashi-release.sh <tag vX.Y.Z-debug> <apk> <target-sha> <title> <notes>
set -euo pipefail
MARKER="app-id: com.dirk.kalshiodds.kashi"
TAG="$1"; APK="$2"; TARGET="$3"; TITLE="$4"; NOTES="${5:-}"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-debug$ ]] || { echo "bad tag $TAG" >&2; exit 1; }
[[ "$(basename "$APK")" == "DipHunter-debug.apk" ]] || { echo "asset must be named DipHunter-debug.apk" >&2; exit 1; }
BODY="$(printf '%s\n\n%s\n' "$MARKER" "$(printf '%s' "$NOTES" | grep -vxF "$MARKER" || true)")"
gh release create "$TAG" "$APK" --target "$TARGET" --title "$TITLE" --notes "$BODY"
FIRST="$(gh release view "$TAG" --json body -q .body | head -n1 | tr -d '\r')"
PRE="$(gh release view "$TAG" --json isPrerelease -q .isPrerelease)"
[[ "$FIRST" == "$MARKER" ]] || { echo "release $TAG body does not start with '$MARKER'" >&2; exit 1; }
[[ "$PRE" == "false" ]] || { echo "release $TAG is a prerelease" >&2; exit 1; }
echo "ok: $TAG starts with $MARKER"
