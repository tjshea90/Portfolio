#!/usr/bin/env bash
# ship.sh — cut a release. GITHUB BUILDS AND SIGNS IT; this prepares and triggers that.
#
#   bash ship.sh "what changed this release"        # normal: GitHub builds the APK
#   bash ship.sh --local "what changed"             # fallback: build it here instead
#
# WHY GITHUB BUILDS IT NOW
# ------------------------
# Every APK up to v7.8 was built inside a Claude container and committed to releases/.
# That worked, but it tied an installable build to a working Claude session and to this
# container having the signing keystore. TJ's rule from 2026-09-10 is that GitHub makes all
# future APKs while Claude writes the code, and this is the mechanism:
#
#   1. Claude codes, tests and checkpoints as usual.
#   2. `ship.sh "note"` runs the FULL gate locally first - a red suite must never spend
#      GitHub's minutes - then tags the commit and pushes the tag.
#   3. The `v*` tag fires .github/workflows/android.yml, which builds, SIGNS with the
#      keystore held in GitHub Secrets, verifies the certificate on the artifact it just
#      produced, and publishes it as a GitHub Release.
#   4. Once that run is green, `tools/record-release.sh` writes the line into BUILDLOG.md.
#
# THE PART THAT MATTERS FOR HANDOFF: the keystore is no longer needed here. A fresh
# container, on any Claude account, with no keystore at all, can now cut a full signed
# release - because the signing happens on GitHub. That removes the one manual, irreplaceable
# thing that used to stand between a new session and a shipped build.
#
# WHY THE APK IS NO LONGER COMMITTED. releases/ held eight megabytes per version, cloned in
# full by every future session before it read a line of code. GitHub Releases hold the
# binaries now; BUILDLOG.md remains the durable record of what shipped, and it is what both
# this script and the workflow gate the next versionCode against.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$D" || exit 1

LOCAL=0
if [ "${1:-}" = "--local" ]; then LOCAL=1; shift; fi
NOTE="${1:-}"; [ -z "$NOTE" ] && { echo "FAIL: a one-line change note is required."; exit 1; }

# COMMIT BEFORE BUILDING. A build run against a dirty tree proves nothing about what is
# actually committed, and leaves the tree looking like the last session was interrupted when
# it wasn't (see tools/resume.sh's mid-change warning - it must not cry wolf on every ship).
if [ -d .git ] && [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  bash tools/ckpt.sh "pre-ship: $NOTE" "ship.sh gates and releases this" >/dev/null 2>&1
  echo "  OK    committed the working tree before releasing"
fi

echo "== ship gates =="

# ---- the init-order guard ---------------------------------------------------
if ! python3 tools/checkinit.py; then
  echo "  FAIL  tools/checkinit.py — see BRIEF.md. Not shipping this."
  exit 1
fi
echo "  OK    checkinit"

# ---- the version Android will actually see -----------------------------------
VCODE="$(grep -m1 -oE 'versionCode *= *[0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')"
VNAME="$(grep -m1 -oE 'versionName *= *"[^"]+"' app/build.gradle.kts | grep -oE '"[^"]+"' | tr -d '"')"
[ -n "$VCODE" ] && [ -n "$VNAME" ] || { echo "  FAIL  could not read versionCode/versionName from app/build.gradle.kts"; exit 1; }

# STRICTLY HIGHER THAN EVERY CODE EVER SHIPPED, or Android refuses the install as a
# downgrade. Read from BUILDLOG.md, which records every version including ones whose APK is
# no longer anywhere in this repo - the workflow gates against exactly the same file.
PREV_MAX="$(grep -oE 'code [0-9]+' BUILDLOG.md 2>/dev/null | grep -oE '[0-9]+' | sort -n | tail -1)"
PREV_MAX="${PREV_MAX:-0}"
if [ "$VCODE" -le "$PREV_MAX" ]; then
  echo "  FAIL  versionCode $VCODE is not higher than the last shipped ($PREV_MAX)."
  echo "        Android will refuse to install this over what's on the phone."
  echo "        Bump versionCode in app/build.gradle.kts, then ship again."
  exit 1
fi
echo "  OK    version v$VNAME (code $VCODE) — higher than every previous ship"

# ---- the full unit suite must be green ---------------------------------------
# RUN HERE EVEN THOUGH THE WORKFLOW RUNS IT TOO. A red suite discovered on a runner has
# already cost five minutes of a 2,000-minute monthly budget and a round trip; discovered
# here it costs nothing but the time it was going to take anyway.
echo "  ..    running the full unit suite (can take minutes cold)"
if ! bash tools/gradle.sh testDebugUnitTest --console=plain > /tmp/ship-test.log 2>&1; then
  echo "  FAIL  the unit suite is red. Not shipping this."
  grep -E 'FAILED|error:' /tmp/ship-test.log | head -20 | sed 's/^/          /'
  echo "        full log: /tmp/ship-test.log"
  exit 1
fi
echo "  OK    unit suite green"

TAG="v$VNAME"

# ------------------------------------------------------------------ local fallback
if [ "$LOCAL" -eq 1 ]; then
  echo "  ..    --local: building and signing HERE instead of on GitHub"
  if ! bash tools/ensure-build-env.sh --release; then
    echo "  FAIL  build environment not ready (the keystore is required for a local build)."
    exit 1
  fi
  if ! bash tools/gradle.sh :app:assembleRelease --console=plain > /tmp/ship-build.log 2>&1; then
    echo "  FAIL  the release build did not succeed."
    tail -30 /tmp/ship-build.log | sed 's/^/          /'
    exit 1
  fi
  APK_OUT="app/build/outputs/apk/release/app-release.apk"
  [ -f "$APK_OUT" ] || { echo "  FAIL  build reported success but $APK_OUT is missing."; exit 1; }
  if ! bash tools/verify-apk.sh "$APK_OUT"; then
    echo "  FAIL  the APK is not signed with the certificate the phone accepts."
    exit 1
  fi
  mkdir -p releases
  cp "$APK_OUT" "releases/Portfolio-v${VNAME}.apk"
  echo "  OK    apk at releases/Portfolio-v${VNAME}.apk"
  bash tools/record-release.sh "$TAG" "$NOTE (built locally)"
  echo
  echo "== released v$VNAME (code $VCODE) locally =="
  exit 0
fi

# ------------------------------------------------------------------ the normal path
#
# WHY THIS DOES NOT PUSH A TAG.
# The obvious design is `git tag v7.9 && git push origin v7.9`, and it does not work from a
# Claude container: the session's egress policy allows pushes to refs/heads/* and answers
# 403 to refs/tags/*. Measured, not guessed. So the tag is created by GitHub instead - the
# workflow's publish step calls `gh release create --target <sha>`, which makes the tag
# server-side. A tag push still triggers a build for anyone who CAN push one.
if ! bash tools/push.sh; then
  echo "  FAIL  could not push the commit. Nothing to build - a run would check out a"
  echo "        commit GitHub does not have."
  exit 1
fi
echo "  OK    commit pushed"

# LEAVE THE NEXT STEP ON DISK, NOT JUST ON SCREEN.
# The seam: this script finishes, and the build still has to be triggered through the
# GitHub API. A usage cap landing in that gap would leave a gated, pushed, unreleased
# commit whose only instruction was printed to a terminal nobody will ever read again.
# Writing it as the checkpoint's "Do this next" means the next session - on any account -
# is told exactly this by tools/resume.sh before it does anything else.
bash tools/ckpt.sh \
  "gated v$VNAME (code $VCODE) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked." \
  "TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {\"full_build\": \"true\"}. When that run is green,
send Tj the APK from the Release and then run:
  bash tools/record-release.sh v$VNAME \"$NOTE\"" >/dev/null 2>&1
echo "  OK    next step recorded in CHECKPOINT.md (survives an interruption here)"
echo
echo "== v$VNAME (code $VCODE) is ready for GitHub to build =="
echo
echo "  NEXT, and Claude does this - it needs the GitHub API, not git:"
echo "    trigger the 'Build APK' workflow on main with full_build=true"
echo "    (mcp__github__actions_run_trigger, method run_workflow, android.yml)"
echo
echo "  That run builds, SIGNS with the keystore in GitHub Secrets, verifies the"
echo "  certificate on the APK it just produced, creates the tag v$VNAME and"
echo "  publishes it under Releases."
echo
echo "  WHEN THAT RUN IS GREEN, and not before:"
echo "    bash tools/record-release.sh v$VNAME \"$NOTE\""
echo "  BUILDLOG.md is what the next release is gated against, so a line in it"
echo "  must never describe a build that does not exist."
