#!/usr/bin/env bash
# save.sh — PostToolUse + Stop. Autosave every repo; speak only if something
# went wrong (a blocked secret, a failed push). Silence is the normal case and
# must stay free.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
hook_collect "tools/autosave.sh" "${CLAUDE_HOOK_BUDGET:-70}" | hook_emit system-message
exit 0
