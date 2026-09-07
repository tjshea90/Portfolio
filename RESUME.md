# RESUME — READ THIS FIRST  (round 58, saved 2026-09-07 21:02:47 UTC)

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

- **In flight:** T10: Full adversarial sweep: bugs, UI, efficiency, code quality (record every finding)
- **Next action:** Write data/HoldingsModels.kt, net/HoldingsFeed.kt, wire a HOLDINGS tab that only appears for funds

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 10/14 done

- [x] T0  Cowork checkpoint system: ck tool, RESUME.md, state.json, git, 3-min watchdog  — ck tool, RESUME.md, state.json, git repo, 3-min watchdog, CHECKPOINT.md section 0 rewritten
- [x] T1  Baseline: release build + 276-test suite green before any edit  — release APK + 276/276 tests green + checkinit ok
- [x] T2  Map the chart pipeline end to end (MarketData, ViewModel, DetailScreen, Widgets)  — chart path mapped: MarketData.yahoo(1d/5m) -> Quote.spark -> Widgets.Sparkline; series cached in quotes.spark; 5 findings recorded
- [x] T3  Chart RANGE SELECTOR: 1D/5D/1M/6M/1Y/5Y/All + after-hours-only, near the chart  — ChartRange (8 ranges incl. after-hours-only), ChartFeed, PriceChart + RangeChips, wired into DetailScreen; range remembered across launches
- [x] T4  Chart CACHING: survive backgrounding; periodic auto-refresh; pull-down forces  — chart_cache at db v7 (disk-first, per-range TTL, pull-down forces); trim no longer blanks charts; sparks restored from SQLite on resume
- [x] T5  Chart FETCH RELIABILITY: find and fix why charts sometimes never load  — partial-failure spark throttle, sparkline no longer gated behind the quote pass, cooling Yahoo host skipped not abandoned
- [x] T6  Vertical stock lists: more pronounced separation (spacing + divider)  — RowSeparator 3dp + 5dp air between rows; InRowDivider 1dp/45% inside a row; applied to Portfolio, Watchlist, Search
- [x] T7  After-hours block: stack dollar and percent vertically like the other sections  — extended-hours cell stacks price / dollar change / percent vertically
- [x] T8  ETF detail: HOLDINGS tab listing each holding and its % of the fund  — FundHoldings model + HoldingsFeed (Yahoo topHoldings/fundProfile/quoteType) + HoldingsTab (holdings with weights, sector split, asset mix) + fund-only tab visibility + detail nav stack
- [x] T9  Background audit: prove the app sleeps - no RAM/CPU/battery use when not visible  — new code audited: loadChart/loadHoldings on fgScope, writes on viewModelScope, no new timers; trim thresholds corrected; duplicate 1D request removed
- [>] T10  Full adversarial sweep: bugs, UI, efficiency, code quality (record every finding)  — sweep pass 3 - pre-existing code I have not touched
- [ ] T11  Fix every finding from T10 without introducing new ones
- [ ] T12  Verification: unit tests, checkinit, lint, simulations, second-pass review
- [ ] T13  Ship v6.9 (versionCode 56) + final checkpoint delivered to TJ

**Resume at T10** (Full adversarial sweep: bugs, UI, efficiency, code quality (record every finding)).

## 5. Open findings — 0 still open, 22 fixed

- [x] F01 (high) onTrimMemory drops every sparkline at TRIM_MEMORY_UI_HIDDEN (20 >= TRIM_RUNNING_CRITICAL 15), which Android delivers on EVERY app switch. The sparkAt 5-minute throttle is already stamped, so charts stay blank for up to 5 minutes after returning. This is TJ's 'charts disappeared when I switched back' report. The doc comment's reasoning ('they ride along with the next quote') went stale in Round 56 when the series moved off the quote path.  — onTrimMemory now releases nothing below TRIM_MODERATE (60); UI_HIDDEN no longer blanks charts
- [x] F02 (high) After a spark drop the DB (quotes.spark) still holds the series, but nothing re-reads it on return - the recovery path is a network fetch that the throttle suppresses.  — restoreSparklines() reads quotes.spark back from SQLite on every resume; chart_cache does the same for fetched ranges
- [x] F03 (high) refreshSparklines only unmarks sparkAt when EVERY symbol failed. On a partial failure the symbols that failed stay stamped 'just fetched' and are not retried for a full 5 minutes, so individual charts are intermittently missing - TJ's 'sometimes they don't load'.  — refreshSparklines un-marks every failed symbol, not only the all-failed case
- [x] F04 (med) refreshSparklines is called at the very end of refresh(), AFTER the 'fetched.isEmpty() -> return@launch' early exit. One failed batch quote pass means the candle series is not refreshed at all that tick, even for symbols whose charts are blank.  — refreshSparklines starts alongside the quote pass, above the no-quotes early return
- [x] F05 (med) MarketData.yahoo() and batchYahoo() return immediately when query1 is in a LOCAL cooldown instead of trying query2. Cooldowns are armed per host, so query1 cooling says nothing about query2 - the chart request is abandoned while a usable host sits unused. Contributes to charts not loading.  — a locally-cooling Yahoo host is now skipped rather than abandoning the request; both chart and batch paths
- [x] F06 (high) fgScope cancellation on ON_STOP has no counterpart on ON_START for the DETAIL SCREEN's own loads. A stock opened and then backgrounded mid-fetch has its news/fundamentals/insider/chart/holdings request cancelled, and the LaunchedEffect(symbol) that started it does not re-fire on return because its key has not changed - so the tab stays empty until a manual pull-to-refresh. Introduced by Round 57's fgScope work; the chart and holdings added this round inherit it.  — DetailScreen re-requests its cache-first loads on ON_START, so an fgScope cancellation no longer strands an open screen
- [x] F07 (med) The 1D chart request and the row-sparkline request are the SAME Yahoo URL (range=1d&interval=5m&includePrePost=true) fired on two independent 5-minute clocks. With a stock's detail screen open the app pulls that body twice per window for no benefit.  — loadChart(D1) feeds its series into the quote's spark and stamps sparkAt, so the identical sparkline request is not made
- [x] F08 (high) ChartRange.OVERNIGHT silently degrades into a 5-day intraday chart when meta carries no tradingPeriods and no currentTradingPeriod: with no regular windows the extended-only filter keeps everything, and it is still captioned 'After-hours and overnight only'.  — OVERNIGHT returns null when no regular session windows can be determined, rather than drawing five days under an after-hours caption
- [x] F09 (high) HoldingsTab keys its LazyColumn on symbol-or-name. Two share classes or a provider repeat give the same key, and a LazyColumn handed a duplicate key THROWS and takes the app down. This exact crash has shipped three times in this project (news, filings, research rows).  — HoldingsTab uses itemsIndexed, so a duplicate symbol or name can no longer crash the LazyColumn
- [x] F10 (med) After a memory trim drops _holdings, a detail screen sitting on the HOLDINGS tab has that tab removed from the visible list while 'tab' still points at it: the indicator jumps to Overview while the Holdings body is still rendered.  — a tab selection that leaves the visible set falls back to Overview
- [x] F11 (high) adoptAsSparkline feeds the D1 chart series into quote.spark, but the two are NOT the same data: MarketData.parseYahoo puts only REGULAR-session points in spark, while ChartFeed keeps pre/post as well. The row sparkline would silently change from a trading-day line into a 24-hour one, with the prevClose baseline no longer matching what is drawn.  — D1 keeps regular-session points only (with the pre-open fallback parseYahoo has), so it is byte-identical in meaning to Quote.spark and adoptAsSparkline is safe
- [x] F12 (med) loadChart marks chartDiskRead BEFORE the disk read. A cancellation between the two leaves the symbol marked as read with nothing loaded, so the disk cache is never consulted again this session.  — chartDiskRead is un-marked when the disk read does not complete
- [x] F13 (high) chart_cache uses a column literally named 'range', which is a reserved keyword in SQLite's window-frame syntax. Unquoted, it is at best relying on the parser's leniency.  — column renamed range_key; verified across all eight ranges against real SQLite
- [x] F14 (high) purgeChartCache binds an Int via execSQL bind args. Android's SQLiteProgram binds only Long/Double/String/byte[]/null and throws IllegalArgumentException otherwise - swallowed by runCatching, so the cache would grow unbounded and silently.  — NOT A BUG - verified against real SQLite via ChartCacheDbTest: DatabaseUtils.bindObjectToProgram binds any Number as a long, so an Int bind arg is fine. The purge test proves rows are actually removed.
- [x] F15 (low) Opening a symbol from SearchSheet does not clear detailStack, so a back press from the searched stock returns to a fund left on the stack rather than dismissing the screen.  — opening a search result clears detailStack
- [x] F16 (low) The new ON_START DisposableEffect is keyed on symbol/chartRange/tab, so it re-registers on every tab and range change - and LifecycleRegistry replays ON_START to a newly added observer, firing all six loads again each time.  — the ON_START observer is keyed only on the lifecycle owner and reads current values via rememberUpdatedState
- [x] F17 (low) purgeChartCache runs on EVERY chart write - a DELETE with an ordered subquery over the whole table each time a chart is fetched, where the other caches purge once per session.  — purgeChartCache moved to the once-a-session purge alongside news, fundamentals and http
- [x] F18 (med) Fundamentals.estimates and .history are rendered as keyed LazyColumn items with no distinctBy. A provider repeating a period or a quarter date is a duplicate key, which throws - the crash this project has already shipped three times.  — estimates and history are de-duplicated before being keyed
- [x] F19 (low) ChartRange.D5 has a 5-minute TTL against a 30-minute candle. Asking more often than the provider makes a new point cannot return anything new - the same rule that put D1 on five minutes.  — D5 TTL raised to 30 minutes to match its 30-minute candle
- [x] F20 (high) A chart never refreshes while its screen stays open. loadChart runs from LaunchedEffect(symbol, range) and ON_START only, so watching one stock for an hour leaves the line frozen at load time with only its tip moving. TJ asked for periodic automatic refresh as well as pull-down.  — the open chart re-checks its TTL on every quote tick and refreshes itself at its own cadence, answered from memory without a coroutine when nothing is due
- [x] F21 (med) RangeChips are ~40dp tall - under the app's own documented 48dp minimum tap target, which it has a helper for and a round-46 finding about. Eight chips in a scrolling row means a mis-tap selects the neighbouring range.  — range chips use minTapTarget(); a rendered UI test asserts 48dp at 1.0 and 1.3 font scale
- [x] F22 (med) An intraday chart left open keeps re-fetching itself after the session that produced it has ended. The regular-session 1D line cannot gain a candle after 16:00 ET, yet the poll clock keeps ticking through the whole EXTENDED window - roughly 48 requests an evening for a picture that cannot change. Same for the after-hours line once extended trading stops at 20:00 ET.  — intradayChartIsFinal stops the automatic refresh once the session that produced the line has ended, tested against the real MarketClock including the pre-market fallback case

## 6. Version

- Shipped: v6.8 (versionCode 55)
- This round ships: v6.9 (versionCode 56)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-07 20:45:55 UTC  finding F18: Fundamentals.estimates and .history are rendered as keyed LazyColumn items with 
- 2026-09-07 20:45:55 UTC  finding F19: ChartRange.D5 has a 5-minute TTL against a 30-minute candle. Asking more often t
- 2026-09-07 20:45:55 UTC  finding F20: A chart never refreshes while its screen stays open. loadChart runs from Launche
- 2026-09-07 20:48:00 UTC  F17 fixed: purgeChartCache moved to the once-a-session purge alongside news, fundamentals and http
- 2026-09-07 20:48:01 UTC  F18 fixed: estimates and history are de-duplicated before being keyed
- 2026-09-07 20:48:01 UTC  F19 fixed: D5 TTL raised to 30 minutes to match its 30-minute candle
- 2026-09-07 20:48:01 UTC  F20 fixed: the open chart re-checks its TTL on every quote tick and refreshes itself at its own cadence, answered from memory without a coroutine when nothing is due
- 2026-09-07 20:49:43 UTC  finding F21: RangeChips are ~40dp tall - under the app's own documented 48dp minimum tap targ
- 2026-09-07 20:55:00 UTC  F21 fixed: range chips use minTapTarget(); a rendered UI test asserts 48dp at 1.0 and 1.3 font scale
- 2026-09-07 20:57:06 UTC  T10 -> doing  sweep pass 3 - pre-existing code I have not touched
- 2026-09-07 20:58:45 UTC  finding F22: An intraday chart left open keeps re-fetching itself after the session that prod
- 2026-09-07 21:02:47 UTC  F22 fixed: intradayChartIsFinal stops the automatic refresh once the session that produced the line has ended, tested against the real MarketClock including the pre-market fallback case

