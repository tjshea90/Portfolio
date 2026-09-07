# RESUME — READ THIS FIRST  (round 58, saved 2026-09-07 20:20:52 UTC)

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

- **In flight:** T8: ETF holdings - building the model, feed and tab
- **Next action:** Write data/HoldingsModels.kt, net/HoldingsFeed.kt, wire a HOLDINGS tab that only appears for funds

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 8/14 done

- [x] T0  Cowork checkpoint system: ck tool, RESUME.md, state.json, git, 3-min watchdog  — ck tool, RESUME.md, state.json, git repo, 3-min watchdog, CHECKPOINT.md section 0 rewritten
- [x] T1  Baseline: release build + 276-test suite green before any edit  — release APK + 276/276 tests green + checkinit ok
- [x] T2  Map the chart pipeline end to end (MarketData, ViewModel, DetailScreen, Widgets)  — chart path mapped: MarketData.yahoo(1d/5m) -> Quote.spark -> Widgets.Sparkline; series cached in quotes.spark; 5 findings recorded
- [x] T3  Chart RANGE SELECTOR: 1D/5D/1M/6M/1Y/5Y/All + after-hours-only, near the chart  — ChartRange (8 ranges incl. after-hours-only), ChartFeed, PriceChart + RangeChips, wired into DetailScreen; range remembered across launches
- [x] T4  Chart CACHING: survive backgrounding; periodic auto-refresh; pull-down forces  — chart_cache at db v7 (disk-first, per-range TTL, pull-down forces); trim no longer blanks charts; sparks restored from SQLite on resume
- [x] T5  Chart FETCH RELIABILITY: find and fix why charts sometimes never load  — partial-failure spark throttle, sparkline no longer gated behind the quote pass, cooling Yahoo host skipped not abandoned
- [x] T6  Vertical stock lists: more pronounced separation (spacing + divider)  — RowSeparator 3dp + 5dp air between rows; InRowDivider 1dp/45% inside a row; applied to Portfolio, Watchlist, Search
- [x] T7  After-hours block: stack dollar and percent vertically like the other sections  — extended-hours cell stacks price / dollar change / percent vertically
- [>] T8  ETF detail: HOLDINGS tab listing each holding and its % of the fund  — ETF holdings tab
- [ ] T9  Background audit: prove the app sleeps - no RAM/CPU/battery use when not visible
- [ ] T10  Full adversarial sweep: bugs, UI, efficiency, code quality (record every finding)
- [ ] T11  Fix every finding from T10 without introducing new ones
- [ ] T12  Verification: unit tests, checkinit, lint, simulations, second-pass review
- [ ] T13  Ship v6.9 (versionCode 56) + final checkpoint delivered to TJ

**Resume at T8** (ETF detail: HOLDINGS tab listing each holding and its % of the fund).

## 5. Open findings — 0 still open, 5 fixed

- [x] F01 (high) onTrimMemory drops every sparkline at TRIM_MEMORY_UI_HIDDEN (20 >= TRIM_RUNNING_CRITICAL 15), which Android delivers on EVERY app switch. The sparkAt 5-minute throttle is already stamped, so charts stay blank for up to 5 minutes after returning. This is TJ's 'charts disappeared when I switched back' report. The doc comment's reasoning ('they ride along with the next quote') went stale in Round 56 when the series moved off the quote path.  — onTrimMemory now releases nothing below TRIM_MODERATE (60); UI_HIDDEN no longer blanks charts
- [x] F02 (high) After a spark drop the DB (quotes.spark) still holds the series, but nothing re-reads it on return - the recovery path is a network fetch that the throttle suppresses.  — restoreSparklines() reads quotes.spark back from SQLite on every resume; chart_cache does the same for fetched ranges
- [x] F03 (high) refreshSparklines only unmarks sparkAt when EVERY symbol failed. On a partial failure the symbols that failed stay stamped 'just fetched' and are not retried for a full 5 minutes, so individual charts are intermittently missing - TJ's 'sometimes they don't load'.  — refreshSparklines un-marks every failed symbol, not only the all-failed case
- [x] F04 (med) refreshSparklines is called at the very end of refresh(), AFTER the 'fetched.isEmpty() -> return@launch' early exit. One failed batch quote pass means the candle series is not refreshed at all that tick, even for symbols whose charts are blank.  — refreshSparklines starts alongside the quote pass, above the no-quotes early return
- [x] F05 (med) MarketData.yahoo() and batchYahoo() return immediately when query1 is in a LOCAL cooldown instead of trying query2. Cooldowns are armed per host, so query1 cooling says nothing about query2 - the chart request is abandoned while a usable host sits unused. Contributes to charts not loading.  — a locally-cooling Yahoo host is now skipped rather than abandoning the request; both chart and batch paths

## 6. Version

- Shipped: v6.8 (versionCode 55)
- This round ships: v6.9 (versionCode 56)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-07 20:13:54 UTC  F01 fixed: onTrimMemory now releases nothing below TRIM_MODERATE (60); UI_HIDDEN no longer blanks charts
- 2026-09-07 20:13:54 UTC  F02 fixed: restoreSparklines() reads quotes.spark back from SQLite on every resume; chart_cache does the same for fetched ranges
- 2026-09-07 20:15:05 UTC  F03 fixed: refreshSparklines un-marks every failed symbol, not only the all-failed case
- 2026-09-07 20:15:05 UTC  F04 fixed: refreshSparklines starts alongside the quote pass, above the no-quotes early return
- 2026-09-07 20:15:06 UTC  F05 fixed: a locally-cooling Yahoo host is now skipped rather than abandoning the request; both chart and batch paths
- 2026-09-07 20:18:26 UTC  T3/T4/T5/T6/T7 code written; first compile of round 58
- 2026-09-07 20:20:21 UTC  T3 -> done  ChartRange (8 ranges incl. after-hours-only), ChartFeed, PriceChart + RangeChips, wired into DetailScreen; range remembered across launches
- 2026-09-07 20:20:22 UTC  T4 -> done  chart_cache at db v7 (disk-first, per-range TTL, pull-down forces); trim no longer blanks charts; sparks restored from SQLite on resume
- 2026-09-07 20:20:22 UTC  T5 -> done  partial-failure spark throttle, sparkline no longer gated behind the quote pass, cooling Yahoo host skipped not abandoned
- 2026-09-07 20:20:23 UTC  T6 -> done  RowSeparator 3dp + 5dp air between rows; InRowDivider 1dp/45% inside a row; applied to Portfolio, Watchlist, Search
- 2026-09-07 20:20:23 UTC  T7 -> done  extended-hours cell stacks price / dollar change / percent vertically
- 2026-09-07 20:20:23 UTC  T8 -> doing  ETF holdings tab

