# CHECKPOINT 1641 — read me first, then TASKS.md

**Written:** 2026-09-23T16:43:37Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/work-scheduling-capability-6jeiez` · **builds on:** `c523e2e` (this checkpoint is the commit after it)

## Just done
gated v7.38 (code 95) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.38 "Fixed the pull-to-refresh circle sometimes staying stuck on screen after a refresh finished: an indicator left showing when nothing is refreshing, nothing is animating and no finger is down is now put away automatically, on every tab. Regression test proves the stuck state clears (and that a refresh in progress or a pull under the finger is left alone). 1328 tests, 0 failures."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  c523e2e ckpt 1640: pre-ship: Fixed the pull-to-refresh circle sometimes staying stuck on screen 
  48e57e0 ckpt 1639: Stuck pull-to-refresh circle fixed: Refreshable watchdog + PullIndicatorUiTes
  dfce12b ckpt 1638: Recorded Tj request 2026-09-23c (stuck pull-to-refresh circle)
  84318c4 ckpt 1637: SHIPPED v7.37 (code 94): share flow + full-test fixes; release published and 
  4ac5316 ckpt 1636: v7.37 ship.sh passed; build run 35880942669 triggered
  cff89b1 ckpt 1635: gated v7.37 (code 94) and pushed it: checkinit, the full unit suite and the v
  8f0449a ckpt 1634: Diff review R-1..R-11 fixed (R-2 documented tradeoff); suite 1325/0
  2c6d558 ckpt 1633: Diff review landed: R-1..R-11 listed in TASKS
  10ad7d1 ckpt 1632: All audit findings fixed (A-12 documented); suite 1324/0
  d6ae2ee ckpt 1631: N-1 (RecentBodies shared chart body; sweep publishes D1 from it) and N-2 (Cla
```
