# RESUME — READ THIS FIRST  (round 61, saved 2026-09-08 02:24:35 UTC)

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

- **In flight:** T0 baseline
- **Next action:** T1 map every P/L render site

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 0/8 done

- [>] T0  Baseline: v7.1 tree, 390 tests green before any edit
- [ ] T1  Map every place a P/L figure is rendered, so the toggle is complete rather than partial
- [ ] T2  Model + persistence: a PlMode setting that survives a restart, in the backup, no DB migration
- [ ] T3  UI: make the figures tappable to switch, and label so it is never ambiguous which is shown
- [ ] T4  Tests: rendered tests proving BOTH modes on every affected surface
- [ ] T5  REGRESSION: full suite + prove rows, summary, watchlist and detail are otherwise unchanged
- [ ] T6  Adversarial review of the feature, then fix what it finds
- [ ] T7  Only if all of the above is clean: ship v7.2 (versionCode 59) + checkpoint

**Resume at T0** (Baseline: v7.1 tree, 390 tests green before any edit).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v7.1 (versionCode 58)
- This round ships: v7.2 (versionCode 59)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 01:58:48 UTC  T4 -> done  ScrubTest (10 pure) + ScrubGestureUiTest (8 real-touch), incl. both scroll directions
- 2026-09-08 01:58:49 UTC  T5 -> doing  full regression
- 2026-09-08 02:01:30 UTC  T5 -> done  389/389 green, lint clean, checkinit ok - no regressions
- 2026-09-08 02:01:30 UTC  T6 -> doing  adversarial review of the scrub feature
- 2026-09-08 02:01:30 UTC  finding H01: BOTH the scrub state and the pointer handler are keyed on 'shown', which withLiv
- 2026-09-08 02:01:30 UTC  finding H02: ChartReadout calls spansMoreThanADay(s) on every frame of a drag, and that forma
- 2026-09-08 02:08:32 UTC  H01 fixed: scrub state is remember{} with an explicit LaunchedEffect(symbol,range) reset; pointerInput(Unit) with rememberUpdatedState so a quote tick cannot cancel an in-flight drag. Regression test verified by reverting the fix.
- 2026-09-08 02:08:32 UTC  H02 fixed: spansMoreThanADay hoisted into remember(s), off the per-frame path
- 2026-09-08 02:10:32 UTC  T6 -> done  2 findings (H01 mid-drag cancellation, H02 per-frame date formatting), both fixed; H01's test verified by reverting the fix
- 2026-09-08 02:13:12 UTC  T7 -> doing  shipping
- 2026-09-08 02:13:22 UTC  T7 -> done  v7.1 APK + checkpoint 60 delivered
- 2026-09-08 02:24:35 UTC  round 61 started

