# CHECKPOINT 566 — read me first, then TASKS.md

**Written:** 2026-09-10T16:32:35Z · **tests:** all 1 fast checks green (gradle suite: see ship.sh)

## Just done
Audited the resume/checkpoint system for Claude-Code (not Cowork) fitness and Portfolio-specific correctness: removed fantasy-football naming from CLAUDE.md/ckpt.sh, fixed hardcoded Cowork paths in tests/README.md, and fixed a real gap - origin/main was 565 commits behind the working branch (any session opening the repo cold would've seen just the README). Brought main current and made tools/push.sh fast-forward main automatically on every push regardless of which branch a session is on; tools/resume.sh now reports main's sync status every session start.

## Do this next
Verify in the pushed output that origin/main actually advanced (not just the feature branch) - this is the main thing to check after this change.

## How to resume, exactly
Open this GitHub repo in a Claude Code session and say "continue".
The SessionStart hook runs tools/resume.sh, which pulls the latest and
prints this file automatically — nothing has to be attached, uploaded
or explained. If that briefing did not appear, run it by hand:
```bash
bash tools/resume.sh       # pull + this file + TASKS.md + the rules
```
Then continue from **Do this next** above. Do not re-plan, do not re-read
finished work, do not ask Tj to re-explain anything — `TASKS.md` carries his
request in his own words and `git log` carries every step already taken.

## Uncommitted right now
     M CHECKPOINT.md
     M CLAUDE.md
     M tests/README.md
     M tools/ckpt.sh
     M tools/push.sh
     M tools/resume.sh

## Last ten checkpoints
```
  424ad4b ckpt 565: Sent the signing keystore (app/sideload.jks) to Tj directly since it can't be 
  04a9d02 ckpt 564: Migrated the Portfolio Android app from its Cowork checkpoint system into this
  fd72725 ckpt 2: Set up the Claude Code resume/checkpoint handoff system, adapted from the fantas
  624d2a9 ckpt 66: v7.7 SHIPPED: versionCode 64, 797 tests 0 failures, same signing cert
  90b89d5 ckpt 66: 15 done
  0b2467e ckpt 66: 14 done
  de1a54c ckpt 66: All recovered cross-cutting and settings findings fixed. 797 tests, 0 failures
  74ee4cb ckpt 66: fix EXP3
  7f45902 ckpt 66: fix EXP2
  2ce3d76 ckpt 66: fix EXP1
```
