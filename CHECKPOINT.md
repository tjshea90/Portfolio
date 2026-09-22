# CHECKPOINT 1606 — read me first, then TASKS.md

**Written:** 2026-09-22T19:32:51Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-app-testing-i6cfun` · **builds on:** `bc8b165` (this checkpoint is the commit after it)

## Just done
Triggered android.yml run 35774377550 for v7.35 code 92 (first run with the emulator smoke gate).

## Do this next
Watch run 35774377550; if the smoke step fails, read smoke-logs/job logs, fix, re-run; when green: get_release_by_tag v7.35, record-release.sh v7.35 with the ship note (in the ckpt 1605 commit message), post link.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  bc8b165 ckpt 1605: gated v7.35 (code 92) and pushed it: checkinit, the full unit suite and the v
  18f45fb ckpt 1604: Speed pass complete: R8 (no rename), app baseline profile, SQLite WAL pool, l
  0a7d47b ckpt 1603: Perf pass: R8 enabled for release (-dontobfuscate), app baseline profile, CI 
  173e5c2 ckpt 1602: Logged Tj's 2026-09-22b request (make the app as snappy as possible on the Mo
  457cc68 ckpt 1601: SHIPPED v7.34 (code 91): Actions run 35771678004 green, Release published, BU
  f1dbd3a ckpt 1600: Triggered GitHub Actions android.yml on main (run 35771678004, full_build=tru
  09d15bb ckpt 1599: gated v7.34 (code 91) and pushed it: checkinit, the full unit suite and the v
  a95bcbb ckpt 1598: All ~45 audit findings fixed and verified: suite 1292/0. Review found + fixed
  d9b8453 ckpt 1597: Every audit finding now written. LOWs done: D-L8, D-L10, S-L5, S-L6, S-L7 (su
  57d9cc1 ckpt 1596: Suite 1286/0: A-L9/A-L10/A-L11, D-L5/D-L6/D-L7 done. Wrote D-L8 (throttled re
```
