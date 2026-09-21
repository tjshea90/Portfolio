# CHECKPOINT 1581 — read me first, then TASKS.md

**Written:** 2026-09-21T17:16:27Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/stuck-refresh-loop-3bixfe` · **builds on:** `4db5b40` (this checkpoint is the commit after it)

## Just done
Diagnosed and fixed the stuck pull-to-refresh spinner Tj reported (screenshot: spinner still turning on the Portfolio tab). Root cause: loadEtfs() and loadResearch() in PortfolioViewModel hand-cleared manualRefresh/refreshSource to false/PULL_NONE in their finally blocks on force=true, instead of deriving the flag via syncManualIndicator() the way refresh() and refreshFeed() already do. Since manualRefresh/refreshSource is one shared flag across every tab's Refreshable, a pull on the Research/ETFs tab that was still mid-build (10-18 requests) could have its hand-clear race against a pull started on a different tab in the meantime: either stripping that other tab's spinner early, or - the stuck case matching the report - a pull on Portfolio overwriting refreshSource to PULL_PRICES while research was still busy, so syncManualIndicator's derived 'want' stayed true (researchLoading) and never let go of PULL_PRICES until the unrelated, possibly slow/flaky-network research build finished. Fixed both finally blocks to call syncManualIndicator() instead of hand-writing the fields, matching the pattern already documented and used in refresh()/refreshFeed(). Ran light tests: checkinit + full Kotlin unit suite green (BUILD SUCCESSFUL, all tests pass); grepped for other manualRefresh=false/refreshSource=PULL_NONE hand-writes (none remain outside a comment) and other _researchBusy writers (detail lookup, explanations - none touch manualRefresh, unaffected).

## Do this next
Ship this fix per the auto-ship rule (bump versionCode/versionName, ship.sh, trigger GitHub Actions build, confirm green, record-release.sh, post the Release link).

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  ba64fb7 ckpt 1580: Audited the day-trading success-rate feature per Tj's request (numbers 'seem 
  65750c2 ckpt 1579: gated v7.31 (code 88) and pushed it: checkinit, the full unit suite and the v
  7fc4b13 ckpt 1578: Full-tests audit (4 parallel subsystem agents) reconciled and fixed: HIGH bug
  9694006 ckpt 1577: gated v7.30 (code 87) and pushed it: checkinit, the full unit suite and the v
  28ccdb6 ckpt 1576: Fixed the SPY-comparison chart's pan/zoom baseline bug (comparison anchor now
  7ff65c2 ckpt 1575: Fixed Best-Stocks refresh flicker (enrichJob left running as a stray sibling 
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
