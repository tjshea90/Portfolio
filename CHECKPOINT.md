# CHECKPOINT 642 — read me first, then TASKS.md

**Written:** 2026-09-11T09:43:34Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/session-continuity-check-tjlp6d` · **builds on:** `bf012c5` (this checkpoint is the commit after it)

## Just done
Added ResearchRow.atr/vwap/openingRangeHigh/openingRangeLow with JSON round-trip, and the ViewModel's Day Trading live-technicals loop: startDayTradingLive()/stopDayTradingLive() (fgScope-based, so backgrounding the app cancels it outright) driving enrichDayTradingVisible() every 30s for the visible window only. Two care points worked out carefully: (1) the score bonus from ResearchScore.withTechnicals applies EXACTLY ONCE per symbol per rebuild via a new dayTradingTechScored set (cleared alongside analystDone on every fresh stock rebuild) - calling it every 30s tick would have compounded the bonus and duplicated reason lines forever; (2) the list is deliberately NEVER re-sorted by this pass, unlike Best's analyst enrichment, because a live view reshuffling under the user every 30 seconds would be worse than a ranking that occasionally under-ranks a fresh breakout - only entry/stop/target and the raw technicals refresh in place. No Claude calls anywhere in this path, per Tj's explicit screening answer. Fixed a checkinit violation (two properties and a const val needed to move above init / to top level) along the way. compileDebugKotlin green.

## Do this next
Wire the UI: ResearchScreen.kt's LaunchedEffect/DisposableEffect to call startDayTradingLive()/stopDayTradingLive() on entering/leaving the DAY_TRADING tab, the market-session-aware 'up X% today vs last session' label fix (MarketClock.phase()), and the new tap-to-explain detail dialog mirroring RecommendationDialog showing atr/vwap/opening-range and the reasoning behind entry/stop/target. Then the full Gradle unit suite, then a broader UI/code-improvement and bug-fix pass per Tj's closing instruction, then ship.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  47b5e4f ckpt 641: Wired DayTradingTechnicals into ResearchScore.kt: new upgradeLevels(price, tec
  cfee640 ckpt 640: Deep-researched proven day-trading algorithms via WebSearch across many profes
  cb64d0d ckpt 639: Recorded Tj's new Day Trading research request (Part 4 in TASKS.md) verbatim, 
  252e117 ckpt 638: v7.12 (code 69) fully shipped: recorded in BUILDLOG.md, GitHub Actions run #11
  9dd98b5 ckpt 637: gated v7.12 (code 69) and pushed it: checkinit, the full unit suite and the ve
  5b55903 ckpt 636: Full Gradle unit suite green: 871 tests, 0 failures, 0 errors, 0 skipped (849 
  c1fa285 ckpt 635: Wrote and verified tests for Day Trading: new DayTradingTest.kt (16 cases - Re
  0201c31 ckpt 634: Fixed a real bug found while planning tests: carryExplanations() carried notes
  3333c25 ckpt 633: Built the ResearchScreen.kt Day Trading tab: added Section.DAY_TRADING (key/ta
  f69f120 ckpt 632: Finished the ViewModel wiring for Day Trading: added ResearchSet.dtExplained/d
```

(10 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
