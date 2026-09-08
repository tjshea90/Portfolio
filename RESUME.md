# RESUME — READ THIS FIRST  (round 63, saved 2026-09-08 06:04:57 UTC)

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

> Round 63: (1) swipe left/right to change tabs, (2) pinch-zoom charts continuously from All-time down to 5-minute intervals, (3) Best ETFs research tab - online research, periodically refreshed, cached between updates, with the Claude-app export the other sections have, (4) the last approved feature: SPY comparison overlay. Then a thorough sweep for UI/code/network improvements and bugs, repeated until confident the app is clean.

## 3. WHERE THE WORK STOPPED

- **In flight:** (nothing in flight)
- **Next action:** (pick the first unchecked task below)

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 1/12 done

- [x] T0  Baseline: v7.3 tree builds and 443 tests green before any edit  — 443/443 green on the untouched v7.3 tree (one transient Robolectric jar-fetch failure on the first run, clean on re-run)
- [ ] T1  Swipe-to-change-tabs: horizontal gesture paging over the 6 top-level tabs
- [ ] T2  Pinch-zoom charts: continuous zoom across the range ladder, All-time <-> 5m
- [ ] T3  Best ETFs data: ETF screener feed + scorer + cached section + periodic refresh
- [ ] T4  Best ETFs UI: fourth research tab + Claude-app bridge export/import
- [ ] T5  SPY comparison overlay: second series aligned to the first and drawn against it
- [ ] T6  Tests for T1-T5: pure + rendered
- [ ] T7  REGRESSION: full suite, lint, checkinit; prove nothing pre-existing broke
- [ ] T8  SWEEP 1: adversarial bug hunt across the whole app; fix everything found
- [ ] T9  SWEEP 2: UI, code and network-efficiency pass; fix everything found
- [ ] T10  SWEEP 3: re-scan until clean - verify no fix introduced a new bug
- [ ] T11  Ship v7.4 (versionCode 61) + final checkpoint

**Resume at T1** (Swipe-to-change-tabs: horizontal gesture paging over the 6 top-level tabs).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v7.3 (versionCode 60)
- This round ships: v7.4 (versionCode 61)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 04:45:19 UTC  finding J01: A chip figure comes from whatever series is cached for that range, and a cached 
- 2026-09-08 04:45:19 UTC  finding J02: A truncated series - a stock that listed 18 months ago has no 5Y line - would pu
- 2026-09-08 04:47:49 UTC  J01 fixed: a figure is printed only from a series fetched within the last 24h; an unstamped row counts as unknown age, not as fresh
- 2026-09-08 04:47:50 UTC  J02 fixed: a truncated series puts no figure on its chip - the chart captions that case in words and a chip cannot
- 2026-09-08 04:48:05 UTC  T7 -> done  2 findings (age, truncation), both fixed and both covered by tests
- 2026-09-08 04:48:06 UTC  T8 -> doing  shipping v7.3
- 2026-09-08 04:51:10 UTC  T8 -> done  v7.3 shipped: versionCode 60, signed with the archived key (cert SHA-256 unchanged), 443/443 green, lint clean
- 2026-09-08 04:51:25 UTC  T5 -> done  RangeChipTest (16 pure) + RangeChipUiTest (9 rendered, incl. chip-equals-readout with the live edge)
- 2026-09-08 05:56:26 UTC  round 63 started
- 2026-09-08 05:57:17 UTC  round 63 started
- 2026-09-08 05:57:24 UTC  T0 -> doing  baseline build+test
- 2026-09-08 06:04:57 UTC  T0 -> done  443/443 green on the untouched v7.3 tree (one transient Robolectric jar-fetch failure on the first run, clean on re-run)

