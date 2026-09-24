# 2026-09-24 full-test audit — SCREENS, NAVIGATION AND UX (U-)

Status: IN PROGRESS (partial findings below; more being verified)

Scope read: MainActivity.kt, ShareImportActivity.kt, AndroidManifest.xml, ui/Common.kt (Refreshable +
PullGesture), PortfolioScreen, WatchlistScreen, ResearchScreen (non-DT), FeedScreen, ReaderScreen,
AdviceScreen, SettingsScreen, StockRow, RowActions, SwipeTabs, SearchSheet, RecommendationDialog,
Dialogs, PromptShareUi, Theme, Widgets, util/Format.kt, plus the VM code they call
(refresh/refreshFeed/syncManualIndicator/spinnerShouldShow/loadResearch/loadEtfs/explain*/
setNewsVisible/autoJob). No code changed, no gradle, no network.

---

### U-1 [H] The pull spinner on a price screen keeps spinning for up to 15 minutes after unrelated Research work ends
- where: ui/PortfolioViewModel.kt:344-359 (`spinnerShouldShow`), 6113-6126 (`syncManualIndicator`),
  7259 and 7421 (`explainResearch`/`explainDayTrading` finally: `_researchBusy.value = ""`, no sync),
  7072 (`enrichVisible` finally, no sync), 6845/6982 (`loadEtfs`/`loadResearch` finally:
  `if (force) syncManualIndicator()` only), 3416-3421 (autoJob re-derives only once per tick),
  net/MarketClock.kt:183 (closed-market tick = 900 s).
- what's wrong: `refreshSource` names ONE surface, but the "should it spin" rule is surface-blind:
  ```kotlin
  ): Boolean = userAsked && (quotesLoading || feedLoading || researchLoading)
  ```
  So a pull on Portfolio/Watchlist/Detail/Activity/Advice/Settings (`refreshSource = PULL_PRICES`)
  keeps `pulling(PULL_PRICES)` true after its own quote pass ends whenever the feed or ANY research
  job (`BUSY_EXPLAINING`, `BUSY_DETAIL`, an automatic non-forced build/ETF build) is still running.
  Worse, when that research job ends, its `finally` clears `_researchBusy` WITHOUT calling
  `syncManualIndicator()`; the only thing left that re-derives the flag is the autoJob tick
  (15 s open, 60 s extended, **900 s closed**, never if auto-refresh = 0). While `refreshing` is
  true `PullGesture` refuses to grow, so pulling again cannot clear it either.
- failure scenario: evening, market closed. Tj taps "Explain with Claude" on Research (~30-60 s,
  BUSY_EXPLAINING), goes to the Portfolio tab and pulls down. Prices land in ~1 s ("Prices updated
  just now") but the circle keeps spinning next to the total: first because Claude is still
  answering, then - after Claude finishes - for up to another 15 minutes, until the next closed-
  market tick. Same with a pull on a stock opened from Research while the analyst-data pass
  (BUSY_DETAIL) runs, or pull Feed -> switch to Portfolio -> pull (spins until the feed pass ends,
  and the Feed tab's own spinner is stolen mid-pass). This is exactly the symptom Tj has reported
  three times (09-23c screenshot: "Prices updated just now" + circle that won't go away).
- suggested fix (minimal): (1) derive per surface:
  `want = manualRefresh && when (refreshSource) { PULL_PRICES -> quotesLoading; PULL_FEED -> feedLoading; PULL_RESEARCH -> researchLoading; else -> false }`
  (give `spinnerShouldShow` a `source` param); (2) call `syncManualIndicator()` in every place that
  sets `_researchBusy.value = ""` (or route all writes through one setter that syncs).
- missing test: SpinnerTest has no source dimension. Add `spinnerShouldShow(source = PULL_PRICES,
  quotes = false, feed = false, research = true) == false`, and a VM test: set BUSY_EXPLAINING,
  `refresh(manual = true)`, let the pass finish, assert `ui.pulling(PULL_PRICES) == false`; then
  clear the explain and assert it stays false without an autoJob tick.
- confidence: high

## END OF PARTIAL (more findings pending)
