# RESUME — READ THIS FIRST  (round 65, saved 2026-09-09 05:27:11 UTC)

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

## 4. Task ledger — 5/7 done

- [x] T0  Baseline: round-64 tree (29bd6bf) compiles and 663 tests green in the new container  — cold container rebuilt: SDK reinstalled, 29bd6bf compiles, 663/663 green - matches the handover
- [x] T1  Row charts sized by measuring the text, not by weight: ~215dp of chart, no gap  — TextThenChart measuring layout: TJ's row 125dp -> 186.5dp of chart, no gap; floor is round 64's own share so no row is ever worse; unbounded-width branch tested
- [x] T2  One-finger pan on a zoomed chart; press-and-hold always scrubs; caption names the live gestures  — one-finger pan on a zoomed chart, press-and-hold scrub with a haptic tick, vertical drags handed back to the page; M01-M04, M07, M08 designed in
- [x] T3  Tests: 350ms vertical rest still scrolls; slow drag pans not scrubs; caption never promises a dead pan  — PanGestureUiTest: 9 tests including the 3 the old suite could not catch (350ms rest still scrolls, slow drag pans, caption never promises a dead pan)
- [x] T4  REGRESSION: full suite, lint, checkinit  — 679 tests green, lint vital clean, checkinit ok
- [ ] T5  SWEEPS: adversarial bug hunt, repeated until a pass finds nothing above cosmetic
- [ ] T6  Ship v7.6 (versionCode 63) + final checkpoint

**Resume at T5** (SWEEPS: adversarial bug hunt, repeated until a pass finds nothing above cosmetic).

## 5. Open findings — 1 still open, 6 fixed

- [x] N01 (high) The hold armed on UNZOOMED charts too, where a drag already scrubs: it bought nothing and made the chart start consuming before touch slop, so a press that paused then scrolled could stop the page - on the one chart shape that never needed the gesture  — hold is only armed where canPan is true, so an unzoomed chart behaves exactly as v7.5 did
- [x] N02 (low) chartPinching in DetailScreen and onZoomingChanged's KDoc both still say 'two fingers'; a one-finger pan raises them now  — chartPinching and onZoomingChanged both describe a window gesture now, not two fingers
- [x] N03 (med) The gesture caption grew by up to 27 characters, and the full-screen viewer divides a fixed height between the plot and the text under it - an extra wrapped line at a large font scale is exactly round 64's H04 coming back  — worst-case caption (zoomed + SPY overlay + 1.5x type) measured in the full-screen viewer
- [x] N04 (low) The event-path hold test reused holdPossible, which was computed from the previous frame's nearDown - stale by one event  — the event path now evaluates the full condition against the current frame
- [x] N05 (med) canPanNow did not require onWindow: a chart given a window but no onWindow callback (both are optional parameters) would caption 'drag to move' and arm the hold, while pan() returns null and the drag scrubs - M03's fault from the other direction, and the same needless early consume as N01  — canPanNow now requires onWindow, and a test covers a zoomed chart that has nowhere to report a window
- [x] N06 (low) canPanNow is remembered on the onWindow LAMBDA, and DetailScreen builds a fresh one every recomposition, so the remember never hits; keying on whether it is null says what is actually meant  — keyed on whether onWindow is null, not on the lambda instance
- [ ] N07 (med) The caption gained ', drag to scrub' on EVERY chart, including the unzoomed one where nothing about the gesture changed - longer text on the screen TJ reads daily, to describe behaviour he has had since v7.4

## 6. Version

- Shipped: v7.4 (versionCode 61)
- This round ships: v7.5 (versionCode 62)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-09 05:19:11 UTC  N02 fixed: chartPinching and onZoomingChanged both describe a window gesture now, not two fingers
- 2026-09-09 05:19:13 UTC  N03 fixed: worst-case caption (zoomed + SPY overlay + 1.5x type) measured in the full-screen viewer
- 2026-09-09 05:19:14 UTC  finding N04: The event-path hold test reused holdPossible, which was computed from the previo
- 2026-09-09 05:19:14 UTC  N04 fixed: the event path now evaluates the full condition against the current frame
- 2026-09-09 05:20:35 UTC  sweep 1: 4 findings (1 high), all closed
- 2026-09-09 05:22:44 UTC  finding N05: canPanNow did not require onWindow: a chart given a window but no onWindow callb
- 2026-09-09 05:23:26 UTC  N05 fixed: canPanNow now requires onWindow, and a test covers a zoomed chart that has nowhere to report a window
- 2026-09-09 05:26:33 UTC  T4 -> done  679 tests green, lint vital clean, checkinit ok
- 2026-09-09 05:26:34 UTC  sweep 2: 1 finding (N05, med), closed; M01-M08 all covered by tests
- 2026-09-09 05:27:11 UTC  finding N06: canPanNow is remembered on the onWindow LAMBDA, and DetailScreen builds a fresh 
- 2026-09-09 05:27:11 UTC  finding N07: The caption gained ', drag to scrub' on EVERY chart, including the unzoomed one 
- 2026-09-09 05:27:11 UTC  N06 fixed: keyed on whether onWindow is null, not on the lambda instance

