# CHECKPOINT 1743 — read me first, then TASKS.md

**Written:** 2026-10-22T17:17:19Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/dividends-earnings-tracker-0x47k2` · **builds on:** `1ae85247` (this checkpoint is the commit after it)

## Just done
pre-ship: Earnings tab now lists only stocks you own and each ETF you own as one row (soonest big-holding report date, weighted light, holdings listed when tapped) instead of separate rows for stocks you don't own

## Do this next
ship.sh gates and releases this

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M app/build.gradle.kts

## Last ten checkpoints
```
  c13b08b9 ckpt 1742: v8.1 released and recorded (dividends + earnings)
  f66cd2ef ckpt 1741: gated v8.1 (code 100) and pushed it: checkinit, the full unit suite and the 
  c78ce5b4 ckpt 1740: pre-ship: Dividends and Earnings sections inside the Portfolio tab: dividend
  b59964a4 ckpt 1739: Income UI + earnings + dividends tests green (34 tests)
  8c6c64a8 ckpt 1738: Dividends screen+engine+feed, earnings outlook/screen, Portfolio sub-tabs wi
  2b4ac2f8 ckpt 1737: Captured Tj's dividends+earnings request in TASKS.md
  f31683d8 ckpt 1736: Added Tj's standing setup rules (2026-10-10: use plugins/skills/connectors, 
  cd97e265 ckpt 1735: v8.0 shipped: run #44 green, Release published, BUILDLOG recorded; C2 ticked
  fd830310 ckpt 1734: v8.0 build triggered (android.yml full_build on main, after ship.sh gate)
  4f47e631 ckpt 1733: gated v8.0 (code 99) and pushed it: checkinit, the full unit suite and the v
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
