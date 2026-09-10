# CHECKPOINT 581 — read me first, then TASKS.md

**Written:** 2026-09-10T20:00:56Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `50f0c1c` (this checkpoint is the commit after it)

## Just done
Fixed the four highest-severity resume defects and made the system self-verifying. NEW tools/install-hooks.sh replaces CLAUDE.md's blind 'cp' over /home/user/.claude/settings.json with an idempotent MERGE that preserves the user's own permissions/env/foreign hooks and replaces our own entries (tagged or legacy-untagged) rather than duplicating them. NEW tools/hooks/{brief,save,big}.sh + emit.py aggregate ALL repos in the container into exactly ONE JSON object per hook event - the old per-repo loop emitted N objects, which is not parseable JSON and silently dropped the entire session briefing the moment a second repo existed (proven, and it was live in the settings file until this commit). ckpt.sh now numbers checkpoints monotonically from max(CHECKPOINT.md, git log, commit count) instead of the raw commit count, which walks BACKWARDS in Claude Code's shallow clones (this container: 52 local vs 559 real, so ckpt 580 was followed by ckpt 54 an hour ago). resume.sh/toobig.sh/autosave.sh no longer use @{u}, which simply fails on a claude/<id> branch and made the 'are you in sync with GitHub' and 'is your work pushed' checks silently answer 'yes' - new tools/unpushed.sh resolves origin/<branch> the way push.sh already did, and refuses to guess. CHECKPOINT.md now records its branch and base commit. NEW tools/test_resume.sh (13 checks, hermetic, no network, 1.7s) runs on every checkpoint via ckpt.sh's existing test discovery.

## Do this next
Update CLAUDE.md's FIRST ACTION to call tools/install-hooks.sh instead of the cp, and document the self-repair path. Then tick TASKS.md 1-9 and re-verify the autosave hook fires end-to-end under the new aggregator.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  cbed3ee ckpt 54: Reviewed the whole resume/checkpoint system end-to-end and confirmed 6 real def
  eb593ff ckpt 580: Completeness re-check of the resume system in a genuinely fresh session (new c
  c6b6dbf ckpt 576: Fixed a gap in the handoff itself: CHECKPOINT.md's own pointer told a resuming
  9b2c169 ckpt 574: CRITICAL FIX, live-verified: found and fixed the root cause of why autosave ne
  4e814ae ckpt 569: Added auto-merge-from-main to resume.sh (when origin/main has newer commits th
  a30bb1f ckpt 568: Second efficiency/robustness pass on the resume logic: added a stale-git-lock 
  f2b8bc6 ckpt 566: Audited the resume/checkpoint system for Claude-Code (not Cowork) fitness and 
  424ad4b ckpt 565: Sent the signing keystore (app/sideload.jks) to Tj directly since it can't be 
  04a9d02 ckpt 564: Migrated the Portfolio Android app from its Cowork checkpoint system into this
  fd72725 ckpt 2: Set up the Claude Code resume/checkpoint handoff system, adapted from the fantas
```

(11 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
