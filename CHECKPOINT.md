# CHECKPOINT 651 — read me first, then TASKS.md

**Written:** 2026-09-11T15:13:49Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-signals-research-6t8rr3` · **builds on:** `c02a9dd` (this checkpoint is the commit after it)

## Just done
Review pass on the new engine found and fixed three real issues before shipping: (1) the plan was computed from the RAW tick's technicals while the card displayed per-field fallbacks, so a tick where only the intraday half failed built a plan as if the stock had no VWAP while still showing one - both now use one effectiveTechnicals() reading; (2) the 30-second live sweep silently overwrote an imported Claude plan within half a minute of the import, defeating the round trip - a Claude plan now stands until the next full rebuild; (3) the stop floor was 0.75x the 5-minute ATR, below the 1.5x-2.5x band the sources actually give, and since a breakout's nearest support IS the level just broken, that floor is what every breakout stop lands on - raised to 1.5x. Also added a thin-reward warning when real resistance sits closer than 2R. 922 tests, 0 failures.

## Do this next
Re-read the whole diff once more for bugs, then bump versionCode/versionName and ship.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  303ef0e ckpt 650: Rebuilt the Day Trading level engine on real setups (Round 69). ResearchScore.
  378940f ckpt 649: Recorded Tj's Part 5 day-trading request (real entry triggers, Claude may rewr
  05036b9 ckpt 648: v7.13 (code 70) fully and correctly shipped: run #13 built the right commit (e
  1037269 ckpt 647: RESOLVED A BRANCH DIVERGENCE: a sibling session (branch claude/resume-function
  8ab5217 ckpt 646: gated v7.13 (code 70) and pushed it: checkinit, the full unit suite and the ve
  9770178 ckpt 645: Ran the /code-review skill (extra-high effort) against everything built this s
  a16e560 ckpt 644: Grounded Claude's export/import path in the new real technicals: DayTradingBri
  3063ff2 ckpt 643: Wired the UI: ResearchScreen.kt now starts/stops the Day Trading live-technica
  3554f35 ckpt 642: Added ResearchRow.atr/vwap/openingRangeHigh/openingRangeLow with JSON round-tr
  47b5e4f ckpt 641: Wired DayTradingTechnicals into ResearchScore.kt: new upgradeLevels(price, tec
```

(6 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
