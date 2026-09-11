#!/usr/bin/env bash
# screener.sh — the model-choice check. Runs from the UserPromptSubmit hook
# (tools/hooks/screen.sh), on every single message Tj sends — see
# SCREENER.md for why, and for the actual criteria.
#
# WHY THIS EXISTS
# ----------------
# Tj runs Sonnet 5 by default and wants anything that genuinely needs Opus
# flagged BEFORE any work starts, not discovered partway through, so he can
# switch models and restart the same request under the right one. A hook is
# what makes "every request" mechanically true instead of aspirational: it
# fires fresh on every message no matter how long the session has run,
# immune to compaction and to a session simply forgetting to re-check.
#
# WHAT THIS SCRIPT DOES NOT DO
# -----------------------------
# It cannot judge "is this hard enough for Opus" — that is not a grep. So it
# only ever does two cheap things: (1) a keyword hint, matched here, and (2)
# hand Claude the mandatory protocol to run for itself, pointing at
# SCREENER.md for the real criteria rather than inlining the whole file on
# every turn — this text is resent on every subsequent turn for the rest of
# the session (see tools/resume.sh's identical budget note).
#
# ALWAYS exits 0 and never blocks the prompt reaching Claude. A screener that
# can stop Tj's own message from arriving is a worse failure than one that
# occasionally fails to flag — see tools/autosave.sh for the same reasoning
# applied to commits.
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 0

TEXT_MODE=0
for a in "$@"; do [ "$a" = "--text" ] && TEXT_MODE=1; done

# The prompt arrives as the UserPromptSubmit event JSON on stdin (forwarded
# by tools/hooks/screen.sh via lib.sh's hook_collect). Also tolerate plain
# text, so this is callable by hand while testing without constructing JSON.
RAW="$(cat 2>/dev/null || true)"
PROMPT="$RAW"
if command -v python3 >/dev/null 2>&1; then
  EXTRACTED="$(printf '%s' "$RAW" | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
    sys.stdout.write(json.loads(raw).get("prompt", raw))
except Exception:
    sys.stdout.write(raw)
' 2>/dev/null || true)"
  [ -n "$EXTRACTED" ] && PROMPT="$EXTRACTED"
fi

# CHEAP HINT ONLY. SCREENER.md points here rather than duplicating this
# list, so there is exactly one place it can drift from — edit it here.
KEYWORDS=(
  keystore sideload.jks signing applicationId versionCode "ship.sh" release
  "cost basis" "tax lot" ledger accounting recommendation scoring
  "buy/hold/sell" ResearchScore consensus architecture refactor migrate
  rewrite security credential secret important critical "make sure"
  accuracy accurate irreversible
)
HIT=""
if [ -n "$PROMPT" ]; then
  for k in "${KEYWORDS[@]}"; do
    if printf '%s' "$PROMPT" | grep -qiF -- "$k" 2>/dev/null; then
      HIT="$HIT${HIT:+, }$k"
    fi
  done
fi
HINT="${HIT:-none matched}"

MSG="SCREENER (tools/screener.sh): before acting on the request above, weigh it against SCREENER.md's Opus-escalation criteria — Sonnet 5 is the default. Keyword hint: $HINT (a hint only, not a verdict — a miss is not clearance). If it matches: call get_session (session_id omitted), check session_context.model / external_metadata.last_served_model; if the name doesn't contain 'opus', STOP before any edit, build, or ship.sh/git-push, and output SCREENER.md's flag so Tj can switch models and restart. Already on Opus, no match, or Tj already overrode the flag in this conversation: proceed normally."

if [ "$TEXT_MODE" -eq 1 ]; then
  printf '%s\n' "$MSG"
elif command -v python3 >/dev/null 2>&1; then
  printf '%s' "$MSG" | python3 -c '
import json, sys
print(json.dumps({"hookSpecificOutput": {
    "hookEventName": "UserPromptSubmit",
    "additionalContext": sys.stdin.read()}}))
' 2>/dev/null || printf '%s\n' "$MSG"
else
  printf '%s\n' "$MSG"
fi
exit 0
