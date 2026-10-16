#!/usr/bin/env bash
# Create a Kalshi Scalper in-app-updatable release. The body ALWAYS starts with the app-id line the updater requires,
# and the script fails if the published release body does not start with it.
# usage: scripts/kashi-release.sh <tag scalp-vX.Y.Z> <apk> <target-sha> <title> <notes>
set -euo pipefail
MARKER="app-id: com.dirk.kalshiodds.scalp"
TAG="$1"; APK="$2"; TARGET="$3"; TITLE="$4"; NOTES="${5:-}"
[[ "$TAG" =~ ^scalp-v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "bad tag $TAG" >&2; exit 1; }
[[ "$(basename "$APK")" == "KalshiScalper.apk" ]] || { echo "asset must be named KalshiScalper.apk" >&2; exit 1; }
BODY="$(printf '%s\n\n%s\n' "$MARKER" "$(printf '%s' "$NOTES" | grep -vxF "$MARKER" || true)")"
# A rerun may verify an existing published release, but never overwrite it.
if gh release view "$TAG" --json isDraft -q .isDraft > /dev/null 2>&1; then
    EXISTING_TARGET="$(gh release view "$TAG" --json targetCommitish -q .targetCommitish)"
    if [[ "$EXISTING_TARGET" != "$TARGET" ]]; then
        git fetch --no-tags origin "refs/tags/$TAG"
        git diff --quiet FETCH_HEAD "$TARGET" -- app gradle build.gradle.kts settings.gradle.kts gradle.properties || {
            echo "Version $TAG already exists with different app source; increment the version" >&2; exit 1;
        }
    fi
    gh release view "$TAG" --json assets -q '.assets[] | select(.name == "KalshiScalper.apk" and .size > 0) | .name' | grep -qxF 'KalshiScalper.apk' || {
        echo "Existing release is missing its APK; refusing to publish" >&2; exit 1;
    }
else
    # The updater never sees a release until its APK upload is complete.
    gh release create "$TAG" "$APK" --draft --target "$TARGET" --title "$TITLE" --notes "$BODY"
fi
FIRST="$(gh release view "$TAG" --json body -q .body | head -n1 | tr -d '\r')"
PRE="$(gh release view "$TAG" --json isPrerelease -q .isPrerelease)"
[[ "$FIRST" == "$MARKER" ]] || { echo "release $TAG body does not start with '$MARKER'" >&2; exit 1; }
[[ "$PRE" == "false" ]] || { echo "release $TAG is a prerelease" >&2; exit 1; }
# Keep other branches' repository-wide latest pointer intact; Kashi lists releases.
gh release edit "$TAG" --draft=false --latest=false
DRAFT="$(gh release view "$TAG" --json isDraft -q .isDraft)"
[[ "$DRAFT" == "false" ]] || { echo "release $TAG is still a draft" >&2; exit 1; }
echo "ok: $TAG published with $MARKER and KalshiScalper.apk"
