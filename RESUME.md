# RESUME — READ THIS FIRST  (round 59, saved 2026-09-08 01:02:18 UTC)

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

> Check the shipped v6.9: thorough sweep for UI and code optimizations, verify the app truly sleeps in the background when not in use, and find and fix bugs.

## 3. WHERE THE WORK STOPPED

- **In flight:** T1: BACKGROUND AUDIT: trace every coroutine, timer, listener and lifecycle path in v6.9 from scratch
- **Next action:** Then T1, the background audit

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 1/8 done

- [x] T0  Baseline on the shipped tree: release build + 358 tests green before any edit  — release APK + 358/358 green on the shipped tree
- [>] T1  BACKGROUND AUDIT: trace every coroutine, timer, listener and lifecycle path in v6.9 from scratch  — background audit
- [ ] T2  UI SWEEP: every screen rendered and measured - overflow, tap targets, font scale 1.0/1.3/2.0, dark mode
- [ ] T3  CODE + EFFICIENCY SWEEP: main-thread work, recomposition, allocation, DB queries, request rate
- [ ] T4  BUG HUNT: correctness across the whole app, adversarial not confirmatory
- [ ] T5  Fix every finding without introducing new ones
- [ ] T6  Verify: full suite, checkinit, lint, second-pass review of every fix
- [ ] T7  Ship v7.0 (versionCode 57) + checkpoint delivered

**Resume at T1** (BACKGROUND AUDIT: trace every coroutine, timer, listener and lifecycle path in v6.9 from scratch).

## 5. Open findings — 1 still open, 0 fixed

- [ ] G01 (high) refresh() launches its network pass into viewModelScope, which OUTLIVES backgrounding. autoJob.cancel() stops the LOOP but not a refresh already in flight, so the batched quote request keeps transferring after the user leaves and its socket is never disconnected - the exact class of work Round 57 moved to fgScope. It also strands loading=true for up to 15s, during which the resume refresh returns early at its own guard and the user comes back to stale prices.

## 6. Version

- Shipped: v6.9 (versionCode 56)
- This round ships: v7.0 (versionCode 57)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-07 20:58:45 UTC  finding F22: An intraday chart left open keeps re-fetching itself after the session that prod
- 2026-09-07 21:02:47 UTC  F22 fixed: intradayChartIsFinal stops the automatic refresh once the session that produced the line has ended, tested against the real MarketClock including the pre-market fallback case
- 2026-09-08 00:47:24 UTC  T10 -> done  22 findings across 3 passes, all fixed; lint clean
- 2026-09-08 00:47:25 UTC  T11 -> done  every finding closed and re-verified by the suite
- 2026-09-08 00:49:43 UTC  T12 -> done  358/358 tests, checkinit ok, lintVital 'No issues found', APK signed with the archived keystore (cert SHA-256 matches), versionCode 56 / 6.9 confirmed in the built APK
- 2026-09-08 00:49:43 UTC  T13 -> doing  shipping v6.9
- 2026-09-08 00:49:54 UTC  T13 -> done  v6.9 APK + checkpoint 58 delivered
- 2026-09-08 01:00:59 UTC  round 59 started
- 2026-09-08 01:01:02 UTC  round 59 ledger created; baseline build running
- 2026-09-08 01:01:55 UTC  finding G01: refresh() launches its network pass into viewModelScope, which OUTLIVES backgrou
- 2026-09-08 01:02:17 UTC  T0 -> done  release APK + 358/358 green on the shipped tree
- 2026-09-08 01:02:18 UTC  T1 -> doing  background audit

