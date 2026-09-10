# Round 66 audit - stock detail screen and its tabs

Scope: DetailScreen.kt, DetailTabs.kt, MetricUi.kt, TxnEditor.kt, ActivityScreen.kt,
InsiderUi.kt, FullScreenChart.kt, with PortfolioViewModel's per-symbol loaders, Ledger.kt,
Format.kt and the data models read as far as needed to verify a claim. PriceChart.kt's gesture
machine was audited separately and nothing gestural is reported here. Ranked most severe first.

---

ID: DET-1
SEVERITY: MEDIUM
WHERE: app/src/main/java/com/tj/portfolio/ui/DetailTabs.kt:205, :209, :211-226
BUG: The Price target card prints an unreported `targetLow` / `targetHigh` as "$0.00" and then
feeds that zero into the high-minus-low spread sentence, fabricating a claim that the analysts
"genuinely disagree" - breaking the file's own rule 1 ("a figure the provider did not report
prints 'not reported', never a zero", DetailTabs.kt:41).
FAILURE: `Consensus.hasTarget` is `targetMean > 0.0` alone (Fundamentals.kt:295), and both
parsers default the bounds to zero when the provider omits them:
`targetLow = num(fin, "targetLowPrice") ?: 0.0` (FundamentalsFeed.kt:419) and
`targetLow = o.optDouble("lowPriceTarget", 0.0)` (FundamentalsFeed.kt:605) - `num` returns null
for an absent key (FundamentalsFeed.kt:236). `Fundamentals.merge` takes the consensus whole
(`base.consensus ?: fill.consensus`, Fundamentals.kt:426), so a Nasdaq answer carrying only
`priceTarget` cannot be topped up from Yahoo. With targetMean = 45.00, targetHigh = 60.00,
targetLow = 0.0 the card renders "Lowest target $0.00" and then, because `spread = 60.00 - 0.0
= 60.00 > 0`, prints "The gap between the highest and lowest target is $60.00 - 133% of the
average. A wide gap means the professionals genuinely disagree about this company." No analyst
targets that stock at zero and there is no such disagreement; the sentence is invented from a
missing field. When both bounds are missing the card simply shows two "$0.00" rows.
FIX: Guard the two rows the way "Median target" already is at :207 - render "Lowest target" only
when `consensus.targetLow > 0` and "Highest target" only when `consensus.targetHigh > 0` - and
change the spread condition at :212 to `if (consensus.targetLow > 0 && consensus.targetHigh >
consensus.targetLow && consensus.targetMean > 0)`.
CONFIDENCE: certain

---

ID: DET-2
SEVERITY: MEDIUM
WHERE: app/src/main/java/com/tj/portfolio/ui/DetailScreen.kt:1101-1109 (inside the `!row.watchOnly` branch opened at :1049)
BUG: Realized P/L is rendered only inside the "you still hold shares" branch, so the moment a
position is fully closed the symbol's realized profit disappears from its own detail screen -
even though the ledger still holds it and the sells are listed a few rows further down.
FAILURE: Buy 100 AAPL at $180, sell all 100 at $185. `Ledger.fifo` keeps the symbol in the
position list with `shares = 0.0, realized = 500.0` (Ledger.kt:248-255), but `publish()` builds
rows only from `positions.filter { it.shares > 1e-9 }` (PortfolioViewModel.kt:1754), so AAPL
reaches DetailScreen either as a watchlist row or as the transient search row - `watchOnly =
true` both ways. The screen therefore takes the else-branch at :1144 and prints "On your
watchlist - no shares held, so nothing here counts toward your totals." The $500 is never shown,
while the "Transactions (2)" section below lists both fills. The screen's own explanatory line -
"Profit from shares already sold is the realized figure below" (:1121) - is in the branch that
was skipped, so the one screen that promises the number is the one that hides it.
FIX: Lift the `realized` lookup and its `KeyValue("Realized (closed lots)", ...)` out of the
`!row.watchOnly` branch - compute it once before the `if` at :1049 and render it in both arms
(in the else-arm, inside its own `StatCard`), so a closed position still reports what it made.
CONFIDENCE: certain

---

ID: DET-3
SEVERITY: MEDIUM
WHERE: app/src/main/java/com/tj/portfolio/ui/DetailScreen.kt:1141 (with :716 and RowActions.kt:59-68)
BUG: The Overview tab's watch button is hard-labelled "Also watch" but fires the same
WATCH_TOGGLE action the round-66 PUI-7 fix made two-way, so on a stock that is both held and
watched it silently REMOVES the symbol from the watchlist.
FAILURE: NVDA is held (a position) and also on the watchlist. `publish()` emits it once from
the positions loop as `Row(watchOnly = false, watched = true)` (PortfolioViewModel.kt:1769).
DetailScreen takes the `row?.watchOnly != true` header branch, so no star is drawn and the only
watch control on the screen is the Overview tab's `TextButton(onClick = onWatchToggle) {
Text("Also watch") }`. Tapping it sets `PendingAction(symbol, WATCH_TOGGLE)`; `RowActionHost`
reads `row.watched == true` and calls `vm.removeWatch(symbol)`, toasting "NVDA removed from
watchlist". The button said "Also watch" and un-watched the stock. The mirror case is just as
wrong: for a held, un-watched symbol the same button correctly adds - so the one label covers
two opposite actions.
FIX: In `OverviewTab`, take `watched: Boolean` (pass `row?.watched == true` from DetailScreen
alongside `tracked`) and label the button `if (watched) "Remove from watchlist" else "Also
watch"`, matching what `RowActionHost` will actually do.
CONFIDENCE: certain

---

ID: DET-4
SEVERITY: MEDIUM
WHERE: app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:3244-3248 (feeding DetailScreen.kt:291 and the News tab's filings section at :1264-1289)
BUG: `loadInsider`'s first guard returns whenever the symbol already has ANY filing in memory,
which short-circuits the 30-minute `INSIDER_SYMBOL_TTL_MS` check on the very line below it - so
for a stock the user does not hold or watch, the detail screen's Form 4 list is frozen at
whatever the first fetch returned for the entire life of the process.
FAILURE: Search PLTR (not held, not watched) and open it. `LaunchedEffect(symbol)` calls
`vm.loadInsider("PLTR")`; EDGAR answers with three filings, `_insider["PLTR"]` is populated and
`insiderAt["PLTR"]` is stamped. Two hours later a Form 4 for a CEO purchase is filed. Re-opening
PLTR, backgrounding and resuming (the `ON_START` observer at DetailScreen.kt:338), and switching
tabs all call `loadInsider` again - and every one of them hits
`if (_insider.value[symbol]?.isNotEmpty() == true) return` and stops before the TTL line is ever
evaluated. The half-hourly `refreshInsiders` pass cannot cover it either: it is handed
`rows.map { it.symbol }` (refreshInsidersIfEmpty at :3392, and the same shape from the auto
loop), and PLTR is in neither list. Only an explicit pull-to-refresh (`force = true`) ever gets
the new filing on screen. The 30-minute TTL directly beneath is unreachable code for every
symbol that has filings, which is precisely the set of symbols the timer was written for.
FIX: Delete the `if (_insider.value[symbol]?.isNotEmpty() == true) return` line at :3245 and let
the `insiderAt` / `INSIDER_SYMBOL_TTL_MS` guard on the next two lines govern both cases - it
already stamps only on a resolved answer (:3290-3292). To keep the SEC request count where it is
for followed symbols, stamp `insiderAt[sym]` for each symbol in `merged.groupBy { it.symbol }`
inside `publishInsiders` (:3412), so a symbol the month-long pass just covered still suppresses
the per-symbol listing request for its half hour.
CONFIDENCE: certain

---

ID: DET-5
SEVERITY: LOW
WHERE: app/src/main/java/com/tj/portfolio/ui/MetricUi.kt:59 (MetricUnit.PRICE, via util/Format.kt:69-70) and DetailTabs.kt:449, :452, :455, :498-499
BUG: `MetricUnit.PRICE` and the Earnings tab format a negative figure as "$-0.42" - dollar sign
outside the minus - while the neighbouring `MetricUnit.MONEY` branch one line above deliberately
writes "-$0.42", so a loss-making company's EPS prints in a shape that appears nowhere else in
the app.
FAILURE: ONDS reports `trailingEps = -0.42`. The Stats tab's EARNINGS group calls
`formatMetric(MetricUnit.PRICE, -0.42)` -> `Fmt.price(-0.42)`; `abs(v) < 1.0` so it takes the
`money3` branch, and `DecimalFormat("#,##0.000").format(-0.42)` returns "-0.420", to which the
function prepends "$": the row reads "Earnings per share (last 12m)   $-0.420". Directly above
it in the same card, "Net income (last 12m)" goes through the MONEY branch
(`(if (v < 0) "-$" else "$") + Fmt.compact(abs(v))`, MetricUi.kt:58) and reads "-$18.40M". The
Earnings tab repeats it on every loss-making quarter: "Expected $-0.150  -  reported $-0.120"
(DetailTabs.kt:498), plus "Range across analysts", "Same period last year" and "Expected earnings
per share". `Fmt.usdSigned` (Format.kt:66) already uses the "-$" form, so this is the one
formatter in the app that disagrees with the rest.
FIX: In `Fmt.price` / `Fmt.priceBare` (Format.kt:69-73), format `abs(v)` and prepend the sign the
way `usdSigned` and the MONEY branch do: `(if (v < 0) "-$" else "$") + <fmt>.format(abs(v))`.
Nothing else calls `Fmt.price` with a negative - prices, targets and fills are all non-negative -
so the change is confined to the EPS figures.
CONFIDENCE: certain

---

ID: DET-6
SEVERITY: LOW
WHERE: app/src/main/java/com/tj/portfolio/ui/TxnEditor.kt:242-245
BUG: The "worked out from the price per share, not the total you typed" caveat compares the GROSS
`quantity x price` against the typed total, but the total box is the NET figure (fees included,
per its own label at :215) - so the warning fires on every fee-bearing trade even when the saved
cash effect equals the typed total to the cent.
FAILURE: Open the imported ONDS buy - 100 shares, price 1.50, fees 5.95, amount -155.95 (Ally's
low-priced commission; the worked example in Models.kt:40-45). The boxes seed to "100", "1.5",
"155.95", "5.95". `previewCash = Txn.cashEffect(BUY, 100, 1.5, 155.95, 5.95) = -(150) - 5.95 =
-155.95` - exactly the total that was typed. But the guard evaluates
`abs(100 * 1.5 - 155.95) = 5.95 > 0.005`, so the dialog prints "This takes $155.95 out of your
cash - worked out from the price per share, not the total you typed", telling the user their
total was discarded when it was reproduced exactly. A sell has the same shape from the other
side: 100 @ 1.50 with a $0.03 TAF nets 149.97 and the gross is 150.00, so the caveat fires
again. The genuine case the sentence exists for - a total that really disagrees - is now
indistinguishable from the fee, and on TJ's sub-$2 holdings every trade is a fee-bearing one.
FIX: Compare the answer against the typed total instead of the gross: replace
`kotlin.math.abs(qNum * pNum - aNum) > 0.005` with
`kotlin.math.abs(kotlin.math.abs(previewCash) - aNum) > 0.005`.
CONFIDENCE: certain

---

ID: DET-7
SEVERITY: LOW
WHERE: app/src/main/java/com/tj/portfolio/ui/DetailScreen.kt:589
BUG: `refreshEverything` force-reloads the fund register only when the Holdings tab is selected -
but that tab exists only once the register has already loaded, so pull-to-refresh can never
recover from a failed holdings lookup, which is the one case it is needed for.
FAILURE: `tabs` includes HOLDINGS only when `fundHoldings?.isFund == true` (:209), and
`LaunchedEffect(tabs)` forces `tab` back to OVERVIEW whenever the selection is not in the list
(:278) - so `tab == DetailTab.HOLDINGS` is unreachable while the register is missing. Open SPY on
a flaky connection: `HoldingsFeed.holdings("SPY")` returns null, `holdingsRetry.failure` records
it (PortfolioViewModel.kt:3552), no Holdings tab appears. Pull down to refresh - TJ's stated rule
is "refresh every time I gesture pull down" - and `refreshEverything` re-fetches quotes, news,
filings, fundamentals and the chart with `force = true`, but skips `loadHoldings` because the
tab is not selected. Every un-forced route back in (`LaunchedEffect(symbol)` at :296, the
`ON_START` observer at :339) is then turned away by `holdingsRetry.blocked` for up to five
minutes (backoffMs, :594). The tab stays missing and the only gesture that would fix it does
nothing.
FIX: Drop the condition at :589 - call `vm.loadHoldings(symbol, force = true)` unconditionally in
`refreshEverything`. It is one request per deliberate pull against a register cached for twelve
hours, and it is the only path that bypasses the retry backoff.
CONFIDENCE: certain

---

ID: DET-8
SEVERITY: LOW
WHERE: app/src/main/java/com/tj/portfolio/ui/DetailScreen.kt:908
BUG: `OverviewTab`'s `rememberLazyListState()` is not keyed on the symbol, so when the screen
changes stock in place the new stock's Overview opens at the previous stock's scroll offset.
FAILURE: MainActivity renders the detail screen from a `when` branch (`open != null ->
DetailScreen(...)`, MainActivity.kt:421), so changing `detail` from one symbol to another keeps
the same composition slot. Open SPY, Holdings tab, tap NVDA (`onOpenSymbol`, :429) - `tab` is
keyed on symbol so it resets to OVERVIEW and OverviewTab is composed fresh, fine. Now scroll
NVDA's Overview down to its transactions and press Back: `detail` becomes SPY again, `tab` is
already OVERVIEW, so `OverviewTab` never leaves the composition and its `listState` survives
with `firstVisibleItemIndex = 4`. SPY's Overview therefore opens part-way down - past its chart
and price header - instead of at the top. The same happens on any in-place symbol change made
while Overview is showing.
FIX: `val listState = rememberLazyListState()` -> `rememberSaveable(symbol, saver =
LazyListState.Saver) { LazyListState() }`, or pass `symbol` down and use
`remember(symbol) { LazyListState() }`. `symbol` is already an OverviewTab parameter.
CONFIDENCE: certain

---

## Not reported, but noted (below the cap of 8)

* `DetailTabs.kt:411` - `(f.earningsDate - System.currentTimeMillis()) / 86_400_000L` truncates
  toward zero, so "In N days" is systematically one short of the calendar gap to the date printed
  directly above it (Sep 10 -> "Oct 29, 2026" reads "In 48 days"), and reads "In 1 days" at N=1.
* `InsiderUi.kt:209-227` - `ScopeChips` is a hand-rolled `Box` + `clickable` with only
  `padding(vertical = 8.dp)` around a `bodyMedium` line, so each chip measures about 36dp tall,
  under the 48dp rule the app's own `minTapTarget` helper exists to enforce and which the chart's
  range chips and `CompareToggle` do apply. It renders on the Feed's Insider tab, not on the
  detail screen.

## Categories that came back clean

Category 5 - layout at large font scale and touch targets - came back clean on the detail screen itself: `KeyValue` measures and stacks rather than dropping a half, `MetricRow`'s label wraps rather than clipping, `FullScreenChart` measures the chart instead of budgeting a fixed 150dp, `InfoDot` is a deliberate 40dp inside an already-tappable row, and `RangeChips`, `CompareToggle` and every `IconButton` reach 48dp; the only sub-48dp control found in these files is `ScopeChips`, which renders on the Feed tab (noted above). Category 2 came close, with only DET-8 against it - every other cross-symbol `remember` here (`tab`, `chartWindow`, `chartExpanded`, `chartPinching`, `zoomSettling`, `chartPerf`, `chartLoadingRanges`, `tabs`, `txns`, `items`, `filings`) is keyed on the symbol or on the data it derives from, the two deliberately unkeyed ones (`chartRange`, `compareOn`) are documented cross-symbol preferences, and no `LaunchedEffect` key on this screen re-runs a per-symbol fetch on a quote tick.
