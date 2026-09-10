#!/usr/bin/env bash
# secretscan.sh — refuse to commit an obvious credential.
#
# WHY THIS EXISTS
# ---------------
# autosave.sh commits with `git add -A` and pushes to GitHub without a human
# looking at the diff first. That is the whole point of it — and it is also
# exactly how a pasted API key ends up permanently in history. Once pushed,
# rotating the key is the only real remedy; deleting the commit is not,
# because GitHub keeps unreachable objects reachable by SHA.
#
# So: high-confidence patterns ONLY. A false positive here silently stops all
# auto-checkpointing, which is a worse failure than the one being prevented,
# so nothing vague ("password=", "secret") is matched — only shapes that are
# unambiguously a live credential.
#
#   bash tools/secretscan.sh          # scans STAGED changes; 0 = clean
#
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 0

# Added lines only. An existing line that already looked like this was already
# pushed long ago; re-flagging it would wedge every future checkpoint.
#
# Staged changes are what autosave.sh is about to commit, so they are the real
# subject. But a bare `bash tools/secretscan.sh` typed by hand — including the
# one autosave.sh tells you to run after it blocks, by which point it has
# already unstaged everything — must not report "clean" on an unstaged secret.
# So fall back to the whole working tree when the index is empty.
DIFF="$(git diff --cached -U0 2>/dev/null || true)"
[ -z "$DIFF" ] && DIFF="$(git diff HEAD -U0 2>/dev/null || true)"
ADDED="$(printf '%s\n' "$DIFF" | grep '^+' | grep -v '^+++' || true)"
[ -z "$ADDED" ] && exit 0

# Test fixtures are not credentials. If a test ever needs to assert that a key
# gets redacted from output, it necessarily CONTAINS a key-shaped string.
# Matching it would wedge every checkpoint the moment anyone edits near that
# line — the exact false-positive failure this file's header warns about. Two
# independent guards:
#   1. length — a real key carries dozens of chars after the prefix, fixtures
#      are usually short, so the patterns below already lean on that
#   2. these markers, for fixtures in any other shape
ADDED="$(printf '%s\n' "$ADDED" | grep -viE 'DO-?NOT-?(EXPORT|COMMIT|USE)|EXAMPLE|PLACEHOLDER|REDACTED|DUMMY|FAKE|YOUR_?KEY|XXXXX' || true)"
[ -z "$ADDED" ] && exit 0

HITS="$(printf '%s\n' "$ADDED" | grep -nE \
  -e 'sk-ant-[A-Za-z0-9_-]{40,}' \
  -e 'sk-[A-Za-z0-9_-]{32,}' \
  -e 'ghp_[A-Za-z0-9]{30,}' \
  -e 'gho_[A-Za-z0-9]{30,}' \
  -e 'github_pat_[A-Za-z0-9_]{30,}' \
  -e 'AIza[0-9A-Za-z_-]{30,}' \
  -e 'AKIA[0-9A-Z]{16}' \
  -e 'xox[baprs]-[A-Za-z0-9-]{10,}' \
  -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' \
  || true)"

[ -z "$HITS" ] && exit 0

echo "SECRET SCAN TRIPPED — nothing was committed."
printf '%s\n' "$HITS" | head -5 | cut -c1-120 | sed 's/^/    /'
echo "Remove the credential, then checkpoint again. If this is a false"
echo "positive, widen the exclusion in tools/secretscan.sh — do NOT bypass it."
exit 1
