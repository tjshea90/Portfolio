# Full-test audit 2026-09-24: charts and the stock detail screen (prefix C-)

Scope read in full: ui/PriceChart.kt, ui/FullScreenChart.kt, ui/DetailScreen.kt (chart wiring, OverviewTab,
CompareToggle, liveEdgePrice), ui/DetailTabs.kt, ui/HoldingsTab.kt, ui/InsiderUi.kt, ui/MetricUi.kt,
data/ChartModels.kt, data/ChartWindow.kt, net/ChartFeed.kt, util/MemoryTrim.kt, and PortfolioViewModel's
loadChart / adoptAsSparkline / adoptRecentD1 / intradayChartIsFinal / sparkIsFinal / onTrimMemory. I
cross-checked the tests against the code (ContinuousZoomUiTest, ScrubGestureUiTest, ChartTest,
ChartWindowTest, CompareChartTest and others). I skipped Http cancel-disconnect because other auditors
already reported it. Read-only audit: no files were changed apart from this one.

Checked and found sound (no finding): the MemoryTrim threshold (nothing is released below 60; the listener is
held strongly by the VM field). Disk-before-network order in loadChart and in the trim recovery path. The
in-flight guard (fgScope inherits Main.immediate, so the flag is set synchronously). clipToWindow always
returns 2 or more points, so ChartCanvas cannot index an empty list. The y-span floors at 1e-9. NaN and zero
closes are dropped in both the parser and the codec. nearestIndex behaves correctly at both edges. Live-edge
NaN is guarded. `pointerInput(Unit)` plus provider lambdas survive quote ticks. Scrub is reset on
symbol/range change. The window, the settle and the pinching state are all keyed on the symbol. HoldingsTab
bars are NaN-safe and ordering survives the codec. AnalystsTab keys are deduplicated in both parsers and in
merge.

---

## Findings

### C-1 [H] A pinch or pan that crosses into a range this symbol has not fetched yet kills the gesture and blanks the chart
- where: ui/PriceChart.kt:522-550 (placeholder `Box(... .then(gestures))` + `return@Column`) vs
  ui/PriceChart.kt:727-735 (the real chart `Box(plotSize.clipToBounds().then(gestures))`);
  ui/DetailScreen.kt:321-322 (`val chart = chartMap[chartKey]`, no fallback), 545-575 (`onChartWindow`
  switches `chartRange` mid-gesture), 649-657 (380 ms settle before loadChart), 1205-1208 (`series = chart`).
- what's wrong: the gesture modifier is attached to two different Boxes at two different call sites. When
  `series` goes from non-null to null, the chart Box leaves composition and the placeholder Box is a new
  LayoutNode with a new pointer-input node. Compose only hit-tests pointers on DOWN, so the new node never
  receives the fingers that are already on the glass. The old node's coroutine is cancelled, and its
  `finally` reports `ChartGesture.NONE`. The Round-63 comment says "the placeholder carries the same
  surface - so a pinch continues across a window that has not arrived yet". That is only true for a gesture
  that STARTS on the placeholder, and that is the only case `ScrubGestureUiTest` "a pinch keeps working while
  the chart it landed on is still loading" covers (the chart is null from the first frame). No test covers
  the loaded-to-null transition, which is the case the comment describes.
- failure scenario: open a stock for the first time (only 1D cached) and pinch outward. Once the window
  passes about 1 day, `rangeForLookback` picks D5, `chartMap["SYM|D5"]` is null, the chart is replaced by a
  placeholder and the pinch stops responding until both fingers lift. For the 380 ms settle,
  `loading == false`, so the placeholder shows "No 5D chart available - pull down to retry" before
  switching to "Loading...". The same happens zooming into any uncached finer range (1Y to 6M or 1M, and
  so on), and when panning a zoomed 1D window back past today's open. This is the headline gesture ("zoom
  gradually all the way to all-time"), and it fails on every first use of each range.
- suggested fix: (a) keep ONE gesture host. Render ChartReadout/Spacer only when non-empty, then a single
  `Box(plotSize.then(gestures)) { if (empty) placeholderText else {canvas + labels} }` at a fixed call site.
  (b) In DetailScreen, keep drawing the last non-null series for this symbol while `chartWindow != null` and
  the new range is loading (`chart ?: lastShown`), so the window stays filled. (c) Pass
  `loading = chartLoading || zoomSettling` so the settle does not print "not available". Add a Robolectric
  test: start a pinch on a loaded series, swap `series` to null mid-gesture, assert `onWindow` keeps
  receiving frames.
- confidence: high (node identity follows from call-site positional memoization; verified that no fallback
  series is passed).

### C-2 [M] The 5D chart is flagged "truncated" every Friday from 09:30 to about 14:18 ET
- where: net/ChartFeed.kt:182-183, 210-213 (`D5 -> 7.0`, `TRUNCATION_RATIO = 0.6`); consumers
  ui/PriceChart.kt:1015-1022 (caption), 1470 (`rangePct` returns null).
- what's wrong: `truncated = spanDays < 7.0 * 0.6 = 4.2 days`. Five trading sessions during market hours on
  a Friday are Mon-Fri, so first point Mon 09:30 and last point Fri 10:xx gives a span of about 4.0-4.2 days.
  (Every other weekday spans at least 6 days, because a weekend sits inside the window. The weekend itself
  is about 4.25 days, just clear.)
- failure scenario: Friday 11:00 ET on 5D. The caption says "this is the whole history on record, which is
  shorter than 5D" (false), and the 5D range chip shows no figure.
- suggested fix: `ChartRange.D5 -> 4.0` (threshold 2.4 days still catches a stock listed two days ago), or
  count distinct session days (fewer than 3 means truncated). Add a test with a Mon 09:30 to Fri 11:00 fixture.
- confidence: high on the arithmetic. It assumes Yahoo's `range=5d` counts today's session once it has begun,
  which is the standard behaviour.

### C-3 [M] "vs SPY" on the All range measures the two lines from different start dates when the stock is older than SPY
- where: ui/PriceChart.kt:2437-2439 (`compareAnchorFor` = the stock's first candle), 2494-2498
  (`valueAtOrBefore(cs, baseT) ?: cs.first().close`), 2457 (the stock is anchored at its own first candle).
- what's wrong: when the benchmark starts AFTER the anchor, `valueAtOrBefore` returns null and the code
  silently falls back to SPY's own first close. The stock is measured from its 1980s first candle and SPY
  from Jan 1993, yet the caption says "Percent change, both lines from the same start". The KDoc (line 2422)
  promises null "whenever ... a benchmark series does not reach the window". The only test,
  CompareChartTest "a longer benchmark history is rebased to the start of the stock's window", covers the
  opposite direction.
- failure scenario: KO, IBM, AAPL or MSFT on "All" with vs SPY. The stock reads +50,000% since 1962-86, SPY
  reads +1,000% since 1993, and "+49,000 pts vs SPY" is printed as out-performance. The SPY line starts at 0%
  in 1993 while the stock line is already far above zero there.
- suggested fix: anchor both at `max(shown.first.t, benchmark.first.t)` for non-previous-close ranges. Pass
  the benchmark into `compareAnchorFor` so `inside`, the summary and `sinceLabel` use the same anchor. Or
  return null to honour the KDoc.
- confidence: high.

### C-4 [M] The range hysteresis does nothing at four of six ladder boundaries, so a wobbling pinch flips the range every frame
- where: data/ChartModels.kt:123-137 (`KEEP_UP = 1.0`, `KEEP_DOWN = 0.22`); ui/DetailScreen.kt:563-574 and
  635-643 (each flip sets `zoomSettling`, `chartRange` and `vm.setChartRange`, which is a DB write).
- what's wrong: a rung is abandoned as soon as span exceeds 1.0 x its coverage (no margin on the way out).
  On the way back, the coarser rung's KEEP_DOWN band is `0.22 * coarse`. That band only helps if it is below
  the finer rung's coverage, and it is not for D5 (1.54 d vs 1 d), M6 (40.9 d vs 31 d), Y5 (402 d vs 366 d) or
  MAX (3220 d vs 1830 d). Example: `rangeForLookback(1.01 d, D1) = D5` and `rangeForLookback(0.99 d, D5) = D1`.
  The only remaining margin is the one-candle `candleMs` offset: 25 min at D1/D5, 0 at M1/M6. The claim
  "cannot thrash" is false. ChartWindowTest "the range sticks until the window has clearly left it" only
  tests the middle of a band.
- failure scenario: holding a pinch near a 1-day or 31-day window alternates the drawn series between 5-min
  and 30-min candles (or daily and 1M/6M series) on successive frames. The result is visible flicker, a
  SQLite write per flip and restarted LaunchedEffects. If one side is uncached, this triggers C-1.
- suggested fix: when `current` is set and the candidate is FINER than current, only switch down when
  `span <= candidate.approxSpanMs * 0.85`. Add boundary-wobble tests for each adjacent pair.
- confidence: high.

### C-5 [M] A benchmark series that ends before the stock's window begins is carried forward as a flat line (1D compares two different sessions)
- where: ui/PriceChart.kt:2499-2502 (D1 base = `compare.from`), 2534 (`valueAtOrBefore` carries the last
  value forward without limit); ui/PortfolioViewModel.kt:5097-5113 plus 819-825 (a D1 series fetched after
  yesterday's close counts as final until the next open, so SPY D1 is not refetched in pre-market).
- what's wrong: `comparePercents` only refuses a benchmark that starts too LATE. It never checks that the
  benchmark reaches the stock's window at all.
- failure scenario: pre-market (04:00-09:30 ET), vs SPY on, open a stock not viewed since yesterday. Its D1
  is freshly fetched (the pre-open fallback shows today's pre-market). SPY D1 comes from yesterday's cached,
  "final" session. Every stock timestamp is later than SPY's last candle, so the SPY line is flat at
  yesterday's full-day change, the readout prints "SPY +0.8%" (yesterday), and the spread subtracts
  yesterday's SPY move from today's pre-market move. Any SPY refresh failure during the session produces
  the same effect.
- suggested fix: return null (no overlay) when `cs.last().t < pts.first().t`. For D1/OVERNIGHT, also require
  both series to cover the same ET session day. Optionally, when comparing, let loadChart refetch SPY whenever
  its `endMs` is older than the primary series' `startMs`.
- confidence: medium-high. The mechanism is verified in code; the trigger window is pre-market only unless
  the SPY fetch is failing.

### C-6 [L] Compare toggled on but no overlay drawable: the zoomed price chart gets the comparison's anchor
- where: ui/PriceChart.kt:616 (`compareAnchorT` keyed on `compare != null`), 652-671, 712-722, 751.
- what's wrong: `inside`, `sinceLabel` and the dotted baseline switch on `compare != null`, not on
  `cmp != null`. When `compareLines` returns null (misaligned or too few SPY readings in the window), the
  chart falls back to a DOLLAR chart. On a zoomed window it then measures from the range's first candle,
  labels the result "since <date>", draws the dotted line at that old price and widens the y-axis to reach
  it. That contradicts the non-compare zoom rule (measure from the first point on screen).
- suggested fix: compute `cmp` first and key the three branches on `cmp != null`.
- confidence: high.

### C-7 [L] Pinch-chosen resolution overwrites the user's saved range; "Reset zoom" returns to it, not to the chip they picked
- where: ui/DetailScreen.kt:567-574 and 639-643 (`vm.setChartRange(next)` from a gesture), 847 (reset only
  nulls the window).
- what's wrong: since Round 64 the range under a continuous window is a resolution detail, but it is still
  persisted and still highlighted as the selection. Pinching a 1Y chart to 2 months leaves 6M selected. Reset
  shows 6M, and the next stock opened starts on 6M.
- suggested fix: remember the chip-selected range separately. Reset should restore it, and only chip taps
  should persist. (This is a design question for Tj.)
- confidence: high (behaviour); whether it is intended is unclear.

### C-8 [L] After the close, the 1D and After-hours readouts measure from the last 5-minute candle, not the official close
- where: ui/DetailScreen.kt:1723-1734 (`liveEdgePrice` D1 applies only when `OPEN`); net/ChartFeed.kt:171-173,
  270-290 (the OVERNIGHT baseline is the last regular candle's close).
- what's wrong: after 16:00 the 1D tip stays at the 15:55 candle's close, and the After-hours baseline is the
  same candle's close. The header uses `regularMarketPrice` (the closing-auction print). They differ by the
  auction gap, so the 1D readout and 1D chip disagree with the header's day change, and the after-hours
  change disagrees with the header's After-hours figure.
- suggested fix: for D1 when `marketState == "AFTER"`, the series is `regularOnly`, and its last point is in
  the same ET session as `q.quoteTime`, use `q.price` as the tip. For OVERNIGHT during AFTER, prefer the
  quote's close as the baseline.
- confidence: medium (depends on the size of Yahoo's close-auction gap).

### C-9 [L] 5D and After-hours lines draw straight segments across closed-market gaps
- where: ui/PriceChart.kt:1589-1592 (x is linear in time), 1646-1649 (one continuous path).
- what's wrong: 5D is regular-session only, so about 32.5 h of trading sit in a roughly 150 h span. About
  78% of the width is straight interpolation across nights and a weekend. The stated rationale ("drawing
  evenly spaced would invent trading") is still violated, because the line bridges the gap. The benchmark
  overlay already breaks at gaps; the primary line does not.
- suggested fix: break the path, or draw it dashed or faint, where the gap between points is larger than
  about 3 candles. See also the idea about session-compressed x.
- confidence: high.

### C-10 [L] An insider row can print "filed Dec 31, 1969"
- where: ui/InsiderUi.kt:185-192; stamp source net/Insider.kt:298-299, 520 (`Fmt.parseRss(...)` returns 0 on
  failure).
- what's wrong: `tradeDate > 0 && !sameDay(tradeDate, filedAt)` is true when `filedAt == 0`.
- suggested fix: add `&& f.filedAt > 0`.
- confidence: medium-high.

### C-11 [L] Switching range while the first disk read is still in flight fetches from the network instead of disk
- where: ui/PortfolioViewModel.kt:5229-5243.
- what's wrong: `chartDiskRead.add(sym)` is set before the query finishes. A second `loadChart(sym, other)`
  in that window sees the symbol as read, finds nothing in memory yet, skips the disk and downloads a series
  that was about to be published from SQLite. The result is one wasted request, never a wrong chart (the
  merge keeps the newer copy).
- suggested fix: track the in-flight disk read as a Deferred per symbol and await it.
- confidence: medium-high.

### C-12 [L] The 5D tip is never live-edged
- where: ui/DetailScreen.kt:1728-1733 (`else -> 0.0`).
- what's wrong: D5 is intraday (30-minute candles) with a 30-minute TTL, but unlike 1D it gets no live
  edge, so its tip and its chip lag the header by up to about 30 minutes during the session. There is no
  comment saying this is deliberate.
- suggested fix: treat D5 like D1 (regular price while `OPEN`).
- confidence: high (behaviour), low (whether it was intended).

---

## Code / UI quality (C-Q)

### C-Q1 ChartCanvas rebuilds every Path on every scrub frame
- ui/PriceChart.kt:1573-1723. `scrub` is read inside the draw lambda (correctly avoiding recomposition), but
  that lambda also rebuilds the price path, the fill path (`addPath`) and the benchmark segments, which is
  O(n) allocation at 120 Hz while scrubbing. Build the paths in `drawWithCache` keyed on (points, axis,
  yBounds, cmp) and draw the crosshair in a separate layer.

### C-Q2 Every pan or pinch frame recomposes the whole PriceChart
- ui/PriceChart.kt:250-305, 631-743, 894-1026. Per frame: clipToWindow and insideIndices each do two
  linear `indexOfFirst/indexOfLast` scans, compareLines allocates two DoubleArrays with binary searches,
  there are 4-8 SimpleDateFormat calls (axis labels, spansMoreThanADay/Year), and the caption is rebuilt
  with `buildString`.
- `nearestIndex` also adds two linear scans per scrub frame (1785-1786), which undercuts its own "binary
  search" rationale. Use binary searches for these bounds, and memoise the axis-label strings on the
  window's minute or day.

### C-Q3 ChartTest checks a copy of the finality rule, and the copy is stale
- ChartTest.kt:353-367 restates `intradayChartIsFinal` because it is private. The copy predates N-6's
  `sparkIsFinal` clause, so the finality tests assert the OLD rule and the real one is untested. Make
  `intradayChartIsFinal(held, now)` an internal pure top-level function and test that directly.

### C-Q4 The rung-ladder zoom path is effectively dead
- DetailScreen passes both `onZoom` and `onWindow`. The rung path (`onChartZoom`) only fires when
  `chartBounds == null`, meaning no series at all is held for the symbol. In that state a pinch steps whole
  rungs, and everywhere else it is continuous. Either drop `onZoom` from the detail screen or document the
  fallback.

### C-Q5 Insider scope and source chips are below the 48 dp tap-target rule
- InsiderUi.kt:257-266 and 292-301 use `padding(vertical = 8.dp)` around bodyMedium, about 36 dp tall. The
  app's own rule is 48 dp (`minTapTarget`, which RangeChips and CompareToggle use). Add `.minTapTarget()`.

### C-Q6 The "vs SPY" toggle can be on with no overlay and no explanation
- DetailScreen.kt:1659-1702. If the SPY fetch failed or cannot align, the chip stays lit (showing "SPY",
  not "...") over a plain dollar chart. Show a muted "SPY unavailable" state.

### C-Q7 Unused import
- PriceChart.kt:52 imports `kotlinx.coroutines.withTimeoutOrNull`, but the call at 2081 resolves to the
  `AwaitPointerEventScope` member. Remove the import to avoid confusion.

### Missing tests (named)
- `ContinuousZoomUiTest`: "a pinch survives the series going null mid-gesture" (C-1).
- `ChartWindowTest`: "range does not flip on a boundary wobble" for D1/D5, M1/M6, Y1/Y5 and Y5/MAX (C-4).
- `ChartTest`: "a Mon-Fri 5D series on Friday morning is not truncated" (C-2).
- `CompareChartTest`: "a benchmark starting after the stock shares a common start or is refused" (C-3), and
  "a benchmark ending before the window is refused" (C-5).
- A real test of `intradayChartIsFinal` (C-Q3).

---

## Ideas: need Tj's approval, do NOT implement
1. Session-compressed x-axis for 5D (and optionally 1M/6M daily): lay out trading minutes or days by index
   with thin day separators, as Robinhood and Yahoo do. This removes the dead diagonal lines from C-9.
2. Touch-down shows the value immediately on an unzoomed chart (today a tap does nothing until a
   horizontal slop drag).
3. Show the axis clock in ET (market time), or add an "ET" suffix. Today it uses the phone's timezone.
4. Add the vs SPY toggle inside the full-screen viewer (it only has the range chips).
5. When the chart is drawn from a stale cache row because a refresh failed, show "as of 3:55 PM Thu"
   under the readout.
6. Label the dotted previous-close line with its price at the right edge, as TradingView does.
7. Volume bars under 1D and 5D (the chart API already returns `volume`).
8. Double-tap to reset zoom, in addition to the Reset chip.

---

## Summary

| Severity | Count | IDs |
|---|---|---|
| H | 1 | C-1 |
| M | 4 | C-2, C-3, C-4, C-5 |
| L | 7 | C-6 to C-12 |
| Quality | 7 | C-Q1 to C-Q7 (plus 5 named missing tests) |
| Ideas | 8 | see the Ideas section |

Top 3: C-1 (a pinch into an uncached range destroys the gesture node and blanks the chart), C-3 (vs SPY on
All measures from different start dates for stocks older than 1993), C-4 (no effective hysteresis, so the
range flips on a boundary wobble).

## END OF REPORT (complete)
