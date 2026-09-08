# RESUME — READ THIS FIRST  (round 61, saved 2026-09-08 02:49:04 UTC)

You are picking up a long-running Android project that was interrupted.
Everything you need is on disk. Do NOT re-read CHECKPOINT.md end to end —
it is 240 KB of round history. This file plus `state.json` is the live state;
CHECKPOINT.md sections 0-5 (lines 1-530) are the only part worth reading cold,
and only if you need the architecture.

## 1. Bring the container back up

```bash
cd /home/claude && tar xzf <the checkpoint tarball>   # if the tree is missing
bash /home/claude/portfolio/setup-env.sh              # Android SDK, ~2 min, once
export ANDROID_HOME=/root/android-sdk
bash /home/claude/portfolio/watchdog.sh &             # restart the 3-min autosave
./ck status                                           # where the work stopped
```

Build traps that have cost real time before are in CHECKPOINT.md lines 22-60.
The short version: never blank `JAVA_TOOL_OPTIONS`; never run two Gradle builds
at once or kill one mid-flight; always background the build with
`setsid nohup ./gradlew ... > /home/claude/build.log 2>&1 < /dev/null & disown`.

## 2. The request this round is answering

> Feature 2 of 4: a percent/dollar toggle for profit-and-loss figures across the app. Do NOT ship until it is proven to work, be optimised, and to have broken nothing. Checkpoint frequently - usage may run out.

## 3. WHERE THE WORK STOPPED

- **In flight:** T7: Only if all of the above is clean: ship v7.2 (versionCode 59) + checkpoint
- **Next action:** T1 map every P/L render site

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 7/8 done

- [x] T0  Baseline: v7.1 tree, 390 tests green before any edit  — 390/390 green on the v7.1 tree
- [x] T1  Map every place a P/L figure is rendered, so the toggle is complete rather than partial  — affected surfaces: StockRow money cells, PortfolioScreen summary BigLines + detail card, DetailScreen position block. Watchlist rows carry no P/L (watchOnly hides the money half), so nothing there to switch.
- [x] T2  Model + persistence: a PlMode setting that survives a restart, in the backup, no DB migration  — PlMode enum, Keys.PL_MODE, UiState.plMode seeded in init, setPlMode/togglePlMode, plLead/plSub/plInline helpers
- [x] T3  UI: make the figures tappable to switch, and label so it is never ambiguous which is shown  — summary lines tappable + labelled hint; rows, detail card and stock page all follow the mode
- [x] T4  Tests: rendered tests proving BOTH modes on every affected surface  — PlModeTest (8 pure) + PlModeUiTest (7 rendered/persistence incl. backup)
- [x] T5  REGRESSION: full suite + prove rows, summary, watchlist and detail are otherwise unchanged  — 405/405 green, lint clean, checkinit ok
- [x] T6  Adversarial review of the feature, then fix what it finds  — 2 findings, both fixed; VM link and seeding order now covered, and the seeding test verified by breaking it
- [>] T7  Only if all of the above is clean: ship v7.2 (versionCode 59) + checkpoint  — shipping v7.2

**Resume at T7** (Only if all of the above is clean: ship v7.2 (versionCode 59) + checkpoint).

## 5. Open findings — 0 still open, 2 fixed

- [x] I01 (med) BigLine's parameters are still named 'money' and 'pct', but they now receive plLead/plSub - so in percent mode the parameter called 'money' holds a percentage. Stale naming that contradicts the value is exactly how two of this project's bugs shipped.  — BigLine parameters renamed money/pct -> lead/sub, which is the role rather than the unit
- [x] I02 (low) The mode hint reads 'Showing dollars, then percent - tap a line to swap them'. At a 2.0 font scale that wraps to three lines in the summary card for a sentence that only needs to say two things.  — hint shortened to '$ first - tap a line for %'

## 6. Version

- Shipped: v7.1 (versionCode 58)
- This round ships: v7.2 (versionCode 59)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 02:30:26 UTC  T3 -> done  summary lines tappable + labelled hint; rows, detail card and stock page all follow the mode
- 2026-09-08 02:30:26 UTC  T4 -> doing  tests
- 2026-09-08 02:34:52 UTC  T4 -> done  PlModeTest (8 pure) + PlModeUiTest (7 rendered/persistence incl. backup)
- 2026-09-08 02:34:52 UTC  T5 -> doing  full regression
- 2026-09-08 02:36:50 UTC  T5 -> done  405/405 green, lint clean, checkinit ok
- 2026-09-08 02:36:51 UTC  T6 -> doing  adversarial review
- 2026-09-08 02:37:23 UTC  finding I01: BigLine's parameters are still named 'money' and 'pct', but they now receive plL
- 2026-09-08 02:37:23 UTC  finding I02: The mode hint reads 'Showing dollars, then percent - tap a line to swap them'. A
- 2026-09-08 02:44:44 UTC  I01 fixed: BigLine parameters renamed money/pct -> lead/sub, which is the role rather than the unit
- 2026-09-08 02:44:45 UTC  I02 fixed: hint shortened to '$ first - tap a line for %'
- 2026-09-08 02:44:45 UTC  T6 -> done  2 findings, both fixed; VM link and seeding order now covered, and the seeding test verified by breaking it
- 2026-09-08 02:49:04 UTC  T7 -> doing  shipping v7.2

