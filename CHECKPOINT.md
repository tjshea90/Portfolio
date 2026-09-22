# CHECKPOINT 1591 — read me first, then TASKS.md

**Written:** 2026-09-22T18:32:21Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-app-testing-i6cfun` · **builds on:** `0a03f97` (this checkpoint is the commit after it)

## Just done
Floor green (1261/0). A-H2/A-M3/A-M4/A-L7/D-H1 ticked. Wrote D-H2 (account figure now applies the 25% position cap per trade, cappedTrades count on card) and S-H1 (all-stale dated coverage no longer falls back to 45-90% undated trust; correct reason line; Recommendation.allRatingsStale). Suite re-run in background (scratchpad run2.log).

## Do this next
When run2 is green tick D-H2, S-H1. Then MEDIUMs: D-M3, D-M4, S-M2, S-M3, S-M4, N-M1..M4, U-M1, U-M2, U-M5, A-M5; then LOWs.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  c56eca8 ckpt 1590: Wrote A-H2/A-M3/A-M4/A-L7: Ledger.replayOrder (split-first on date ties + ove
  a071192 ckpt 1589: Resumed full-tests fix pass. Finished A-H1 (EditPositionDialog only overrides
  f0245d7 ckpt 1588: All 5 audit agents reported (~45 findings incl. HIGHs in accounting, day-trad
  d816196 ckpt 1587: Found and fixed (own review of v7.33 diff): session rollover left planByClaud
  24b6fc9 ckpt 1586: Resumed: confirmed v7.33 (code 90) is published (get_release_by_tag) and alre
  ec4e6d1 ckpt 1585: gated v7.33 (code 90) and pushed it: checkinit, the full unit suite and the v
  fba8e12 ckpt 1584: Ran the CLAUDE.md 'Full tests' protocol Tj asked for after the APK-report UI 
  7916349 ckpt 1583: Reviewed the attached third-party APK static-analysis report (v7.31) per Tj's
  8b5a8fc ckpt 1582: gated v7.32 (code 89) and pushed it: checkinit, the full unit suite and the v
  0f6e081 ckpt 1581: Diagnosed and fixed the stuck pull-to-refresh spinner Tj reported (screenshot
```

(8 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
