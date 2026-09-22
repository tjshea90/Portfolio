# CHECKPOINT 1604 — read me first, then TASKS.md

**Written:** 2026-09-22T19:30:06Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-app-testing-i6cfun` · **builds on:** `5732a20` (this checkpoint is the commit after it)

## Just done
Speed pass complete: R8 (no rename), app baseline profile, SQLite WAL pool, lazy-list contentType; suite 1292/0; R8 green. Bumped to v7.35 code 92.

## Do this next
ship.sh, trigger android.yml (first run of the emulator smoke gate), watch it, record, post link.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md
     M app/build.gradle.kts

## Last ten checkpoints
```
  0a7d47b ckpt 1603: Perf pass: R8 enabled for release (-dontobfuscate), app baseline profile, CI 
  173e5c2 ckpt 1602: Logged Tj's 2026-09-22b request (make the app as snappy as possible on the Mo
  457cc68 ckpt 1601: SHIPPED v7.34 (code 91): Actions run 35771678004 green, Release published, BU
  f1dbd3a ckpt 1600: Triggered GitHub Actions android.yml on main (run 35771678004, full_build=tru
  09d15bb ckpt 1599: gated v7.34 (code 91) and pushed it: checkinit, the full unit suite and the v
  a95bcbb ckpt 1598: All ~45 audit findings fixed and verified: suite 1292/0. Review found + fixed
  d9b8453 ckpt 1597: Every audit finding now written. LOWs done: D-L8, D-L10, S-L5, S-L6, S-L7 (su
  57d9cc1 ckpt 1596: Suite 1286/0: A-L9/A-L10/A-L11, D-L5/D-L6/D-L7 done. Wrote D-L8 (throttled re
  61de483 ckpt 1595: All MEDIUMs done (suite 1281/0). LOWs: A-L6 done; A-L9 (pre-destructive snaps
  14c0bdb ckpt 1594: N-M3 done (suite green). Wrote N-M4 (post-close live loop at 5min), U-M1/U-M2
```

(3 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
