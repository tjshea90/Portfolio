#!/usr/bin/env bash
# ckpt.sh — THE FAST CHECKPOINT. Run after every meaningful edit, not just at
# the end of a task. Takes well under a second.
#
# WHY THIS IS UNGATED
# --------------------
# A session that can only checkpoint when everything is green cannot
# checkpoint at all while it is halfway through a multi-file change — and
# that is precisely when a usage cap tends to land. So ckpt.sh has NO gate:
# it commits whatever is on disk, red tests and all, and records honestly
# whether they passed. A broken intermediate state that is COMMITTED and
# DESCRIBED is recoverable; the same state uncommitted is not.
#
#   bash tools/ckpt.sh "what I just did" "what comes next"
#
# Both notes are written into CHECKPOINT.md and into the commit message, so a
# session resuming hours later reads one file and knows where it stands. The
# second argument is the important one: "what comes next" is the thing that is
# lost when a session dies, and it is the thing no diff can reconstruct.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 1

DID="${1:-}"; NEXT="${2:-}"
[ -z "$DID" ] && { echo "usage: bash tools/ckpt.sh \"what I just did\" \"what comes next\""; exit 1; }

# ---- test state, recorded rather than enforced ------------------------------
# Never gate on this. The point is to capture the state, whatever it is.
#
# DISCOVERED, NOT LISTED — same reasoning as the fantasy-football tracker's
# ckpt.sh: a hard-coded list silently stops covering a suite added later.
#
# Only the FAST checks run here, deliberately:
#   - tools/checkinit.py — pure Python regex, well under a second (see BRIEF.md)
#   - tools/test_*.js / tools/test_*.sh, if any ever land directly under tools/
#   - `npm test`, if package.json ever declares one
# The real unit suite (./gradlew testDebugUnitTest, 797 tests as of v7.7) is
# NOT run here — a cold Gradle invocation is minutes, not "well under a
# second", which is what a per-edit checkpoint needs. That's what ship.sh
# gates on; run it before a release, not after every edit.
PASS=0; FAIL=0; REDS=""; RAN_ANY=0

if [ -f tools/checkinit.py ]; then
  RAN_ANY=1
  if python3 tools/checkinit.py >/dev/null 2>&1; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); REDS="$REDS checkinit"; fi
fi

if [ -f package.json ] && command -v npm >/dev/null 2>&1 && command -v node >/dev/null 2>&1 \
   && node -e "const p=require('./package.json'); process.exit(p.scripts && p.scripts.test ? 0 : 1)" 2>/dev/null; then
  RAN_ANY=1
  if npm test --silent >/dev/null 2>&1; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); REDS="$REDS npm-test"; fi
fi

for T in tools/test_*.js tools/test_*.sh; do
  [ -f "$T" ] || continue
  RAN_ANY=1
  case "$T" in
    *.sh) RUNNER="bash" ;;
    *)    RUNNER="node" ;;
  esac
  if "$RUNNER" "$T" >/dev/null 2>&1; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); REDS="$REDS $(basename "$T")"; fi
done

if [ "$RAN_ANY" -eq 0 ]; then
  TESTS="no test suite configured yet"
elif [ "$FAIL" -eq 0 ]; then
  TESTS="all $PASS fast checks green (gradle suite: see ship.sh)"
else
  TESTS="$FAIL RED:$REDS ($PASS green)"
fi

STAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
N="$(git rev-list --count HEAD 2>/dev/null || echo 0)"
N=$((N+1))

# ---- rewrite the resume card ------------------------------------------------
# CHECKPOINT.md is regenerated every time rather than appended to, so it can
# never grow stale or contradict itself. The history lives in git log; this
# file is only ever "where things stand right now".
{
  echo "# CHECKPOINT $N — read me first, then TASKS.md"
  echo
  echo "**Written:** $STAMP · **tests:** $TESTS"
  echo
  echo "## Just done"
  echo "$DID"
  echo
  echo "## Do this next"
  echo "${NEXT:-see the first unticked box in TASKS.md}"
  echo
  echo "## How to resume, exactly"
  echo "Open this GitHub repo in a Claude Code session and say \"continue\"."
  echo "The SessionStart hook runs tools/resume.sh, which pulls the latest and"
  echo "prints this file automatically — nothing has to be attached, uploaded"
  echo "or explained. If that briefing did not appear, run it by hand:"
  echo '```bash'
  echo "bash tools/resume.sh       # pull + this file + TASKS.md + the rules"
  echo '```'
  echo "Then continue from **Do this next** above. Do not re-plan, do not re-read"
  echo "finished work, do not ask Tj to re-explain anything — \`TASKS.md\` carries his"
  echo "request in his own words and \`git log\` carries every step already taken."
  echo
  echo "## Uncommitted right now"
  if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    git status --porcelain 2>/dev/null | sed 's/^/    /'
  else
    echo "    (nothing — the tree is clean as of this checkpoint)"
  fi
  echo
  echo "## Last ten checkpoints"
  echo '```'
  # DELIBERATE CHECKPOINTS ONLY, and truncated.
  #
  # The autosave hook commits after every edit and every bash command, so a
  # plain `git log -10` would fill this block with 'auto-checkpoint: <ts>'
  # lines instead of history. Nothing is lost either way — `git log` keeps
  # everything, and the auto-checkpoints since the last deliberate one are
  # counted just below.
  git log --oneline -10 --extended-regexp --grep='^(ckpt [0-9]+:|ship v)' 2>/dev/null | cut -c1-96 | sed 's/^/  /'
  echo '```'
  AUTOS="$(git log --oneline --grep='^auto-checkpoint:' "$(git log -1 --format=%H --extended-regexp --grep='^(ckpt [0-9]+:|ship v)' 2>/dev/null)"..HEAD 2>/dev/null | wc -l | tr -d ' ')"
  if [ "${AUTOS:-0}" -gt 0 ]; then
    echo
    echo "($AUTOS automatic checkpoint(s) since the last deliberate one — the"
    echo "session was still mid-step. \`git diff\` against it shows what changed.)"
  fi
} > CHECKPOINT.md

# ---- commit -----------------------------------------------------------------
git add -A >/dev/null 2>&1

# THE ONE EXCEPTION TO "NO GATE".
# Everything above is deliberately ungated: a red suite commits, a half-written
# function commits, because a described broken state is recoverable and an
# uncommitted one is not. A live credential is a different category — once
# pushed, it has to be rotated, not deleted, because GitHub keeps the object
# reachable by SHA. So this refuses, and it is the only thing that does.
if ! bash tools/secretscan.sh; then
  git reset -q >/dev/null 2>&1
  echo "  NOTHING COMMITTED. Remove the credential above and re-run this."
  exit 1
fi

if git diff --cached --quiet 2>/dev/null; then
  echo "  ckpt $N: nothing changed on disk — no commit made"
else
  git commit -q -m "ckpt $N: $DID

next: ${NEXT:-see TASKS.md}
tests: $TESTS

Co-Authored-By: Claude <noreply@anthropic.com>" >/dev/null 2>&1
  echo "  ckpt $N committed · $TESTS"
fi
[ "$FAIL" -gt 0 ] && echo "  NOTE: red suites recorded, not hidden:$REDS"

# ---- push --------------------------------------------------------------------
# A commit that never leaves this container is not a checkpoint. Each session
# may start in a FRESH container that clones from GitHub — so anything only
# committed locally is exactly as lost as if it had never been written, the
# moment a usage cap ends the session. Best-effort: a failed push must not
# fail the checkpoint (the commit is made either way, and autosave.sh retries
# the push after the next edit).
if bash tools/push.sh; then
  echo "  pushed to GitHub — a new session resumes from here"
else
  echo "  WARN  COULD NOT PUSH. This checkpoint exists only in this container,"
  echo "        and containers do not survive the session. Retry by hand:"
  echo "          git push origin HEAD"
fi
exit 0
