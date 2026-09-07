# RESUME — READ THIS FIRST  (round 58, saved 2026-09-07 20:00:12 UTC)

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

> Chart time-range selector (1D/5D/1M/6M/1Y/5Y/All/overnight); more pronounced separation between stocks in vertical lists; charts vanish after backgrounding - cache instead of reloading, but always refresh on pull-down; stack after-hours $ and % vertically; fix unreliable/inefficient chart fetching; ETF Holdings tab with each holding's weight; verify the app truly sleeps in the background (RAM/CPU/battery); full sweep for bugs, UI, efficiency and code improvements without introducing new bugs.

## 3. WHERE THE WORK STOPPED

- **In flight:** (nothing in flight)
- **Next action:** Finish ck/watchdog, then confirm baseline build (T1)

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 1/14 done

- [x] T0  Cowork checkpoint system: ck tool, RESUME.md, state.json, git, 3-min watchdog  — ck tool, RESUME.md, state.json, git repo, 3-min watchdog, CHECKPOINT.md section 0 rewritten
- [ ] T1  Baseline: release build + 276-test suite green before any edit
- [ ] T2  Map the chart pipeline end to end (MarketData, ViewModel, DetailScreen, Widgets)
- [ ] T3  Chart RANGE SELECTOR: 1D/5D/1M/6M/1Y/5Y/All + after-hours-only, near the chart
- [ ] T4  Chart CACHING: survive backgrounding; periodic auto-refresh; pull-down forces
- [ ] T5  Chart FETCH RELIABILITY: find and fix why charts sometimes never load
- [ ] T6  Vertical stock lists: more pronounced separation (spacing + divider)
- [ ] T7  After-hours block: stack dollar and percent vertically like the other sections
- [ ] T8  ETF detail: HOLDINGS tab listing each holding and its % of the fund
- [ ] T9  Background audit: prove the app sleeps - no RAM/CPU/battery use when not visible
- [ ] T10  Full adversarial sweep: bugs, UI, efficiency, code quality (record every finding)
- [ ] T11  Fix every finding from T10 without introducing new ones
- [ ] T12  Verification: unit tests, checkinit, lint, simulations, second-pass review
- [ ] T13  Ship v6.9 (versionCode 56) + final checkpoint delivered to TJ

**Resume at T1** (Baseline: release build + 276-test suite green before any edit).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v6.8 (versionCode 55)
- This round ships: v6.9 (versionCode 56)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-07 19:59:39 UTC  round 58 started
- 2026-09-07 19:59:53 UTC  round 58 ledger created
- 2026-09-07 20:00:12 UTC  T0 -> done  ck tool, RESUME.md, state.json, git repo, 3-min watchdog, CHECKPOINT.md section 0 rewritten

