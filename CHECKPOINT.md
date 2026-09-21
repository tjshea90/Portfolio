# CHECKPOINT 1576 — read me first, then TASKS.md

**Written:** 2026-09-21T07:16:39Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/stock-etf-refresh-stale-data-mohnpm` · **builds on:** `58da4b8` (this checkpoint is the commit after it)

## Just done
Fixed the SPY-comparison chart's pan/zoom baseline bug (comparison anchor now always the selected range's true start, never the panned window's edge - closes a repeat of the round-67 'spy line jumps' report), confirmed the screenshot's 'flat SPY' is expected axis-scale behavior not a bug, and rewrote ComparePanAnchorUiTest for the new fixed-anchor design. Light tests green: 1212 tests / 0 failures, checkinit ok. Updated TASKS.md with full progress on both the flicker/cache fixes and the chart fix.

## Do this next
Ship: bump versionCode/versionName, run ship.sh, trigger the GitHub Actions build, confirm green, record the release, post the link

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  7ff65c2 ckpt 1575: Fixed Best-Stocks refresh flicker (enrichJob left running as a stray sibling 
  2c54651 ckpt 1574: Logged Tj's new request (Best Stocks/ETFs/Trending refresh flicker + stale Cl
  6e0ce02 ckpt 1573: v7.29 (code 86) shipped: GitHub build green, Release published and recorded i
  30a8a51 ckpt 1572: gated v7.29 (code 86) and pushed it: checkinit, the full unit suite and the v
  204a04b ckpt 1571: Reconciled and fixed all findings from the 4 parallel subsystem audits: HIGH 
  5539101 ckpt 1570: Full-tests floor green (1204 tests) + the un-CI'd compiled-class harnesses (S
  d0b0d69 ckpt 1569: Recorded v7.28 release in BUILDLOG.md (build was green, Release already publi
  934d899 ckpt 1568: gated v7.28 (code 85) and pushed it: checkinit, the full unit suite and the v
  9b9c71c ckpt 1567: Full-tests audit COMPLETE and green: 1204 tests / 0 failures / 0 skipped, che
```

(10 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
