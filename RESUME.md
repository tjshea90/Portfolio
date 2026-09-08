# RESUME — READ THIS FIRST  (round 64, saved 2026-09-08 22:15:36 UTC)

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

> Round 64: (1) the portfolio row sparklines are small - make them fill the blank area they sit in, not necessarily square. (2) ANY chart in the app: tap to open full screen, rotating with the phone's sensors. (3) make pinch-to-zoom SMOOTH and continuous instead of chopping between fixed intervals. Then more sweeps for bugs, UI and code, verifying nothing else breaks.

## 3. WHERE THE WORK STOPPED

- **In flight:** (nothing in flight)
- **Next action:** (pick the first unchecked task below)

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 7/9 done

- [x] T0  Baseline: v7.4 tree builds and 569 tests green before any edit  — v7.4 baseline green
- [x] T1  Row sparklines fill their space: measure what the row actually gives them and use it  — row sparkline 1.4:1 weights, 48dp tall
- [x] T2  Continuous pinch zoom: a real time window scaled smoothly, with the range ladder behind it as the data source  — continuous window zoom + pan, axis-scaled canvas, reset chip
- [x] T3  Full-screen chart: tap any chart to open it, sensor rotation, back to close  — FullScreenChart dialog + sensor orientation + manifest configChanges
- [x] T4  Tests for T1-T3: pure + rendered + measured  — ChartWindowTest 38, ContinuousZoomUiTest 12, SparklineSizeUiTest 6, FullScreenChartUiTest 7
- [x] T5  REGRESSION: full suite, lint, checkinit; prove nothing pre-existing broke  — 632/632 green, lint vital clean, checkinit ok
- [x] T6  SWEEP 1: adversarial bug hunt over the new code and the app  — 9 findings, all fixed, 640 tests green
- [ ] T7  SWEEP 2: verify sweep 1's own fixes; repeat until a pass finds nothing above cosmetic
- [ ] T8  Ship v7.5 (versionCode 62) + final checkpoint

**Resume at T7** (SWEEP 2: verify sweep 1's own fixes; repeat until a pass finds nothing above cosmetic).

## 5. Open findings — 0 still open, 9 fixed

- [x] F01 (high) Pinch-out cannot widen past loaded series: windowBounds is the union of LOADED series, so on a first-open (only 1D cached) a pinch-out saturates instantly and 'zoom out to all time' is impossible
- [x] F02 (high) Pinching the After-hours chart blanks it permanently: windowBounds excludes OVERNIGHT, so the window is clamped into the regular session which the overnight series does not overlap
- [x] F03 (high) Window is never re-anchored when a new range's series arrives; lookback is measured from a stale coarse-candle timestamp, so a spread near the right edge of a 5Y chart can land on a blank chart
- [x] F04 (high) Readout, percent change and y-axis labels are computed from the two carried points OUTSIDE the window - a 7-day picture reports a 9-day change
- [x] F05 (high) Comparison overlay pastes SPY's live price onto a mid-window point once zoomed: tip-pairing tests drawn.lastIndex, not the series' true tip
- [x] F06 (med) Chip figure, caption and point count still describe the unzoomed range while the readout describes the window - two figures on one screen that disagree
- [x] F07 (med) Reset zoom chip appears on its own every ~5 min as windowBounds advances with the periodic refresh
- [x] F08 (low) Crosshair can land on a carried off-window point: readout updates but the dot and line are drawn off-canvas
- [x] F09 (low) Chart canvas is not clipped to bounds, so a zoomed line bleeds into the 16dp gutters

## 6. Version

- Shipped: v7.4 (versionCode 61)
- This round ships: v7.5 (versionCode 62)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 21:56:12 UTC  finding F09: Chart canvas is not clipped to bounds, so a zoomed line bleeds into the 16dp gut
- 2026-09-08 22:15:19 UTC  F01 fixed
- 2026-09-08 22:15:20 UTC  F02 fixed
- 2026-09-08 22:15:21 UTC  F03 fixed
- 2026-09-08 22:15:22 UTC  F04 fixed
- 2026-09-08 22:15:23 UTC  F05 fixed
- 2026-09-08 22:15:24 UTC  F06 fixed
- 2026-09-08 22:15:25 UTC  F07 fixed
- 2026-09-08 22:15:26 UTC  F08 fixed
- 2026-09-08 22:15:27 UTC  F09 fixed
- 2026-09-08 22:15:28 UTC  sweep 1 fixes: F01-F09 all closed; 640 tests green
- 2026-09-08 22:15:36 UTC  T6 -> done  9 findings, all fixed, 640 tests green

