# RESUME — READ THIS FIRST  (round 60, saved 2026-09-08 01:58:48 UTC)

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

> Add the approved new features ONE AT A TIME, shipping each on its own. Feature 1: chart scrubbing - drag across the chart to read the price and time at that point. Test and optimise before shipping; must not disturb anything else in the app.

## 3. WHERE THE WORK STOPPED

- **In flight:** (nothing in flight)
- **Next action:** T1 gesture design

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 5/8 done

- [x] T0  Baseline: v7.0 tree builds and 371 tests green before any edit  — baseline green before the feature
- [x] T1  Design the scrub gesture so it cannot break vertical scrolling of the list it sits in  — detectHorizontalDragGestures: horizontal touch slop claims the scrub, vertical swipes fall through to the list scroll
- [x] T2  Implement: crosshair, nearest-point lookup, readout that does not shift the layout  — crosshair + dot, readout at fixed height, index-based scrub state
- [x] T3  Optimise: no allocation per drag event, binary search not linear scan, no recomposition storm  — mutableIntStateOf (no boxing per frame), draw-phase-only state read in the canvas, readout isolated so only it recomposes, binary search lookup
- [x] T4  Test: rendered gesture tests + pure-function tests for the lookup  — ScrubTest (10 pure) + ScrubGestureUiTest (8 real-touch), incl. both scroll directions
- [ ] T5  REGRESSION CHECK: full suite, and prove the chart/holdings/row behaviour is unchanged
- [ ] T6  Adversarial review of the feature, then fix what it finds
- [ ] T7  Ship v7.1 (versionCode 58) + checkpoint

**Resume at T5** (REGRESSION CHECK: full suite, and prove the chart/holdings/row behaviour is unchanged).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v7.0 (versionCode 57)
- This round ships: v7.1 (versionCode 58)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 01:17:13 UTC  T6 -> doing  verification
- 2026-09-08 01:25:26 UTC  v7.0 final verification build running
- 2026-09-08 01:27:41 UTC  T6 -> done  371/371 tests, checkinit ok, lintVital clean, APK signed with the archived keystore (fingerprint matches), versionCode 57 / 7.0 confirmed
- 2026-09-08 01:27:41 UTC  T7 -> doing  shipping v7.0
- 2026-09-08 01:27:53 UTC  T7 -> done  v7.0 APK + checkpoint 59 delivered
- 2026-09-08 01:46:47 UTC  round 60 started
- 2026-09-08 01:49:33 UTC  T0 -> done  baseline green before the feature
- 2026-09-08 01:49:36 UTC  T1 -> done  detectHorizontalDragGestures: horizontal touch slop claims the scrub, vertical swipes fall through to the list scroll
- 2026-09-08 01:49:37 UTC  T2 -> done  crosshair + dot, readout at fixed height, index-based scrub state
- 2026-09-08 01:49:37 UTC  T3 -> done  mutableIntStateOf (no boxing per frame), draw-phase-only state read in the canvas, readout isolated so only it recomposes, binary search lookup
- 2026-09-08 01:49:38 UTC  T4 -> doing  tests
- 2026-09-08 01:58:48 UTC  T4 -> done  ScrubTest (10 pure) + ScrubGestureUiTest (8 real-touch), incl. both scroll directions

