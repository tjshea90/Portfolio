# CHECKPOINT 576 — read me first, then TASKS.md

**Written:** 2026-09-10T19:10:39Z · **tests:** all 1 fast checks green (gradle suite: see ship.sh)

## Just done
Fixed a gap in the handoff itself: CHECKPOINT.md's own pointer told a resuming session to read CLAUDE.md's 'Starting a session' section, but the critical hook-install step lives in a separate, earlier section ('FIRST ACTION OF EVERY SESSION') added after that pointer text was written - a cold session could read past it and work an entire session with no autosave, same failure this whole checkpoint fixed. Pointer now names the right section explicitly.

## Do this next
No active job (see TASKS.md). Waiting on Tj. If picking this up: CLAUDE.md's FIRST ACTION section is self-contained - a fresh session reading it cold has everything needed, no prior conversation context required.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  9b2c169 ckpt 574: CRITICAL FIX, live-verified: found and fixed the root cause of why autosave ne
  4e814ae ckpt 569: Added auto-merge-from-main to resume.sh (when origin/main has newer commits th
  a30bb1f ckpt 568: Second efficiency/robustness pass on the resume logic: added a stale-git-lock 
  f2b8bc6 ckpt 566: Audited the resume/checkpoint system for Claude-Code (not Cowork) fitness and 
  424ad4b ckpt 565: Sent the signing keystore (app/sideload.jks) to Tj directly since it can't be 
  04a9d02 ckpt 564: Migrated the Portfolio Android app from its Cowork checkpoint system into this
  fd72725 ckpt 2: Set up the Claude Code resume/checkpoint handoff system, adapted from the fantas
  624d2a9 ckpt 66: v7.7 SHIPPED: versionCode 64, 797 tests 0 failures, same signing cert
  90b89d5 ckpt 66: 15 done
  0b2467e ckpt 66: 14 done
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
