#!/usr/bin/env bash
# test_resume.sh — prove the resume system still works, on every checkpoint.
#
# WHY THIS EXISTS
# ---------------
# Everything under tools/ exists to survive an interruption, and every failure
# it guards against is SILENT: a briefing that does not parse, a hook that was
# never installed, a "0 commits unpushed" that was really "I could not tell".
# Nothing about the repo looks wrong when one of these breaks — you find out
# when a session is lost, which is exactly too late.
#
# tools/ckpt.sh auto-discovers tools/test_*.sh, so this runs on every single
# checkpoint. That means it must be FAST (well under a second), hermetic (it
# must never touch the real settings file, the real repo state, or the
# network) and quiet unless something fails.
#
#   bash tools/test_resume.sh        # 0 = the handoff is intact
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 1

# ckpt.sh runs every tools/test_*.sh, and one of these cases runs ckpt.sh
# inside a fixture — without this guard that recurses forever.
[ -n "${RESUME_SELFTEST:-}" ] && exit 0
export RESUME_SELFTEST=1

FAILED=0
ok()   { printf '  ok    %s\n' "$1"; }
bad()  { printf '  FAIL  %s\n' "$1"; FAILED=$((FAILED+1)); }
check(){ if [ "$1" = "0" ]; then ok "$2"; else bad "$2${3:+ — $3}"; fi; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t

# ---- 1. everything parses ----------------------------------------------------
# A syntax error in a hook script is invisible: hooks swallow stderr, so the
# safety net just quietly stops existing.
RC=0
for f in tools/*.sh tools/hooks/*.sh ship.sh bootstrap.sh; do
  [ -f "$f" ] || continue
  bash -n "$f" 2>/dev/null || { echo "      bad syntax: $f"; RC=1; }
done
python3 -c "import ast,sys; [ast.parse(open(f).read()) for f in sys.argv[1:]]" \
  tools/*.py tools/hooks/*.py 2>/dev/null || RC=1
python3 -c "import json; json.load(open('tools/session-root-hooks.json'))" 2>/dev/null || RC=1
check "$RC" "every script and the hook template parse"

# ---- 2. fixtures --------------------------------------------------------------
# Two throwaway repos that look like this one. Everything below runs against
# these, never against the real checkout and never against the network: a repo
# with no remote makes `git fetch` fail instantly, whereas the real one would
# sit on a 25s timeout per call whenever GitHub is unreachable — and this test
# runs on EVERY checkpoint, so that would turn a fast checkpoint into a
# minute-and-a-half one at exactly the wrong moment.
FAKE="$TMP/root"; mkdir -p "$FAKE"
for r in repoA repoB; do
  mkdir -p "$FAKE/$r"
  cp -r tools "$FAKE/$r/tools"
  rm -f "$FAKE/$r"/tools/test_*.sh
  cp CHECKPOINT.md TASKS.md bootstrap.sh "$FAKE/$r/" 2>/dev/null || true
  ( cd "$FAKE/$r" && git init -q . && git add -A >/dev/null 2>&1 && git commit -qm init >/dev/null 2>&1 )
done
RA="$FAKE/repoA"

# ---- 3. the briefing is exactly one JSON object ------------------------------
# Hook stdout is parsed as ONE JSON document. This is the check that would have
# caught the multi-repo bug: N repos used to print N objects, which is not JSON,
# and the whole session briefing was dropped in silence.
( cd "$RA" && bash tools/resume.sh ) >"$TMP/brief.json" 2>/dev/null
python3 - "$TMP/brief.json" <<'PY' >/dev/null 2>&1
import json,sys
d=json.load(open(sys.argv[1]))
assert d["hookSpecificOutput"]["hookEventName"]=="SessionStart"
assert d["hookSpecificOutput"]["additionalContext"].strip()
PY
check $? "resume.sh emits one parseable SessionStart object"

( cd "$RA" && bash tools/resume.sh --text ) >"$TMP/brief.txt" 2>/dev/null
if head -c 1 "$TMP/brief.txt" | grep -q '{'; then
  bad "resume.sh --text still wrapped the briefing in JSON"
else
  [ -s "$TMP/brief.txt" ] && ok "resume.sh --text emits plain text" || bad "resume.sh --text emitted nothing"
fi

CLAUDE_REPO_ROOT="$FAKE" CLAUDE_HOOK_SETTINGS="$TMP/settings.json" \
  bash "$RA/tools/hooks/brief.sh" >"$TMP/multi.json" 2>/dev/null
python3 - "$TMP/multi.json" <<'PY' >/dev/null 2>&1
import json,sys
d=json.load(open(sys.argv[1]))          # fails outright if two objects were emitted
c=d["hookSpecificOutput"]["additionalContext"]
assert "repoA" in c and "repoB" in c, "both repos must appear in the briefing"
PY
check $? "two repos still emit ONE parseable object, both briefed"

# ---- 4. the hook installer merges, never clobbers -----------------------------
# The FIRST ACTION used to be a plain `cp` over the user's own settings file.
SET="$TMP/settings.json"
cat > "$SET" <<'JSON'
{"permissions": {"allow": ["Bash(ls:*)"]},
 "env": {"KEEP": "me"},
 "hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": "echo someone-elses-hook"}]}]}}
JSON
CLAUDE_HOOK_SETTINGS="$SET" CLAUDE_REPO_ROOT="$FAKE" bash tools/install-hooks.sh --quiet >/dev/null 2>&1
python3 - "$SET" <<'PY' >/dev/null 2>&1
import json,sys
d=json.load(open(sys.argv[1]))
assert d["permissions"]["allow"] == ["Bash(ls:*)"], "permissions were destroyed"
assert d["env"]["KEEP"] == "me", "env was destroyed"
cmds = [h["command"] for e in d["hooks"]["SessionStart"] for h in e["hooks"]]
assert any("someone-elses-hook" in c for c in cmds), "another project's hook was destroyed"
assert any("portfolio-checkpoint-hooks" in c for c in cmds), "our hook was not installed"
assert all("__REPO_ROOT__" not in c for c in cmds), "placeholder was left unsubstituted"
PY
check $? "install-hooks merges: permissions, env and foreign hooks all survive"

# A legacy entry — installed by the original `cp`, so it carries no marker —
# must be REPLACED, not kept alongside the new one. Keeping both put the
# double-JSON bug straight back into the live config once already.
cat > "$SET" <<'JSON'
{"hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": "for d in /home/user/*/; do (cd \"$d\" && bash tools/resume.sh); done"}]}]}}
JSON
CLAUDE_HOOK_SETTINGS="$SET" CLAUDE_REPO_ROOT="$FAKE" bash tools/install-hooks.sh --quiet >/dev/null 2>&1
python3 - "$SET" <<'LEGACY' >/dev/null 2>&1
import json,sys
d=json.load(open(sys.argv[1]))
cmds=[h["command"] for x in d["hooks"]["SessionStart"] for h in x["hooks"]]
assert len(cmds)==1, "legacy entry kept alongside the new one: %r" % cmds
assert "portfolio-checkpoint-hooks" in cmds[0]
LEGACY
check $? "an untagged legacy hook entry is replaced, not duplicated"

BEFORE="$(cat "$SET")"
CLAUDE_HOOK_SETTINGS="$SET" CLAUDE_REPO_ROOT="$FAKE" bash tools/install-hooks.sh --quiet >/dev/null 2>&1
[ "$BEFORE" = "$(cat "$SET")" ] && ok "install-hooks is idempotent" || bad "install-hooks rewrote an already-current file"
CLAUDE_HOOK_SETTINGS="$SET" CLAUDE_REPO_ROOT="$FAKE" bash tools/install-hooks.sh --check >/dev/null 2>&1
check $? "install-hooks --check reports 'current' once installed"

# ---- 5. checkpoint numbers only ever go up -----------------------------------
# The shallow-clone regression: a fresh container has fewer commits than the
# history it was cloned from, so a commit-count number walks BACKWARDS.
FX="$RA"
echo '# CHECKPOINT 9000 — read me first, then TASKS.md' > "$FX/CHECKPOINT.md"
( cd "$FX" && CLAUDE_HOOK_SETTINGS="$TMP/fx-settings.json" CLAUDE_REPO_ROOT="$FAKE" \
    bash tools/ckpt.sh "self-test" "self-test" >/dev/null 2>&1 )
GOT="$(sed -n '1s/^# CHECKPOINT \([0-9][0-9]*\).*/\1/p' "$FX/CHECKPOINT.md" 2>/dev/null || echo 0)"
if [ "${GOT:-0}" -gt 9000 ]; then
  ok "checkpoint numbers are monotonic in a shallow clone (9000 -> $GOT)"
else
  bad "checkpoint number went backwards" "9000 -> ${GOT:-none}"
fi
# THE "SESSION FORGOT THE FIRST ACTION" PATH.
# ckpt.sh must put the hooks back by itself, or a session that never read
# CLAUDE.md's install step runs to its usage cap with nothing auto-saved.
# The fixture ckpt.sh above pointed at $TMP/fx-settings.json, which did not
# exist before it ran.
if [ -f "$TMP/fx-settings.json" ] && grep -q 'portfolio-checkpoint-hooks' "$TMP/fx-settings.json" 2>/dev/null; then
  ok "ckpt.sh reinstalls missing hooks by itself"
else
  bad "ckpt.sh did NOT repair missing hooks — a session that skips the install step stays unprotected"
fi

grep -q '^\*\*Branch:\*\*' "$FX/CHECKPOINT.md" && ok "CHECKPOINT.md records its branch" \
  || bad "CHECKPOINT.md does not say which branch the work is on"

# A cold session must be TOLD about the keystore, not discover it minutes into
# a failed Gradle run. The fixtures have no app/ dir, so this is the missing case.
# NOTE the shape: capture, then match. Piping into `grep -q` under `set -o
# pipefail` reports FAILURE even on a match — grep -q exits at the first hit,
# the producer takes SIGPIPE, and pipefail surfaces that. It cost a false red
# here already.
KSOUT="$( cd "$RA" && bash bootstrap.sh 2>/dev/null )"
case "$KSOUT" in
  *"signing keystore"*) ok "bootstrap reports the signing keystore state at session start" ;;
  *) bad "bootstrap says nothing about the keystore" ;;
esac

# ---- the build environment provisions itself -------------------------------
# The point of these: a session must never be told "go run a second command"
# before it can build, and must never be allowed to ship with the wrong key.
case "$( bash tools/ensure-build-env.sh --check 2>&1 )" in
  *"android sdk"*|*"build environment ready"*) ok "ensure-build-env --check reports SDK state without installing" ;;
  *) bad "ensure-build-env --check said something unexpected" ;;
esac

# A release path must REFUSE a wrong keystore rather than build with it. Run it
# against a fixture holding a freshly generated (therefore wrong) key.
KSFX="$TMP/kstest"; mkdir -p "$KSFX/app" "$KSFX/tools"
cp tools/checkkeystore.sh "$KSFX/tools/"
if command -v keytool >/dev/null 2>&1; then
  keytool -genkeypair -alias portfolio -keystore "$KSFX/app/sideload.jks" \
    -storepass portfolio -keypass portfolio -keyalg RSA -keysize 2048 -validity 30 \
    -dname "CN=Portfolio, OU=Personal, O=TJ, L=NA, ST=NA, C=US" >/dev/null 2>&1
  ( cd "$KSFX" && bash tools/checkkeystore.sh >/dev/null 2>&1 )
  [ "$?" = "2" ] && ok "checkkeystore rejects a regenerated key with an identical DN" \
                 || bad "checkkeystore ACCEPTED a wrong keystore — a ship would erase the phone"
  rm -f "$KSFX/app/sideload.jks"
  ( cd "$KSFX" && bash tools/checkkeystore.sh >/dev/null 2>&1 )
  [ "$?" = "1" ] && ok "checkkeystore reports a missing keystore distinctly from a wrong one" \
                 || bad "checkkeystore does not distinguish missing from wrong"
else
  ok "checkkeystore cases skipped (no keytool here)"
fi

# gradle.sh must pick the release-strict mode from the task name, not guess.
grep -q 'assembleRelease' tools/gradle.sh && grep -q '\-\-release' tools/gradle.sh \
  && ok "gradle.sh escalates to release-strict mode for assembleRelease" \
  || bad "gradle.sh does not treat a release task as release-strict"

# Nothing may tell a session to run the SDK setup by hand any more.
if grep -n 'Run: bash tools/setup-android-sdk.sh' ship.sh >/dev/null 2>&1; then
  bad "ship.sh still hard-fails telling a human to install the SDK by hand"
else
  ok "no build path asks a human to install the SDK by hand"
fi

# ---- CI must never be wired to fire on every autosave ------------------------
# autosave mirrors every commit to main; a push trigger would start a run every
# few seconds. This is the one mistake that would make CI actively harmful.
WF=".github/workflows/android.yml"
if [ -f "$WF" ]; then
  if python3 - "$WF" <<'PY' >/dev/null 2>&1
import sys
try: import yaml
except ImportError: sys.exit(0)
w = yaml.safe_load(open(sys.argv[1]))
on = w.get(True, w.get("on"))          # bare `on:` parses as the boolean True
push = (on or {}).get("push") or {}
assert "branches" not in push, "workflow has a branch push trigger"
assert "tags" in push or "workflow_dispatch" in (on or {}), "no tag/dispatch trigger"
PY
  then ok "CI workflow triggers on tags/dispatch only, never on every push"
  else bad "CI workflow would run on branch pushes — autosave would fire it constantly"
  fi
  case "$(cat "$WF")" in
    *"tools/checkkeystore.sh"*) ok "CI verifies the keystore with the repo's own check" ;;
    *) bad "CI does not verify the signing keystore" ;;
  esac
  case "$(cat "$WF")" in
    *"tools/verify-apk.sh"*) ok "CI verifies the built APK's certificate" ;;
    *) bad "CI publishes an APK without checking what signed it" ;;
  esac
  case "$(cat "$WF")" in
    *"git push"*|*"git commit"*) bad "CI commits or pushes — it would retrigger autosave and race live sessions" ;;
    *) ok "CI commits nothing" ;;
  esac
fi

# verify-apk.sh and checkkeystore.sh must agree on the fingerprint, always.
EXP="$(bash tools/checkkeystore.sh --expected 2>/dev/null | tr -d ': ' | tr 'A-Z' 'a-z')"
case "$EXP" in
  2e8c38472d1657b7*) ok "the expected certificate is published from one place" ;;
  *) bad "checkkeystore --expected did not return the shipped certificate" ;;
esac

# ---- 6. the secret scan still has teeth --------------------------------------
S="$TMP/secret"; mkdir -p "$S"; cp -r tools "$S/tools"
( cd "$S" && git init -q . && git add -A >/dev/null 2>&1 && git commit -qm init >/dev/null 2>&1 )
printf 'token = "ghp_%s"\n' "0123456789abcdef0123456789abcdef01" > "$S/leak.txt"
( cd "$S" && git add -A >/dev/null 2>&1 )
( cd "$S" && bash tools/secretscan.sh >/dev/null 2>&1 ) && bad "secretscan missed a GitHub token" || ok "secretscan still catches a live-shaped token"
rm -f "$S/leak.txt"
printf 'EXAMPLE_KEY = "ghp_%s"  # PLACEHOLDER\n' "0123456789abcdef0123456789abcdef01" > "$S/fixture.txt"
( cd "$S" && git add -A >/dev/null 2>&1 )
( cd "$S" && bash tools/secretscan.sh >/dev/null 2>&1 ) && ok "secretscan ignores an obvious placeholder" \
  || bad "secretscan false-positives on a placeholder — this wedges ALL auto-saving"

# ---- 7. 'nothing to push' is never guessed -----------------------------------
# Answering 0 when the truth is unknown is how work gets left in a dead
# container while every status line says it was saved.
( cd "$S" && bash tools/unpushed.sh >/dev/null 2>&1 ) \
  && bad "unpushed.sh claimed a count with no remote at all" \
  || ok "unpushed.sh refuses to guess when there is no remote"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "  resume system: all checks green"
  exit 0
fi
echo "  resume system: $FAILED CHECK(S) FAILED — the handoff is not safe"
exit 1
