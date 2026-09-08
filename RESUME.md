# RESUME — READ THIS FIRST  (round 60, saved 2026-09-08 01:49:33 UTC)

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

## 4. Task ledger — 1/8 done

- [x] T0  Baseline: v7.0 tree builds and 371 tests green before any edit  — baseline green before the feature
- [ ] T1  Design the scrub gesture so it cannot break vertical scrolling of the list it sits in
- [ ] T2  Implement: crosshair, nearest-point lookup, readout that does not shift the layout
- [ ] T3  Optimise: no allocation per drag event, binary search not linear scan, no recomposition storm
- [ ] T4  Test: rendered gesture tests + pure-function tests for the lookup
- [ ] T5  REGRESSION CHECK: full suite, and prove the chart/holdings/row behaviour is unchanged
- [ ] T6  Adversarial review of the feature, then fix what it finds
- [ ] T7  Ship v7.1 (versionCode 58) + checkpoint

**Resume at T1** (Design the scrub gesture so it cannot break vertical scrolling of the list it sits in).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v7.0 (versionCode 57)
- This round ships: v7.1 (versionCode 58)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 01:15:07 UTC  G04 fixed: loadHoldings uses the same RetryClock
- 2026-09-08 01:15:08 UTC  G05 fixed: refreshSparklines records failures in a RetryClock, so a transient failure still retries quickly but a dead symbol settles at one attempt per 5 minutes
- 2026-09-08 01:15:08 UTC  G06 fixed: AdviceScreen action numbers use widthIn(min) instead of a fixed width
- 2026-09-08 01:15:09 UTC  G07 fixed: FeedScreen trending rank uses widthIn(min) instead of a fixed width
- 2026-09-08 01:17:12 UTC  T5 -> done  G01-G07 fixed, plus three refinements found reviewing my own fixes: persist merged not stamped, one connectivity answer gating both passes, clear the backoff with the cache
- 2026-09-08 01:17:13 UTC  T6 -> doing  verification
- 2026-09-08 01:25:26 UTC  v7.0 final verification build running
- 2026-09-08 01:27:41 UTC  T6 -> done  371/371 tests, checkinit ok, lintVital clean, APK signed with the archived keystore (fingerprint matches), versionCode 57 / 7.0 confirmed
- 2026-09-08 01:27:41 UTC  T7 -> doing  shipping v7.0
- 2026-09-08 01:27:53 UTC  T7 -> done  v7.0 APK + checkpoint 59 delivered
- 2026-09-08 01:46:47 UTC  round 60 started
- 2026-09-08 01:49:33 UTC  T0 -> done  baseline green before the feature

