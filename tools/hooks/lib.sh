#!/usr/bin/env bash
# lib.sh — shared by the three hook entry points. Sourced, never run.
#
# One job: find every checkpoint-managed repo in the container, run the same
# script in each, and hand the combined TEXT to emit.py so that exactly one
# JSON object reaches Claude Code. See tools/hooks/emit.py for why that
# matters.

# The container root. Every repo is a directory directly under it. Overridable
# so tools/test_resume.sh can point this at a fixture with two fake repos and
# actually prove the multi-repo case, rather than assuming it.
hook_root() {
  if [ -n "${CLAUDE_REPO_ROOT:-}" ]; then printf '%s' "$CLAUDE_REPO_ROOT"; return; fi
  cd "$(dirname "${BASH_SOURCE[0]}")/../.." 2>/dev/null && printf '%s' "$(dirname "$PWD")"
}

# Run "$1" (a path relative to each repo root) in every repo, in --text mode,
# and print the concatenation. $2 is the total time budget in seconds; it is
# SHARED between repos rather than per-repo, because the hook's own timeout is
# what actually gets enforced and blowing it loses everything, not just the
# slow repo. $3, if given, is fed to each script as stdin — resume/autosave/
# toobig need nothing from the event itself, so it is always empty for them.
hook_collect() {
  local script="$1" budget="${2:-100}" input="${3-}" d n per
  local -a repos=()
  local root; root="$(hook_root)"
  [ -d "$root" ] || return 0

  for d in "$root"/*/; do
    [ -d "$d/.git" ] || continue
    [ -f "$d/$script" ] || continue
    repos+=("$d")
  done
  n=${#repos[@]}
  [ "$n" -eq 0 ] && return 0

  per=$(( budget / n ))
  [ "$per" -lt 12 ] && per=12

  # Tells the scripts below that the hooks are demonstrably firing — they are
  # what is running us. Without it every repo's resume.sh would try to repair
  # the hook install on every session start, and two repos carrying different
  # versions of the template would overwrite each other's config in turn.
  export CLAUDE_HOOKS_ACTIVE=1

  for d in "${repos[@]}"; do
    local out
    # A here-string rather than a pipe so a script that never reads stdin
    # (resume/autosave/toobig) is unaffected either way.
    out="$( cd "$d" && timeout "$per" bash "$script" --text <<<"$input" 2>/dev/null || true )"
    [ -n "$out" ] || continue
    # Only label the source when there is more than one repo. With a single
    # repo — the normal case — a header is pure context cost for no
    # information, and this text is resent on every turn.
    if [ "$n" -gt 1 ]; then
      printf '===== %s =====\n' "$(basename "${d%/}")"
    fi
    printf '%s\n' "$out"
  done
}

# Emit collected text as one JSON object, or as plain text if python3 is
# missing (Claude Code accepts plain stdout as context too — losing the
# structure is survivable, losing the briefing is not).
hook_emit() {
  local mode="$1" text; text="$(cat)"
  [ -z "${text//[[:space:]]/}" ] && return 0
  local py; py="$(dirname "${BASH_SOURCE[0]}")/emit.py"
  if command -v python3 >/dev/null 2>&1 && [ -f "$py" ]; then
    printf '%s' "$text" | python3 "$py" "$mode" 2>/dev/null || printf '%s\n' "$text"
  else
    printf '%s\n' "$text"
  fi
}
