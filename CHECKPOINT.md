# CHECKPOINT 1609 — read me first, then TASKS.md

**Written:** 2026-09-22T19:58:06Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-app-testing-i6cfun` · **builds on:** `ab189e9` (this checkpoint is the commit after it)

## Just done
Fixed the SPY comparison on zoomed 1D/5D charts (fixed anchor for every range; readout says what it counts from). CompareAnchorTest mutation-checked. Suite 1295/0. Bumped to v7.36 code 93.

## Do this next
ship.sh, trigger android.yml, confirm green (incl. emulator gate), record, post link.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md
     M app/build.gradle.kts

## Last ten checkpoints
```
  3f8f7e7 ckpt 1608: Logged Tj's 2026-09-22c request (SPY baseline still jumps when holding and dr
  dfa6002 ckpt 1607: SHIPPED v7.35 (code 92): R8 + app baseline profile + SQLite WAL + list conten
  01877da ckpt 1606: Triggered android.yml run 35774377550 for v7.35 code 92 (first run with the e
  bc8b165 ckpt 1605: gated v7.35 (code 92) and pushed it: checkinit, the full unit suite and the v
  18f45fb ckpt 1604: Speed pass complete: R8 (no rename), app baseline profile, SQLite WAL pool, l
  0a7d47b ckpt 1603: Perf pass: R8 enabled for release (-dontobfuscate), app baseline profile, CI 
  173e5c2 ckpt 1602: Logged Tj's 2026-09-22b request (make the app as snappy as possible on the Mo
  457cc68 ckpt 1601: SHIPPED v7.34 (code 91): Actions run 35771678004 green, Release published, BU
  f1dbd3a ckpt 1600: Triggered GitHub Actions android.yml on main (run 35771678004, full_build=tru
  09d15bb ckpt 1599: gated v7.34 (code 91) and pushed it: checkinit, the full unit suite and the v
```

(4 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
