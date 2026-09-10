#!/usr/bin/env bash
# big.sh — PreCompact. Save everything, then advise on the cheaper move.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
hook_collect "tools/toobig.sh" "${CLAUDE_HOOK_BUDGET:-70}" | hook_emit system-message
exit 0
