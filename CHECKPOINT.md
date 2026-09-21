# CHECKPOINT 1580 — read me first, then TASKS.md

**Written:** 2026-09-21T15:24:47Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-success-rate-gonujf` · **builds on:** `70e8d24` (this checkpoint is the commit after it)

## Just done
Audited the day-trading success-rate feature per Tj's request (numbers 'seem too good to be true'). Traced the full pipeline (recording in PortfolioViewModel.captureDayTradingRecommendations, evaluation in DayTradingEval.evaluate/stats, display in ResearchScreen's DayTradingSuccessRate) and ran the 36-test DayTradingEvalTest suite standalone (36/36 green). Confirmed: it evaluates real 5-minute intraday bars for exact entry/stop/target crossings after recordedAt only - never a whole-day open/close delta - resolves same-bar ambiguity conservatively (never in the strategy's favor), excludes untriggered picks from the rate denominator, and the headline figure is already net of a realistic slippage/cost model that can only make results worse. No bug found; no code changes needed. Logged the request and findings in TASKS.md.

## Do this next
Nothing pending - await Tj's next request. If he still doubts the numbers after this explanation, the next useful step would be exposing the underlying per-trade log rows on screen (symbol/day/entry/stop/target/outcome) so he can spot-check a few himself against a chart, rather than re-auditing code that 3 separate prior sessions have already reviewed for this exact concern.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  65750c2 ckpt 1579: gated v7.31 (code 88) and pushed it: checkinit, the full unit suite and the v
  7fc4b13 ckpt 1578: Full-tests audit (4 parallel subsystem agents) reconciled and fixed: HIGH bug
  9694006 ckpt 1577: gated v7.30 (code 87) and pushed it: checkinit, the full unit suite and the v
  28ccdb6 ckpt 1576: Fixed the SPY-comparison chart's pan/zoom baseline bug (comparison anchor now
  7ff65c2 ckpt 1575: Fixed Best-Stocks refresh flicker (enrichJob left running as a stray sibling 
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
