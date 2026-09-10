#!/usr/bin/env bash
# push.sh — get commits to GitHub, and never decide "no" by accident.
#
# WHY THIS IS ITS OWN FILE
# ------------------------
# Both ckpt.sh and autosave.sh need to answer "is there anything to push?"
# cheaply, because autosave runs after every single tool call and a no-op
# network round trip on each one is latency paid hundreds of times a session
# for nothing.
#
# The obvious test — `git rev-list --count @{u}..HEAD` — has a failure mode
# that's worth guarding against. `@{u}` needs a remote-TRACKING ref
# (refs/remotes/origin/main). A checkout whose branch was created from
# FETCH_HEAD has branch.main.remote and branch.main.merge set, looks
# completely normal to `git remote show`, and still has no such ref — so
# `@{u}` errors, the `|| echo 0` fallback reads as "nothing to push", and the
# push is skipped in silence. The commit is made, the session reports success,
# and the work never leaves the container. On a usage cap that is total loss.
#
# So the rule here: when the count cannot be determined, PUSH. A wasted push
# costs a second; a skipped one costs the session.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 0
[ -d .git ] || exit 0

BR="$(git branch --show-current 2>/dev/null || true)"
[ -z "$BR" ] && exit 0   # detached HEAD — no branch to push to

UP="$(git rev-parse --abbrev-ref '@{u}' 2>/dev/null || true)"
[ -z "$UP" ] && UP="origin/$BR"

if git rev-parse --verify -q "$UP" >/dev/null 2>&1; then
  AHEAD="$(git rev-list --count "$UP"..HEAD 2>/dev/null || echo 1)"
else
  AHEAD=1
fi
[ "${AHEAD:-1}" -eq 0 ] && exit 0

# Explicit destination: works whether or not tracking is configured.
# `timeout` bounds this well under the caller's 60s hook budget — a hung
# connection should fail fast and cleanly (commit stays local, retried on the
# next tool call) rather than silently eating the whole hook slot with no
# result either way.
if timeout 45 git push -q origin "HEAD:refs/heads/$BR" >/dev/null 2>&1; then
  # Self-heal, so the cheap check above starts working and this stops paying
  # for a network round trip on every tool call. The root cause seen here was
  # an EMPTY remote.origin.fetch: `git clone --depth 1` of a repo with no
  # commits leaves no refspec, so `git fetch origin` only ever writes
  # FETCH_HEAD and refs/remotes/origin/* is never populated. A normal clone
  # has this set already, so this is a no-op almost everywhere.
  git config --get-all remote.origin.fetch >/dev/null 2>&1 || \
    git config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*' 2>/dev/null || true
  timeout 10 git fetch -q origin "$BR:refs/remotes/origin/$BR" >/dev/null 2>&1 || true
  git branch --set-upstream-to="origin/$BR" "$BR" >/dev/null 2>&1 || true

  # KEEP main CURRENT, BEST-EFFORT. Claude Code can assign a different local
  # branch name to every session (this repo has seen it happen), so a session
  # that only pushes its own branch leaves the DEFAULT branch stale — and a
  # future session/account that just opens the repo (rather than rediscovering
  # a specific branch name) lands on that stale default and sees none of this.
  # Genuinely lost this once: main sat 565 commits behind for the length of a
  # whole migration before anyone noticed.
  #
  # Plain (non-force) push, so this can only FAST-FORWARD main. If main ever
  # diverges (something else was pushed there independently), git refuses and
  # this fails silently — never overwrite unrelated work to keep a convenience
  # in sync.
  if [ "$BR" != "main" ]; then
    timeout 10 git push -q origin "HEAD:refs/heads/main" >/dev/null 2>&1 || true
  fi
  exit 0
fi
exit 1
