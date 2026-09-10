#!/usr/bin/env bash
# toobig.sh — fires when the conversation has grown enough to auto-compact.
#
# WHY THIS EXISTS
# ---------------
# Every turn resends the whole conversation, and prompt caching only makes
# that cheap while the cache is WARM. Come back to a big session hours later
# and the cache has expired: the entire conversation is re-read at full price
# before a single new word is written.
#
# Compaction is not the escape either. It REWRITES the earlier messages, which
# is itself a cold start on the rewritten portion, and it summarises away
# detail that CHECKPOINT.md holds losslessly on disk anyway.
#
# The cheap move is to stop and start a fresh session: tools/resume.sh rebuilds
# everything a session needs from GitHub in well under a hundred lines, versus
# re-reading a conversation that by this point is far larger than that.
#
# So: guarantee everything is saved, then say so plainly.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 0

TEXT_MODE=0
for a in "$@"; do [ "$a" = "--text" ] && TEXT_MODE=1; done

bash tools/autosave.sh --text >/dev/null 2>&1 || true

# Was '@{u}'..HEAD, which fails outright on a Claude-Code-assigned
# `claude/<id>` branch (no upstream configured) and printed a literal "?" —
# so the one line that is supposed to tell you whether your work is safe read
# "WARNING: ? commit(s) are not pushed". tools/unpushed.sh resolves the remote
# branch the way push.sh does.
UNPUSHED="$(bash tools/unpushed.sh 2>/dev/null || echo '?')"
if [ "$UNPUSHED" = "0" ]; then
  STATE="Everything is committed and pushed to GitHub."
else
  STATE="WARNING: $UNPUSHED commit(s) are not pushed yet — run: git push origin HEAD"
fi

MSG="This session is now large enough to auto-compact, which is the point where it starts costing real usage: every turn resends the whole conversation, and compaction rewrites it rather than shrinking what you pay for. $STATE  Cheapest next move: run  bash tools/ckpt.sh \"what I just did\" \"what comes next\"  and then START A NEW SESSION. It resumes from GitHub in well under a hundred lines instead of re-reading this entire conversation. Nothing is lost by doing that."
if [ "$TEXT_MODE" -eq 1 ]; then
  printf '%s\n' "$MSG"
else
  printf '%s' "$MSG" | python3 -c 'import json,sys; print(json.dumps({"systemMessage": sys.stdin.read()}))' 2>/dev/null \
    || printf '%s\n' "$MSG"
fi
exit 0
