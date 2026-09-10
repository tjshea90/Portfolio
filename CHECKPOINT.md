# CHECKPOINT 596 — read me first, then TASKS.md

**Written:** 2026-09-10T21:21:18Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `3adcc05` (this checkpoint is the commit after it)

## Just done
SHIPPED v7.8 (versionCode 65). All ship gates green: checkinit, build environment (SDK + keystore fingerprint), 806 unit tests 0 failures, signed release build, versionCode 65 higher than every previous ship, APK committed to releases/ and pushed to GitHub. Verified the SHIPPED ARTIFACT itself with tools/verify-apk.sh - Portfolio-v7.8.apk carries certificate 2e8c3847...f396a9f2, the one the phone accepts as an in-place update, so it will not erase the portfolio. Contents: the ScrollableTabRow IndexOutOfBounds crash fix (root-caused from Tj's own device crash log), chart axis labels carrying the year when a window spans calendar years, and the FeedScreen dedupe-by-render-key hardening. Both of Tj's reports are now answered: the chart comparison was CORRECT all along and the axis was hiding why the numbers moved; the news crash was the tab strip, not the news.

## Do this next
Nothing outstanding. If Tj reports the crash again after installing v7.8, get Settings -> Crash log again - the tab-strip fix is verified by a test that reproduces the exact stack, but a second, different crash would show up there. TASKS.md can be reset to 'no active job' once he confirms the build installs.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  3adcc05 ship v7.8: Chart axis labels now carry the year when a window spans calendar years - a p
  66e50e1 ckpt 595: Fixed my own broken regression test rather than the code: DetailTabCrashTest's
  5e88705 ckpt 594: ROOT-CAUSED the crash from the device log Tj sent - and it was NOT what I gues
  64b2e98 ckpt 593: Investigated Tj's two reports from the screen recording and fixed both finding
  cd3efec ckpt 592: Wrote Tj's new request into TASKS.md before any code: a chart-vs-SPY compariso
  ea956c3 ckpt 591: Re-verified CI after bumping actions/checkout and actions/setup-java to v5: ru
  ce48c8f ckpt 590: CI IS LIVE AND VERIFIED. Tj added the SIGNING_KEYSTORE_BASE64 secret and run #
  e7eb17f ckpt 589: Restructured the CI workflow so it cannot waste GitHub's free minutes, then va
  1d9b76c ckpt 588: Added GitHub Actions signed-release CI on Tj's decision, and closed the local 
  f42b374 ckpt 587: Ticked TASKS.md 1-5 (all written, tested via tools/test_resume.sh's 19 checks,
```
