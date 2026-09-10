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

# --text: print the briefing as plain text instead of wrapping it in JSON.
# tools/hooks/brief.sh uses this so that N repos produce ONE JSON object
# rather than N of them, which is not parseable JSON and silently cost the
# entire briefing. See tools/hooks/emit.py.
TEXT_MODE=0
for a in "$@"; do [ "$a" = "--text" ] && TEXT_MODE=1; done

# SELF-REPAIR THE SAFETY NET.
# If this is running from the hook, the hooks are obviously installed. But
# CLAUDE.md also tells a session to run this BY HAND when the briefing did not
# appear — and that is exactly the case where the hooks are missing and every
# edit for the rest of the session would go unsaved. Idempotent and silent
# when already correct, so it costs nothing in the normal case.
# CLAUDE_HOOKS_ACTIVE is set by tools/hooks/lib.sh, i.e. when this is running
# FROM the hook — in which case the hooks obviously work and there is nothing
# to repair.
[ -z "${CLAUDE_HOOKS_ACTIVE:-}" ] && bash tools/install-hooks.sh --quiet >/dev/null 2>&1
true

BRIEF="$(
  echo "=============================================================================="
  echo "  RESUMING PORTFOLIO — this repo can be worked across several Claude Code"
  echo "  sessions. A previous session may have been cut off mid-change. Read this"
  echo "  before planning anything, and do NOT re-derive work that is already"
  echo "  committed."
  echo "=============================================================================="
  echo

  if [ -d .git ]; then
    # See tools/autosave.sh for why: a lock from a hook this SESSION already
    # killed (its own 60s timeout) would otherwise wedge the 'git pull' below.
    find .git -name '*.lock' -mmin +2 -delete 2>/dev/null || true

    # --tags because GitHub creates release tags SERVER-SIDE (see CLAUDE.md's "Releasing"),
    # so a branch-only fetch would never see them and the unrecorded-release check below
    # would silently never fire.
    timeout 25 git fetch -q --tags origin >/dev/null 2>&1 || echo "  NOTE  could not reach GitHub — working from the local checkout only."

    DIRTY="$(git status --porcelain 2>/dev/null)"

    # RESOLVE THE REMOTE BRANCH THE WAY push.sh DOES, not via @{u}.
    # Claude Code assigns sessions a `claude/<id>` branch, and such a branch
    # routinely has NO upstream configured — `git rev-parse @{u}` just fails.
    # This block used to read `HEAD..@{u}`, so both counts fell through to the
    # `|| echo 0` fallback and the check reported "current" without ever
    # looking. Confirmed on this repo's own session branch. Falling back to
    # origin/<branch> is what push.sh already does, and for the same reason.
    CURBR="$(git branch --show-current 2>/dev/null || true)"
    UP="$(git rev-parse --abbrev-ref '@{u}' 2>/dev/null || true)"
    [ -z "$UP" ] && [ -n "$CURBR" ] && UP="origin/$CURBR"
    if [ -n "$UP" ] && git rev-parse --verify -q "$UP" >/dev/null 2>&1; then
      BEHIND="$(git rev-list --count "HEAD..$UP" 2>/dev/null || echo 0)"
      AHEAD="$(git rev-list --count "$UP..HEAD" 2>/dev/null || echo 0)"
    else
      BEHIND=0; AHEAD=0
      if [ -n "$CURBR" ] && [ "$CURBR" != "main" ]; then
        echo "  WARN  this branch ($CURBR) does not exist on GitHub yet — nothing on it"
        echo "        is backed up. tools/push.sh creates it on the next checkpoint."
      fi
    fi

    # Fast-forward only, and only from a clean tree. A merge here could conflict
    # on a half-finished change from the session that just died, which is the
    # worst possible moment to ask a cold session to resolve one.
    if [ -z "$DIRTY" ] && [ "${BEHIND:-0}" -gt 0 ] && [ "${AHEAD:-0}" -eq 0 ]; then
      if timeout 25 git merge -q --ff-only "$UP" >/dev/null 2>&1; then
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

    # IS main CURRENT? Claude Code can assign a different local branch name to
    # every session — this repo has seen it happen. tools/push.sh keeps `main`
    # fast-forwarded to match every push specifically so a session (or account)
    # that just opens the repo, rather than rediscovering a specific branch
    # name, still lands on the real state. This check exists because that sync
    # can only fail SILENTLY (push.sh never surfaces it): main sat 565 commits
    # behind for the length of a whole migration before anyone noticed.
    if [ "$CURBR" != "main" ] && git rev-parse --verify -q origin/main >/dev/null 2>&1; then
      MBEHIND="$(git rev-list --count origin/main..HEAD 2>/dev/null || echo 0)"
      MAHEAD="$(git rev-list --count HEAD..origin/main 2>/dev/null || echo 0)"
      if [ "${MAHEAD:-0}" -gt 0 ] && [ "${MBEHIND:-0}" -eq 0 ]; then
        # A CLEAN fast-forward: this branch's tip is an ancestor of main, so
        # main has strictly MORE — most likely another session/account pushed
        # newer work there. Pull it in now rather than just warning, the same
        # way the upstream check above does, and for the same reason: "another
        # account has newer work" must not require a manual step to see it.
        if [ -z "$DIRTY" ]; then
          if timeout 25 git merge -q --ff-only origin/main >/dev/null 2>&1; then
            echo "  OK    origin/main had $MAHEAD newer commit(s) (another session/account) —"
            echo "        merged in, this checkout is now current."
          else
            echo "  WARN  origin/main has $MAHEAD commit(s) this branch doesn't, and the"
            echo "        fast-forward failed unexpectedly — check 'git log origin/main' by hand."
          fi
        else
          echo "  WARN  origin/main has $MAHEAD newer commit(s) (another session/account) but"
          echo "        this tree is dirty, so it was NOT auto-merged — finish or checkpoint"
          echo "        current work first, then 'git merge --ff-only origin/main'."
        fi
      elif [ "${MAHEAD:-0}" -gt 0 ]; then
        echo "  WARN  this branch and origin/main have DIVERGED ($MBEHIND local, $MAHEAD on"
        echo "        main) — both have commits the other lacks. Reconcile by hand before"
        echo "        trusting either as the full state."
      elif [ "${MBEHIND:-0}" -gt 0 ]; then
        echo "  WARN  origin/main is $MBEHIND commit(s) behind this branch — a fresh"
        echo "        session opening this repo cold would miss real work. Run"
        echo "        'git push origin HEAD:main' (tools/push.sh should do this"
        echo "        automatically on the next checkpoint; say so if it doesn't)."
      else
        echo "  OK    origin/main is current with this branch."
      fi
    fi

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
      echo "        none appear over a few tool calls, the hooks are not firing —"
      echo "        run 'bash tools/install-hooks.sh' and say so rather than"
      echo "        working on unprotected."
    fi

    # IS THERE A RELEASE GITHUB BUILT THAT NOBODY RECORDED?
    #
    # The release flow has a seam: GitHub builds and publishes, then a SECOND command
    # (tools/record-release.sh) writes the line into BUILDLOG.md. A usage cap landing in
    # between leaves a shipped version that BUILDLOG does not know about - and BUILDLOG is
    # what the NEXT release's versionCode is gated against, in ship.sh and in the workflow
    # alike. So the next version would be allowed to reuse a code that is already on the
    # phone, and Android would refuse to install it.
    #
    # Nothing else reports this: the tree is clean, the tests are green, and the only sign
    # is a tag with no matching BUILDLOG line. Cheap to check, and it is checked from the
    # TAGS, which is what GitHub actually creates.
    VC="$(grep -m1 -oE 'versionCode *= *[0-9]+' app/build.gradle.kts 2>/dev/null | grep -oE '[0-9]+' || true)"
    VN="$(grep -m1 -oE 'versionName *= *"[^"]+"' app/build.gradle.kts 2>/dev/null | grep -oE '"[^"]+"' | tr -d '"' || true)"
    if [ -n "$VN" ] && git rev-parse -q --verify "refs/tags/v$VN" >/dev/null 2>&1; then
      if ! grep -q "^| v${VN} |" BUILDLOG.md 2>/dev/null; then
        echo
        echo "  !!    v$VN WAS RELEASED BUT NEVER RECORDED."
        echo "        GitHub built and published tag v$VN, but BUILDLOG.md has no line for"
        echo "        it - a session was cut off between the two. BUILDLOG is what the NEXT"
        echo "        release's versionCode is gated against, so leaving it means the next"
        echo "        version can reuse code $VC and the phone will refuse the install."
        echo
        echo "          bash tools/record-release.sh v$VN \"what that release changed\""
        echo
      fi
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
    LASTCKPT="$(git log -1 --format=%H --extended-regexp --grep='^(ckpt [0-9]+:|ship v)' 2>/dev/null || true)"
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
if [ "$TEXT_MODE" -eq 1 ]; then
  printf '%s\n' "$BRIEF"
elif command -v python3 >/dev/null 2>&1; then
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
