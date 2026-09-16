# CHECKPOINT 700 — read me first, then TASKS.md

**Written:** 2026-09-16T03:56:10Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `ff14521` (this checkpoint is the commit after it)

## Just done
Added tests: 2 new InsiderTest cases for parseCurrentListing (dedup by accession across party entries, drops non-4 form types like the 424B2 trap), 9 new DbTest cases for the watchlist %-since-added feature (watchlistEntries, setWatchBaseline write-once, addWatchWithAnchor, backup round-trip keeping the original add date/baseline instead of resetting it, old bare-string backup still restores), extended the v1-DB upgrade test to check added_price arrives as a real 0.0. New WatchSinceAddedTest.kt: Row.sinceWatchedPct pure arithmetic (positive/negative/no-quote-yet/no-baseline-yet) plus ViewModel-level add-watch/recompute wiring. Extended tools/insider_sim.py with a --market mode mirroring Insider.marketWide, verified live against the real SEC getcurrent feed - produced 10 genuine major discretionary trades (CEO/director/10%-owner buys and sells) from one page, confirming the endpoint, parser and $50k floor all work end to end against real data.

## Do this next
Run the full Gradle unit suite (all pre-existing tests plus everything from this round) and fix anything red before checkpointing again.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
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

(20 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
