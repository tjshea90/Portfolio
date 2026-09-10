#!/usr/bin/env bash
# record-release.sh — write a shipped version into BUILDLOG.md, AFTER GitHub built it.
#
# WHY THIS IS A SEPARATE STEP FROM ship.sh
# ----------------------------------------
# BUILDLOG.md is load-bearing, not documentation: it is the durable record of every
# versionCode that ever shipped, and BOTH ship.sh and .github/workflows/android.yml read it
# to decide whether the next build could actually install on the phone. A line in it is a
# claim that a release EXISTS.
#
# ship.sh no longer builds the APK - GitHub does - so at the moment ship.sh finishes, the
# release does not exist yet. Writing the line there would make BUILDLOG lie whenever a run
# failed, and the next release would then be gated against a version nobody can install.
#
# So: ship.sh tags and pushes, GitHub builds, and only once that run is GREEN does this
# record it.
#
#   bash tools/record-release.sh v7.9 "what changed"
#
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 1

TAG="${1:-}"; NOTE="${2:-}"
[ -z "$TAG" ] || [ -z "$NOTE" ] && {
  echo "usage: bash tools/record-release.sh v7.9 \"what changed this release\""; exit 1; }

VNAME="${TAG#v}"
VCODE="$(grep -m1 -oE 'versionCode *= *[0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')"
[ -n "$VCODE" ] || { echo "  FAIL  could not read versionCode from app/build.gradle.kts"; exit 1; }

if grep -q "^| v${VNAME} |" BUILDLOG.md 2>/dev/null; then
  echo "  ..    v$VNAME is already in BUILDLOG.md — nothing to record"
  exit 0
fi

printf '| v%s | code %s | %s | %s\n' \
  "$VNAME" "$VCODE" "$(date -u +%Y-%m-%dT%H:%MZ)" "$NOTE" >> BUILDLOG.md

git add BUILDLOG.md >/dev/null 2>&1
if ! bash tools/secretscan.sh; then
  git reset -q >/dev/null 2>&1
  echo "  FAIL  a credential is in the tree — nothing recorded."
  exit 1
fi
git commit -q -m "record v$VNAME (code $VCODE): $NOTE

Built and signed by GitHub Actions; this line is the durable record that the
release exists. See the run and its APK under Releases.

Co-Authored-By: Claude <noreply@anthropic.com>" >/dev/null 2>&1

if bash tools/push.sh; then
  echo "  OK    recorded v$VNAME (code $VCODE) in BUILDLOG.md and pushed"
else
  echo "  WARN  recorded locally but COULD NOT PUSH. Retry: git push origin HEAD"
  exit 1
fi
