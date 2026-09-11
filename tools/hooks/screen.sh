#!/usr/bin/env bash
# screen.sh — UserPromptSubmit. Run every checkpoint-managed repo's own
# request screener against the prompt just submitted, in one JSON object.
# See tools/hooks/emit.py for why "one object" matters, and
# tools/screener.sh / SCREENER.md for what is actually being checked.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
PROMPT_JSON="$(cat 2>/dev/null || true)"
hook_collect "tools/screener.sh" "${CLAUDE_HOOK_BUDGET:-20}" "$PROMPT_JSON" | hook_emit user-prompt-submit
exit 0
