# CHECKPOINT 1605 — read me first, then TASKS.md

**Written:** 2026-09-22T19:31:39Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-app-testing-i6cfun` · **builds on:** `18f45fb` (this checkpoint is the commit after it)

## Just done
gated v7.35 (code 92) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.35 "Speed pass for the Moto G 2026 (Dimensity 6300, 4 GB, 120 Hz): release build now optimised by R8 (no renaming, so crash logs stay readable) with an app baseline profile so the app's own code is compiled ahead of time; SQLite switched to explicit write-ahead logging so screen reads no longer wait behind background cache writes; every scrolling list now recycles rows by type. No calculation changed. New release gate: the minified APK is launched on an emulator and fuzzed before it can be published. 1292 tests, 0 failures."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  18f45fb ckpt 1604: Speed pass complete: R8 (no rename), app baseline profile, SQLite WAL pool, l
  0a7d47b ckpt 1603: Perf pass: R8 enabled for release (-dontobfuscate), app baseline profile, CI 
  173e5c2 ckpt 1602: Logged Tj's 2026-09-22b request (make the app as snappy as possible on the Mo
  457cc68 ckpt 1601: SHIPPED v7.34 (code 91): Actions run 35771678004 green, Release published, BU
  f1dbd3a ckpt 1600: Triggered GitHub Actions android.yml on main (run 35771678004, full_build=tru
  09d15bb ckpt 1599: gated v7.34 (code 91) and pushed it: checkinit, the full unit suite and the v
  a95bcbb ckpt 1598: All ~45 audit findings fixed and verified: suite 1292/0. Review found + fixed
  d9b8453 ckpt 1597: Every audit finding now written. LOWs done: D-L8, D-L10, S-L5, S-L6, S-L7 (su
  57d9cc1 ckpt 1596: Suite 1286/0: A-L9/A-L10/A-L11, D-L5/D-L6/D-L7 done. Wrote D-L8 (throttled re
  61de483 ckpt 1595: All MEDIUMs done (suite 1281/0). LOWs: A-L6 done; A-L9 (pre-destructive snaps
```
