#!/usr/bin/env bash
# test_screener.sh — prove the model screener actually fires, on every
# checkpoint. Same reasoning as test_resume.sh: everything here guards
# against a SILENT failure (a hook that stopped firing looks identical to a
# session that simply had no hard requests come in), so this runs on every
# tools/ckpt.sh and must stay fast and hermetic — no network, never touches
# the real settings file.
#
#   bash tools/test_screener.sh        # 0 = the screener is intact
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 1

# See test_resume.sh — a fixture below can end up re-running tools under
# itself; this stops that from ever recursing.
[ -n "${RESUME_SELFTEST:-}" ] && exit 0
export RESUME_SELFTEST=1

FAILED=0
ok()   { printf '  ok    %s\n' "$1"; }
bad()  { printf '  FAIL  %s\n' "$1"; FAILED=$((FAILED+1)); }
check(){ if [ "$1" = "0" ]; then ok "$2"; else bad "$2${3:+ — $3}"; fi; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t

# ---- 1. JSON mode: one parseable UserPromptSubmit object -------------------
PROMPT_JSON='{"session_id":"x","prompt":"bump versionCode and touch app/sideload.jks signing"}'
printf '%s' "$PROMPT_JSON" | bash tools/screener.sh >"$TMP/out1.json" 2>/dev/null
python3 - "$TMP/out1.json" <<'PY' >/dev/null 2>&1
import json, sys
d = json.load(open(sys.argv[1]))
assert d["hookSpecificOutput"]["hookEventName"] == "UserPromptSubmit"
ctx = d["hookSpecificOutput"]["additionalContext"]
assert ctx.strip()
assert "SCREENER.md" in ctx
PY
check $? "screener.sh emits one parseable UserPromptSubmit object"

# ---- 2. --text mode: plain text, no JSON wrapper ----------------------------
TXT="$(printf '%s' "$PROMPT_JSON" | bash tools/screener.sh --text 2>/dev/null)"
if printf '%s' "$TXT" | head -c1 | grep -q '{'; then
  bad "screener.sh --text still wrapped the reminder in JSON"
else
  [ -n "$TXT" ] && ok "screener.sh --text emits plain text" || bad "screener.sh --text emitted nothing"
fi

# ---- 3. the keyword hint actually names what it matched ---------------------
case "$TXT" in
  *"sideload.jks"*|*"signing"*|*"versionCode"*) ok "keyword hint names a real trigger in the prompt" ;;
  *) bad "a prompt naming the keystore and versionCode produced no keyword hint" ;;
esac

QUIET_JSON='{"session_id":"x","prompt":"make the ticker font a bit smaller on the watchlist row"}'
QTXT="$(printf '%s' "$QUIET_JSON" | bash tools/screener.sh --text 2>/dev/null)"
case "$QTXT" in
  *"none matched"*) ok "a routine UI prompt gets an honest 'none matched' hint, not a false trigger" ;;
  *) bad "a routine prompt's keyword hint was something other than 'none matched'" ;;
esac

# ---- 4. never blocks — always exits 0, even on garbage input ---------------
printf '%s' 'not json at all {{{' | bash tools/screener.sh >/dev/null 2>&1
check $? "screener.sh exits 0 on malformed input rather than blocking the prompt"

printf '' | bash tools/screener.sh --text >/dev/null 2>&1
check $? "screener.sh exits 0 on empty stdin"

# ---- 5. the coordinator combines repos into ONE object, like brief.sh ------
FAKE="$TMP/root"; mkdir -p "$FAKE"
for r in repoA repoB; do
  mkdir -p "$FAKE/$r"
  cp -r tools "$FAKE/$r/tools"
  rm -f "$FAKE/$r"/tools/test_*.sh
  ( cd "$FAKE/$r" && git init -q . && git add -A >/dev/null 2>&1 && git commit -qm init >/dev/null 2>&1 )
done
printf '%s' "$PROMPT_JSON" | CLAUDE_REPO_ROOT="$FAKE" bash "$FAKE/repoA/tools/hooks/screen.sh" >"$TMP/out2.json" 2>/dev/null
python3 - "$TMP/out2.json" <<'PY' >/dev/null 2>&1
import json, sys
d = json.load(open(sys.argv[1]))          # fails outright on two concatenated objects
c = d["hookSpecificOutput"]["additionalContext"]
assert "repoA" in c and "repoB" in c, "both repos must appear"
PY
check $? "two repos' screeners still emit ONE parseable object, both represented"

# ---- 6. install-hooks.sh actually installs the UserPromptSubmit entry ------
SET="$TMP/settings.json"
cat > "$SET" <<'JSON'
{"permissions": {"allow": ["Bash(ls:*)"]}, "hooks": {}}
JSON
CLAUDE_HOOK_SETTINGS="$SET" CLAUDE_REPO_ROOT="$FAKE" bash tools/install-hooks.sh --quiet >/dev/null 2>&1
python3 - "$SET" <<'PY' >/dev/null 2>&1
import json, sys
d = json.load(open(sys.argv[1]))
cmds = [h["command"] for e in d["hooks"]["UserPromptSubmit"] for h in e["hooks"]]
assert any("screen.sh" in c for c in cmds), "the screener hook was not installed"
assert all("__REPO_ROOT__" not in c for c in cmds), "placeholder left unsubstituted"
assert d["permissions"]["allow"] == ["Bash(ls:*)"], "an unrelated setting was destroyed"
PY
check $? "install-hooks.sh installs the UserPromptSubmit entry without touching other settings"

# A second install must not duplicate the entry.
CLAUDE_HOOK_SETTINGS="$SET" CLAUDE_REPO_ROOT="$FAKE" bash tools/install-hooks.sh --quiet >/dev/null 2>&1
python3 - "$SET" <<'PY' >/dev/null 2>&1
import json, sys
d = json.load(open(sys.argv[1]))
cmds = [h["command"] for e in d["hooks"]["UserPromptSubmit"] for h in e["hooks"]]
assert len(cmds) == 1, "re-installing duplicated the UserPromptSubmit entry: %r" % cmds
PY
check $? "re-running install-hooks does not duplicate the screener entry"

# ---- 7. both settings templates actually declare the event -----------------
python3 -c "
import json
d = json.load(open('tools/session-root-hooks.json'))
assert 'UserPromptSubmit' in d['hooks'], 'template has no UserPromptSubmit event'
cmd = d['hooks']['UserPromptSubmit'][0]['hooks'][0]['command']
assert 'tools/hooks/screen.sh' in cmd
assert 'portfolio-checkpoint-hooks' in cmd
" 2>/dev/null
check $? "session-root-hooks.json declares UserPromptSubmit through the coordinator"

python3 -c "
import json
d = json.load(open('.claude/settings.json'))
assert 'UserPromptSubmit' in d['hooks'], 'this repo\'s own settings.json has no UserPromptSubmit event'
cmd = d['hooks']['UserPromptSubmit'][0]['hooks'][0]['command']
assert 'tools/screener.sh' in cmd
" 2>/dev/null
check $? "this repo's own .claude/settings.json wires the screener directly too"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "  screener: all checks green"
  exit 0
fi
echo "  screener: $FAILED CHECK(S) FAILED — the model screener is not safe to trust"
exit 1
