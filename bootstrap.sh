#!/usr/bin/env bash
# bootstrap.sh — cold start. Verify the checkout, then brief the session.
#
# WHY THIS PRINTS SO LITTLE
# --------------------------
# Everything printed here becomes permanent context that is resent on every
# future turn. So this stays short: where the session stopped, what is next,
# and the standing rules. Narrative detail belongs in CHECKPOINT.md, TASKS.md,
# CLAUDE.md and BRIEF.md, not in growing this file.
#
# Native Android app (Kotlin + Compose). The checks below are informational
# only — nothing here gates the session. The real build/test gate is
# ship.sh; see BRIEF.md for the traps that cost real time before.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$D" || exit 1
echo "== Portfolio — bootstrap =="
echo "working dir: $D"; echo

# --- toolchain (informational only) ---
command -v java >/dev/null 2>&1 \
  && echo "  OK    java $(java -version 2>&1 | grep -v 'JAVA_TOOL_OPTIONS' | head -1 | tr -d '\n' | sed 's/.*version //')" \
  || echo "  WARN  no java — gradlew will fail"
command -v python3 >/dev/null 2>&1 && echo "  OK    python3 $(python3 -V 2>&1 | cut -d' ' -f2)" || echo "  WARN  no python3 — tools/checkinit.py needs it"
AHOME="${ANDROID_HOME:-/root/android-sdk}"
[ -d "$AHOME/platforms" ] \
  && echo "  OK    android sdk present at $AHOME" \
  || echo "  note  no android sdk yet — run: bash tools/setup-android-sdk.sh (~5 min, once)"
[ -f app/build/outputs/apk/release/app-release.apk ] && echo "  OK    a release APK exists in app/build/ (unshipped)" || echo "  note  no unshipped build output — releases/ holds the last shipped APK"

# --- the checkpoint history ---
if [ -d .git ]; then
  echo "  OK    checkpoint history present ($(git rev-list --count HEAD 2>/dev/null || echo 0) checkpoints)"
  if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    echo "  NOTE  uncommitted edits are present — the last session may have been"
    echo "        interrupted mid-change. 'git status' and 'git diff' show what."
  fi
else
  echo "  WARN  no .git — checkpoint history was lost. Run: git init && bash tools/ckpt.sh 'resumed'"
fi
echo
echo "== bootstrap clean =="
echo

if [ -f CHECKPOINT.md ]; then
  echo "##############################################################################"
  echo "#  CHECKPOINT.md — where the last session stopped. START HERE."
  echo "##############################################################################"
  cat CHECKPOINT.md
  echo
fi
if [ -f TASKS.md ]; then
  echo "##############################################################################"
  echo "#  TASKS.md — the scope of the current job. Continue from the first [ ]."
  echo "##############################################################################"
  cat TASKS.md
  echo
fi

echo "##############################################################################"
echo "#  THE RULES THAT MUST NOT BE BROKEN  (full text: BRIEF.md)"
echo "##############################################################################"
cat <<'SHORT'
- THE KEYSTORE IS IRREPLACEABLE. app/sideload.jks signs every release. A new
  keystore forces an uninstall on the phone, which erases the portfolio.
  Never regenerate it, never change applicationId, always bump versionCode.
- tools/checkinit.py: no PortfolioViewModel property may be declared below
  its init block (crashed the app on every launch once already). Wired into
  Gradle's preBuild; also runs in tools/ckpt.sh.
- Toolchain is pinned (AGP 8.13.2 / Kotlin 2.3.10 / compileSdk 36) — see
  BRIEF.md before "upgrading" a dependency that suddenly demands compileSdk
  37 or AGP 9.1+.
- Build traps that have cost real time before (blanking JAVA_TOOL_OPTIONS,
  concurrent Gradle builds, Maven 429s) are in BRIEF.md — read it before
  fighting a build failure that isn't a code problem.
- Checkpoint constantly:  bash tools/ckpt.sh "did" "next"   (fast, no gate)
- Ship at real versions:  bash ship.sh "note"   (tests + signed APK + push)
- Write new requests into TASKS.md, in Tj's own words, before writing any code.
SHORT
