# 2026-09-24 full-test audit — SCREENS, NAVIGATION AND UX (U-)

Status: COMPLETE

Scope read: MainActivity.kt, ShareImportActivity.kt, AndroidManifest.xml, ui/Common.kt (Refreshable +
PullGesture + backstop + draw-time invariant), PortfolioScreen, WatchlistScreen, ResearchScreen
(non-DT), FeedScreen, ReaderScreen, AdviceScreen, SettingsScreen, StockRow, RowActions, SwipeTabs,
SearchSheet, RecommendationDialog, Dialogs, PromptShareUi, Theme, Widgets, util/Format.kt, the
Refreshable call sites in ActivityScreen/DetailScreen, and the VM code they call (refresh /
refreshFeed / syncManualIndicator / spinnerShouldShow / loadResearch / loadEtfs / explain* /
enrichVisible / setNewsVisible / autoJob / VisibleScope.choose). Tests read: PullGestureTest,
PullIndicatorUiTest, SpinnerTest. No code changed, no gradle, no network. Line numbers as of the
tree at audit time.

---

### U-1 [H] The pull spinner on a price screen keeps spinning for up to 15 minutes after unrelated Research work ends
(The main session is already fixing this: per-surface rule + sync on every research-busy clear.)
- where: ui/PortfolioViewModel.kt:344-359 (`spinnerShouldShow`), 6113-6126 (`syncManualIndicator`),
  7259 and 7421 (`explainResearch`/`explainDayTrading` finally: `_researchBusy.value = ""`, no sync),
  7072 (`enrichVisible` finally, no sync), 6845/6982 (`loadEtfs`/`loadResearch` finally:
  `if (force) syncManualIndicator()` only), 3416-3421 (autoJob re-derives only once per tick),
  net/MarketClock.kt:183 (closed-market tick = 900 s).
- what's wrong: `refreshSource` names ONE surface, but the "should it spin" rule is surface-blind:
  `userAsked && (quotesLoading || feedLoading || researchLoading)`. A pull on any PULL_PRICES
  screen stays "refreshing" after its own quote pass while the feed or ANY research job
  (BUSY_EXPLAINING, BUSY_DETAIL, an automatic build) runs; when that job ends its `finally` clears
  `_researchBusy` without `syncManualIndicator()`, so only the next autoJob tick (15 s open, 60 s
  extended, 900 s closed, never with auto-refresh 0) clears it. While `refreshing` is true
  `PullGesture` refuses to grow, so pulling again cannot clear it.
- failure scenario: evening; "Explain with Claude" on Research, switch to Portfolio, pull. Prices
  land ("Prices updated just now") but the circle spins on - through Claude's answer and then up to
  15 more minutes. Also: pull a stock opened from Research during the analyst pass; pull Feed ->
  switch -> pull Portfolio (Feed's own spinner is also stolen mid-pass).
- suggested fix: per-surface `want` (PRICES->quotes, FEED->feed, RESEARCH->research) and
  `syncManualIndicator()` wherever `_researchBusy` is cleared.
- missing test: SpinnerTest has no source dimension; add a VM test (BUSY_EXPLAINING + manual
  refresh finishes -> `pulling(PULL_PRICES)` false; clearing the explain needs no tick).
- confidence: high

### U-2 [M] Feed "Insider > All companies" empty state says "pull down to try again", but a pull never refetches the market-wide list
- where: ui/FeedScreen.kt:109-113 (the only trigger: `LaunchedEffect(filter, source)` ->
  `vm.refreshMarketInsidersIfEmpty()`), 200 (pull -> `vm.refreshFeed(manual = true)`),
  295-298 (the empty text), 332-342 ("Load more" only when the list is NOT empty);
  ui/PortfolioViewModel.kt ~6038 (`refreshFeed` only calls `startInsiderRefresh` - the
  portfolio-scoped pass - never `loadMoreMarketInsiders`).
- what's wrong: a first market-wide fetch that fails (offline, EDGAR 403/429, fgScope cancelled by
  a quick app switch) leaves `_marketInsiders` empty. The screen then says "Nothing to show yet.
  Either the SEC feed could not be reached ... - pull down to try again." A pull runs the headline
  feed and the portfolio insider pass; nothing retries the market-wide one, and the "Load more"
  button is hidden because the list is empty.
- failure scenario: open Feed > Insider > All companies in a dead spot. Signal comes back, Tj pulls
  down as told: spinner, headlines refresh, the All-companies list stays empty. Only toggling the
  source chip away and back (or leaving the tab) re-fires the effect.
- suggested fix: in the Feed's `onRefresh`, also call `vm.refreshMarketInsidersIfEmpty()` when
  `filter == F_INSIDER && source == ALL_COMPANIES` (or show a "Try again" button in that empty
  state that calls `loadMoreMarketInsiders()`).
- missing test: none exists for the Feed's insider empty states.
- confidence: high

### U-3 [M] The whole-portfolio headline sweep keeps running while an article or a stock opened FROM the Feed tab covers it
- where: MainActivity.kt:443-445
  `LaunchedEffect(tab, detailNow) { vm.setNewsVisible(visible = tab == TAB_FEED, detailSymbol = detailNow) }`;
  ui/PortfolioViewModel.kt:5863-5869 (`feedDue = manual || newsVisible || inGrace`,
  `newsSymbols = if (feedDue) symbols ...`), MarketClock.feedIntervalSecs (180 s open).
- what's wrong: `visible` ignores the reader and the detail layer. VisibleScope.choose (MainActivity
  417-429) treats the reader as `None` and a detail screen as that one symbol, but the news signal
  stays "Feed visible" whenever `tab == TAB_FEED`, and `reader` is not even an effect key. So with
  an article (or a WSB/insider row's stock) open over the Feed, every feed tick still pulls all
  per-symbol headlines + the seven market-wide feeds + (15-min clock) Reddit - the exact cost the
  Round 63 comment at 438-442 says a stock screen must not incur.
- failure scenario: market hours, 24-symbol portfolio. Tap a headline on the Feed and read it for
  10 minutes: ~3 full sweeps (~75+ requests, radio kept awake) for a list nobody can see. The
  "arriving" edge in `setNewsVisible` already refreshes the Feed when he comes back, so nothing is
  gained by it.
- suggested fix: `visible = tab == TAB_FEED && detailNow == null && readerNow == null`, and add
  `readerNow` to the effect's keys.
- missing test: none for the MainActivity -> setNewsVisible mapping; extract it into a pure
  `newsVisibleFor(tab, detail, readerOpen)` next to VisibleScope.choose and test it.
- confidence: high

### U-4 [L] Pulling on Research while a Claude explain / analyst pass runs does nothing, silently
- where: ui/ResearchScreen.kt:411-417; ui/PortfolioViewModel.kt:6878 and 6788
  (`if (_researchBusy.value.isNotEmpty()) return` before any `manualRefresh` is set).
- what's wrong / scenario: during "Claude is reading..." (up to a minute) or BUSY_DETAIL, a pull
  triggers, the circle retracts, and nothing is rebuilt or said. The header's busy text is the only
  hint.
- suggested fix: when busy, toast "Research is busy - try again when it finishes" (or queue a
  forced rebuild for when busy clears).
- confidence: high

### U-5 [L] Switching Watchlist <-> Research throws away both lists' scroll positions
- where: ui/WatchlistScreen.kt:99-110 (plain `if/else` between `ResearchScreen` and
  `WatchlistScreen`); ResearchScreen.kt:419-435 (four `rememberLazyListState`s whose comment says
  they "survive a rotation or a process-death restore").
- what's wrong / scenario: the branch that is switched away from leaves composition, and its
  `rememberSaveable` entries (LazyListState saver, Research `howTo`) are discarded - they are not
  under a `SaveableStateHolder` like MainActivity's tab/detail layers. Scroll to row 30 of the
  watchlist, peek at Research, come back: top of the list. Same for all four Research sections.
- suggested fix: wrap each branch in `rememberSaveableStateHolder().SaveableStateProvider("watch:$subTab")`.
- confidence: high

### U-6 [L] Feed filters share one scroll state
- where: ui/FeedScreen.kt:201 (`LazyColumn` with no `state`, four filters rendered as different
  item sets in it).
- scenario: scrolled 40 headlines down on "All", tap "WallStreetBets" (25 rows): it lands near the
  bottom/at an arbitrary offset instead of the top. ResearchScreen already solved this with one
  state per section.
- suggested fix: one `rememberLazyListState()` per filter (as ResearchScreen does), or
  `LaunchedEffect(filter) { listState.scrollToItem(0) }`.
- confidence: high

### U-7 [L] Number formatting: "-$0.00"/"-0.00%" in red for values that round to zero, "+-0.00%" for -0.0, "$-1,234.56" for negative plain dollars, "1000.00K"
- where: util/Format.kt:64 (`usd`), 66 (`usdSigned`), 85-91 (`pct`/`pctSigned`/`changeSigned`),
  168-177 (`compact`), ui/Theme.kt:175 (`signColor`), ui/Explain.kt:117-118 (`money`).
- what's wrong (verified with the JDK here): `usdSigned(-0.001)` = "-$0.00";
  `pctSigned(-0.001)` = "-0.00%"; `pctSigned(-0.0)` = "+" + "-0.00%" = **"+-0.00%"**;
  `usd(-1234.56)` = "$-1,234.56" (DecimalFormat keeps the sign, "$" is prepended) and
  `usd(-0.001)` = "$-0.00"; Explain's `money(-1.84e7)` = "$-18.40M" (DET-5 fixed this shape only in
  `Fmt.price`/`formatMetric`); `compact(999_995.0)` = "1000.00K". `signColor(v)` is green for 0.0
  and red for -1e-15.
- failure scenario: float noise in `realized` (e.g. 0.1*3 vs 0.3 lots) shows "Profit already banked
  -$0.00" in red; a ledger missing its first deposit shows "Cash not invested $-523.10" and "Money
  you put in $-..."; a stock's page reads "Free cash flow is NEGATIVE at $-1.23B".
- suggested fix: one helper `snapZero(v) = if (abs(v) < 0.005) 0.0 else v` applied in
  `usdSigned`/`pctSigned`/`change*` and in `signColor` (optionally neutral colour for exact 0);
  make `usd` sign-outside like `price` (`(if (v < 0) "-$" else "$") + money.format(abs(v))`), and
  Explain.money likewise; in `compact` pick the unit after rounding.
- missing test: a FormatTest for -0.0, -0.001, -1234.56 and 999_995.
- confidence: high (the values are certain; how often Tj sees them depends on his ledger)

### U-8 [L] Advice tab: "Analyze"/"Make prompt file" are enabled for a watchlist-only user, and the preload lock resets on a tab switch
- where: ui/AdviceScreen.kt:48 (`var preloading by remember`), 84 and 124
  (`enabled = ... && state.rows.isNotEmpty()` - `rows` includes watch-only rows), 100-107.
- what's wrong / scenario: (a) with no holdings but a watchlist, "Add some holdings first." is
  hidden and both buttons send an empty portfolio to Claude (API call or prompt file). (b) tap
  "Make prompt file" (news preload, up to ~48 requests), switch tab and back: `preloading` is false
  again, the button is live, a second tap starts a second preload and a second share sheet.
- suggested fix: gate on `state.rows.any { !it.watchOnly }`; hold the preload flag in the VM
  (a StateFlow) instead of `remember`.
- confidence: high

---

## Code / UI quality (U-Q)

- **U-Q1 Progress bars shift every list 2dp on every automatic tick.** PortfolioScreen:141,
  WatchlistScreen:145, ResearchScreen:400, FeedScreen:195-197 use `if (loading)
  LinearProgressIndicator(height 2.dp)` with nothing in its place, so the list jumps down and back
  every 15 s in market hours. ReaderScreen:211-218 already does it right (`else Spacer(2.dp)`);
  do the same everywhere.
- **U-Q2 Settings pull contradicts its own comment and does disk work on the main thread.**
  SettingsScreen:116-128 says snapshot/crash-log remembers must not be re-run from casual events,
  then the pull does `infoTick++`, which re-runs `lastAutoBackup`, `snapshotCount` (dir listing),
  `CrashLog.latestSummary` (file read), `auditFees`, and four cache-stat DB queries in composition.
  Use a separate `rateTick` for the request meter.
- **U-Q3 Cash override seeded with `Double.toString`.** SettingsScreen:68 shows "1.0E7" for
  >= $10M and would round-trip oddly; use `Fmt.exact` like the other editors.
- **U-Q4 Locale mixing.** Format.kt:17-19 builds DecimalFormat with the device locale while
  pct/compact use `Locale.US`; on a non-US phone a row reads "$1.234,56 ... +1.23%". Pin
  `DecimalFormatSymbols(Locale.US)` (the app is US-market only).
- **U-Q5 Back from a searched stock skips the search.** MainActivity:490-503 closes the sheet and
  drops the query on open, so Back returns to the tab root, not the results (Robinhood/Yahoo return
  to results). SearchSheet's `query` is also plain `remember` while `searching` is saveable, so a
  restored sheet comes back empty.
- **U-Q6 Feed/Advice/Research "Updated Xm ago" labels don't tick.** `Fmt.relative` is only
  evaluated on recomposition; on a quiet screen "just now" can sit for minutes. Minor, but it is
  shown beside refresh controls.

## Checked and fine (not re-reported)
- PullGesture/Refreshable (Common.kt): grows only from post-scroll leftover with UserInput source;
  pre-scroll takes back the pull before the list moves; fling (SideEffect) never grows it; mid-list
  drags that reach the top grow only by the leftover; refresh finishing with a finger down hides
  (distance is always 0 then - it is reset on refresh start and cannot grow while refreshing);
  multi-pointer `fingerDown = any { pressed }` and Compose's synthetic cancel-up clear it; drag
  cancellation still dispatches pre-fling (drag-stop with zero velocity) and a detached child is
  covered by the backstop, which also zeroes `gesture.distance`; hide/show/refresh-start ordering on
  AndroidUiDispatcher makes the refresh's `animateToThreshold` win; the `settling` counter is
  decremented on cancellation. All 8 callers pass the right surface and default state.
- Nav stack: goToTab/popDetail/BackHandler order (reader > search > detail > Research sub-tab >
  tab history > Portfolio > double-back exit), SaveableStateProvider keys by depth, cap at 12,
  shareNav via goToTab, ReaderScreen's own BackHandler registered later so it wins, dialogs handle
  their own back. Yesterday's U-1..U-7 fixes hold (share queue, content:// only, BROWSABLE gone,
  onScreen check in launchPromptShare).
- Lazy keys: every keyed list is de-duplicated by its key expression (feed id, trending symbol,
  filings accession incl. GOOG/GOOGL via publishInsiders, search hits, advice ratings, research).
- Colours: gain/loss text uses greenText/redText everywhere read; verdict text colours per theme.

## Ideas — need Tj's approval, do NOT implement
- Show which surface is refreshing in the header ("Updating prices..." / "Updating headlines...")
  instead of a bare circle, the way Delta/Stock Events label their sync state.
- Swipe between Research sections (Trending/Best/ETFs/Day Trading) instead of switching app tabs
  when on Research, like Yahoo Finance's screener tabs.
- Back from a searched stock returns to the search results with the query intact (Robinhood).
- A small "last updated" relative time that ticks every minute on Portfolio/Watchlist headers.

## Summary

| Severity | Count | IDs |
|---|---|---|
| H | 1 | U-1 (already being fixed in the main session) |
| M | 2 | U-2, U-3 |
| L | 5 | U-4, U-5, U-6, U-7, U-8 |
| Quality | 6 | U-Q1..U-Q6 |
| Ideas | 4 | (approval needed) |

## END OF REPORT (complete)
