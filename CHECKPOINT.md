# CHECKPOINT 1515 — read me first, then TASKS.md

**Written:** 2026-09-16T06:15:59Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `8d6ffad4` (this checkpoint is the commit after it)

## Just done
Implemented Part 15's core: Db v8->9 (day_trading_log table, append-only via INSERT OR IGNORE on UNIQUE(symbol,trading_day)), DayTradingLogEntry/DayTradingOutcome/DayTradingStats models, net/DayTradingEval.kt (fetchDaySeries via Yahoo period1/period2+5m - verified against a live request first; pure evaluate() with no-lookahead filtering, direction-aware entry triggering, conservative same-bar stop-before-target tie-break, PENDING/NO_ENTRY/WIN/LOSS/CLOSED_PROFIT/CLOSED_LOSS/DATA_UNAVAILABLE outcomes; stats() aggregation with target-hit-rate, profitable-rate and an equal-weighted average-return simulated-portfolio number). Capture wired into PortfolioViewModel.cacheResearch (the single choke point every Day Trading mutation path already goes through, so nothing can be missed) rather than scattered per-call-site. Evaluation wired to a new button/StatCard in ResearchScreen.kt's Day Trading tab, deliberately NOT color-coding the raw hit-rate (this app's own day-trading research explicitly accepts low win rates for asymmetric winners - only the actual average-return figure is color-coded). 23 new DayTradingEvalTest cases all green, including a real captured Yahoo intraday fixture and the specific no-lookahead regression test. Fixed 2 self-caught bugs along the way: parseBars needed to be Robolectric+total like every other org.json parser in this codebase, and a test var-name collision.

## Do this next
Add DB-layer tests for day_trading_log (write-once/INSERT OR IGNORE, dayTradingLog() round-trip, setDayTradingOutcome, the v8->v9 migration), then compile everything and run the full suite.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  dd922831 ckpt 1486: Recorded Part 15 in TASKS.md: Tj wants every Day Trading recommendation (buy
  542a9db3 ckpt 1483: v7.23 (code 80) shipped end to end: GitHub Actions run #23 built, signed, ve
  83cdf773 ckpt 1481: gated v7.23 (code 80) and pushed it: checkinit, the full unit suite and the 
  1724f891 ckpt 1480: Second code-review pass (requested by Tj as a pre-ship sanity check) plus an
  03d30211 ckpt 703: Code-review pass (high effort) found and fixed 3 real issues in Insider.marke
  5b7d9bf2 ckpt 702: Full Gradle unit suite green: 1090 tests, 0 failures, 0 errors (51.5s) - cove
  5ae3b3d9 ckpt 701: Fixed a fixture-math bug in my own new test: currentListingDedupesByAccession
  f02ab54f ckpt 700: Added tests: 2 new InsiderTest cases for parseCurrentListing (dedup by access
  0759fb83 ckpt 699: Implemented Insider.marketWide(): fetches EDGAR's getcurrent atom feed (pagin
  5fd0c5f4 ckpt 698: Recorded Part 14 in TASKS.md: Tj wants the Insider tab market-wide (all publi
```

(28 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
