# CHECKPOINT 699 — read me first, then TASKS.md

**Written:** 2026-09-16T03:47:08Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `01f2d98` (this checkpoint is the commit after it)

## Just done
Implemented Insider.marketWide(): fetches EDGAR's getcurrent atom feed (paginated, budgeted MAX_MARKET_DOCS_PER_PASS=60/pass), a new parseCurrentListing() for that feed's different schema (category term= not filing-type, accession inside <id>, dedup by accession since it's one entry per party not per filing), reusing Form4.parse/the accession cache unchanged. Verified live against the real EDGAR endpoint first (type=4 prefix-matches 424B2/497/etc past page 2, same trap as the per-symbol path), fixture edgar_current_listing.xml + 2 new InsiderTest cases. compileDebugUnitTestKotlin green. Also implemented the Watchlist %-since-added feature: Db v7->8 (added_price column), WatchEntry model, Db.watchlistEntries/setWatchBaseline/addWatchWithAnchor, backup export/restore fixed to carry added/addedPrice per entry (was silently resetting the anchor on every restore), Row.watchedAt/watchedBasePrice/sinceWatchedPct, PortfolioViewModel.resolveWatchBaselines() (resolves once via the existing chart series, never re-resolves), wired to fire on Watchlist tab open, and the StockRow UI line.

## Do this next
Wire Insider.marketWide into PortfolioViewModel (My stocks/All companies toggle state, on-open fetch trigger, idle when tab closed) and FeedScreen's Insider tab UI, then add tests for both new features and run the full compile.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  5fd0c5f ckpt 698: Recorded Part 14 in TASKS.md: Tj wants the Insider tab market-wide (all public
  29d5afa ckpt 697: v7.22 (code 79) shipped end to end: GitHub Actions run #22 built, signed, veri
  f2c635d ckpt 696: Shipped v7.22 (code 79): ship.sh gate passed (checkinit, full unit suite, vers
  8de9083 ckpt 695: gated v7.22 (code 79) and pushed it: checkinit, the full unit suite and the ve
  33e3b91 ckpt 694: Finished Part 13's implementation: tradePlan now exposes a decline reason (pla
  f8402fb ckpt 693: Recorded Part 13 in TASKS.md: Tj wants actionable Day Trading stocks surfaced 
  3104ec0 ckpt 692: gated v7.21 (code 78) and pushed it: checkinit, the full unit suite and the ve
  ba81be4 ckpt 691: Fixed Day Trading tab: (1) root-caused the red-text flicker and the missing bu
```

(24 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
