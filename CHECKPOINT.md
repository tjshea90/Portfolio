# CHECKPOINT 639 — read me first, then TASKS.md

**Written:** 2026-09-11T09:14:05Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/session-continuity-check-tjlp6d` · **builds on:** `94a780c` (this checkpoint is the commit after it)

## Just done
Recorded Tj's new Day Trading research request (Part 4 in TASKS.md) verbatim, along with his two clarifying answers that lock the design: no automatic Claude calls anywhere in this feature (Claude only on an explicit button tap or the existing export/import round trip), the app's own data pipeline may run as aggressively as needed but ONLY while the tab is open and must go idle otherwise, stay in the honest risk-plan framing while researching a better algorithm, fix the 'up today' mislabel when the market is closed, and add a tap-to-explain detail view per stock. Also updated CLAUDE.md per Tj's instruction: he no longer wants the APK relayed through chat - he'll grab it himself from the Release page - and documented the session-tooling gap (no MCP tool for private-repo release-asset binary download) as settled, not something to keep re-attempting. Screened this request: money-accuracy + ambiguous design + Tj's own words ('accuracy is important') all trip SCREENER.md, session confirmed still on claude-sonnet-5 (not Opus), so flagging in chat now and stopping before any code changes.

## Do this next
Flagged for Opus in chat; nothing in Part 4 has been touched. Wait for Tj to either switch models and resend/say go, or explicitly say proceed on Sonnet. Do not start the day-trading algorithm research or rework until one of those happens.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  252e117 ckpt 638: v7.12 (code 69) fully shipped: recorded in BUILDLOG.md, GitHub Actions run #11
  9dd98b5 ckpt 637: gated v7.12 (code 69) and pushed it: checkinit, the full unit suite and the ve
  5b55903 ckpt 636: Full Gradle unit suite green: 871 tests, 0 failures, 0 errors, 0 skipped (849 
  c1fa285 ckpt 635: Wrote and verified tests for Day Trading: new DayTradingTest.kt (16 cases - Re
  0201c31 ckpt 634: Fixed a real bug found while planning tests: carryExplanations() carried notes
  3333c25 ckpt 633: Built the ResearchScreen.kt Day Trading tab: added Section.DAY_TRADING (key/ta
  f69f120 ckpt 632: Finished the ViewModel wiring for Day Trading: added ResearchSet.dtExplained/d
  cab1920 ckpt 631: Resumed after the interruption bootstrap flagged: verified the uncommitted net
  fcebfd2 ckpt 630: Wrote buildDayTrading() and toDayTradingRow() in Research.kt, restoring compil
  86067cb ckpt 629: Tj explicitly overrode the SCREENER flag: proceed with the day trading tab on 
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
