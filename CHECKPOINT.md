# CHECKPOINT 1610 — read me first, then TASKS.md

**Written:** 2026-09-22T19:59:24Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-app-testing-i6cfun` · **builds on:** `98bb330` (this checkpoint is the commit after it)

## Just done
gated v7.36 (code 93) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.36 "Fixed the SPY comparison on zoomed 1D and 5D charts: dragging a zoomed chart no longer re-measures the lines. 1D now measures both the stock and SPY from their previous closes whether zoomed or not (the stock was being measured from the first candle on screen, so the same moment could flip above/below SPY as you dragged); 5D uses the range's first candle like every longer range. The readout names what it counts from while comparing. Regression test proves every moment reads identically through every window. 1295 tests, 0 failures."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  98bb330 ckpt 1609: Fixed the SPY comparison on zoomed 1D/5D charts (fixed anchor for every range
  3f8f7e7 ckpt 1608: Logged Tj's 2026-09-22c request (SPY baseline still jumps when holding and dr
  dfa6002 ckpt 1607: SHIPPED v7.35 (code 92): R8 + app baseline profile + SQLite WAL + list conten
  01877da ckpt 1606: Triggered android.yml run 35774377550 for v7.35 code 92 (first run with the e
  bc8b165 ckpt 1605: gated v7.35 (code 92) and pushed it: checkinit, the full unit suite and the v
  18f45fb ckpt 1604: Speed pass complete: R8 (no rename), app baseline profile, SQLite WAL pool, l
  0a7d47b ckpt 1603: Perf pass: R8 enabled for release (-dontobfuscate), app baseline profile, CI 
  173e5c2 ckpt 1602: Logged Tj's 2026-09-22b request (make the app as snappy as possible on the Mo
  457cc68 ckpt 1601: SHIPPED v7.34 (code 91): Actions run 35771678004 green, Release published, BU
  f1dbd3a ckpt 1600: Triggered GitHub Actions android.yml on main (run 35771678004, full_build=tru
```
