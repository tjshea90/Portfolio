# RESUME — READ THIS FIRST  (round 64, saved 2026-09-08 21:13:54 UTC)

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

## 4. Task ledger — 3/9 done

- [x] T0  Baseline: v7.4 tree builds and 569 tests green before any edit  — v7.4 baseline green
- [x] T1  Row sparklines fill their space: measure what the row actually gives them and use it  — row sparkline 1.4:1 weights, 48dp tall
- [x] T2  Continuous pinch zoom: a real time window scaled smoothly, with the range ladder behind it as the data source  — continuous window zoom + pan, axis-scaled canvas, reset chip
- [ ] T3  Full-screen chart: tap any chart to open it, sensor rotation, back to close
- [ ] T4  Tests for T1-T3: pure + rendered + measured
- [ ] T5  REGRESSION: full suite, lint, checkinit; prove nothing pre-existing broke
- [ ] T6  SWEEP 1: adversarial bug hunt over the new code and the app
- [ ] T7  SWEEP 2: verify sweep 1's own fixes; repeat until a pass finds nothing above cosmetic
- [ ] T8  Ship v7.5 (versionCode 62) + final checkpoint

**Resume at T3** (Full-screen chart: tap any chart to open it, sensor rotation, back to close).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v7.4 (versionCode 61)
- This round ships: v7.5 (versionCode 62)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 17:06:38 UTC  S08 fixed: two tests for the forgetting rule: a key untouched for ten minutes starts again at 30s, and one still failing steadily keeps its streak
- 2026-09-08 17:06:57 UTC  T12 -> done  8 findings (S01-S08) incl. a deadlock between the new settings lock and restoreJson's transaction
- 2026-09-08 17:06:58 UTC  T13 -> doing  sweep 5
- 2026-09-08 20:03:55 UTC  T13 -> done  verdict SHIP - no new defect in any of the sweep-4 fixes; the settings lock has one acquisition order and no path holds the connection or the helper monitor and then wants it; the insider stamp is right in all six reachable combinations; the feed pass is correct in all four states. Remaining items are an unused import, a dead default parameter, a doc sentence and two pre-existing edge cases.
- 2026-09-08 20:04:33 UTC  T11 -> doing  final cleanups + ship v7.4
- 2026-09-08 20:15:12 UTC  T11 -> done  v7.4 shipped: versionCode 61, signed with the archived key (cert SHA-256 2e8c3847... unchanged, so it installs in place over 7.3), 569/569 tests green, lint vital clean, checkinit ok
- 2026-09-08 20:24:41 UTC  round 64 started
- 2026-09-08 20:26:04 UTC  T0 -> doing  baseline
- 2026-09-08 20:37:55 UTC  T0 -> done  v7.4 baseline green
- 2026-09-08 20:37:56 UTC  T2 -> doing  continuous window zoom
- 2026-09-08 21:13:52 UTC  T2 -> done  continuous window zoom + pan, axis-scaled canvas, reset chip
- 2026-09-08 21:13:54 UTC  T1 -> done  row sparkline 1.4:1 weights, 48dp tall

