# CHECKPOINT 698 — read me first, then TASKS.md

**Written:** 2026-09-16T03:36:43Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/insider-activity-watchlist-bkzu26` · **builds on:** `5b1894d` (this checkpoint is the commit after it)

## Just done
Recorded Part 14 in TASKS.md: Tj wants the Insider tab market-wide (all public companies, not just held/watched) with a legitimacy signal, plus a Watchlist %-since-added feature excluding the add day. Screened both - stay on Sonnet (no cost-basis/ledger/recommendation-scoring involved; the 10b5-1 legitimacy signal already exists in Form4.kt/InsiderModels.kt). Design decided: market-wide via EDGAR's getcurrent feed with a My-stocks/All-companies toggle and a $50k major-trade floor; watchlist baseline = the add-day's close, resolved once via the existing chart fetch, DB v8 added_price column, backup/restore fixed to stop silently resetting the anchor date.

## Do this next
Implement Insider.marketWide() (EDGAR getcurrent listing, pagination, budget) and wire it into PortfolioViewModel + FeedScreen's new My stocks/All companies toggle.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
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
