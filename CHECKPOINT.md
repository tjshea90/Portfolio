# CHECKPOINT 1740 — read me first, then TASKS.md

**Written:** 2026-10-22T16:29:10Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/dividends-earnings-tracker-0x47k2` · **builds on:** `0df6593e` (this checkpoint is the commit after it)

## Just done
pre-ship: Dividends and Earnings sections inside the Portfolio tab: dividends earned (ledger + calculated), total paid, upcoming payouts by date with amounts from your shares at each ex-date, per-holding yield and pay schedule; earnings calendar for stocks and ETF top holdings with a green/yellow/red outlook from track record, estimates, analyst stance, news tone and momentum

## Do this next
ship.sh gates and releases this

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md
     M app/build.gradle.kts

## Last ten checkpoints
```
  b59964a4 ckpt 1739: Income UI + earnings + dividends tests green (34 tests)
  8c6c64a8 ckpt 1738: Dividends screen+engine+feed, earnings outlook/screen, Portfolio sub-tabs wi
  2b4ac2f8 ckpt 1737: Captured Tj's dividends+earnings request in TASKS.md
  f31683d8 ckpt 1736: Added Tj's standing setup rules (2026-10-10: use plugins/skills/connectors, 
  cd97e265 ckpt 1735: v8.0 shipped: run #44 green, Release published, BUILDLOG recorded; C2 ticked
  fd830310 ckpt 1734: v8.0 build triggered (android.yml full_build on main, after ship.sh gate)
  4f47e631 ckpt 1733: gated v8.0 (code 99) and pushed it: checkinit, the full unit suite and the v
  ebc9c885 ckpt 1732: C1 done: R3G-2 scoped to realOpens (touch rules keep NaN = no gap); full sui
  5e9f0eb1 ckpt 1731: Round 3 done inline: R3G-1 (422 from both hosts), R3G-2 (straddle gap ambigu
  51ef498a ckpt 1730: Resumed: round-3 agents were cut off with empty reports; recorded resume poi
```

(4 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
