# RESUME — READ THIS FIRST  (round 65, saved 2026-09-09 04:57:50 UTC)

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

> Rebuild round 65 after container loss: (1) row charts sized by measuring, not weights; (2) one-finger pan on a zoomed chart + press-and-hold scrub; with the M01-M08 fixes designed in from the start and the 3 tests the old suite could not catch.

## 3. WHERE THE WORK STOPPED

- **In flight:** (nothing in flight)
- **Next action:** (pick the first unchecked task below)

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 2/7 done

- [x] T0  Baseline: round-64 tree (29bd6bf) compiles and 663 tests green in the new container  — cold container rebuilt: SDK reinstalled, 29bd6bf compiles, 663/663 green - matches the handover
- [x] T1  Row charts sized by measuring the text, not by weight: ~215dp of chart, no gap  — TextThenChart measuring layout: TJ's row 125dp -> 186.5dp of chart, no gap; floor is round 64's own share so no row is ever worse; unbounded-width branch tested
- [ ] T2  One-finger pan on a zoomed chart; press-and-hold always scrubs; caption names the live gestures
- [ ] T3  Tests: 350ms vertical rest still scrolls; slow drag pans not scrubs; caption never promises a dead pan
- [ ] T4  REGRESSION: full suite, lint, checkinit
- [ ] T5  SWEEPS: adversarial bug hunt, repeated until a pass finds nothing above cosmetic
- [ ] T6  Ship v7.6 (versionCode 63) + final checkpoint

**Resume at T2** (One-finger pan on a zoomed chart; press-and-hold always scrubs; caption names the live gestures).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v7.4 (versionCode 61)
- This round ships: v7.5 (versionCode 62)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-09 02:32:27 UTC  finding L01: Full-screen empty/loading placeholder collapses to the 120dp floor instead of fi
- 2026-09-09 02:32:27 UTC  finding L02: atRightEdge's comment says the slack is one candle, not a percentage; the code c
- 2026-09-09 02:37:28 UTC  L01 fixed
- 2026-09-09 02:37:30 UTC  L02 fixed
- 2026-09-09 02:37:31 UTC  T7 -> done  6 sweeps: 45 findings, all closed; sweep 6 found one low + one stale comment
- 2026-09-09 02:37:33 UTC  T8 -> doing  ship v7.5
- 2026-09-09 02:42:05 UTC  T8 -> done  v7.5 (versionCode 62) built, signed with the same cert, 663 tests green
- 2026-09-09 04:48:55 UTC  round 65 started
- 2026-09-09 04:49:04 UTC  T0 -> doing  cold-container baseline build running
- 2026-09-09 04:52:56 UTC  T0 -> done  cold container rebuilt: SDK reinstalled, 29bd6bf compiles, 663/663 green - matches the handover
- 2026-09-09 04:52:58 UTC  T1 -> doing  replacing the 1.6:1 weight split with a measuring layout
- 2026-09-09 04:57:50 UTC  T1 -> done  TextThenChart measuring layout: TJ's row 125dp -> 186.5dp of chart, no gap; floor is round 64's own share so no row is ever worse; unbounded-width branch tested

