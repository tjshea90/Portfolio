# CHECKPOINT 1749 — read me first, then TASKS.md

**Written:** 2026-10-27T03:53:26Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/gallant-mccarthy-8kcbvp` · **builds on:** `8a6945db` (this checkpoint is the commit after it)

## Just done
gated v8.3 (code 102) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v8.3 "v8.3: offline refresh (50 MB prioritized cache, saved article pages, light-refresh switch), Android 10 Go / MT8168 low-RAM tuning"
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  8a6945db ckpt 1748: v8.3 prepared: version bump, BRIEF row, tests (OfflineCacheTest 20, Research
  207e8fae ckpt 1747: offline sync: budget, page saver, db v11, OfflineSync orchestrator, VM wirin
  a4242298 ckpt 1746: captured offline-cache request in TASKS.md
  16bfbc31 ckpt 1745: v8.2 released and recorded
  cc6c9d47 ckpt 1744: gated v8.2 (code 101) and pushed it: checkinit, the full unit suite and the 
  5b0c59a8 ckpt 1743: pre-ship: Earnings tab now lists only stocks you own and each ETF you own as
  c13b08b9 ckpt 1742: v8.1 released and recorded (dividends + earnings)
  f66cd2ef ckpt 1741: gated v8.1 (code 100) and pushed it: checkinit, the full unit suite and the 
  c78ce5b4 ckpt 1740: pre-ship: Dividends and Earnings sections inside the Portfolio tab: dividend
  b59964a4 ckpt 1739: Income UI + earnings + dividends tests green (34 tests)
```
