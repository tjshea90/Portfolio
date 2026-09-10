#!/usr/bin/env bash
# install-hooks.sh — put the checkpoint hooks where Claude Code will actually
# read them, WITHOUT destroying anything else that lives in that file.
#
# WHY THIS REPLACED A `cp`
# ------------------------
# CLAUDE.md's FIRST ACTION used to be, literally:
#
#     cp tools/session-root-hooks.json /home/user/.claude/settings.json
#
# That works, and it is also an unconditional overwrite of the one file a user's
# own Claude Code settings live in — permissions, env, statusLine, hooks
# belonging to other projects in the same container. Every session runs this
# step, so the day anything else is written there, the next session silently
# deletes it. Nothing warns, because a clobbered settings file looks exactly
# like a settings file.
#
# So this merges instead: our hook entries are tagged with a marker, and only
# entries carrying that marker are ever replaced. Everything else in the file
# is preserved byte-for-byte through a JSON round-trip.
#
# It is idempotent — running it when it is already current changes nothing and
# writes nothing — so it is safe to call from other tools (ckpt.sh and
# resume.sh both do, so a session that forgets the FIRST ACTION still gets the
# safety net the moment it checkpoints).
#
#   bash tools/install-hooks.sh           # install or repair
#   bash tools/install-hooks.sh --check   # 0 = current, 1 = needs installing
#   bash tools/install-hooks.sh --quiet   # only speak if something changed
#
set -uo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$D" || exit 1

MODE="install"; QUIET=0
for a in "$@"; do
  case "$a" in
    --check) MODE="check" ;;
    --quiet) QUIET=1 ;;
  esac
done

# WHERE DOES CLAUDE CODE READ HOOKS FROM HERE?
# Confirmed 2026-09-10 in this environment (Claude Code on the web): NOT this
# repo's own .claude/settings.json. CLAUDE_PROJECT_DIR is unset and the harness
# resolves one project root for the session — which is the directory CONTAINING
# the repo, because the container can hold several repos side by side. $HOME is
# /root here and is a different thing entirely (the harness keeps its own hooks
# there), so do not use it.
#
# CLAUDE_HOOK_SETTINGS overrides all of this — tools/test_resume.sh sets it to a
# temp file so the tests can never touch the real one.
ROOT="${CLAUDE_REPO_ROOT:-$(dirname "$D")}"
TARGET="${CLAUDE_HOOK_SETTINGS:-$ROOT/.claude/settings.json}"
TEMPLATE="$D/tools/session-root-hooks.json"

[ -f "$TEMPLATE" ] || { echo "  FAIL  missing $TEMPLATE — cannot install hooks."; exit 1; }

if ! command -v python3 >/dev/null 2>&1; then
  # Fallback only when there is nothing to lose. Refusing is correct here: a
  # blind cp over an existing settings file is the exact failure this script
  # was written to remove, and doing it "just this once" is how it comes back.
  if [ ! -f "$TARGET" ]; then
    mkdir -p "$(dirname "$TARGET")"
    sed "s|__REPO_ROOT__|$ROOT|g" "$TEMPLATE" > "$TARGET"
    echo "  OK    hooks installed at $TARGET (no python3 — plain copy, file did not exist)"
    exit 0
  fi
  echo "  FAIL  no python3, and $TARGET already exists. Merge it by hand rather"
  echo "        than overwriting — it may hold settings that are not ours."
  exit 1
fi

mkdir -p "$(dirname "$TARGET")"

CLAUDE_INSTALL_MODE="$MODE" CLAUDE_INSTALL_QUIET="$QUIET" \
CLAUDE_INSTALL_TARGET="$TARGET" CLAUDE_INSTALL_TEMPLATE="$TEMPLATE" \
CLAUDE_INSTALL_ROOT="$ROOT" python3 <<'PY'
import json, os, sys, time, shutil

target   = os.environ["CLAUDE_INSTALL_TARGET"]
template = os.environ["CLAUDE_INSTALL_TEMPLATE"]
root     = os.environ["CLAUDE_INSTALL_ROOT"]
mode     = os.environ["CLAUDE_INSTALL_MODE"]
quiet    = os.environ["CLAUDE_INSTALL_QUIET"] == "1"

# The marker is what makes this a merge rather than an overwrite: it is the
# only thing that identifies an entry as ours, and therefore the only thing
# this script is allowed to replace.
MARKER = "portfolio-checkpoint-hooks"

with open(template) as f:
    wanted = json.load(f)
wanted_hooks = wanted.get("hooks", {})

# Bake the real container layout into the commands. The template ships a
# placeholder so it never hard-codes /home/user, which is this container's
# layout and not a guarantee.
def subst(obj):
    if isinstance(obj, str):
        return obj.replace("__REPO_ROOT__", root)
    if isinstance(obj, list):
        return [subst(x) for x in obj]
    if isinstance(obj, dict):
        return {k: subst(v) for k, v in obj.items()}
    return obj
wanted_hooks = subst(wanted_hooks)

existing = {}
damaged = False
if os.path.exists(target):
    try:
        with open(target) as f:
            existing = json.load(f)
        if not isinstance(existing, dict):
            existing, damaged = {}, True
    except Exception:
        # Unparseable. Treat as damaged rather than as empty: it is still
        # backed up below, so nothing is lost either way.
        existing, damaged = {}, True

merged = json.loads(json.dumps(existing))   # deep copy
hooks = merged.setdefault("hooks", {})
if not isinstance(hooks, dict):
    hooks, damaged = {}, True
    merged["hooks"] = hooks

# LEGACY ENTRIES COUNT AS OURS TOO.
# The first version of these hooks predates the marker: it was copied into
# place with a plain `cp`, so its entries carry no tag. Merging naively left
# BOTH the old untagged entry and the new tagged one installed for every
# event — and since the old one printed its own JSON per repo, that put the
# exact double-JSON bug this rewrite removes straight back into the live
# config. Observed, not theorised. Any hook command that invokes one of our
# own scripts is ours by definition, whether or not it is tagged.
LEGACY = ("tools/resume.sh", "tools/autosave.sh", "tools/toobig.sh",
          "tools/hooks/", "tools/ckpt.sh")

def is_ours(entry):
    for h in (entry or {}).get("hooks", []) or []:
        cmd = str(h.get("command", ""))
        if MARKER in cmd or any(x in cmd for x in LEGACY):
            return True
    return False

for event, entries in wanted_hooks.items():
    kept = [e for e in hooks.get(event, []) or [] if not is_ours(e)]
    hooks[event] = kept + entries

# Drop an event key we emptied out entirely (we removed our old entry and the
# template no longer defines one) so stale events don't linger forever.
for event in list(hooks.keys()):
    if event not in wanted_hooks and isinstance(hooks[event], list):
        left = [e for e in hooks[event] if not is_ours(e)]
        if left:
            hooks[event] = left
        else:
            del hooks[event]

# The old `cp` install dropped the template's own _comment into the user's
# settings file, where it is meaningless. Remove it when it is unambiguously
# ours; never touch a _comment that is not.
c = merged.get("_comment")
if isinstance(c, str) and ("tools/session-root-hooks.json" in c or "tools/install-hooks.sh" in c):
    del merged["_comment"]

new_text = json.dumps(merged, indent=2) + "\n"
old_text = None
if os.path.exists(target):
    try:
        with open(target) as f:
            old_text = f.read()
    except Exception:
        pass

if old_text == new_text and not damaged:
    if mode == "check":
        sys.exit(0)
    if not quiet:
        print("  OK    checkpoint hooks already current at %s" % target)
    sys.exit(0)

if mode == "check":
    sys.exit(1)

# Back up anything we are about to change, but only when it actually held
# something of its own — a backup per session of a file we wrote ourselves is
# just litter.
if old_text is not None and (existing.get("hooks") or [k for k in existing if k != "hooks"] or damaged):
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    try:
        shutil.copy2(target, "%s.bak-%s" % (target, stamp))
    except Exception:
        pass

tmp = target + ".tmp"
with open(tmp, "w") as f:
    f.write(new_text)
os.replace(tmp, target)   # atomic: a killed session never leaves a half file

kept_keys = sorted(k for k in merged if k != "hooks")
note = " (preserved: %s)" % ", ".join(kept_keys) if kept_keys else ""
if damaged:
    print("  WARN  %s was unreadable or malformed — rebuilt it (a .bak- copy was kept)%s" % (target, note))
else:
    print("  OK    checkpoint hooks installed at %s%s" % (target, note))
PY
exit $?
