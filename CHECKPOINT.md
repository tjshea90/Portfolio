# CHECKPOINT 703 — read me first, then TASKS.md

**Written:** 2026-09-16T04:13:00Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `56fdfd7` (this checkpoint is the commit after it)

## Just done
Code-review pass (high effort) found and fixed 3 real issues in Insider.marketWide/loadMoreMarketInsiders: Load-more could get permanently stuck on a page once its real content ran dry (pageStart only advanced inside an isNotEmpty guard); leftover refs past the 60-doc budget in a busy 300-entry window were lost forever the instant Load-more advanced past that window instead of draining it (fixed: Insider.marketWide now returns MarketResult(filings, remaining) and the caller only advances once a window is fully drained, re-listing the same window in between - the same carry-over forSymbols already relies on); and the market-wide firehose was writing into the SAME bounded accession cache the portfolio-scoped feed depends on, risking a wholesale-clear-on-overflow that forces a redundant re-fetch of a held stock's own filings (fixed: market-wide reads insiderDocs only to cross-reference, never writes into it; its own accumulated list is its cache). Also fixed: insiderSummary's hardcoded 'in the last month' suffix was wrong for the unbounded All-companies view. Also caught and documented a real methodology bug this session: a backgrounded gradle run detached with '& disown' finishes independently of the run_in_background tool's completion notification, which only tracks the wrapper shell - two premature checks read stale pre-fix test reports as if they were fresh. Fixed by blocking on the log directly with an until-loop instead. Full suite re-verified green for real this time: 1090 tests, 0 failures, 58.2s. TASKS.md Part 14 checklist all ticked except shipping, which needs Tj's go-ahead.

## Do this next
Report Part 14 complete to Tj and ask whether to ship it as the next release.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  5b7d9bf ckpt 702: Full Gradle unit suite green: 1090 tests, 0 failures, 0 errors (51.5s) - cover
  5ae3b3d ckpt 701: Fixed a fixture-math bug in my own new test: currentListingDedupesByAccessionA
  f02ab54 ckpt 700: Added tests: 2 new InsiderTest cases for parseCurrentListing (dedup by accessi
  0759fb8 ckpt 699: Implemented Insider.marketWide(): fetches EDGAR's getcurrent atom feed (pagina
  5fd0c5f ckpt 698: Recorded Part 14 in TASKS.md: Tj wants the Insider tab market-wide (all public
  29d5afa ckpt 697: v7.22 (code 79) shipped end to end: GitHub Actions run #22 built, signed, veri
  f2c635d ckpt 696: Shipped v7.22 (code 79): ship.sh gate passed (checkinit, full unit suite, vers
  8de9083 ckpt 695: gated v7.22 (code 79) and pushed it: checkinit, the full unit suite and the ve
  33e3b91 ckpt 694: Finished Part 13's implementation: tradePlan now exposes a decline reason (pla
  f8402fb ckpt 693: Recorded Part 13 in TASKS.md: Tj wants actionable Day Trading stocks surfaced 
```

(7 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
