# CHECKPOINT 1575 — read me first, then TASKS.md

**Written:** 2026-09-21T06:55:33Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/stock-etf-refresh-stale-data-mohnpm` · **builds on:** `e29e9c1` (this checkpoint is the commit after it)

## Just done
Fixed Best-Stocks refresh flicker (enrichJob left running as a stray sibling job that could splice stale analyst-boosted scores onto a newer rebuilt list; added enrichJob cancellation + a generation-staleness guard in enrichPass) and Claude-analysis cache staleness (added ResearchRow.whyAt timestamp, stamped in ResearchBridge/DayTradingBridge merge, gated carryExplanations/carryEtfExplanations on a 14-day WHY_STALE_MS window so stale why text is evicted rather than carried forward, plus cold-load eviction in loadCachedResearch). Logged Tj's new SPY-baseline chart report (screenshot+video) into TASKS.md.

## Do this next
Investigate the SPY-baseline chart bug from the video/screenshot: read the percent-change rebasing code (PriceChart.kt), determine if it's an axis-scale illusion or a real pan/zoom rebasing bug, then fix, run light tests, and ship

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  2c54651 ckpt 1574: Logged Tj's new request (Best Stocks/ETFs/Trending refresh flicker + stale Cl
  6e0ce02 ckpt 1573: v7.29 (code 86) shipped: GitHub build green, Release published and recorded i
  30a8a51 ckpt 1572: gated v7.29 (code 86) and pushed it: checkinit, the full unit suite and the v
  204a04b ckpt 1571: Reconciled and fixed all findings from the 4 parallel subsystem audits: HIGH 
  5539101 ckpt 1570: Full-tests floor green (1204 tests) + the un-CI'd compiled-class harnesses (S
  d0b0d69 ckpt 1569: Recorded v7.28 release in BUILDLOG.md (build was green, Release already publi
  934d899 ckpt 1568: gated v7.28 (code 85) and pushed it: checkinit, the full unit suite and the v
  9b9c71c ckpt 1567: Full-tests audit COMPLETE and green: 1204 tests / 0 failures / 0 skipped, che
```

(13 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
