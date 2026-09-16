# CHECKPOINT 702 — read me first, then TASKS.md

**Written:** 2026-09-16T03:59:10Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `5ae3b3d` (this checkpoint is the commit after it)

## Just done
Full Gradle unit suite green: 1090 tests, 0 failures, 0 errors (51.5s) - covers everything from this round: Insider.marketWide's parser/dedup/type-filter tests, and the full Watchlist %-since-added feature (Db migration/entries/baseline write-once/backup round-trip, Row.sinceWatchedPct arithmetic). No regressions in the pre-existing 1075.

## Do this next
Run a high-effort /code-review pass over the whole diff (Insider.kt, InsiderUi.kt, FeedScreen.kt, Db.kt, Models.kt, PortfolioViewModel.kt, StockRow.kt, WatchlistScreen.kt, the two test files, insider_sim.py) before considering Part 14 done and asking Tj whether to ship.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  5ae3b3d ckpt 701: Fixed a fixture-math bug in my own new test: currentListingDedupesByAccessionA
  f02ab54 ckpt 700: Added tests: 2 new InsiderTest cases for parseCurrentListing (dedup by accessi
  0759fb8 ckpt 699: Implemented Insider.marketWide(): fetches EDGAR's getcurrent atom feed (pagina
  5fd0c5f ckpt 698: Recorded Part 14 in TASKS.md: Tj wants the Insider tab market-wide (all public
  29d5afa ckpt 697: v7.22 (code 79) shipped end to end: GitHub Actions run #22 built, signed, veri
  f2c635d ckpt 696: Shipped v7.22 (code 79): ship.sh gate passed (checkinit, full unit suite, vers
  8de9083 ckpt 695: gated v7.22 (code 79) and pushed it: checkinit, the full unit suite and the ve
  33e3b91 ckpt 694: Finished Part 13's implementation: tradePlan now exposes a decline reason (pla
  f8402fb ckpt 693: Recorded Part 13 in TASKS.md: Tj wants actionable Day Trading stocks surfaced 
  3104ec0 ckpt 692: gated v7.21 (code 78) and pushed it: checkinit, the full unit suite and the ve
```
