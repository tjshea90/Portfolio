# CHECKPOINT 1480 — read me first, then TASKS.md

**Written:** 2026-09-16T04:55:34Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `dd80d084` (this checkpoint is the commit after it)

## Just done
Second code-review pass (requested by Tj as a pre-ship sanity check) plus an independent regression-sweep agent found 3 more real issues in Insider.marketWide, all fixed: a per-document network failure (fetch returns null, not Unreadable) was counted as 'resolved' by the old remaining calc, so a transient timeout inside a window that fit the budget silently and permanently dropped that filing once pageStart advanced past it; a failed EDGAR listing page (one of the 3 per pass) shrank refs without any signal, so a window could look fully drained and get skipped past even though a third of it was never actually read; and MarketResult was re-including every already-cached filing the caller already had, redundant sort/dedupe work on every Load-more tap. Fixed: fetch failures are tracked separately from skip-worthy 'Unreadable' docs so they stay retryable; currentListing's own .answered flag now forces remaining>=1 whenever any of the 3 pages failed to answer; MarketResult.filings returns only newly-fetched entries, not the caller's own cached ones echoed back. The independent regression-sweep agent found zero issues across Row construction sites, Db.watchlist() vs watchlistEntries() callers, backup format consumers, DB_VERSION/BACKUP_VERSION references, and Insider-store confusion. Full suite re-verified green for real (blocking wait, not a premature check): 1090 tests, 0 failures, 86s. Bumped versionCode 79->80, versionName 7.22->7.23 in app/build.gradle.kts, ready for ship.sh.

## Do this next
Run ship.sh to gate and push this release, then trigger the GitHub Actions build, confirm it goes green, record it in BUILDLOG.md, and report the Release page link to Tj (not the APK bytes, per the standing rule).

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  03d30211 ckpt 703: Code-review pass (high effort) found and fixed 3 real issues in Insider.marke
  5b7d9bf2 ckpt 702: Full Gradle unit suite green: 1090 tests, 0 failures, 0 errors (51.5s) - cove
  5ae3b3d9 ckpt 701: Fixed a fixture-math bug in my own new test: currentListingDedupesByAccession
  f02ab54f ckpt 700: Added tests: 2 new InsiderTest cases for parseCurrentListing (dedup by access
  0759fb83 ckpt 699: Implemented Insider.marketWide(): fetches EDGAR's getcurrent atom feed (pagin
  5fd0c5f4 ckpt 698: Recorded Part 14 in TASKS.md: Tj wants the Insider tab market-wide (all publi
  29d5afa5 ckpt 697: v7.22 (code 79) shipped end to end: GitHub Actions run #22 built, signed, ver
  f2c635d8 ckpt 696: Shipped v7.22 (code 79): ship.sh gate passed (checkinit, full unit suite, ver
  8de9083e ckpt 695: gated v7.22 (code 79) and pushed it: checkinit, the full unit suite and the v
  33e3b919 ckpt 694: Finished Part 13's implementation: tradePlan now exposes a decline reason (pl
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
