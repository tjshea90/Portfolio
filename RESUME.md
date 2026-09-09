# RESUME — READ THIS FIRST  (round 65, saved 2026-09-09 05:19:10 UTC)

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

## 4. Task ledger — 4/7 done

- [x] T0  Baseline: round-64 tree (29bd6bf) compiles and 663 tests green in the new container  — cold container rebuilt: SDK reinstalled, 29bd6bf compiles, 663/663 green - matches the handover
- [x] T1  Row charts sized by measuring the text, not by weight: ~215dp of chart, no gap  — TextThenChart measuring layout: TJ's row 125dp -> 186.5dp of chart, no gap; floor is round 64's own share so no row is ever worse; unbounded-width branch tested
- [x] T2  One-finger pan on a zoomed chart; press-and-hold always scrubs; caption names the live gestures  — one-finger pan on a zoomed chart, press-and-hold scrub with a haptic tick, vertical drags handed back to the page; M01-M04, M07, M08 designed in
- [x] T3  Tests: 350ms vertical rest still scrolls; slow drag pans not scrubs; caption never promises a dead pan  — PanGestureUiTest: 9 tests including the 3 the old suite could not catch (350ms rest still scrolls, slow drag pans, caption never promises a dead pan)
- [ ] T4  REGRESSION: full suite, lint, checkinit
- [ ] T5  SWEEPS: adversarial bug hunt, repeated until a pass finds nothing above cosmetic
- [ ] T6  Ship v7.6 (versionCode 63) + final checkpoint

**Resume at T4** (REGRESSION: full suite, lint, checkinit).

## 5. Open findings — 2 still open, 1 fixed

- [x] N01 (high) The hold armed on UNZOOMED charts too, where a drag already scrubs: it bought nothing and made the chart start consuming before touch slop, so a press that paused then scrolled could stop the page - on the one chart shape that never needed the gesture  — hold is only armed where canPan is true, so an unzoomed chart behaves exactly as v7.5 did
- [ ] N02 (low) chartPinching in DetailScreen and onZoomingChanged's KDoc both still say 'two fingers'; a one-finger pan raises them now
- [ ] N03 (med) The gesture caption grew by up to 27 characters, and the full-screen viewer divides a fixed height between the plot and the text under it - an extra wrapped line at a large font scale is exactly round 64's H04 coming back

## 6. Version

- Shipped: v7.4 (versionCode 61)
- This round ships: v7.5 (versionCode 62)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-09 04:48:55 UTC  round 65 started
- 2026-09-09 04:49:04 UTC  T0 -> doing  cold-container baseline build running
- 2026-09-09 04:52:56 UTC  T0 -> done  cold container rebuilt: SDK reinstalled, 29bd6bf compiles, 663/663 green - matches the handover
- 2026-09-09 04:52:58 UTC  T1 -> doing  replacing the 1.6:1 weight split with a measuring layout
- 2026-09-09 04:57:50 UTC  T1 -> done  TextThenChart measuring layout: TJ's row 125dp -> 186.5dp of chart, no gap; floor is round 64's own share so no row is ever worse; unbounded-width branch tested
- 2026-09-09 04:57:51 UTC  T2 -> doing  one-finger pan + press-and-hold scrub in chartGestures
- 2026-09-09 05:13:00 UTC  T2 -> done  one-finger pan on a zoomed chart, press-and-hold scrub with a haptic tick, vertical drags handed back to the page; M01-M04, M07, M08 designed in
- 2026-09-09 05:13:02 UTC  T3 -> done  PanGestureUiTest: 9 tests including the 3 the old suite could not catch (350ms rest still scrolls, slow drag pans, caption never promises a dead pan)
- 2026-09-09 05:17:24 UTC  finding N01: The hold armed on UNZOOMED charts too, where a drag already scrubs: it bought no
- 2026-09-09 05:17:24 UTC  finding N02: chartPinching in DetailScreen and onZoomingChanged's KDoc both still say 'two fi
- 2026-09-09 05:17:24 UTC  finding N03: The gesture caption grew by up to 27 characters, and the full-screen viewer divi
- 2026-09-09 05:19:10 UTC  N01 fixed: hold is only armed where canPan is true, so an unzoomed chart behaves exactly as v7.5 did

