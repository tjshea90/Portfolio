# RESUME — READ THIS FIRST  (round 63, saved 2026-09-08 07:18:00 UTC)

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

- **In flight:** T8: SWEEP 1: adversarial bug hunt across the whole app; fix everything found
- **Next action:** (pick the first unchecked task below)

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 8/12 done

- [x] T0  Baseline: v7.3 tree builds and 443 tests green before any edit  — 443/443 green on the untouched v7.3 tree (one transient Robolectric jar-fetch failure on the first run, clean on re-run)
- [x] T1  Swipe-to-change-tabs: horizontal gesture paging over the 6 top-level tabs  — gesture-based tab paging (not a pager - neighbours stay uncomposed so VisibleScope still describes one screen); pure swipeTarget decision + slide animation shared with the bar
- [x] T2  Pinch-zoom charts: continuous zoom across the range ladder, All-time <-> 5m  — pinch zoom walks ChartRange.ZOOM_LADDER (All->5Y->1Y->6M->1M->5D->1D/5-minute); one pointer loop shared with scrubbing, per-rung 1.55x accumulator, 380ms settle so a multi-rung spread fetches only the rung it lands on; chip row auto-scrolls to the selection
- [x] T3  Best ETFs data: ETF screener feed + scorer + cached section + periodic refresh  — EtfScreener (3 keyless Yahoo fund screens, 10 requests, ~850 funds - measured that top_performing_etfs duplicates top_etfs_us and dropped it), EtfRow/EtfFacts, EtfScore (returns weighted to 5y/3y, cost, size, liquidity, age, trend; leveraged+inverse excluded), Research.buildEtfs on its own 6h TTL, vm.loadEtfs/etfsStale with its own job
- [x] T4  Best ETFs UI: fourth research tab + Claude-app bridge export/import  — fourth research tab with its own scroll state, timestamp, refresh target, blurb, sources and warnings; ETF facts grid on the card; prompt/bundle/parse/merge carry the etfs array and name the universe gaps so Claude adds the funds Yahoo's screens omit; funds Claude adds survive a rebuild
- [x] T5  SPY comparison overlay: second series aligned to the first and drawn against it  — SPY overlay: percent mode with both lines rebased to the same moment (previous close intraday, the benchmark's value at the window start for longer ranges), zero line, segmented benchmark path across gaps, dual crosshair, legend, live edge on both tips, 'vs SPY' chip beside the range chips; shares loadChart's cache so it costs one fetch per range per TTL for the whole app
- [x] T6  Tests for T1-T5: pure + rendered  — SwipeTabTest 15, ChartZoomTest 12, CompareChartTest 10, EtfTest 23, GestureUiTest 11 (real multi-touch), CompareChartUiTest 8 - 79 new
- [x] T7  REGRESSION: full suite, lint, checkinit; prove nothing pre-existing broke  — 522/522 green (443 baseline + 79 new, nothing pre-existing touched), lint vital clean, checkinit ok
- [>] T8  SWEEP 1: adversarial bug hunt across the whole app; fix everything found  — adversarial sweep
- [ ] T9  SWEEP 2: UI, code and network-efficiency pass; fix everything found
- [ ] T10  SWEEP 3: re-scan until clean - verify no fix introduced a new bug
- [ ] T11  Ship v7.4 (versionCode 61) + final checkpoint

**Resume at T8** (SWEEP 1: adversarial bug hunt across the whole app; fix everything found).

## 5. Open findings — 1 still open, 5 fixed

- [x] J01 (high) isLeveragedOrInverse excluded every short-duration bond fund: ' short ' and ' ultrashort ' matched 'iShares Short Treasury Bond ETF', 'Vanguard Short-Term Bond', 'PIMCO Enhanced Short Maturity' and 'iShares Ultra Short-Term Bond'. Short-duration bond funds are among the most widely held ETFs there are - the Best ETFs list could not have contained the safe half of a portfolio.  — the test is now what the fund is short OF: 'short' followed by a duration or credit word (term/duration/maturity/treasury/bond/...) is an ordinary bond fund; anything else is inverse. Explicit multiples and 'bear'/'inverse'/'ultrapro' still exclude outright. 9 real fund names asserted both ways.
- [x] F01 (high) loadEtfs shares _researchBusy with the stock pass, so opening the ETFs tab while the 18-request stock build is running silently does nothing - and nothing ever retries. The tab sits empty until the user switches away and back or pulls down.  — the section's build effect is keyed on the shared busy flag as well, so a request dropped while the other pass was in flight is re-made the moment it clears; neither call can loop because both return immediately inside their own TTL
- [x] F02 (med) A chip tap straight after a pinch is delayed 380ms: zoomSettling is only cleared inside the settle branch, so it is still true when the tap's LaunchedEffect runs. Contradicts the documented 'a tap is not a zoom' rule.  — tapping a range chip clears zoomSettling rather than merely not setting it - an explicit destination has no intermediate rungs to swallow
- [x] F03 (med) zoomFactor reads event.changes[0] and [1] positionally. A third finger landing, or one of two lifting, reshuffles that list and produces an impossible one-frame separation ratio - which the accumulator then spends as several real zoom rungs.  — the pinch now measures a NAMED pair of pointer ids chosen when the gesture begins; if either finger leaves, the pair is re-chosen and that frame yields no reading, so a third finger costs one frame instead of an arbitrary jump
- [x] F04 (low) showMoreResearch on the ETF section calls enrichVisible, which only has work for BEST and WORST - so revealing ten more funds starts an analyst pass and flips the busy indicator for nothing.  — showMoreResearch only enriches Best and Worst - a fund's numbers arrive with its screener row and have no second stage
- [ ] F05 (high) The F01 fix reintroduced a worse bug: keying the build effect on the shared busy flag means a FAILED pass re-triggers itself the instant busy clears. An empty result leaves the set stale, so loadResearch/loadEtfs launch again immediately - an unbounded retry loop of 18 (or 10) requests against providers that are almost certainly rate-limiting, which is exactly what the backoff machinery elsewhere in the app exists to prevent.

## 6. Version

- Shipped: v7.3 (versionCode 60)
- This round ships: v7.4 (versionCode 61)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 07:00:39 UTC  T7 -> doing  full regression
- 2026-09-08 07:06:28 UTC  T7 -> done  522/522 green (443 baseline + 79 new, nothing pre-existing touched), lint vital clean, checkinit ok
- 2026-09-08 07:08:12 UTC  T8 -> doing  adversarial sweep
- 2026-09-08 07:08:13 UTC  finding F01: loadEtfs shares _researchBusy with the stock pass, so opening the ETFs tab while
- 2026-09-08 07:08:13 UTC  finding F02: A chip tap straight after a pinch is delayed 380ms: zoomSettling is only cleared
- 2026-09-08 07:08:13 UTC  finding F03: zoomFactor reads event.changes[0] and [1] positionally. A third finger landing, 
- 2026-09-08 07:08:13 UTC  finding F04: showMoreResearch on the ETF section calls enrichVisible, which only has work for
- 2026-09-08 07:15:27 UTC  F01 fixed: the section's build effect is keyed on the shared busy flag as well, so a request dropped while the other pass was in flight is re-made the moment it clears; neither call can loop because both return immediately inside their own TTL
- 2026-09-08 07:15:27 UTC  F02 fixed: tapping a range chip clears zoomSettling rather than merely not setting it - an explicit destination has no intermediate rungs to swallow
- 2026-09-08 07:15:28 UTC  F03 fixed: the pinch now measures a NAMED pair of pointer ids chosen when the gesture begins; if either finger leaves, the pair is re-chosen and that frame yields no reading, so a third finger costs one frame instead of an arbitrary jump
- 2026-09-08 07:15:28 UTC  F04 fixed: showMoreResearch only enriches Best and Worst - a fund's numbers arrive with its screener row and have no second stage
- 2026-09-08 07:18:00 UTC  finding F05: The F01 fix reintroduced a worse bug: keying the build effect on the shared busy

