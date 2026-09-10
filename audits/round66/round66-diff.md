# Round 66 — adversarial review of the round's own fixes

Read-only review of `e7f89e8~1..HEAD` (28 fixes). Findings below are regressions
INTRODUCED BY the fixes, not the original bugs. Ranked most severe first.

---

## ID: REG-1
**SEVERITY:** HIGH
**WHERE:** `app/src/main/java/com/tj/portfolio/data/EtfModels.kt:94`
**BUG:** The expense-ratio sentinel was changed from `0.0` to `-1.0` everywhere except
`EtfRow.merge`, which still tests `if (expenseRatio > 0)` — so a genuinely free fund's real
`0.00%` is thrown away during the multi-list merge and replaced by the other row's value,
which is `-1.0` (unknown) whenever that page omitted the field.

**FAILURE:**
`Research.buildEtfs` merges three screens (`top_etfs_us`, `bond_etfs`, `commodity_etfs`) into
one universe with `universe[r.symbol] = existing.merge(r)` (`net/Research.kt:240`). BKAG is a
zero-fee bond fund and appears in more than one of those screens. Suppose the first page
parsed carries `netExpenseRatio = 0.0` and the second page omits the field (parsed as `-1.0`
by the new `EtfScreener.parse`). `merge` evaluates `if (0.0 > 0) 0.0 else other.expenseRatio`
→ **-1.0**. The merged row is now "fee unknown":
* `EtfScore.best` skips the whole cost block (`>= 0.0` is false) → the fund forfeits all
  **20 of 20** cost points and takes a `have/want` confidence penalty, moving it toward being
  dropped by `MIN_ETF_CONFIDENCE`;
* `EtfFactsGrid` prints `—` for Expense (`ui/ResearchScreen.kt:801`);
* `ETF_ORDER`'s cost tie-break sorts it with `Double.MAX_VALUE`, i.e. dead last
  (`net/Research.kt:387`).

That is precisely the ETF-6 failure the sentinel change was written to fix — the cheapest
funds in existence being invisible to the list — reintroduced through the merge path, for the
two funds (BKLC / BKAG) named in the fix's own comment. The same line is also a silent
data-corruption path in the other direction: a free fund merged with a row reporting a real
fee adopts the *other* row's fee and displays it as its own.

**FIX:** `EtfModels.kt:94` → `expenseRatio = if (expenseRatio >= 0) expenseRatio else other.expenseRatio,`
(the file's own KDoc 48 lines above states the invariant: "Anything reading it must test
`>= 0.0`, not `> 0.0`").

**CONFIDENCE:** certain

---

## ID: REG-2
**SEVERITY:** HIGH
**WHERE:** `app/src/main/java/com/tj/portfolio/data/ResearchModels.kt:287-296` (`repairModelScore`)
**BUG:** The v1→v2 cache migration recognises "a row nothing in this app computed" as
`reasons.isEmpty() && etf == null`, but `ResearchScore.best` can legitimately return a
positive score with an **empty** reasons list — so the migration silently converts real
app-measured Best rows into fake Claude rows, and the card then attributes a number to Claude
that Claude never produced.

**FAILURE:**
Every reason line in `ResearchScore.best` (`net/ResearchScore.kt:62-190`) is conditional, and
there is no fallback line. Take a beaten-down mid-cap:
`epsGrowth = -0.10` (forward EPS below trailing → `ramp` gives 0 pts, `g > 0.05` is false, **no
line**), `forwardPe = 30` (→ 8.1 pts, `forwardPe <= 25` is false, **no line**),
`price < fiftyDayAvg` and `fiftyDayAvg < twoHundredDayAvg` (→ `t == 0`, **no line**),
`rangePos = 0.30` (→ 3.4 pts, only `> 0.90` prints, **no line**), `marketCap = 5e9`
(→ 6.7 pts, `>= 1e10` is false, **no line**), `avgVolume3M = 1e6` (→ 2.9 pts, **no line**),
`lists = {day_losers, most_shorted}` (0 pts, `scoringScreens` empty, **no line**),
`priceToBook >= 0` (**no line**).

Result: `Scored(score = 21, reasons = [], confidence = 100)`. It passes
`score > 0 && confidence >= 50` and lands in the 50-row `best` buffer
(`net/Research.kt:126-131`), and `ResearchRow.toJson` writes `score:21` with no `reasons` key
and no `etf` (`ResearchModels.kt:189`).

On the first launch after upgrade the cache has no `version`, so `ResearchSet.fromJson`
defaults to 1 and `repairModelScore` fires: `appMeasuredIt` is false, `score > 0`, so the row
becomes `score = 0, conviction = (21 + 5) / 10 = 2`. `ResearchScreen.kt:601-611` then computes
`fromClaude = score <= 0 && conviction > 0` → true, and draws the badge as
**"CLAUDE 2/10"** with the screen-reader text *"Claude's conviction 2 out of 10; this fund was
not scored by the app"* — on a stock the app scored itself and that Claude has never seen. The
row's real score is destroyed (it now sorts last), and it is destroyed permanently: the next
`toJson` writes it back at version 2 with `score = 0`.

The KDoc's claim that the reverse mapping "is exact" holds only for rows the old writer
actually stored as `conviction * 10`; here it manufactures a conviction out of an app score.

**FIX:** Narrow the recogniser so it cannot match a Best/Trending row. The old writer only did
this for funds Claude ADDED to the ETF list, so gate on that: require the row to be a
model-added fund, e.g.
`val appMeasuredIt = reasons.isNotEmpty() || etf != null || score % 10 != 0 || why.isBlank()`
— or, cleanest, pass the section key into `fromJson` and only repair rows from `"etfs"`.

**CONFIDENCE:** certain

---

## ID: REG-3
**SEVERITY:** HIGH
**WHERE:** `app/src/main/java/com/tj/portfolio/ui/DetailScreen.kt:1141` (with `ui/RowActions.kt:60`)
**BUG:** PUI-7 changed `RowAction.WATCH_TOGGLE` from `row.watchOnly` to `row.watched` and
updated the *row menu* label to match, but missed the Detail screen's hard-coded **"Also
watch"** button — which is now a destructive, mislabelled action for any symbol that is both
held and watched.

**FAILURE:**
`DetailScreen.kt:1136-1142` sits inside `if (row != null && !row.watchOnly)`
(`DetailScreen.kt:1048`), i.e. the *held position* branch, and unconditionally renders
`TextButton(onClick = onWatchToggle) { Text("Also watch") }`. `onWatchToggle` fires
`RowAction.WATCH_TOGGLE` (`DetailScreen.kt:716`), which `RowActions.kt:59-66` now resolves as:

```kotlin
if (row?.watched == true) { vm.removeWatch(symbol); vm.toast("$symbol removed from watchlist") }
else { vm.addWatch(symbol); ... }
```

For a symbol TJ both holds and watches, `publish()` emits one row with
`watchOnly = false, watched = true` (`PortfolioViewModel.kt:1768`). Open that stock's Detail
screen, tap the button that says **"Also watch"**, and it is **removed from the watchlist**,
with a toast saying so. Before the fix the same tap ran `addWatch` — an idempotent no-op that
matched the label.

The button is also drawn for every held-but-not-watched stock, where it still adds — so the
same control does opposite things with the same caption, and the destructive case is the one
that is not announced.

**FIX:** Give this button the same treatment `StockRow.kt:302` already got:
`Text(if (row.watched) "Remove from watchlist" else "Also watch")` — or leave the label fixed
and call `vm.addWatch(symbol)` directly instead of routing through `WATCH_TOGGLE`.

**NOTE (same fix, other half):** `DetailScreen.kt:612` still branches the header star on
`row?.watchOnly != true`, so a held-and-watched symbol still gets no watchlist control in the
top bar — the original PUI-7 complaint, unfixed in this second location.

**CONFIDENCE:** certain

---

## ID: REG-4
**SEVERITY:** MEDIUM
**WHERE:** `app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt:5285-5292` (`oneFundPerExposure` inside `fillPricesNow`)
**BUG:** The RES-3 fix re-implements exposure de-duplication on the import/fill path but
**appends** the "Same exposure as ..." line instead of prepending it — reintroducing audit
finding ETF-3/E3 (`net/Research.kt:336`, "FIRST, NOT LAST"), so the fund it deletes is deleted
with no visible explanation.

**FAILURE:**
`ResearchBridge` now asks Claude for BND by name (`net/ResearchBridge.kt:252`). The screener
universe already contains AGG, and both key to `"Bonds - US aggregate"`
(`net/EtfExposure.kt:179`). The user imports Claude's reply:
1. `applyResearchAnswer` merges BND in and caches; the toast says *"Research updated - N
   explained, 1 added"*.
2. `fillResearchPrices` → `fillPricesNow` fills BND's name, then
   `oneFundPerExposure(dropLeveraged(fill(s.etfs)))` drops BND and does
   `row.copy(reasons = (row.reasons + "Same exposure as BND - ...").distinct())` on AGG.

AGG is an app-scored fund, so `EtfScore.best` has already emitted its six standard lines
(returns, cost, size, trend, yield, "Found in ..."). The card renders `r.reasons.take(6)`
(`ui/ResearchScreen.kt:679`), so the new line is the **seventh and never drawn**. BND
disappears from a list that just said it had been added, and nothing on screen says where it
went — which is verbatim the failure E3 was raised for and fixed on the screener path by
putting the line FIRST.

Secondary: when the survivor already carries a screener-path "Same exposure as X, Y" line at
position 1, this appends a *second*, differently-worded one; `.distinct()` cannot merge them
because the ticker lists differ, so a row can end up asserting two different sibling sets.

**FIX:** Prepend, matching `net/Research.kt:346`:
`row.copy(reasons = (listOf("Same exposure as " + also.joinToString(", ") + " - this one scored highest of them") + row.reasons).distinct())`
— and, better, merge into an existing "Same exposure as" line rather than adding a second.

**CONFIDENCE:** certain

---

## ID: REG-5
**SEVERITY:** MEDIUM
**WHERE:** `app/src/main/java/com/tj/portfolio/ui/PortfolioScreen.kt:584` (and `ui/StockRow.kt:307`, `ui/TxnEditor.kt:255,292`, `ui/SettingsScreen.kt:477,865`, `ui/ReaderScreen.kt:225`)
**BUG:** The contrast fix changed only the `redText` accessor and left every hard-coded
`color = Red` text site behind, so the rule it declares in its own KDoc — *"the split is text
versus fill, exactly as for `greenText`"* (`ui/Theme.kt:155`) — is not actually enforced, and
the app now draws two different reds for the same meaning.

**FAILURE:**
`PortfolioScreen.kt:584` renders the heading **"Your transactions are missing"** with
`color = Red` inside a `StatCard`, whose container is `surfaceVariant`
(`ui/Widgets.kt:513`). Those are the exact two colours the fix measured:
`Red` `#EA4B5D` (L=0.2332) on dark `surfaceVariant` `#1C2027` (L=0.01427) →
**(0.2332+0.05)/(0.01427+0.05) = 4.41:1**, below the 4.5:1 AA needs for a 16sp bold heading
(WCAG "large" starts at 18.66px bold). That is the same number the `RedTextDark` KDoc cites as
the reason the accessor had to change — on the app's most alarming screen, in the theme the
palette was tuned in.

Second effect: the same semantic now has two colours. `DetailScreen.kt:1142` draws "Delete
position" in `redText` (`#F2606F`) while `StockRow.kt:307` draws "Delete this position" in
`Red` (`#EA4B5D`) — adjacent affordances for the same destructive action, in visibly
different reds, one of them ~4.40:1 on the dropdown container. Before the fix these matched,
because `redText` in dark theme *was* `Red`.

`ContrastTest` does not catch it because it exercises the accessors (`greenText` / `redText` /
`signColor`) rather than the call sites.

**FIX:** Replace `color = Red` with `color = redText` at every site where `Red` is used as
TEXT (the list above; `Widgets.kt:495` `PricePill` and the chart/sparkline fills are correct
as they stand), and add a test that fails on a literal `Red` inside a `Text(...)`/`tint =`.

**CONFIDENCE:** certain (arithmetic verified against the fix's own published figure)

---

## ID: REG-6
**SEVERITY:** LOW
**WHERE:** `app/src/main/java/com/tj/portfolio/ui/PriceChart.kt:396-398`
**BUG:** The CHT-3 guard tests the candle against the axis in **milliseconds** while
`nearestIndex`'s own on-screen clamp tests it in **truncated seconds**, so the two disagree at
the left edge and the new guard can refuse a scrub that `nearestIndex` deliberately allowed.

**FAILURE:**
`nearestIndex` clamps to points inside the axis using
`lowSec = axis.startMs / 1000L` and `it.t >= lowSec` (`PriceChart.kt:1687-1690`) — integer
division, so `lowSec` floors. The new guard uses `pts[idx].t * 1000L in axis.startMs..axis.endMs`.

`ChartWindow.panned` / `.zoomed` produce `startMs` by adding `roundToLong()` of a fractional
shift (`data/ChartWindow.kt:105-113`), so after any pinch or drag `startMs % 1000 != 0` with
probability ~999/1000. If the leftmost candle's timestamp `t` equals `floor(startMs/1000)` —
i.e. the window edge landed in the same second as, and just after, that candle — then
`nearestIndex` returns it as `firstIn` (inside), but `t * 1000 < startMs`, so the guard
returns `NO_SCRUB`. On a 5-minute intraday series that is roughly a 1-in-300 chance per pinch;
when it hits, dragging to the far left of the chart shows no crosshair and the readout snaps
back to the live price, for that one candle only.

**FIX:** Compare in the same units `nearestIndex` clamps in:
`if (pts[idx].t >= axis.startMs / 1000L && pts[idx].t <= axis.endMs / 1000L) idx else NO_SCRUB`
— or, better, have `nearestIndex` return a sentinel for its own "nothing inside" fallback so
there is only one predicate.

**CONFIDENCE:** certain (unit mismatch); trigger is narrow

---

## Clean verdicts — changes traced and found SOUND

* **`net/EtfScore.kt` bounds.** The rescale is correct and the total still cannot leave
  0..100. Max is 30 (5Y+3Y rescaled) + 4 (YTD) + 20 (cost) + 16 (size) + 12 (liquidity) +
  8 (age) + 10 (trend) = **exactly 100**, and every term is a `ramp`/`logRamp` clamped at 0,
  so it cannot go negative either. `confidence` still means what its KDoc says: `want` is
  incremented exactly six times and `have` only inside each block.
* **`net/EtfScore.kt` monotonicity.** The YTD split fixes what it claims: a fund with 5Y=3Y=10%
  scores 15 for the return term with or without a 3Y, and gains (never loses) up to 4 more for
  a reported YTD. Relative ordering between funds reporting different *sets* of long-run
  horizons is unchanged (10%/yr on 5Y alone, on 3Y alone, or on both all give 15).
  The remaining "not published beats published-and-negative" asymmetry is the pre-existing
  `0.0` sentinel on `threeYearAnnualPct` / `fiveYearAnnualPct`, not something this fix added.
* **`net/EtfScreener.kt` null handling.** Every path is covered: `fetch` returns `null` only
  after both hosts fail, `fetchAllWith` breaks on `null` with `complete = false`, breaks on
  `emptyList()` with `complete = true`, and `Research.buildEtfs` supplies a
  `Fetched(emptyList(), complete = false, ...)` default for the `runCatching`. `pagesRead`
  correctly counts the terminal empty page, so a genuinely short list raises no false warning.
* **`net/EtfExposure.kt`.** Traced by hand against real names. VT / ACWI still group
  ("Global equity incl US"); VXUS / IXUS / VEU / ACWX group separately with `" ex us "` tested
  before `" all world "`, which is what "Vanguard FTSE All-World ex-US" needs. BND / AGG /
  SPAB still reach "Bonds - US aggregate" (`" u s "` does not contain `" ex us "`); BNDX, BNDW
  and IAGG correctly fall through to ungrouped. I could not construct an equity fund whose
  name contains one of the eleven `bondWords` *and* matches a region key, so the
  `isFixedIncome` gate does not strand anything it should group.
* **`util/Format.kt` `Fmt.exact` at 12 significant digits.** All four callers are edit-box
  seeds (`ui/RowActions.kt:114,124`, `ui/TxnEditor.kt:65,67,70,72`); nothing hashes, sums or
  compares the string. The round-trip guarantee documented at `TxnEditor.kt:61` is now
  approximate rather than exact, but nothing depends on it: `Db.findDuplicateId` matches on a
  tolerance (`max(0.02, amt*0.005)`, `Db.kt:679`), not equality, and `publish()` discards
  positions under `1e-9` shares (`PortfolioViewModel.kt:1755`), which is three orders of
  magnitude above the worst residue a realistic share count can produce.
* **`ui/PriceChart.kt` `centroidN`.** Correct. A third finger landing leaves `pair` valid, so
  `centroidN != pressed` for exactly one frame, the pan is skipped and the reference re-seeds
  — and it re-seeds again symmetrically when that finger lifts. No double-report.
* **`ui/PriceChart.kt` ZOOM→PAN.** Cannot double-report: the two-finger branch requires
  `pressed > 1` and the transition frame has `pressed == 1`, so only one of the two pan
  measurements can run per frame. It cannot start a pan the user did not ask for either —
  `onPan` is guarded by `delta.x != 0f`. The only artifact is a transient
  `report(ChartGesture.PAN)` on the way up from a pinch that is lifted one finger at a time,
  which the `finally`'s `report(NONE)` clears a frame later.
* **`ui/PriceChart.kt` `canPanNow` + `windowBounds`.** Sound. Adding `windowBounds != null`
  only ever makes the predicate stricter, and the two things it gates — the 350ms hold arm and
  the caption at line 922 — now agree with `pan()`, which returns null without bounds. A
  zoomed chart with no bounds falls through to `SCRUB` rather than becoming inert.
* **`ui/PortfolioViewModel.kt` `enrichPass` write-back.** The re-projection is correct: the
  section is re-read at write time, the enriched copies are swapped in by symbol, rows added
  during the pass survive in `freshTail`, and the `while (enrichPass() && passes++ < 6)` loop
  still terminates because `analystDone` is marked for the whole window before the write.
* **`ui/PortfolioViewModel.kt` `resetResearchPaging`.** Merging into the existing map instead
  of replacing it is right, both call sites are in success branches that actually replaced
  their rows, and every reader defaults with `?: PAGE`.
* **`ui/PortfolioViewModel.kt` `pricelessRows`.** The per-30-minute re-request of
  unresolvable trending tickers is bounded: one batched `MarketData.quotes` call, and the
  four-provider fallback is already behind its own failure memory
  (`net/MarketData.kt`, "THE FALLBACK'S OWN FAILURE MEMORY").
* **`ui/PortfolioViewModel.kt` `Row.watched`.** `publish()` derives it correctly
  (`watchSet` from `cachedWatch`, `watched = true` by construction in the watch loop), and the
  new field is positional-safe because it is inserted mid-constructor, so any missed call site
  is a compile error. `DetailScreen.kt:146`'s synthetic row correctly defaults it to `false` —
  that row only exists for symbols in neither list. The only consumer that got it wrong is
  REG-3 above.
* **`data/EtfModels.kt` JSON round trip.** `toJson` (`>= 0`) and `fromJson` (`optDouble(..., -1.0)`
  with a NaN/∞ guard) are consistent, and `isEmpty` (`expenseRatio < 0.0`) matches. An older
  build's cache degrades a free fund to "unknown" rather than to "free", which is the safe
  direction. Every other reader of the field tests `>= 0` correctly — `EtfScore.kt:274`,
  `Research.kt:387`, `ResearchBridge.kt:197`, `ResearchScreen.kt:801`. `EtfRow.merge` is the
  sole exception (REG-1). `HoldingsTab.kt:90`'s `> 0.0` is a *different* field
  (`HoldingsModels.expenseRatio`, a fraction defaulting to 0.0) and is unaffected.
* **`ui/WatchlistScreen.kt` PUI-6 and `ui/PortfolioScreen.kt` PUI-2.** Weighting all three
  children of `BigLine` removes the measurement-order trap; `AutoFitNumber` with
  `weight(n, fill = false)` shrinks rather than clips. The figures are now capped at ~2/3 of
  the row where before they were measured first at full constraints, so a very wide number
  shrinks slightly earlier than it used to — deliberate, and stated in the comment.

---

## Change-sets actually traced

1. `net/EtfScore.kt` — return-term restructure, `confidence`, cost `>= 0.0` — **traced, sound**
2. Expense-ratio `-1.0` sentinel across `data/EtfModels.kt`, `net/EtfScreener.kt`,
   `net/ResearchBridge.kt`, `ui/ResearchScreen.kt`, plus every other reader found by grep
   (`net/Research.kt`, `ui/HoldingsTab.kt`, JSON round trip, sorting) — **REG-1**
3. `net/EtfExposure.kt` — asset class before region, "Global equity" split, aggregate-bond
   region exclusions — **traced, sound**
4. `net/EtfScreener.kt` — `fetch: List<EtfRow>?`, `fetchAll: Fetched`, all null paths —
   **traced, sound**
5. `ui/PriceChart.kt` — `centroidN`, ZOOM→PAN, `pointAt`/`NO_SCRUB`, `canPanNow` +
   `windowBounds` — **REG-6**, rest sound
6. `ui/PortfolioViewModel.kt` — `pricelessRows`/`fillPricesNow`, `Row.watched`, `enrichPass`
   write-back, `resetResearchPaging`, `dropLeveraged`, `oneFundPerExposure` — **REG-3, REG-4**
7. `data/ResearchModels.kt` — v1→v2 cache migration, Trending rows, Claude import path
   (which goes through `ResearchBridge.parse`, not `ResearchSet.fromJson`) — **REG-2**
8. `ui/PortfolioScreen.kt`, `ui/RowActions.kt`, `ui/Widgets.kt`, `ui/Theme.kt`,
   `util/Format.kt` — layout, colour, `Fmt.exact` and all four of its callers — **REG-5**,
   rest sound
