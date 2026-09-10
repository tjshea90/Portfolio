#!/usr/bin/env bash
# bootstrap.sh — cold start. Verify the checkout, then brief the session.
#
# WHY THIS PRINTS SO LITTLE
# --------------------------
# Everything printed here becomes permanent context that is resent on every
# future turn. So this stays short: where the session stopped, what is next,
# and the standing rules — once there are any. Narrative detail belongs in
# CHECKPOINT.md, TASKS.md and CLAUDE.md, not in growing this file.
#
# Unlike the fantasy-football tracker this repo is adapted from, there is no
# fixed manifest or build target here yet — the stack hasn't been decided.
# So the checks below are informational only; nothing here gates the session.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$D" || exit 1
echo "== Portfolio — bootstrap =="
echo "working dir: $D"; echo

# --- toolchain (informational only) ---
if [ -f package.json ]; then
  command -v node >/dev/null 2>&1 && echo "  OK    node $(node -v)" || echo "  WARN  package.json present but no node — installs/builds will fail"
  command -v npm  >/dev/null 2>&1 && echo "  OK    npm $(npm -v)"   || echo "  WARN  no npm"
else
  echo "  note  no package.json yet — no build tooling configured"
fi

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
echo "#  STANDING RULES  (full text: CLAUDE.md)"
echo "##############################################################################"
cat <<'SHORT'
- Checkpoint constantly:  bash tools/ckpt.sh "did" "next"   (fast, no gate)
- Write new requests into TASKS.md, in Tj's own words, before writing any code.
- No project-specific build/stack rules are recorded yet. Once the stack
  (framework, hosting/deploy target, content structure, etc.) is decided, add
  them to CLAUDE.md under "Project rules" so every future session inherits
  them automatically — this file will start printing them here too.
SHORT
