# Independent review: diff 370bfa4..5feb55dc (app/src/main)

Scope: `git diff 370bfa4..5feb55dc -- app/src/main` (27 files, about 2,490 diff lines), read hunk by hunk. Every finding below was checked against the code at HEAD (a620881c). None of the flagged lines changed after 5feb55dc, so the line numbers are HEAD's. This was a read-only review: no gradle, no edits.

`python3 tools/checkinit.py` passes: "every property is declared above init (line 2660)". The new VM properties are all above `init` (quoteJob 1883, quoteGen 1890, pendingPriceFill 2027, dayTradingRebuildGen 2511, dayTradingLiveAt 2514, chartDiskInFlight 2635). The 5feb55dc snapshot has no annotated (`@Volatile`) property below `init` either, which the script's regex would not catch.

Totals: H 0, M 3, L 6.

---

## R1-1 [M] D-1 is only partly fixed: a row whose intraday fetch failed still counts as "live" and gets logged

- Where: `ui/PortfolioViewModel.kt:8137-8140`
  ```kotlin
  captureDayTradingRecommendations(
      finalRows,
      fetched.filterValues { it != null && !it.isEmpty && it.sessionLive }.keys
  )
  ```
  Also `net/DayTradingTechnicals.kt:238-240` (`isEmpty` is true only when atr14, vwap, the OR high/low and prevHigh are all 0) and `:274` (the daily leg is memoised per symbol, per day and per close state).
- The problem: `!isEmpty` does not mean "re-planned from live bars". The daily leg is almost always a memo hit, so when the intraday leg fails, `tech` still has `atr14`/`prevHigh` > 0 and is not empty. `sessionLive` comes from the clock (`MarketClock.phase(now) == OPEN`). So the symbol is in `liveNow`.
  - `mergeDayTradingTech` then plans with `price = livePrice ?: row.price`, and `livePrice` is null here.
  - Via `effectiveTechnicals`' `fetchFailed` → `keepIntraday` path, it uses the pre-market values carried on the row.
  - This is the sub-case D-1 names itself: "It also happens for any row whose intraday fetch fails on the busy first OPEN tick."
- Failure scenario:
  - 04:00-09:29: pre-market sweeps stamp `sessionDay = today` and cache the daily leg under `sym|today|0`.
  - 09:30:10, first OPEN tick: XYZ's intraday request fails on both hosts (a 429 cooldown or a timeout). `tech` = daily-only, not empty, `sessionLive = true`, so XYZ is in `liveNow`.
  - The plan is rebuilt from the carried pre-market high at the 09:29 pre-market price. `loggableDayTradingRows` passes: `sessionDay == today`, streak 0, levels > 0.
  - It is logged with `recordedAt = 09:30:10` and `priceAtRecommendation` = the pre-market price. `INSERT OR IGNORE` then locks the real live plan out for the day.
- Fix: require today's intraday bars, not just a non-empty reading:
  `fetched.filterValues { it != null && it.sessionLive && it.sessionDay.isNotBlank() && it.lastPrice > 0.0 }.keys`.

## R1-2 [M] D-5 brings back the level flicker `effectiveTechnicals` exists to prevent: the OR high/low vanish on every failed-intraday tick

- Where:
  - `net/ResearchScore.kt:750-751`:
    ```kotlin
    val orHigh = if (tech.openingRangeComplete) tech.openingRangeHigh else 0.0
    val orLow = if (tech.openingRangeComplete) tech.openingRangeLow else 0.0
    ```
    These feed `overhead` (:763) and `below` (:782, :785): the breakout target, the pullback support and the structural stop.
  - `ui/PortfolioViewModel.kt:1541`: `effectiveTechnicals` returns `tech.copy(...)`. It carries `openingRangeHigh`/`Low` from the row on a failed fetch (`keepIntraday`), but NOT `openingRangeComplete`, which stays at the failed reading's default of `false`. The row does not store that flag either.
- Failure scenario:
  - 11:00. Tick N: the intraday leg succeeds. `openingRangeComplete = true`, the OR low (say $48.10) is the nearest support, so the stop sits under $48.10.
  - Tick N+1 (30 s later): the intraday leg fails, so it is daily-only. `effective.openingRangeHigh`/`Low` are the carried non-zero values, but `openingRangeComplete = false`, so `orHigh = orLow = 0`.
  - `planInternal` picks the next support down (VWAP carried, or the prior close). Stop, risk and often target move, and `plan != null` publishes the change at once, with no debounce on "good news".
  - Tick N+2 succeeds and the levels snap back. During a Yahoo cooldown (minutes) every row's plan changes and changes back.
  - Combined with R1-1, the shifted plan can also be the one that gets logged.
  - This is the "three money levels flickering between two entirely different trade plans, for no reason but a dropped request" that the `effectiveTechnicals` header describes.
- Fix: carry the flag with the values:
  ```kotlin
  openingRangeComplete = tech.openingRangeComplete ||
      (keepIntraday && !tech.intradayFetched && row.openingRangeHigh > 0 && <ET now >= 10:00>)
  ```
  in `effectiveTechnicals`, or persist `openingRangeComplete` on `ResearchRow` and carry it like the other intraday fields.

## R1-3 [M] S-5 strips Claude's own earnings phrase when the app has none, which drops the earnings-today warning it meant to protect

- Where: `net/Research.kt:799-816`
  ```kotlin
  internal fun claudeCatalystPart(s: String): String {
      val app = appEarningsPart(s) ?: return s.trim()
      return s.removePrefix(app).trim().removePrefix("-").trim()
  }
  internal fun combineCatalyst(app: String, claude: String): String {
      val own = claudeCatalystPart(claude)
      if (own.isBlank()) return app
      val earnings = appEarningsPart(app)
      return if (earnings != null) "$earnings - $own" else own
  }
  ```
  Called from `DayTradingBridge.kt:500` and `ResearchBridge.kt:451`.
- The problem: the stripped prefix is assumed to be the app's own stale phrase and is dropped. When the app row has NO earnings phrase (`catalystFor` returns "" whenever the screener lacks `earningsAt`, common on the small caps Day Trading screens), nothing puts it back. Claude's own earnings information is deleted.
- Failure scenarios:
  - Day Trading row with app `catalyst = ""`. Claude's `risk` is "Earnings today after the close could gap it". The result is "after the close could gap it".
    - `mergeDayTradingTech`'s `earningsToday = row.catalyst.startsWith(CATALYST_EARNINGS_TODAY)` is now false, so the "cancel unfilled buys before the close" plan note is gone.
    - Before this diff the line was Claude's full text (`c.catalyst.ifBlank { app.catalyst }`) and the warning fired.
  - Research row with app `catalyst = ""`. Claude's catalyst "Earnings tomorrow" (target/risk blank) gives `own = ""` and returns `app = ""`, so Claude's catalyst disappears entirely.
    - With target/risk, "Earnings tomorrow - $210 - guidance" becomes "$210 - guidance".
- Fix: when `appEarningsPart(app) == null`, keep Claude's line whole on the MERGE path (`return claude.trim()`). Keep stripping only on the carry path (`carryWhy`), where the prefix really is a frozen app phrase. For example, add a `stripLead: Boolean` parameter that the bridges pass as false.

## R1-4 [L] S-4 makes a just-imported, earlier-dated Research answer read as stale, so it is rebuilt the moment it is shown

- Where: `ui/PortfolioViewModel.kt:7698` `explained = writtenAt` (answer's `asOf`, noon ET) against `:7239` `val freshAt = maxOf(s.generated, s.explained, s.dtExplained)` in `researchStale()`.
- The problem: the 09-23 U-1/D-1 rule ("A CLAUDE ANSWER COUNTS AS A REFRESH ... Tj then watched the answer he had just imported be replaced") works by raising `explained` to the import time. For an answer dated an earlier day, `explained` is now yesterday noon.
- Failure scenario:
  - 09-24 10:00: Tj shares yesterday's answer (`asOf 2026-09-23`). `applyResearchAnswer` merges it and `jumpToResearch` navigates.
  - The Research screen's non-forced `loadResearch()` sees `freshAt` = max(list build, 09-23 12:00), which is more than 30 minutes old, so it is stale.
  - A full ~18-request rebuild starts at once. Rows are re-screened and reordered, and Claude-added rows are re-appended after the carried rows.
- Fix: keep aging the paragraphs from `asOf` (whyAt), but record the import time separately for freshness (for example an `appliedAt` or `importedAt` field used in `researchStale`), or use `System.currentTimeMillis()` for `explained` and keep the old date only in the note.

## R1-5 [L] C-1 bridging pairs the stock series of the OLD range with the SPY series of the NEW range

- Where:
  - `ui/DetailScreen.kt:363-364`: `compareKey = vm.chartKey(BENCHMARK_SYMBOL, chartRange)`; `compareSeries = ... chartMap[compareKey]`.
  - `:572` `val (drawnChart, drawnRange) = bridgedChart(...)`.
  - Inline: `chart = drawnChart, chartRange = drawnRange` with `compare = compareSeries` (:883-884).
  - Full screen: `series = drawnChart, range = drawnRange` with `compare = ... compareSeries` and `compareLivePrice = liveEdgePrice(benchmarkQuote, drawnRange, compareSeries)` (:970-972).
- Failure scenario:
  - Compare is on, and SPY's 5D series is cached from another stock.
  - Tj pinches out on this stock's 1D chart, and `rangeForLookback` selects 5D, which is not fetched yet for this stock. The bridge draws the stock's D1 series (`range = D1`) with SPY's D5 series as `compare`.
  - In `comparePercents`, the D1/D1 same-session check is skipped because the compare range is D5. `baseT` is null (D1 anchor), so `base = compare.from` = SPY's first close five days ago.
  - The overlay and the out/under-performance spread show "SPY since 5 days ago" against "stock since yesterday's close" until the stock's 5D series lands.
  - Before C-1 this state showed the placeholder, not a wrong comparison.
- Fix: look up the benchmark for the range actually drawn, e.g. `chartMap[vm.chartKey(BENCHMARK_SYMBOL, drawnRange)]` whenever the chart is bridged. Pass null if absent.

## R1-6 [L] S-8's high-yield maturity split does not do what its comment says: "0-5 Year" is not a recognised band

- Where: `net/EtfExposure.kt:194-196`:
  ```kotlin
  // High yield keeps its band when it states one: a 0-5 year junk fund is not HYG.
  n.contains(" high yield ") || n.contains(" junk ") ->
      maturityKey(n, "Bonds - high yield") ?: group("Bonds - high yield")
  ```
  `maturityKey` (:236-248) knows " 0 3 month ", " 1 3 month ", " ultra short ", " 0 1 year ", " 1 3 year ", " short term ", " short duration ", " 3 7 year ", " 5 10 year ", " intermediate ", " 7 10 year ", " 10 20 year ", " 20 year ", " 25 year ", " long term ", " extended duration ". There is no " 0 5 year ".
- Failure scenario:
  - "iShares 0-5 Year High Yield Corporate Bond ETF" (SHYG) normalises to " ... 0 5 year high yield corporate bond etf ". No band matches, so it falls back to the key "Bonds - high yield".
  - That is the same key as "iShares iBoxx $ High Yield Corporate Bond ETF" (HYG), so `dedupe` removes one of them under "same exposure". This is exactly the example the comment says is handled. The muni fallback has the same gap for "0-5 year" munis.
- Fix: add `n.contains(" 0 5 year ") || n.contains(" 1 5 year ")` as a short band ("0-5 year"). Also consider ungrouping on a stated but unknown year band instead of falling back to the unbanded key.

## R1-7 [L] D-2's "plan's own time" is `whyAt`, which a levels-only Claude plan does not update

- Where:
  - `ui/PortfolioViewModel.kt:1155-1156`:
    ```kotlin
    val claudePlanStands = row.planByClaude && !claudeLevelsUnusable && (!sessionChanged ||
        (now > 0L && row.whyAt > 0L && planStillForSession(row.whyAt, now)))
    ```
  - Against `net/DayTradingBridge.kt:499` `whyAt = if (c.why.isNotBlank()) now else app.whyAt`.
- Failure scenario:
  - Monday 18:00: an evening import of a pick with entry/stop/target and `risk` but blank `why`. `parse` admits it: `if (why.isBlank() && risk.isBlank() && !sane) continue`.
  - `whyAt` stays at `app.whyAt`: 0 for an app row, or an older paragraph time. Post-close sweeps stamp `sessionDay = Monday`.
  - At Tuesday 04:00 `sessionChanged` is true, and `row.whyAt > 0L` fails (or `planStillForSession(oldWhyAt, now)` is false). Claude's plan for Tuesday is replaced by the app's, which is the D-2 bug for this reply shape.
- Fix: stamp a dedicated plan time in `DayTradingBridge.merge` when `takeLevels` (the audit's suggested `planAt`) and test that instead of `whyAt`.

## R1-8 [L] D-14's `dayTradingLiveAt` is bumped by ticks that did not refresh the list

- Where: `ui/PortfolioViewModel.kt:8130` `if (changed) dayTradingLiveAt = System.currentTimeMillis()`. The value goes into `dataAgeMinutes` via `maxOf(set.generated, liveAt)` (`DayTradingBridge.bundleJson`).
- The problem: `changed` is true for (a) a single-symbol run (`only != null`, a detail screen open), which fetched ONE row, and (b) a daily-only reading (intraday failed, see R1-1), which refreshed no price.
- Failure scenario: the Day Trading tab is left at 10:00, so its loop stops. At 14:00 Tj opens one pick's detail, and its single-symbol loop ticks. At 14:05 he writes the Day Trading prompt. `dataAgeMinutes` is about 5, yet 9 of the 10 rows' prices and levels are from 10:00. That is the D-14 error in the other direction.
- Fix: set it only when `only == null` and at least one row got `tech.sessionDay.isNotBlank()`. Better, stamp per row and send the oldest shown row's age.

## R1-9 [L] N-2: an "answered, but empty" batch still asks the second host the same question every tick

- Where: `net/MarketData.kt:269-272`:
  ```kotlin
  val parsed = runCatching { parseBatch(r.body) }.getOrDefault(emptyList())
  if (parsed.isNotEmpty()) return Batch.OK to parsed
  if (batchAnswered(r.body)) answeredEmpty = true
  ```
  The loop continues to query2.
- Failure scenario: a detail screen for a ticker Yahoo does not carry (SPX from Trending). `refresh()` batches `onScreen = [SPX]` every tick. query1 answers `{"result":[],"error":null}`, and query2 is sent and answers the same. That is 2 batch requests per 15 s tick for as long as the screen is open. The per-symbol fallback is backed off; the batch is not.
- The same "an answer needs no second host" fix was made later for `DayTradingEval.fetchDaySeries` (N-12) but not here.
- Fix: `if (batchAnswered(r.body)) return Batch.INCONCLUSIVE to emptyList()`.

---

## Areas checked with no defect found

- **Http L-1/N-1:** `onCancelling`, `invokeOnCompletion(onCancelling = true)`, the `ensureActive` window, disposal in `finally`, and the rethrow of a cancelled exception from the catch are correct. `blockedForTests` is inert on a device.
- **FundamentalsFeed N-3:** the retry happens only after a 401 (`sawUnauthorized`). Correct.
- **Insider N-4:** every sec.gov request goes through `secGet`. The mutex and delay pacing is cancellation-safe.
- **Db A-3:** `duplicateTolerance` and both duplicate finders are consistent. The prompt digest already includes amounts.
- **Ledger/restore A-2:** the ranges predicate, the Merge/Replace handling, device-local exclusion from backups and the empty-string parse are all fine. The txns table is AUTOINCREMENT, so the "ids can be reused" comment is harmless.
- **SharedAnswer A-1:** `BACKUP` kind, `rejection()` and the `importClaudeFile` guard are fine. `epochDate` handles ms and s.
- **Storage A-4:** the LIKE narrowing plus the `matchesOwnName` regex, and the newest own `_ID`, are fine.
- **MarketClock D-3/D-7:** `sessionFor`, `planCloseMinute`, `answerIsCurrent` rule 3 and `planStillForSession` rule 3 are correct over weekends, holidays and half days.
- **DayTradingEval D-11/D-12:** `beforeFlatTime` is right. An empty `tradable` is safe in `evaluate`. The null/empty split in the caller is right.
- **DayTradingTechnicals D-6** (three close states) and **D-5** (`openingBarComplete` on today's regular bars only) are correct in themselves. See R1-2 for the downstream gap.
- **D-4** (rebuild generation plus loop restart) and **D-8** (`dtExplained > generated`) are consistent with rebuild and carry.
- **ChartModels C-4** (finer-rung margin) and **ChartFeed C-2** (5D = 4 days) are correct.
- **InsiderUi C-10** is correct. **DetailScreen C-11** disk gate: no deadlock, and the gate is released in `finally`.
- **PriceChart C-3/C-5/C-6:** no crash paths. `cs`/`pts` are non-empty by the `isEmpty` guards, and the anchor is never before SPY's first candle.
- **DetailScreen C-8/C-12:** `marketState` "AFTER" covers POST and CLOSED, and `endMs`/`quoteTime` are both ms. **S-2:** `LaunchedEffect` keyed on the data re-enters safely through `_recLoading` and `settledForToday`.
- **Recommend S-1:** analyst weight matches `ResearchScore.holding`'s voteless branch. `ratingsDated` consumers (`settledForToday`, `freshnessNote`) are consistent.
- **evictStaleWhy/carryWhy S-6/S-7:** consistent with `planStillForSession`, so evening and weekend plans survive. The `etf == null` marker for Claude-added funds is sound.
- **L-2** (`quoteJob`/`quoteGen` re-entry) and **L-3** (`pendingPriceFill` flush on foreground): no race found on Main.
- **U-1/U-3:** per-source spinner, `syncManualIndicator` on every busy clear, `feedListVisible`. Correct.
- **Launch-crash risk:** none found. The new object `val`s (`APP_EARNINGS`, `MUNI_STATES`) are not read during object init. `parseRepairRanges` tolerates null, "" and junk.
- **Persistence round trips:** no new persisted `ResearchRow` fields in this diff. `REPLAY_REPAIR_RANGES` is a settings string with a symmetric format/parse.

## END OF REPORT (complete)
