# CHECKPOINT 1753 — read me first, then TASKS.md

**Written:** 2026-10-28T18:16:55Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `ccr-39dee522-y8oyky` · **builds on:** `179c8a75` (this checkpoint is the commit after it)

## Just done
gated v8.4 (code 103) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v8.4 "v8.4: saved articles - Google News links resolved to the publisher page, summary-card fallback so every story opens offline, 'saved' markers on stories"
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  179c8a75 ckpt 1752: pre-ship: v8.4: saved articles - Google News links resolved to the publisher
  52a33367 ckpt 1751: W1-W2 coded: GoogleNewsLink resolver, summary-card fallback, saved markers, 
  1c0c3509 ckpt 1750: captured saved-website request
  7a464da5 ckpt 1749: gated v8.3 (code 102) and pushed it: checkinit, the full unit suite and the 
  8a6945db ckpt 1748: v8.3 prepared: version bump, BRIEF row, tests (OfflineCacheTest 20, Research
  207e8fae ckpt 1747: offline sync: budget, page saver, db v11, OfflineSync orchestrator, VM wirin
  a4242298 ckpt 1746: captured offline-cache request in TASKS.md
  16bfbc31 ckpt 1745: v8.2 released and recorded
  cc6c9d47 ckpt 1744: gated v8.2 (code 101) and pushed it: checkinit, the full unit suite and the 
  5b0c59a8 ckpt 1743: pre-ship: Earnings tab now lists only stocks you own and each ETF you own as
```
