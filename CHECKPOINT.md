# CHECKPOINT 1646 — read me first, then TASKS.md

**Written:** 2026-09-23T17:16:34Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/work-scheduling-capability-6jeiez` · **builds on:** `83c5cb6` (this checkpoint is the commit after it)

## Just done
gated v7.39 (code 96) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.39 "Pull-to-refresh circle, second fix: it could still get stuck at the top while scrolling (v7.38 only helped once the finger lifted). The pull gesture is now the app's own instead of Material3's - it only reacts to a real pull past the top of a list, and letting go always puts the circle away - and the circle is only drawn while a refresh is running, a finger is pulling, or it is animating in or out. Same look, threshold and feel. 1337 tests, 0 failures."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  98f07b3 ckpt 1645: Stuck pull circle v2: own PullGesture + draw-time invariant + watchdog; tests
  6975ecb ckpt 1644: Recorded Tj request 2026-09-23d (pull circle still sticks on v7.38, video)
  178ebbb ckpt 1643: SHIPPED v7.38 (code 95): stuck pull-to-refresh fix; release published and rec
  0024d91 ckpt 1642: v7.38 ship.sh passed; build run 35890763962 triggered
  05abc54 ckpt 1641: gated v7.38 (code 95) and pushed it: checkinit, the full unit suite and the v
  c523e2e ckpt 1640: pre-ship: Fixed the pull-to-refresh circle sometimes staying stuck on screen 
  48e57e0 ckpt 1639: Stuck pull-to-refresh circle fixed: Refreshable watchdog + PullIndicatorUiTes
  dfce12b ckpt 1638: Recorded Tj request 2026-09-23c (stuck pull-to-refresh circle)
  84318c4 ckpt 1637: SHIPPED v7.37 (code 94): share flow + full-test fixes; release published and 
  4ac5316 ckpt 1636: v7.37 ship.sh passed; build run 35880942669 triggered
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
