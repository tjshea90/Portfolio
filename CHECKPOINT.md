# CHECKPOINT 574 — read me first, then TASKS.md

**Written:** 2026-09-10T19:07:01Z · **tests:** all 1 fast checks green (gradle suite: see ship.sh)

## Just done
CRITICAL FIX, live-verified: found and fixed the root cause of why autosave never fired in this session. This environment (Claude Code on the web, multi-repo container under /home/user) does not load hooks from this repo's own .claude/settings.json - CLAUDE_PROJECT_DIR is unset and the harness only checks ONE project root (/home/user itself, not the repo subdirectory) for the whole session. Fix: wrote tools/session-root-hooks.json (a multi-repo-aware version that loops over every repo under /home/user) and copied it to /home/user/.claude/settings.json. Confirmed live: it took effect immediately with no restart, and the next 4 edits all produced real auto-checkpoint commits automatically. Added a prominent 'FIRST ACTION OF EVERY SESSION' section to CLAUDE.md instructing every future session to do this copy as step 1 and verify it with git log before trusting it.

## Do this next
This same bug almost certainly affects the sibling fantasy-football repo too (same environment, same missing /home/user/.claude/settings.json) - worth checking there next, low urgency since Portfolio was the active ask. Otherwise: no active job, waiting on Tj per TASKS.md.

*(resuming? read CLAUDE.md's "Starting a session" — this file is only step 1 of that.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  4e814ae ckpt 569: Added auto-merge-from-main to resume.sh (when origin/main has newer commits th
  a30bb1f ckpt 568: Second efficiency/robustness pass on the resume logic: added a stale-git-lock 
  f2b8bc6 ckpt 566: Audited the resume/checkpoint system for Claude-Code (not Cowork) fitness and 
  424ad4b ckpt 565: Sent the signing keystore (app/sideload.jks) to Tj directly since it can't be 
  04a9d02 ckpt 564: Migrated the Portfolio Android app from its Cowork checkpoint system into this
  fd72725 ckpt 2: Set up the Claude Code resume/checkpoint handoff system, adapted from the fantas
  624d2a9 ckpt 66: v7.7 SHIPPED: versionCode 64, 797 tests 0 failures, same signing cert
  90b89d5 ckpt 66: 15 done
  0b2467e ckpt 66: 14 done
  de1a54c ckpt 66: All recovered cross-cutting and settings findings fixed. 797 tests, 0 failures
```

(4 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
