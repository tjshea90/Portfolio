# RESUME — READ THIS FIRST  (round 63, saved 2026-09-08 06:43:45 UTC)

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

- **In flight:** T6: Tests for T1-T5: pure + rendered
- **Next action:** (pick the first unchecked task below)

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 6/12 done

- [x] T0  Baseline: v7.3 tree builds and 443 tests green before any edit  — 443/443 green on the untouched v7.3 tree (one transient Robolectric jar-fetch failure on the first run, clean on re-run)
- [x] T1  Swipe-to-change-tabs: horizontal gesture paging over the 6 top-level tabs  — gesture-based tab paging (not a pager - neighbours stay uncomposed so VisibleScope still describes one screen); pure swipeTarget decision + slide animation shared with the bar
- [x] T2  Pinch-zoom charts: continuous zoom across the range ladder, All-time <-> 5m  — pinch zoom walks ChartRange.ZOOM_LADDER (All->5Y->1Y->6M->1M->5D->1D/5-minute); one pointer loop shared with scrubbing, per-rung 1.55x accumulator, 380ms settle so a multi-rung spread fetches only the rung it lands on; chip row auto-scrolls to the selection
- [x] T3  Best ETFs data: ETF screener feed + scorer + cached section + periodic refresh  — EtfScreener (3 keyless Yahoo fund screens, 10 requests, ~850 funds - measured that top_performing_etfs duplicates top_etfs_us and dropped it), EtfRow/EtfFacts, EtfScore (returns weighted to 5y/3y, cost, size, liquidity, age, trend; leveraged+inverse excluded), Research.buildEtfs on its own 6h TTL, vm.loadEtfs/etfsStale with its own job
- [x] T4  Best ETFs UI: fourth research tab + Claude-app bridge export/import  — fourth research tab with its own scroll state, timestamp, refresh target, blurb, sources and warnings; ETF facts grid on the card; prompt/bundle/parse/merge carry the etfs array and name the universe gaps so Claude adds the funds Yahoo's screens omit; funds Claude adds survive a rebuild
- [x] T5  SPY comparison overlay: second series aligned to the first and drawn against it  — SPY overlay: percent mode with both lines rebased to the same moment (previous close intraday, the benchmark's value at the window start for longer ranges), zero line, segmented benchmark path across gaps, dual crosshair, legend, live edge on both tips, 'vs SPY' chip beside the range chips; shares loadChart's cache so it costs one fetch per range per TTL for the whole app
- [>] T6  Tests for T1-T5: pure + rendered  — tests for T1-T5
- [ ] T7  REGRESSION: full suite, lint, checkinit; prove nothing pre-existing broke
- [ ] T8  SWEEP 1: adversarial bug hunt across the whole app; fix everything found
- [ ] T9  SWEEP 2: UI, code and network-efficiency pass; fix everything found
- [ ] T10  SWEEP 3: re-scan until clean - verify no fix introduced a new bug
- [ ] T11  Ship v7.4 (versionCode 61) + final checkpoint

**Resume at T6** (Tests for T1-T5: pure + rendered).

## 5. Open findings — 0 still open, 0 fixed

(none recorded yet)

## 6. Version

- Shipped: v7.3 (versionCode 60)
- This round ships: v7.4 (versionCode 61)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 06:04:57 UTC  T0 -> done  443/443 green on the untouched v7.3 tree (one transient Robolectric jar-fetch failure on the first run, clean on re-run)
- 2026-09-08 06:05:05 UTC  T1 -> doing  swipe-to-change-tabs
- 2026-09-08 06:09:10 UTC  T1 -> done  gesture-based tab paging (not a pager - neighbours stay uncomposed so VisibleScope still describes one screen); pure swipeTarget decision + slide animation shared with the bar
- 2026-09-08 06:09:11 UTC  T2 -> doing  pinch-zoom charts
- 2026-09-08 06:15:35 UTC  T2 -> done  pinch zoom walks ChartRange.ZOOM_LADDER (All->5Y->1Y->6M->1M->5D->1D/5-minute); one pointer loop shared with scrubbing, per-rung 1.55x accumulator, 380ms settle so a multi-rung spread fetches only the rung it lands on; chip row auto-scrolls to the selection
- 2026-09-08 06:15:36 UTC  T3 -> doing  Best ETFs data layer
- 2026-09-08 06:26:50 UTC  T3 -> done  EtfScreener (3 keyless Yahoo fund screens, 10 requests, ~850 funds - measured that top_performing_etfs duplicates top_etfs_us and dropped it), EtfRow/EtfFacts, EtfScore (returns weighted to 5y/3y, cost, size, liquidity, age, trend; leveraged+inverse excluded), Research.buildEtfs on its own 6h TTL, vm.loadEtfs/etfsStale with its own job
- 2026-09-08 06:26:51 UTC  T4 -> doing  Best ETFs UI + Claude bridge
- 2026-09-08 06:33:55 UTC  T4 -> done  fourth research tab with its own scroll state, timestamp, refresh target, blurb, sources and warnings; ETF facts grid on the card; prompt/bundle/parse/merge carry the etfs array and name the universe gaps so Claude adds the funds Yahoo's screens omit; funds Claude adds survive a rebuild
- 2026-09-08 06:33:56 UTC  T5 -> doing  SPY comparison overlay
- 2026-09-08 06:43:44 UTC  T5 -> done  SPY overlay: percent mode with both lines rebased to the same moment (previous close intraday, the benchmark's value at the window start for longer ranges), zero line, segmented benchmark path across gaps, dual crosshair, legend, live edge on both tips, 'vs SPY' chip beside the range chips; shares loadChart's cache so it costs one fetch per range per TTL for the whole app
- 2026-09-08 06:43:45 UTC  T6 -> doing  tests for T1-T5

