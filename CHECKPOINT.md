# CHECKPOINT 1578 — read me first, then TASKS.md

**Written:** 2026-09-21T07:42:54Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/stock-etf-refresh-stale-data-mohnpm` · **builds on:** `f9a473c` (this checkpoint is the commit after it)

## Just done
Full-tests audit (4 parallel subsystem agents) reconciled and fixed: HIGH bug where whyAt was stamped unconditionally, defeating v7.30's own staleness eviction whenever a reply touched anything but why; MEDIUM bug extending staleness eviction to the batch-level explained/notes/dtExplained/dtNotes fields; MEDIUM bug adding staleness eviction for cold-loaded day-trading trade-plan levels; MEDIUM fix showing the date on a stale cached recommendation's timestamp; MEDIUM/LOW fix surfacing when a recommendation's underlying fundamentals data itself is stale. 1228 tests, 0 failures, checkinit clean. v7.30 (already-shipped light-tests build) confirmed green and recorded in BUILDLOG.md.

## Do this next
Bump versionCode/versionName to 88/7.31, run ship.sh, trigger the GitHub Actions build, confirm green, record the release, and post the link

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  9694006 ckpt 1577: gated v7.30 (code 87) and pushed it: checkinit, the full unit suite and the v
  28ccdb6 ckpt 1576: Fixed the SPY-comparison chart's pan/zoom baseline bug (comparison anchor now
  7ff65c2 ckpt 1575: Fixed Best-Stocks refresh flicker (enrichJob left running as a stray sibling 
  2c54651 ckpt 1574: Logged Tj's new request (Best Stocks/ETFs/Trending refresh flicker + stale Cl
  6e0ce02 ckpt 1573: v7.29 (code 86) shipped: GitHub build green, Release published and recorded i
  30a8a51 ckpt 1572: gated v7.29 (code 86) and pushed it: checkinit, the full unit suite and the v
  204a04b ckpt 1571: Reconciled and fixed all findings from the 4 parallel subsystem audits: HIGH 
  5539101 ckpt 1570: Full-tests floor green (1204 tests) + the un-CI'd compiled-class harnesses (S
  d0b0d69 ckpt 1569: Recorded v7.28 release in BUILDLOG.md (build was green, Release already publi
  934d899 ckpt 1568: gated v7.28 (code 85) and pushed it: checkinit, the full unit suite and the v
```

(24 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
