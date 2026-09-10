#!/usr/bin/env bash
# ship.sh — MILESTONE release. Not the routine checkpoint; that is tools/ckpt.sh.
#
# WHY THIS EXISTS SEPARATELY FROM ckpt.sh
# ----------------------------------------
# tools/ckpt.sh is deliberately fast and ungated — it has to be, so a session
# can checkpoint mid-change without paying for a full Gradle build every time.
# That is exactly wrong for something that becomes an installable APK: a
# release has to be green, signed with the one keystore Android will accept
# as an in-place update, and versioned higher than what's already on the
# phone (see BRIEF.md — "the keystore is irreplaceable"). ship.sh is that
# gate. Use it at real versions, not mid-task.
#
#   bash ship.sh "what changed this release"
#
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$D" || exit 1
NOTE="${1:-}"; [ -z "$NOTE" ] && { echo "FAIL: a one-line change note is required."; exit 1; }

# COMMIT BEFORE BUILDING. A build run against a dirty tree proves nothing
# about what's actually committed, and leaves the tree looking like the last
# session was interrupted when it wasn't (see tools/resume.sh's mid-change
# warning — it must not cry wolf on every clean ship).
if [ -d .git ] && [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  bash tools/ckpt.sh "pre-ship: $NOTE" "ship.sh gates and releases this" >/dev/null 2>&1
  echo "  OK    committed the working tree before building"
fi

echo "== ship gates =="

# ---- the init-order guard ---------------------------------------------------
if ! python3 tools/checkinit.py; then
  echo "  FAIL  tools/checkinit.py — see BRIEF.md. Not shipping this."
  exit 1
fi
echo "  OK    checkinit"

# ---- the SDK must be present -------------------------------------------------
export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
if [ ! -d "$ANDROID_HOME/platforms" ]; then
  echo "  FAIL  no Android SDK at \$ANDROID_HOME ($ANDROID_HOME)."
  echo "        Run: bash tools/setup-android-sdk.sh"
  exit 1
fi
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

# ---- the full unit suite must be green ---------------------------------------
# Backgrounded and given real time: a cold container downloads Gradle itself
# plus every dependency, and Maven Central can 429 on a cold pull — see
# BRIEF.md's build traps before "fixing" a failure here that is really that.
echo "  ..    running ./gradlew testDebugUnitTest (797 tests as of v7.7 — can take minutes cold)"
if ! ./gradlew testDebugUnitTest --console=plain > /tmp/ship-test.log 2>&1; then
  echo "  FAIL  the unit suite is red. Not shipping this."
  grep -E 'FAILED|error:' /tmp/ship-test.log | head -20 | sed 's/^/          /'
  echo "        full log: /tmp/ship-test.log"
  exit 1
fi
echo "  OK    unit suite green"

# ---- the release build itself -------------------------------------------------
echo "  ..    running ./gradlew :app:assembleRelease"
if ! ./gradlew :app:assembleRelease --console=plain > /tmp/ship-build.log 2>&1; then
  echo "  FAIL  the release build did not succeed. Not shipping this."
  tail -30 /tmp/ship-build.log | sed 's/^/          /'
  echo "        full log: /tmp/ship-build.log"
  exit 1
fi
APK_OUT="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK_OUT" ] || { echo "  FAIL  build reported success but $APK_OUT is missing."; exit 1; }
echo "  OK    release APK built"

# ---- read the version Android will actually see -------------------------------
VCODE="$(grep -m1 -oE 'versionCode *= *[0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')"
VNAME="$(grep -m1 -oE 'versionName *= *"[^"]+"' app/build.gradle.kts | grep -oE '"[^"]+"' | tr -d '"')"
[ -n "$VCODE" ] && [ -n "$VNAME" ] || { echo "  FAIL  could not read versionCode/versionName from app/build.gradle.kts"; exit 1; }

# versionCode must be STRICTLY HIGHER than every code this repo has already
# shipped, or Android refuses the install as a downgrade. Checked against
# what's actually committed under releases/, which survives across
# containers (unlike anything in a build/ directory).
PREV_MAX=0
for f in releases/Portfolio-v*.apk; do
  [ -f "$f" ] || continue
  V="$(basename "$f" .apk | sed -E 's/^Portfolio-v//')"
  C="$(grep -q "^| v${V} |" BUILDLOG.md 2>/dev/null && grep "^| v${V} |" BUILDLOG.md | head -1 | grep -oE 'code [0-9]+' | grep -oE '[0-9]+' || true)"
  [ -n "$C" ] && [ "$C" -gt "$PREV_MAX" ] && PREV_MAX="$C"
done
if [ "$VCODE" -le "$PREV_MAX" ]; then
  echo "  FAIL  versionCode $VCODE is not higher than the last shipped code ($PREV_MAX)."
  echo "        Android will refuse to install this over what's on the phone."
  echo "        Bump versionCode in app/build.gradle.kts, then ship again."
  exit 1
fi
echo "  OK    version v$VNAME (code $VCODE) — higher than every previous ship"

# ---- publish the APK under releases/, committed -------------------------------
mkdir -p releases
APK="releases/Portfolio-v${VNAME}.apk"
cp "$APK_OUT" "$APK"
echo "  OK    apk staged at $APK"

# PRUNE OLD RELEASES. Every fresh session clones every committed release APK
# before reading a line of code. Three is enough to roll back to a
# known-good build; older ones stay in git history, recoverable by SHA.
KEEP=3
OLD="$(ls -1 releases/Portfolio-v*.apk 2>/dev/null | sort -V | head -n -"$KEEP")"
if [ -n "$OLD" ]; then
  for f in $OLD; do
    [ "$f" = "$APK" ] && continue
    git rm -q --cached "$f" >/dev/null 2>&1 || true
    rm -f "$f"
    echo "  ..    pruned old release $(basename "$f") (still in git history)"
  done
fi

touch BUILDLOG.md
printf '| v%s | code %s | %s | %s\n' "$VNAME" "$VCODE" "$(date -u +%Y-%m-%dT%H:%MZ)" "$NOTE" >> BUILDLOG.md

# ---- commit everything the gates produced --------------------------------------
git add -A >/dev/null 2>&1
if ! bash tools/secretscan.sh; then
  git reset -q >/dev/null 2>&1
  echo "  FAIL  a credential is in the tree — nothing committed or published."
  exit 1
fi
if git diff --cached --quiet 2>/dev/null; then
  echo "  ..    nothing new to commit (releases/BUILDLOG.md already current)"
else
  git commit -q -m "ship v$VNAME: $NOTE

versionCode: $VCODE
tests: unit suite green

Co-Authored-By: Claude <noreply@anthropic.com>" >/dev/null 2>&1
  echo "  OK    working tree committed"
fi

# THE SHIP IS NOT DONE UNTIL IT IS PUSHED — fatal on failure, unlike ckpt.sh:
# a "shipped" version that exists nowhere but this container is a lie, and
# the next session would bump past it and never build it again.
if bash tools/push.sh; then
  echo "  OK    pushed to GitHub"
else
  echo "  FAIL  COULD NOT PUSH. v$VNAME exists only in this container and will"
  echo "        be lost when the session ends. Retry:  git push origin HEAD"
  exit 1
fi

echo
echo "== shipped v$VNAME (code $VCODE) =="
echo
echo "  Tj installs it from the repo: $APK  (tap it, then Download)"
echo
echo "  To continue in a NEW session: open the repo and say \"continue\"."
echo "  The SessionStart hook briefs it automatically."
