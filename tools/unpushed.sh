#!/usr/bin/env bash
# unpushed.sh — how many commits exist ONLY in this container?
#
# One number, three callers (autosave.sh, toobig.sh, test_resume.sh), and one
# rule: never answer "0" unless it is provably 0.
#
# `git rev-list --count @{u}..HEAD` was used inline in two places and is wrong
# on exactly the branch Claude Code hands out. A `claude/<session-id>` branch
# routinely has NO upstream configured, so @{u} errors and the surrounding
# `|| echo` fallback turned that into "?" or "0" — i.e. "your work is safe" —
# at the one moment it is not. Resolve the remote branch the way push.sh does.
#
# Prints a count and exits 0, or exits 1 when it genuinely cannot tell.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 1
[ -d .git ] || exit 1

BR="$(git branch --show-current 2>/dev/null || true)"
UP="$(git rev-parse --abbrev-ref '@{u}' 2>/dev/null || true)"
[ -z "$UP" ] && [ -n "$BR" ] && UP="origin/$BR"

if [ -n "$UP" ] && git rev-parse --verify -q "$UP" >/dev/null 2>&1; then
  git rev-list --count "$UP..HEAD" 2>/dev/null && exit 0
fi

# The branch has never been pushed, so nothing on it is on GitHub. origin/main
# is the next best yardstick — push.sh mirrors every push there, so commits
# ahead of it are commits no other session can see.
if git rev-parse --verify -q origin/main >/dev/null 2>&1; then
  git rev-list --count origin/main..HEAD 2>/dev/null && exit 0
fi
exit 1
