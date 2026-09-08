# RESUME — READ THIS FIRST  (round 61, saved 2026-09-08 02:34:52 UTC)

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

- **In flight:** (nothing in flight)
- **Next action:** T1 map every P/L render site

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 5/8 done

- [x] T0  Baseline: v7.1 tree, 390 tests green before any edit  — 390/390 green on the v7.1 tree
- [x] T1  Map every place a P/L figure is rendered, so the toggle is complete rather than partial  — affected surfaces: StockRow money cells, PortfolioScreen summary BigLines + detail card, DetailScreen position block. Watchlist rows carry no P/L (watchOnly hides the money half), so nothing there to switch.
- [x] T2  Model + persistence: a PlMode setting that survives a restart, in the backup, no DB migration  — PlMode enum, Keys.PL_MODE, UiState.plMode seeded in init, setPlMode/togglePlMode, plLead/plSub/plInline helpers
- [x] T3  UI: make the figures tappable to switch, and label so it is never ambiguous which is shown  — summary lines tappable + labelled hint; rows, detail card and stock page all follow the mode
- [x] T4  Tests: rendered tests proving BOTH modes on every affected surface  — PlModeTest (8 pure) + PlModeUiTest (7 rendered/persistence incl. backup)
- [ ] T5  REGRESSION: full suite + prove rows, summary, watchlist and detail are otherwise unchanged
- [ ] T6  Adversarial review of the feature, then fix what it finds
- [ ] T7  Only if all of the above is clean: ship v7.2 (versionCode 59) + checkpoint

**Resume at T5** (REGRESSION: full suite + prove rows, summary, watchlist and detail are otherwise unchanged).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v7.1 (versionCode 58)
- This round ships: v7.2 (versionCode 59)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 02:10:32 UTC  T6 -> done  2 findings (H01 mid-drag cancellation, H02 per-frame date formatting), both fixed; H01's test verified by reverting the fix
- 2026-09-08 02:13:12 UTC  T7 -> doing  shipping
- 2026-09-08 02:13:22 UTC  T7 -> done  v7.1 APK + checkpoint 60 delivered
- 2026-09-08 02:24:35 UTC  round 61 started
- 2026-09-08 02:25:50 UTC  T0 -> done  390/390 green on the v7.1 tree
- 2026-09-08 02:25:51 UTC  T1 -> done  affected surfaces: StockRow money cells, PortfolioScreen summary BigLines + detail card, DetailScreen position block. Watchlist rows carry no P/L (watchOnly hides the money half), so nothing there to switch.
- 2026-09-08 02:25:51 UTC  T2 -> doing  model + persistence
- 2026-09-08 02:27:32 UTC  T2 -> done  PlMode enum, Keys.PL_MODE, UiState.plMode seeded in init, setPlMode/togglePlMode, plLead/plSub/plInline helpers
- 2026-09-08 02:27:32 UTC  T3 -> doing  wiring the three surfaces
- 2026-09-08 02:30:26 UTC  T3 -> done  summary lines tappable + labelled hint; rows, detail card and stock page all follow the mode
- 2026-09-08 02:30:26 UTC  T4 -> doing  tests
- 2026-09-08 02:34:52 UTC  T4 -> done  PlModeTest (8 pure) + PlModeUiTest (7 rendered/persistence incl. backup)

