# CHECKPOINT 1486 — read me first, then TASKS.md

**Written:** 2026-09-16T05:56:56Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `ead919cc` (this checkpoint is the commit after it)

## Just done
Recorded Part 15 in TASKS.md: Tj wants every Day Trading recommendation (buy/sell targets) recorded permanently (never deleted/modified even when a plan later 'falls apart'), then a button to see the real success rate and simulated portfolio return based on whether actual same-day intraday prices hit the targets AFTER the recommendation was made (no lookahead). Screened as money-accuracy (simulated trading P&L) + ambiguous novel design + Tj's own repeated correctness emphasis - flagged for Opus, but Tj explicitly said 'Do this on the current sonnet model' before the flag finished posting, so proceeding on Sonnet per SCREENER.md's own override rule.

## Do this next
Read ResearchScore.kt's TradePlan/ResearchRow to see exactly what fields (setup, entry/stop/target, direction, source) already exist to build the capture snapshot from, then design the DB schema for the append-only recommendation log.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  542a9db3 ckpt 1483: v7.23 (code 80) shipped end to end: GitHub Actions run #23 built, signed, ve
  83cdf773 ckpt 1481: gated v7.23 (code 80) and pushed it: checkinit, the full unit suite and the 
  1724f891 ckpt 1480: Second code-review pass (requested by Tj as a pre-ship sanity check) plus an
  03d30211 ckpt 703: Code-review pass (high effort) found and fixed 3 real issues in Insider.marke
  5b7d9bf2 ckpt 702: Full Gradle unit suite green: 1090 tests, 0 failures, 0 errors (51.5s) - cove
  5ae3b3d9 ckpt 701: Fixed a fixture-math bug in my own new test: currentListingDedupesByAccession
  f02ab54f ckpt 700: Added tests: 2 new InsiderTest cases for parseCurrentListing (dedup by access
  0759fb83 ckpt 699: Implemented Insider.marketWide(): fetches EDGAR's getcurrent atom feed (pagin
  5fd0c5f4 ckpt 698: Recorded Part 14 in TASKS.md: Tj wants the Insider tab market-wide (all publi
  29d5afa5 ckpt 697: v7.22 (code 79) shipped end to end: GitHub Actions run #22 built, signed, ver
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
