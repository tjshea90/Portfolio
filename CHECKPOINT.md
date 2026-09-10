# CHECKPOINT 594 — read me first, then TASKS.md

**Written:** 2026-09-10T21:16:49Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `29079f1` (this checkpoint is the commit after it)

## Just done
ROOT-CAUSED the crash from the device log Tj sent - and it was NOT what I guessed. Real stack: IndexOutOfBoundsException: Index 5 out of bounds for length 5 at TabRowKt$ScrollableTabRow$1.invoke(TabRow.kt:1409), four times between 9:42 and 9:43 AM. That is Material3's DEFAULT tab indicator doing tabPositions[selectedTabIndex], and it has nothing to do with news data. visibleTabs() hides the Holdings tab until the fund lookup returns, which is about a second after the screen opens - the same second the news headlines land. So: open a stock (5 tabs), tap News (index 4), lookup returns 'fund', tabs grows to 6 so indexOf(NEWS) becomes 5, and the strip recomposes with selectedTabIndex=5 while tabPositions still holds the 5 entries from the previous measure pass. The existing coerceAtLeast(0) could not help - it guards the list SHRINKING under a selection, and this is the list GROWING. Fixed by extracting DetailTabRow with a custom indicator that clamps to positions.lastIndex, so a one-frame disagreement misplaces the indicator for a frame instead of taking the process down. Added DetailTabCrashTest which reproduces it through the real composable with createComposeRule. Also corrected two fixtures in my own ChartAxisYearTest that were wrong (the 'same calendar year' pair was actually Dec-2025 to May-2026, and the shared title prefix was 71 chars against an 80-char id truncation so the ids never collided). Being explicit: the FeedScreen dedupe fix from the previous checkpoint is a real crash class but was NOT this crash, and is not credited with it.

## Do this next
Full suite re-running. When green: bash ship.sh for v7.8 (versionCode 65), then send the APK to Tj. Note the earlier run had BackgroundTest fail on a Robolectric MavenArtifactFetcher IOException - that is the documented network/Maven trap in BRIEF.md, not a code failure; check whether it passes on this run before treating it as real.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  64b2e98 ckpt 593: Investigated Tj's two reports from the screen recording and fixed both finding
  cd3efec ckpt 592: Wrote Tj's new request into TASKS.md before any code: a chart-vs-SPY compariso
  ea956c3 ckpt 591: Re-verified CI after bumping actions/checkout and actions/setup-java to v5: ru
  ce48c8f ckpt 590: CI IS LIVE AND VERIFIED. Tj added the SIGNING_KEYSTORE_BASE64 secret and run #
  e7eb17f ckpt 589: Restructured the CI workflow so it cannot waste GitHub's free minutes, then va
  1d9b76c ckpt 588: Added GitHub Actions signed-release CI on Tj's decision, and closed the local 
  f42b374 ckpt 587: Ticked TASKS.md 1-5 (all written, tested via tools/test_resume.sh's 19 checks,
  cec13a5 ckpt 586: Built the APK auto-provisioning chain and proved the SDK half of it live. NEW 
  efa40b9 ckpt 585: Checked Tj's GitHub assumption and wrote the APK auto-provisioning request int
  c27799f ckpt 584: Installed the signing keystore and added a keystore check to bootstrap.sh, so 
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
