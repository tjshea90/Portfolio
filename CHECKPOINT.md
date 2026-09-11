# CHECKPOINT 649 — read me first, then TASKS.md

**Written:** 2026-09-11T14:37:19Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-signals-research-6t8rr3` · **builds on:** `05036b9` (this checkpoint is the commit after it)

## Just done
Recorded Tj's Part 5 day-trading request (real entry triggers, Claude may rewrite the section) verbatim in TASKS.md before touching code. Confirmed the defect in ResearchScore: both tradeLevels() and upgradeLevels() hardcode entry = r.price, so the 'target buy' is always spot.

## Do this next
Deep research on day-trading entry-trigger methodology (ORB, VWAP reclaim/pullback, pivots, measured moves, ATR targets) from professional sources, then rebuild the level engine around a named setup + trigger.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  05036b9 ckpt 648: v7.13 (code 70) fully and correctly shipped: run #13 built the right commit (e
  1037269 ckpt 647: RESOLVED A BRANCH DIVERGENCE: a sibling session (branch claude/resume-function
  8ab5217 ckpt 646: gated v7.13 (code 70) and pushed it: checkinit, the full unit suite and the ve
  9770178 ckpt 645: Ran the /code-review skill (extra-high effort) against everything built this s
  a16e560 ckpt 644: Grounded Claude's export/import path in the new real technicals: DayTradingBri
  3063ff2 ckpt 643: Wired the UI: ResearchScreen.kt now starts/stops the Day Trading live-technica
  3554f35 ckpt 642: Added ResearchRow.atr/vwap/openingRangeHigh/openingRangeLow with JSON round-tr
  47b5e4f ckpt 641: Wired DayTradingTechnicals into ResearchScore.kt: new upgradeLevels(price, tec
  43650fa ckpt 640: Resumed after a container restart into a stale-and-blocking checkpoint, and re
  cb64d0d ckpt 639: Recorded Tj's new Day Trading research request (Part 4 in TASKS.md) verbatim, 
```
