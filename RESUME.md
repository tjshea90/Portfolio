# RESUME — READ THIS FIRST  (round 62, saved 2026-09-08 04:27:34 UTC)

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

> Feature 3 of 4: per-range performance chips - each chart range button shows what that window did, from data the app already holds (no new network traffic). Ship only when proven and nothing else broke. Checkpoint frequently - usage may run out.

## 3. WHERE THE WORK STOPPED

- **In flight:** T0: Baseline: v7.2 tree builds and 411 tests green before any edit
- **Next action:** Round 62 open: per-range performance chips. Work the ladder T0->T8.

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 0/9 done

- [>] T0  Baseline: v7.2 tree builds and 411 tests green before any edit  — baseline build + suite
- [ ] T1  Design: chip figures mirror the drawn chart exactly, cached-only, zero new requests
- [ ] T2  Pure model: a total function for what each chip shows
- [ ] T3  RangeChips UI: two-line chip, sign colour, 48dp rule, contentDescription
- [ ] T4  Wire DetailScreen: per-range live edge, loading state, no extra fetches
- [ ] T5  Tests: pure + rendered, incl. chip equals readout and no-new-request proof
- [ ] T6  REGRESSION: full suite, lint, checkinit; prove chart and the rest unchanged
- [ ] T7  Adversarial review of the feature, then fix what it finds
- [ ] T8  Only if all clean: ship v7.3 (versionCode 60) + checkpoint

**Resume at T0** (Baseline: v7.2 tree builds and 411 tests green before any edit).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v7.2 (versionCode 59)
- This round ships: v7.3 (versionCode 60)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

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
- 2026-09-08 04:27:06 UTC  round 62 started
- 2026-09-08 04:27:34 UTC  T0 -> doing  baseline build + suite

