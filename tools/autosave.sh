#!/usr/bin/env bash
# autosave.sh — the automatic safety net. Called by hooks, never by hand.
#
# WHY THIS IS NOT ckpt.sh
# -----------------------
# ckpt.sh is the DELIBERATE checkpoint: it runs whatever tests exist, and it
# rewrites CHECKPOINT.md with "what I just did" and "what comes next" — the
# two things no diff can reconstruct. It needs a session that knows its own
# intent.
#
# This runs from a hook, after every file edit and every bash command, with no
# idea what the session is trying to do. So it does the opposite:
#   - no test run (it fires constantly; it must take milliseconds)
#   - it NEVER touches CHECKPOINT.md — overwriting a real "Do this next" with
#     an invented one would destroy the exact thing the next session needs
#   - no gate of any kind: a broken half-edit that is COMMITTED is recoverable,
#     the same half-edit uncommitted dies with the session
#
# It exists for one failure: the usage cap landing mid-change. Everything up to
# the last completed tool call is already on GitHub when that happens.
#
# ALWAYS exits 0. A checkpoint that breaks the session it is protecting is
# worse than no checkpoint.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 0
[ -d .git ] || exit 0

# --text: emit warnings as plain text rather than as a JSON systemMessage, so
# tools/hooks/save.sh can combine several repos into ONE JSON object. Two JSON
# objects on hook stdout do not parse — see tools/hooks/emit.py.
TEXT_MODE=0
for a in "$@"; do [ "$a" = "--text" ] && TEXT_MODE=1; done

# Say something, in whichever form the caller needs. Warnings from this script
# are the only thing standing between a silent failure and a lost session, so
# they must never be dropped just because the wrapper changed.
say() {
  if [ "$TEXT_MODE" -eq 1 ]; then
    printf '%s\n' "$1"
  else
    printf '%s' "$1" | python3 -c 'import json,sys; print(json.dumps({"systemMessage": sys.stdin.read()}))' 2>/dev/null \
      || printf '%s\n' "$1"
  fi
}

# A STALE LOCK MUST NEVER WEDGE THE SAFETY NET.
# This hook has a 60s timeout. If Claude Code kills it mid `git commit`
# (network hiccup, slow disk, whatever), git can leave a `*.lock` file
# behind — and every commit for the REST OF THE SESSION then fails silently
# until something removes it, which is exactly the "usage ran out and
# nothing after that point was saved" failure this file exists to prevent.
# A fresh container never inherits one from a past session (nothing here
# is tracked in git, so a clone can't carry it in) — this only guards
# against THIS session's own hook timing out. Anything older than the
# timeout is unambiguously dead, not just slow, so it's safe to clear.
find .git -name '*.lock' -mmin +2 -delete 2>/dev/null || true

git add -A >/dev/null 2>&1

if ! git diff --cached --quiet 2>/dev/null; then
  if bash tools/secretscan.sh >.git/autosave-scan.log 2>&1; then
    git commit -q -m "auto-checkpoint: $(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      -m "Automatic hook checkpoint — not a reviewed commit. See CHECKPOINT.md
for where the session actually stands." >/dev/null 2>&1
  else
    # Unstage so a later deliberate ckpt.sh does not inherit the staged secret,
    # and make it LOUD: a silent skip here would look identical to working.
    # The detail stays in .git/autosave-scan.log (untracked, never pushed).
    git reset -q >/dev/null 2>&1
    say "AUTOSAVE BLOCKED in $(basename "$D"): what looks like a live credential is in the working tree, so nothing was committed or pushed. Run: bash tools/secretscan.sh -- and remove the credential. Auto-checkpointing stays off until it is clean."
    exit 0
  fi
fi

# push.sh skips the network when there is provably nothing to push, and pushes
# whenever it cannot prove that. Read the header there before "optimising" it —
# the obvious version of this check fails silently on a checkout with no
# remote-tracking ref, which is the one failure this whole file exists to stop.
if ! bash tools/push.sh >/dev/null 2>&1; then
  # A FAILED PUSH MUST NEVER BE SILENT.
  # Committing locally and failing to push looks identical to working: the tree
  # is clean, CHECKPOINT.md updates, every status line says saved. But the
  # container is destroyed when the session ends, so those commits are as lost
  # as work never written — and nobody finds out until the next session clones
  # and the work simply is not there.
  N="$(bash tools/unpushed.sh 2>/dev/null || echo 'Some')"
  say "PUSH TO GITHUB IS FAILING in $(basename "$D"). $N commit(s) exist ONLY in this container and will be LOST when the session ends — the work is committed locally but is NOT on GitHub, so a new session will not see it. Check the connection, then run:  git push origin HEAD"
fi

exit 0
