# CHECKPOINT 1640 — read me first, then TASKS.md

**Written:** 2026-09-23T16:42:09Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/work-scheduling-capability-6jeiez` · **builds on:** `48e57e0` (this checkpoint is the commit after it)

## Just done
pre-ship: Fixed the pull-to-refresh circle sometimes staying stuck on screen after a refresh finished: an indicator left showing when nothing is refreshing, nothing is animating and no finger is down is now put away automatically, on every tab. Regression test proves the stuck state clears (and that a refresh in progress or a pull under the finger is left alone). 1328 tests, 0 failures.

## Do this next
ship.sh gates and releases this

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md
     M app/build.gradle.kts

## Last ten checkpoints
```
  48e57e0 ckpt 1639: Stuck pull-to-refresh circle fixed: Refreshable watchdog + PullIndicatorUiTes
  dfce12b ckpt 1638: Recorded Tj request 2026-09-23c (stuck pull-to-refresh circle)
  84318c4 ckpt 1637: SHIPPED v7.37 (code 94): share flow + full-test fixes; release published and 
  4ac5316 ckpt 1636: v7.37 ship.sh passed; build run 35880942669 triggered
  cff89b1 ckpt 1635: gated v7.37 (code 94) and pushed it: checkinit, the full unit suite and the v
  8f0449a ckpt 1634: Diff review R-1..R-11 fixed (R-2 documented tradeoff); suite 1325/0
  2c6d558 ckpt 1633: Diff review landed: R-1..R-11 listed in TASKS
  10ad7d1 ckpt 1632: All audit findings fixed (A-12 documented); suite 1324/0
  d6ae2ee ckpt 1631: N-1 (RecentBodies shared chart body; sweep publishes D1 from it) and N-2 (Cla
  effef6a ckpt 1630: D-6, D-10, U-2, U-6 fixed; all S/A/D/U findings done (A-12 documented no-chan
```
