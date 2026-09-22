# CHECKPOINT 1590 — read me first, then TASKS.md

**Written:** 2026-09-22T18:26:48Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-app-testing-i6cfun` · **builds on:** `7910194` (this checkpoint is the commit after it)

## Just done
Wrote A-H2/A-M3/A-M4/A-L7: Ledger.replayOrder (split-first on date ties + oversell-driven same-date group reversal), ImportOrder.chronological for new imports, ImportDupes one-to-one dup classification with REPEAT badge, prompt wording, parseDate 2-digit-year fix; ReplayOrderTest (15 tests). Suite not yet run (Maven 429; added ~/.gradle/init.d/central-mirror.gradle swapping Central for Google's mirror - container-local).

## Do this next
Get gradle suite green (floor.log in scratchpad), tick A-H2/M3/M4/L7, then D-H1, D-H2, S-H1, MEDIUMs, LOWs.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  a071192 ckpt 1589: Resumed full-tests fix pass. Finished A-H1 (EditPositionDialog only overrides
  f0245d7 ckpt 1588: All 5 audit agents reported (~45 findings incl. HIGHs in accounting, day-trad
  d816196 ckpt 1587: Found and fixed (own review of v7.33 diff): session rollover left planByClaud
  24b6fc9 ckpt 1586: Resumed: confirmed v7.33 (code 90) is published (get_release_by_tag) and alre
  ec4e6d1 ckpt 1585: gated v7.33 (code 90) and pushed it: checkinit, the full unit suite and the v
  fba8e12 ckpt 1584: Ran the CLAUDE.md 'Full tests' protocol Tj asked for after the APK-report UI 
  7916349 ckpt 1583: Reviewed the attached third-party APK static-analysis report (v7.31) per Tj's
  8b5a8fc ckpt 1582: gated v7.32 (code 89) and pushed it: checkinit, the full unit suite and the v
  0f6e081 ckpt 1581: Diagnosed and fixed the stuck pull-to-refresh spinner Tj reported (screenshot
  ba64fb7 ckpt 1580: Audited the day-trading success-rate feature per Tj's request (numbers 'seem 
```

(9 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
