# CHECKPOINT 701 — read me first, then TASKS.md

**Written:** 2026-09-16T03:57:32Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `f1abb82` (this checkpoint is the commit after it)

## Just done
Fixed a fixture-math bug in my own new test: currentListingDedupesByAccessionAndDropsOtherFormTypes expected 4 distinct accessions but the edgar_current_listing.xml fixture only has 3 (two accession-sharing pairs plus the excluded 424B2, out of 6 entry blocks) - corrected the assertion and comment. Targeted re-run green: DbTest, WatchSinceAddedTest, InsiderTest, WatchToggleTest all pass with 0 failures/errors.

## Do this next
Run the full Gradle unit test suite (all ~1075+ tests) to catch any regression from this round's changes, then do a code-review pass over the whole diff before considering Part 14 done.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  f02ab54 ckpt 700: Added tests: 2 new InsiderTest cases for parseCurrentListing (dedup by accessi
  0759fb8 ckpt 699: Implemented Insider.marketWide(): fetches EDGAR's getcurrent atom feed (pagina
  5fd0c5f ckpt 698: Recorded Part 14 in TASKS.md: Tj wants the Insider tab market-wide (all public
  29d5afa ckpt 697: v7.22 (code 79) shipped end to end: GitHub Actions run #22 built, signed, veri
  f2c635d ckpt 696: Shipped v7.22 (code 79): ship.sh gate passed (checkinit, full unit suite, vers
  8de9083 ckpt 695: gated v7.22 (code 79) and pushed it: checkinit, the full unit suite and the ve
  33e3b91 ckpt 694: Finished Part 13's implementation: tradePlan now exposes a decline reason (pla
  f8402fb ckpt 693: Recorded Part 13 in TASKS.md: Tj wants actionable Day Trading stocks surfaced 
  3104ec0 ckpt 692: gated v7.21 (code 78) and pushed it: checkinit, the full unit suite and the ve
  ba81be4 ckpt 691: Fixed Day Trading tab: (1) root-caused the red-text flicker and the missing bu
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
