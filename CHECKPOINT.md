# CHECKPOINT 627 — read me first, then TASKS.md

**Written:** 2026-09-11T02:26:02Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/portfolio-recommendations-tab-jc8y40` · **builds on:** `508bddc` (this checkpoint is the commit after it)

## Just done
Fixed the SPY compare-line pan bug (root-caused by a background Explore agent, verified independently against the extracted video frames first): PriceChart.kt's rebase anchor for comparison mode was a CANDLE INDEX (insideRange.first) that only advances once a full day of drag crosses a real candle on a 1Y chart - each crossing re-rebased both percent lines from a brand-new anchor in one discrete step. Invisible on the stock's own line (the shared y-axis is dominated by its much wider range) but a violent jump on the benchmark's line and the dashed zero-reference line (both read off that same shared axis). Fixed by freezing the anchor for the life of a live gesture (gestureLive-gated, mirroring the existing held.window pattern) and re-syncing once it ends. CAUGHT MY OWN BUG mid-fix: the first draft froze an INDEX into , which is a re-sliced view whose shape changes as the window pans - a frozen index silently points at a different candle every frame, worse than the original bug. Corrected to freeze the TIMESTAMP instead, with the stock's own anchor VALUE looked up from the unclipped  series (new  override param on primaryPercents) rather than an index into the changing . Tests: 2 new pure-logic cases for the fromValue override in CompareChartTest.kt, and a full gesture-simulation regression test (ComparePanAnchorUiTest.kt) that drives a real multi-step one-finger pan across many candles within one continuous gesture and proves the rendered benchmark readout matches the FROZEN-anchor expectation mid-drag and the LIVE-anchor expectation after lifting - computed via the same production functions, fed the real window PriceChart reported, not hand-predicted. Full suite: 849 tests, 0 failures, including all 176 pre-existing chart tests (no regressions).

## Do this next
Finish TASKS.md for parts 1+2 (tab placement + chart bug, both done and tested), bump versionCode/versionName, ship.sh, trigger build, send Tj the APK.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  7d1f651 ckpt 626: Moved the buy/hold/sell indicator per Tj's screenshot: removed DetailTab.RECOM
  7d12935 ckpt 625: Wrote Tj's three-part request into TASKS.md verbatim, screened separately per 
  ccfdac2 ckpt 624: v7.10 (code 67) shipped end to end: GitHub Actions run #9 built, signed, verif
  ef6d8db ckpt 623: gated v7.10 (code 67) and pushed it: checkinit, the full unit suite and the ve
  f092746 ckpt 622: Wrote 7 new tests (StockRowRecommendationUiTest.kt: placeholder chip, Buy/Hold
  fe1ed74 ckpt 621: Wired the BUY/HOLD/SELL chip into the main Portfolio tab: StockRowItem (ui/Sto
  837ccdf ckpt 620: Wrote Tj's request into TASKS.md verbatim - show the buy/hold/sell badge next 
  57cdf7c ckpt 619: Reset TASKS.md to no-active-job now that both this session's jobs (the model s
  9d571d6 ckpt 618: v7.9 (code 66) shipped end to end: GitHub Actions run #8 built, signed, verifi
  0c8acb5 ckpt 617: gated v7.9 (code 66) and pushed it: checkinit, the full unit suite and the ver
```

(6 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
