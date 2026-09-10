# CHECKPOINT 564 — read me first, then TASKS.md

**Written:** 2026-09-10T16:10:37Z · **tests:** all 1 fast checks green (gradle suite: see ship.sh)

## Just done
Migrated the Portfolio Android app from its Cowork checkpoint system into this repo: merged 559 commits of history, retired ck/ck.py/watchdog.sh, adapted checkinit.py+setup-android-sdk.sh into tools/, added BRIEF.md and ship.sh, placed the v7.7 APK in releases/. The signing keystore (app/sideload.jks) is intentionally NOT committed (excluded by .gitignore) - the platform blocked committing it even to this private repo, so it must be supplied locally before any build/ship.

## Do this next
Ask Tj how he wants the keystore handled long-term (commit it anyway with an explicit override, or keep supplying it out-of-band each session) - see the final chat summary. Otherwise: run tools/setup-android-sdk.sh and verify the gradle test suite in a real dev session.

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

## Last ten checkpoints
```
  fd72725 ckpt 2: Set up the Claude Code resume/checkpoint handoff system, adapted from the fantas
  624d2a9 ckpt 66: v7.7 SHIPPED: versionCode 64, 797 tests 0 failures, same signing cert
  90b89d5 ckpt 66: 15 done
  0b2467e ckpt 66: 14 done
  de1a54c ckpt 66: All recovered cross-cutting and settings findings fixed. 797 tests, 0 failures
  74ee4cb ckpt 66: fix EXP3
  7f45902 ckpt 66: fix EXP2
  2ce3d76 ckpt 66: fix EXP1
  21ee9d2 ckpt 66: fix CRX1
  f82a8f5 ckpt 66: fix CRX2
```
