# Independent review: L + Q fixes (`git diff 5feb55dc..HEAD -- app/src/main`)

Reviewer: adversarial read-only pass, 2026-09-24. I read all 2,187 diff lines hunk by hunk and grepped
every changed function, field and constant for other callers. The only command I ran was
`python3 tools/checkinit.py`, which prints "ok - every property is declared above init (line 2660)".
I ran no gradle builds and no git writes.

Totals: **0 H, 1 M, 6 L.**

---

## Findings

### R2-1 [M] U-7's `signColor` snap disagrees with the sub-cent dollar figure beside it: a falling stock's readout is drawn green, or reads "-$0.02 / +0.00%"
- where: `ui/Theme.kt:176-177`; callers `ui/PriceChart.kt:700` (resting readout colour) and `ui/Widgets.kt:336-345` (detail price block). Formatters are in `util/Format.kt:72,104,129`.
  ```kotlin
  fun signColor(v: Double): Color =
      if (com.tj.portfolio.util.Fmt.snapZero(v) >= 0) greenText else redText      // eps 0.005, always
  ...
  val line = signColor(inside.change)                                              // PriceChart:700, a $ change
  Fmt.changeMoney(summary.last, summary.change) + "   " + Fmt.pctSigned(summary.changePct)   // coloured `line`
  ```
  `signColor` snaps at a fixed 0.005. `changeMoney` snaps at 0.0005 (0.00005 for stocks under $1), so it still prints sub-cent moves with their minus sign.
- failure 1 (chart readout, any sub-dollar holding): a $0.42 stock falls $0.003 over the window. The text is `"-$0.0030   -0.71%"`. `line = signColor(-0.003)` snaps to 0, so the text is drawn **green**. On a $0.42 stock, half a cent is about 1.2%, so every window move inside ±1.2% is coloured "up" whichever way it went. Before U-7 this was red.
- failure 2 (detail price block, stocks over about $10): a $500 stock at -$0.02 has `dayPct` of -0.004%. `first = changeMoney(...)` prints `"-$0.02"`, `second = pctSigned(-0.004)` now prints `"+0.00%"`, and `color = signColor(row.dayPct)` is green. The same cell shows a minus and a plus, in the "up" colour. Before U-7 it read "-$0.02 / -0.00%" in red. This happens on high-priced tickers at quiet moments, such as just after the open.
- fix (minimal): colour and sign should come from the most precise figure printed.
  - In PriceChart, use `signColor(inside.changePct)`. The percentage is snapped at the same 0.005 as `pctSigned`, which fixes failure 1.
  - In the two readouts, when the dollar figure is non-zero after its own snap, let it decide the colour and the sign of a percentage that rounds to zero. One way: a `Fmt.pctSignedFor(pct, change, price)` that prints "-0.00%" when `changeMoney` would print a minus.
  - Alternatively, give `signColor` an `eps` parameter and pass the dollar formatter's eps (`0.0005` / `0.00005`) at these call sites.

### R2-2 [L] S-11 still leaves Claude-added Trending/Best rows off screen, and out of the next prompt
- where: `ui/PortfolioViewModel.kt:7680-7686`; `net/ResearchBridge.kt:468-469`; readers `ui/ResearchScreen.kt:293,518` and `PortfolioViewModel.kt:7555-7558`.
  ```kotlin
  fun pageEnd(section: String) = _researchShown.value[section] ?: ResearchSet.PAGE
  ... ResearchBridge.merge(cur.trending, parsed.trending, writtenAt, insertAt = pageEnd(...))
  val cut = insertAt.coerceIn(0, merged.size)
  return merged.take(cut) + added + merged.drop(cut)
  ```
  The screen draws `rows.take(visibleCount)`, with `visibleCount = shown[section]`. `visibleResearch()` cuts every section to the same count.
- failure: `shown = 10`, and Claude adds 2 Trending picks. They land at indices 10 and 11, which are the first two hidden rows. The toast says "2 added", but neither row is drawn until "Load more" is tapped. The next "Explain with Claude" or prompt file is built from `visibleResearch()` (the first 10 rows), so it still never sends them back. The KDoc on `merge.insertAt` claims both problems are fixed. Only the tap count improved, from 4-5 taps to 1.
- fix: after the merge, grow the page by what was inserted, for example `_researchShown.value += section to (pageEnd(section) + addedIn(section))` for TRENDING and BEST. Then the rows are both visible and included in the next prompt.

### R2-3 [L] D-9's `planPrice` is not used by the recommendation log, so the success-rate evaluation reads a Claude plan's direction backwards from what the card now tells Tj
- where: `ui/PortfolioViewModel.kt:7006` (`priceAtRecommendation = r.price`) → `net/DayTradingEval.kt:211-213,248` (`entryRises`).
- failure: an evening import (22:00) takes Claude's pullback plan: entry 100, stop 97, target 108, with `planPrice = app.price = 105` (DayTradingBridge:541). The stock opens at 98. On the first live tick, `claudePlanStands` holds the plan, the row is loggable (streak 0, in `liveNow`), and it is logged with `priceAtRecommendation = 98`.
  - The beginner card (D-9: `planEntryRises(…,100,105) == false`, price < entry) now says "It already reached the buy price ($100.00) ... a buy order sitting there would have gone through".
  - The evaluator computes `entryRises(setup, 100, 98) == true` and treats the plan as a buy-stop that waits for `high >= 100`. That can give NO_ENTRY, or a fill later and higher, for a trade the card told Tj had filled.
  - App plans are unaffected, because a loggable app plan has `planPrice == price` on the same tick.
- fix: log the plan's own reference price for direction: `priceAtRecommendation = r.planPrice.takeIf { r.planByClaude && it > 0.0 } ?: r.price`. Alternatively, add a column for it.

### R2-4 [L] N-8 counts a cancelled technicals fetch as a failure, so leaving the tab mid-sweep backs symbols off and can bury them in the one-time sort
- where: `ui/PortfolioViewModel.kt:8057-8064`.
  ```kotlin
  else runCatching { DayTradingTechnicals.fetch(row.symbol) }.getOrNull().also { t ->
      if (t == null || t.isEmpty) dayTradingTechRetry.failure(row.symbol) ...
  ```
  `runCatching` also swallows the `CancellationException`, or the IOException that Http rethrows after the cancel-disconnect, raised when `stopDayTradingLive()` or ON_STOP cancels the sweep.
- failure: after a rebuild, the first call sweeps the whole section (about 50 rows, 5 at a time). Tj switches to Trending mid-sweep. The up to 5 in-flight symbols each record `failure()`. He comes back within 30 s, and the resumed first sweep skips them (`blocked` → `tech = null` → row unchanged, no plan). `sortDayTradingForActionability`, which runs once per rebuild, then ranks them as non-actionable, possibly below "Load more", until the next rebuild. Repeated flicking escalates the backoff to 5 minutes.
- fix: don't record a failure when the coroutine is no longer active. For example, rethrow on cancellation: `catch (e: CancellationException) { throw e }`. Or check `coroutineContext.isActive` before `failure()`.

### R2-5 [L] A-7: when a screenshot extraction lands on a waiting review, a no-row result is dropped silently, and the open review's tick choices are reset
- where: `ui/PortfolioViewModel.kt:4337-4341` (`land`), `:1343-1352` (`mergeIntoPendingReview`); `ui/ActivityScreen.kt:~330` (`val checks = remember(r) {...}`).
  ```kotlin
  val merged = mergeIntoPendingReview(_importResult.value, res)
  if (merged !== res && res.error != null) toast(res.error)
  setImportResult(merged)
  ```
- failure (a), silent result: a shared answer's rows are waiting and a screenshot extraction lands. Suppose Claude returned 0 usable rows with notes, for example "3 rows left out: no share count" from `appendUnusable`, or "no transactions visible", and `error == null`. `mergeIntoPendingReview` returns `waiting` unchanged, and no toast fires because `error` is null. The spinner stops and the paid call's outcome is never shown. With no review waiting, the same result opens the dialog with its notes.
- failure (b), selections reset: if the result has rows, `setImportResult(merged)` publishes a new `ExtractResult`. `ImportReviewDialog`'s `remember(r)` state then resets `checks` to the "new rows only" defaults. Any rows Tj had unticked in the open review are ticked again, and one tap on Import commits rows he had deselected. The share path already had (b); A-7 adds it for the screenshot path.
- fix: in `land()`:
  - when `merged !== res` and the incoming result has no rows, toast `res.error ?: res.notes.ifBlank { "No transactions found in those screenshots" }`;
  - when rows were added, toast "N added to the review already waiting", as the share path does;
  - for (b), key the dialog's selection state on transaction identity, or carry the existing `checks` forward for the rows that were already there.

### R2-6 [L] D-10: a row restored from a pre-D-10 cache takes its already-boosted score as the "build-time" base, so the old technicals bonus and reason line stay in until the next rebuild
- where: `ui/PortfolioViewModel.kt:1463-1467`.
  ```kotlin
  val recorded = withLevels.dtBaseLikelihood > 0
  val baseLikelihood = if (recorded) withLevels.dtBaseLikelihood else withLevels.dtLikelihood
  val baseReasons = if (recorded) withLevels.reasons.take(withLevels.dtBaseReasonCount) else withLevels.reasons
  ```
- failure: the old build persisted `dtLikelihood`, `dtConfidence` and `reasons` after its one-time bonus had been added: +8 or +12 likelihood, +20 or +40 confidence, plus "Trading above its session VWAP ($X) - buyers in control today". On the first tick after the update, that row's boosted values become its permanent base. Every tick then adds the bonus on top again (double count), and while the stock stays above VWAP a second VWAP line appears. After it falls below VWAP, the frozen line still claims "buyers in control". The row is persisted like this (`dtBase*` now > 0) until the next rebuild, which may be up to 30 minutes later, or longer if the rebuild fails offline. This happens once per install and is not a regression (the old code double-counted on every cold start), but it contradicts the KDoc's "nothing compounds".
- fix: `if (!recorded) return withLevels`. That keeps a legacy row exactly as it was until the rebuild gives it a real base.

### R2-7 [L] A-5's failure toast arrives, but the add-transaction dialogs close anyway, so the typed entry is lost
- where: `ui/PortfolioViewModel.kt:4021-4025`; callers `ui/ActivityScreen.kt:253` (`onSave = { t -> vm.addTxnRecord(t); showAdd = false }`) and `ui/DetailScreen.kt:994` (`...; addingTxn = false`).
- failure: when `insertOrThrow` throws (disk full, a constraint), the toast says "Couldn't save that transaction", but the caller has already dismissed the editor. Tj has to re-type the whole entry. This is rare, since it needs a real write failure.
- fix: return a `Boolean` from `addTxnRecord`, and close the dialog only on `true`.

---

## Areas checked with no defect found (one line each)

- **ResearchRow new fields.** `planPrice` is set, cleared, copied and persisted consistently in `mergeDayTradingTech`, `carryWhy`, `dropUnusableClaudeLevels`, `evictStaleDayTradingPlan`, `DayTradingBridge.merge` and `toJson`/`fromJson`. The only omission is the log (R2-3). `dtBase*` survive `DayTradingBridge.merge`/`carryWhy`, and `withTechnicals` only appends, so `take(dtBaseReasonCount)` is sound apart from R2-6.
- **`scoreDayTradingRow` every tick.** It is idempotent for rows with a recorded base, and its only caller is `enrichDayTradingVisible`. It is not applied to Claude-added rows (`dtLikelihood <= 0`).
- **Http.**
  - `HttpResult.tls`: only Social reads it.
  - postJson: 429/503/529 arm the cooldown and 403 no longer does; the error body is now read, and all callers go through `apiError`.
  - `noteRequest`: now counted after the in-permit re-check in both `get` and `postJson`.
  - Non-local `return@withContext` inside `withPermit` is safe (the lambda is inline and releases in `finally`).
  - `detachDiskCache` is identity-checked under `@Synchronized`.
  - The cookie policy is a valid SAM lambda. `fc`, `query1`/`query2` and the consent hosts all end in `.yahoo.com`.
  - Removing `conditionalKey` from chart URLs is harmless (no validators were ever stored).
  - Note only: `Claude.models()` uses `Http.get`, where a 403 still arms the api.anthropic.com cooldown, but that endpoint returns 403 only in unusual key setups.
- **YahooAuth N-5.** Compare-and-clear, and the mint's write share the object monitor. Both callers pass the crumb actually sent.
- **Db.**
  - `insertOrThrow` callers: restore (caught, rolled back, "Restore failed"), import commit (runCatching, rollback, keeps the extraction), `addTxnRecord` (see R2-7). `addTxn` has no callers.
  - `ensureColumn` in `onOpen` runs after every index it could affect, and a read-only open is covered by runCatching.
  - Finite-only restore covers txns and the day-trading log. `addWatchWithAnchor` and the overrides were already finite-guarded.
- **Mutex/sequence.**
  - `autosaveWrite`: both writers of `AUTOSAVE_FILE` hold it. The non-local `return@launch` inside the inline `withLock` unlocks in `finally`, and there is no re-entrant acquire.
  - `researchPersistSeq`/`researchPersistLock`: out-of-order lock acquisition is handled by the `seq < researchWrittenSeq` skip, which runs before serialisation. The writer is Main-thread.
- **Format.**
  - The only parse-back sites (`TxnEditor.kt:41`, `SettingsScreen.kt:683`) strip every `$` and `,`, so "-$" and `Fmt.exact` parse correctly.
  - No other `NumberFormat` or locale-dependent parsing exists.
  - `compact` picks its unit correctly at 999,995 → "1.00M".
  - Aside from R2-1, the new snap is consistent between `usd`, `usdSigned` and `pct`.
- **Compose.**
  - `SaveableStateProvider("watch:$subTab")` is used with a single key per composition, and effects still dispose on switch.
  - The four `rememberLazyListState()`s are created unconditionally, in a fixed order.
  - `advicePreparing`: both increments are balanced by `finally`. Launches start on Main.immediate from a click, so the body always runs.
  - `minTapTarget` (`defaultMinSize`) with `weight` only raises the height.
- **checkinit.** Every new property (`_advicePreparing`, `autosaveWrite`, `sparksTrimmed`, `httpDiskCache`, `coreDataAt`, `researchPersist*`, `dayTradingTechRetry`) is above `init` (line 2660), and the script passes.
- **Other fixes.**
  - N-7 `baselineWindowHasSession`: local-calendar weekday walk, DST-safe, and `chartRetry` "|baseline" key.
  - N-9 body-time stamp and the same-spark early return.
  - N-11 insider stamping order.
  - N-12 404/answered-empty early return.
  - S-3 `coreDataAt`.
  - S-9 `coreFill`.
  - S-12 priced-line average.
  - S-Q1 `roundTowardMid`.
  - L-5 `sparksTrimmed` (trims at level ≥ 60 only happen in the background).
  - L-7 search spinner.
  - A-6 `snapshotFileName`.
  - U-4 busy toast (only forced callers).
  - U-Q2 `rateTick`, U-Q3 `Fmt.exact` seed.
  - C-Q3 top-level `intradayChartIsFinal` (the overload resolves correctly).

## END OF REPORT (complete)
