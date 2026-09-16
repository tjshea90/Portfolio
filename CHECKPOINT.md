# CHECKPOINT 1519 — read me first, then TASKS.md

**Written:** 2026-09-16T06:17:36Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `8c0de852` (this checkpoint is the commit after it)

## Just done
Added 9 new DbTest cases for day_trading_log: round-trip, the core append-only guarantee (a second recommendation for the same symbol+day never overwrites the first - the literal feature Tj asked for), separate rows for different symbols/days, Claude-source recording, invalid-input rejection, setDayTradingOutcome write/re-resolve/null-exit-price handling, and confirming the log survives backup restore untouched (it's app-local measurement data, deliberately not part of the portable backup). Extended the existing v1-database upgrade test to prove day_trading_log works immediately after a v8->v9 migration, not just that it doesn't crash. All green.

## Do this next
Check whether existing Day Trading test infrastructure (DayTradingTest.kt/DayTradingUiTest.kt) makes a ViewModel-level integration test for captureDayTradingRecommendations feasible without excessive setup; if so add one, otherwise rely on the DB-level coverage since captureDayTradingRecommendations itself is a thin filter+loop over an already-tested DB function. Then run the full suite.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  0be47dfc ckpt 1515: Implemented Part 15's core: Db v8->9 (day_trading_log table, append-only via
  dd922831 ckpt 1486: Recorded Part 15 in TASKS.md: Tj wants every Day Trading recommendation (buy
  542a9db3 ckpt 1483: v7.23 (code 80) shipped end to end: GitHub Actions run #23 built, signed, ve
  83cdf773 ckpt 1481: gated v7.23 (code 80) and pushed it: checkinit, the full unit suite and the 
  1724f891 ckpt 1480: Second code-review pass (requested by Tj as a pre-ship sanity check) plus an
  03d30211 ckpt 703: Code-review pass (high effort) found and fixed 3 real issues in Insider.marke
  5b7d9bf2 ckpt 702: Full Gradle unit suite green: 1090 tests, 0 failures, 0 errors (51.5s) - cove
  5ae3b3d9 ckpt 701: Fixed a fixture-math bug in my own new test: currentListingDedupesByAccession
  f02ab54f ckpt 700: Added tests: 2 new InsiderTest cases for parseCurrentListing (dedup by access
  0759fb83 ckpt 699: Implemented Insider.marketWide(): fetches EDGAR's getcurrent atom feed (pagin
```

(3 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
