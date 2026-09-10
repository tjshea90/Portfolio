#!/usr/bin/env bash
# brief.sh — SessionStart. Brief every checkpoint-managed repo in the
# container, in one JSON object. See tools/hooks/emit.py.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
hook_collect "tools/resume.sh" "${CLAUDE_HOOK_BUDGET:-100}" | hook_emit session-start
exit 0
