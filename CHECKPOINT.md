# CHECKPOINT 1648 — read me first, then TASKS.md

**Written:** 2026-09-23T17:30:49Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/work-scheduling-capability-6jeiez` · **builds on:** `a8f3dfb` (this checkpoint is the commit after it)

## Just done
v7.39 run 35894509527 failed in emulator SDK download (infra, before any test); re-ran failed jobs once

## Do this next
When run 35894509527 (attempt 2) is green: get_release_by_tag v7.39, record-release.sh v7.39 with ckpt 1646 message, tick TASKS 2026-09-23d ship box, post link. If it fails the same way again, tell Tj the CI smoke-test infra is failing

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  a8f3dfb ckpt 1647: v7.39 ship.sh passed; build run 35894509527 triggered
  e73ace3 ckpt 1646: gated v7.39 (code 96) and pushed it: checkinit, the full unit suite and the v
  98f07b3 ckpt 1645: Stuck pull circle v2: own PullGesture + draw-time invariant + watchdog; tests
  6975ecb ckpt 1644: Recorded Tj request 2026-09-23d (pull circle still sticks on v7.38, video)
  178ebbb ckpt 1643: SHIPPED v7.38 (code 95): stuck pull-to-refresh fix; release published and rec
  0024d91 ckpt 1642: v7.38 ship.sh passed; build run 35890763962 triggered
  05abc54 ckpt 1641: gated v7.38 (code 95) and pushed it: checkinit, the full unit suite and the v
  c523e2e ckpt 1640: pre-ship: Fixed the pull-to-refresh circle sometimes staying stuck on screen 
  48e57e0 ckpt 1639: Stuck pull-to-refresh circle fixed: Refreshable watchdog + PullIndicatorUiTes
  dfce12b ckpt 1638: Recorded Tj request 2026-09-23c (stuck pull-to-refresh circle)
```
