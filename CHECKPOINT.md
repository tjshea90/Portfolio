# CHECKPOINT 569 — read me first, then TASKS.md

**Written:** 2026-09-10T19:00:42Z · **tests:** all 1 fast checks green (gradle suite: see ship.sh)

## Just done
Added auto-merge-from-main to resume.sh (when origin/main has newer commits than this branch and it's a clean fast-forward, merge them in automatically instead of just warning - covers 'another account pushed to main while I was away'). Tested the OLD warn-only version worked; the NEW auto-merge version is UNVERIFIED - my test methodology had two bugs (stale local main ref confused a scratch-clone checkout; then discovered this exact edit had sat uncommitted in the working tree for many tool calls, meaning the autosave PostToolUse hook did NOT fire during part of this session - unconfirmed why, could be a real gap or an artifact of nested bash git commands not tripping the hook matcher). This commit was made by manually running ckpt.sh rather than relying on the hook, specifically because I could not confirm the hook was firing.

## Do this next
1) VERIFY THE HOOK: make a trivial edit with the Edit tool (not Bash), then run 'git log -1 --oneline' - if no new auto-checkpoint commit appears, the PostToolUse hook is genuinely broken and that's the top-priority bug (the whole safety net depends on it). 2) If the hook is fine, re-verify the main-auto-merge logic added to tools/resume.sh (lines ~69-96) with a CLEAN test: clone into a scratch dir, checkout the actual branch by full SHA (not a branch name, to avoid ref ambiguity), push a test commit to a throwaway remote's main, run tools/resume.sh, confirm 'git log -1' shows the merge happened. 3) This session also found (unfixed, low priority): gc.auto=0 locally (harmless, doesn't propagate to fresh clones) and considered adding 'git gc --quiet &' to ship.sh for repo hygiene at milestones - not done, optional.

*(resuming? read CLAUDE.md's "Starting a session" — this file is only step 1 of that.)*

## Uncommitted right now
     M CHECKPOINT.md
     M tools/resume.sh

## Last ten checkpoints
```
  a30bb1f ckpt 568: Second efficiency/robustness pass on the resume logic: added a stale-git-lock 
  f2b8bc6 ckpt 566: Audited the resume/checkpoint system for Claude-Code (not Cowork) fitness and 
  424ad4b ckpt 565: Sent the signing keystore (app/sideload.jks) to Tj directly since it can't be 
  04a9d02 ckpt 564: Migrated the Portfolio Android app from its Cowork checkpoint system into this
  fd72725 ckpt 2: Set up the Claude Code resume/checkpoint handoff system, adapted from the fantas
  624d2a9 ckpt 66: v7.7 SHIPPED: versionCode 64, 797 tests 0 failures, same signing cert
  90b89d5 ckpt 66: 15 done
  0b2467e ckpt 66: 14 done
  de1a54c ckpt 66: All recovered cross-cutting and settings findings fixed. 797 tests, 0 failures
  74ee4cb ckpt 66: fix EXP3
```
