# CHECKPOINT 1582 — read me first, then TASKS.md

**Written:** 2026-09-21T17:17:59Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/stuck-refresh-loop-3bixfe` · **builds on:** `cd26d09` (this checkpoint is the commit after it)

## Just done
gated v7.32 (code 89) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.32 "Fixed the stuck pull-to-refresh spinner: loadEtfs() and loadResearch() now derive the manual-refresh indicator via syncManualIndicator() instead of hand-clearing it in their finally blocks, matching the pattern refresh()/refreshFeed() already use. The hand-clear could strand or prematurely drop the spinner on an unrelated tab since manualRefresh/refreshSource is one flag shared across every screen's pull-to-refresh."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  0f6e081 ckpt 1581: Diagnosed and fixed the stuck pull-to-refresh spinner Tj reported (screenshot
  ba64fb7 ckpt 1580: Audited the day-trading success-rate feature per Tj's request (numbers 'seem 
  65750c2 ckpt 1579: gated v7.31 (code 88) and pushed it: checkinit, the full unit suite and the v
  7fc4b13 ckpt 1578: Full-tests audit (4 parallel subsystem agents) reconciled and fixed: HIGH bug
  9694006 ckpt 1577: gated v7.30 (code 87) and pushed it: checkinit, the full unit suite and the v
  28ccdb6 ckpt 1576: Fixed the SPY-comparison chart's pan/zoom baseline bug (comparison anchor now
  7ff65c2 ckpt 1575: Fixed Best-Stocks refresh flicker (enrichJob left running as a stray sibling 
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
