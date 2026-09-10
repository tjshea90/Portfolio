#!/usr/bin/env bash
# resume.sh — the cold-start handoff. Runs from the SessionStart hook.
#
# WHY THIS EXISTS
# ---------------
# A session can be killed by a usage cap at any point, with no warning. The
# next session — possibly a different account, a different device, hours or
# days later — opens this repo COLD: no memory of the conversation, no idea a
# previous session existed. Everything it needs has to already be on disk and
# has to arrive without Tj typing an explanation, because the whole point is
# that he does not have to remember one.
#
# So this prints the briefing into the new session's context automatically:
# where the last session stopped (CHECKPOINT.md), what the job is (TASKS.md),
# the working agreement, and — the part bootstrap.sh cannot know — whether
# this checkout is actually current with GitHub, and whether the automatic
# checkpointing was still working when the last session died.
#
# BUDGET. Everything printed here is re-sent on every subsequent turn. Keep
# this short — narrative detail belongs in CHECKPOINT.md/TASKS.md/CLAUDE.md,
# not in growing this file.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 0

BRIEF="$(
  echo "=============================================================================="
  echo "  RESUMING PORTFOLIO — this repo can be worked across several Claude Code"
  echo "  sessions. A previous session may have been cut off mid-change. Read this"
  echo "  before planning anything, and do NOT re-derive work that is already"
  echo "  committed."
  echo "=============================================================================="
  echo

  if [ -d .git ]; then
    timeout 25 git fetch -q origin >/dev/null 2>&1 || echo "  NOTE  could not reach GitHub — working from the local checkout only."

    DIRTY="$(git status --porcelain 2>/dev/null)"
    BEHIND="$(git rev-list --count HEAD..@{u} 2>/dev/null || echo 0)"
    AHEAD="$(git rev-list --count @{u}..HEAD 2>/dev/null || echo 0)"

    # Fast-forward only, and only from a clean tree. A merge here could conflict
    # on a half-finished change from the session that just died, which is the
    # worst possible moment to ask a cold session to resolve one.
    if [ -z "$DIRTY" ] && [ "${BEHIND:-0}" -gt 0 ] && [ "${AHEAD:-0}" -eq 0 ]; then
      if timeout 25 git pull -q --ff-only >/dev/null 2>&1; then
        echo "  OK    pulled $BEHIND new commit(s) from GitHub — this checkout is now current."
      fi
    elif [ "${BEHIND:-0}" -gt 0 ] && [ "${AHEAD:-0}" -gt 0 ]; then
      echo "  WARN  this branch has DIVERGED from GitHub ($AHEAD local, $BEHIND remote)."
      echo "        Two sessions were probably running at once. Reconcile before working."
    elif [ "${BEHIND:-0}" -gt 0 ]; then
      echo "  WARN  $BEHIND commit(s) behind GitHub and the tree is dirty — do not"
      echo "        start new work until this is reconciled, or the two will conflict."
    fi
    [ "${AHEAD:-0}" -gt 0 ] && echo "  NOTE  $AHEAD commit(s) not yet pushed — 'git push origin HEAD' when convenient."

    # Is the safety net actually running? A hook that silently stopped firing
    # looks exactly like a session that made no edits, and the difference is
    # everything. Say it out loud so a broken hook is caught on the next start
    # rather than discovered after a cap eats an hour of work.
    LASTAUTO="$(git log -1 --format=%cr --grep='^auto-checkpoint:' 2>/dev/null || true)"
    NAUTO="$(git log --oneline --grep='^auto-checkpoint:' 2>/dev/null | wc -l | tr -d ' ')"
    if [ -n "$LASTAUTO" ]; then
      echo "  OK    auto-checkpointing is live ($NAUTO so far, most recent $LASTAUTO)."
    else
      echo "  NOTE  no auto-checkpoint commits yet. If this session makes edits and"
      echo "        none appear, the hook in .claude/settings.json is not firing —"
      echo "        say so rather than working on unprotected."
    fi

    # WAS THE LAST SESSION CUT OFF MID-CHANGE?
    # This is the question CHECKPOINT.md cannot answer about itself. The
    # autosave hook commits after every edit, so a session killed by a usage
    # cap leaves a CLEAN tree whose HEAD is a half-finished change — it looks
    # exactly like a finished piece of work, and CHECKPOINT.md still describes
    # the state as of the last DELIBERATE checkpoint, which may be several
    # steps behind. Without this warning the next session reads a stale
    # "Do this next", assumes everything up to HEAD is done, and builds on top
    # of a half-written change.
    #
    # The tell: commits after the newest 'ckpt N:'. That's the only one a
    # session makes on purpose.
    LASTCKPT="$(git log -1 --format=%H --extended-regexp --grep='^ckpt [0-9]+:' 2>/dev/null || true)"
    if [ -n "$LASTCKPT" ]; then
      SINCE="$(git rev-list --count "$LASTCKPT"..HEAD 2>/dev/null || echo 0)"
      if [ "${SINCE:-0}" -gt 0 ]; then
        echo
        echo "  !!    THE LAST SESSION WAS INTERRUPTED MID-CHANGE."
        echo "        $SINCE automatic checkpoint(s) were saved AFTER the last"
        echo "        deliberate one, which means the session stopped without"
        echo "        finishing a step — almost certainly a usage cap."
        echo
        echo "        CHECKPOINT.md below describes the last DELIBERATE"
        echo "        checkpoint, NOT the current HEAD. The code in these files"
        echo "        may be half-written. Read the change before trusting it:"
        echo
        echo "          git diff $(git rev-parse --short "$LASTCKPT")..HEAD"
        echo
        git diff --stat "$LASTCKPT"..HEAD 2>/dev/null | tail -15 | sed 's/^/          /'
        echo
        echo "        Finish that change first, then checkpoint properly with"
        echo "        tools/ckpt.sh before starting anything new."
      fi
    fi

    if [ -n "$DIRTY" ]; then
      echo
      echo "  !!    UNCOMMITTED WORK IS PRESENT — even the autosave hook did not"
      echo "        get to this. 'git diff' is what was in flight; read it before"
      echo "        deciding anything. It is probably the task you are resuming."
      printf '%s\n' "$DIRTY" | head -20 | sed 's/^/          /'
    fi
    echo
  fi

  bash bootstrap.sh 2>&1 || true
)"

# Claude Code takes SessionStart stdout as context. JSON with additionalContext
# is the documented path; plain text is the fallback when python3 is absent —
# never emit both, that would make the JSON unparseable and lose the briefing
# entirely.
if command -v python3 >/dev/null 2>&1; then
  printf '%s' "$BRIEF" | python3 -c '
import json, sys
print(json.dumps({"hookSpecificOutput": {
    "hookEventName": "SessionStart",
    "additionalContext": sys.stdin.read()}}))
' 2>/dev/null || printf '%s\n' "$BRIEF"
else
  printf '%s\n' "$BRIEF"
fi
exit 0
