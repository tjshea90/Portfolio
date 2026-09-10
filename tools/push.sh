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
if git push -q origin "HEAD:refs/heads/$BR" >/dev/null 2>&1; then
  # Self-heal, so the cheap check above starts working and this stops paying
  # for a network round trip on every tool call. The root cause seen here was
  # an EMPTY remote.origin.fetch: `git clone --depth 1` of a repo with no
  # commits leaves no refspec, so `git fetch origin` only ever writes
  # FETCH_HEAD and refs/remotes/origin/* is never populated. A normal clone
  # has this set already, so this is a no-op almost everywhere.
  git config --get-all remote.origin.fetch >/dev/null 2>&1 || \
    git config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*' 2>/dev/null || true
  git fetch -q origin "$BR:refs/remotes/origin/$BR" >/dev/null 2>&1 || true
  git branch --set-upstream-to="origin/$BR" "$BR" >/dev/null 2>&1 || true
  exit 0
fi
exit 1
