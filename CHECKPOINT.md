# CHECKPOINT 1528 — read me first, then TASKS.md

**Written:** 2026-09-16T06:26:24Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `5334e7e1` (this checkpoint is the commit after it)

## Just done
Requested code-review pass surfaced 3 real bugs in the ALREADY-SHIPPED (v7.23) watchlist %-since-added feature, not the new Day Trading log work - fixed all three since Tj asked to check the whole app, not just the new feature. (1) closeOnOrAfter picked the FIRST point of the add-day via minByOrNull instead of the LAST (the actual close) - invisible in the original tests because a daily-interval series has only one point per day, but wrong for any symbol added within the last ~6 days (resolves from the 30-min/5-min series, which has many points per day). (2) The lookback was measured from the exact add timestamp instead of the start of the add-day, so a symbol added late in the evening could pick ChartRange.D1 (which only ever contains TODAY's candles) and silently never resolve a baseline for a prior calendar day. (3) resolveWatchBaselines fetched pending symbols one at a time instead of with the same bounded-concurrency pattern every other per-symbol loop in this file uses. Fixed: extracted the selection logic into a new top-level internal fun lastCloseInWindow (testable without network, now covered by 4 new regression tests including the exact shipped-bug scenario), lookback now measured from dayStart, and resolveWatchBaselines uses Semaphore(MAX_PARALLEL_REQUESTS)+async/awaitAll like everywhere else. All green.

## Do this next
Run the full Gradle suite once more (now covering both the Day Trading log feature and these watchlist fixes), then decide whether to ship.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  a11832bb ckpt 1520: Full Gradle unit suite green: 1121 tests, 0 failures, 0 errors (56.5s, verif
  07e3d492 ckpt 1519: Added 9 new DbTest cases for day_trading_log: round-trip, the core append-on
  0be47dfc ckpt 1515: Implemented Part 15's core: Db v8->9 (day_trading_log table, append-only via
  dd922831 ckpt 1486: Recorded Part 15 in TASKS.md: Tj wants every Day Trading recommendation (buy
  542a9db3 ckpt 1483: v7.23 (code 80) shipped end to end: GitHub Actions run #23 built, signed, ve
  83cdf773 ckpt 1481: gated v7.23 (code 80) and pushed it: checkinit, the full unit suite and the 
  1724f891 ckpt 1480: Second code-review pass (requested by Tj as a pre-ship sanity check) plus an
  03d30211 ckpt 703: Code-review pass (high effort) found and fixed 3 real issues in Insider.marke
  5b7d9bf2 ckpt 702: Full Gradle unit suite green: 1090 tests, 0 failures, 0 errors (51.5s) - cove
  5ae3b3d9 ckpt 701: Fixed a fixture-math bug in my own new test: currentListingDedupesByAccession
```

(7 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
