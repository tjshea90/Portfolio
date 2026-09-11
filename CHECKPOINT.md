# CHECKPOINT 628 — read me first, then TASKS.md

**Written:** 2026-09-11T02:28:38Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/portfolio-recommendations-tab-jc8y40` · **builds on:** `de75de8` (this checkpoint is the commit after it)

## Just done
gated v7.11 (code 68) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
send Tj the APK from the Release and then run:
  bash tools/record-release.sh v7.11 "Moved the buy/hold/sell indicator off the detail screen's tab row to sit beside the price, where it was pointed to in a screenshot - same verdict and popup, just relocated. Fixed the full-screen chart's SPY overlay jumping during a pan: the comparison-mode rebase anchor was snapping to a new candle once a day of drag crossed one, invisible on the stock's own line but a violent jump on the benchmark line and the dashed zero reference; now frozen for the life of a gesture and re-synced once it ends. 849 tests, 0 failures."

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  38d61c9 ckpt 627: Fixed the SPY compare-line pan bug (root-caused by a background Explore agent,
  7d1f651 ckpt 626: Moved the buy/hold/sell indicator per Tj's screenshot: removed DetailTab.RECOM
  7d12935 ckpt 625: Wrote Tj's three-part request into TASKS.md verbatim, screened separately per 
  ccfdac2 ckpt 624: v7.10 (code 67) shipped end to end: GitHub Actions run #9 built, signed, verif
  ef6d8db ckpt 623: gated v7.10 (code 67) and pushed it: checkinit, the full unit suite and the ve
  f092746 ckpt 622: Wrote 7 new tests (StockRowRecommendationUiTest.kt: placeholder chip, Buy/Hold
  fe1ed74 ckpt 621: Wired the BUY/HOLD/SELL chip into the main Portfolio tab: StockRowItem (ui/Sto
  837ccdf ckpt 620: Wrote Tj's request into TASKS.md verbatim - show the buy/hold/sell badge next 
  57cdf7c ckpt 619: Reset TASKS.md to no-active-job now that both this session's jobs (the model s
  9d571d6 ckpt 618: v7.9 (code 66) shipped end to end: GitHub Actions run #8 built, signed, verifi
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
