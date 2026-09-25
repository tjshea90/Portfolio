# Round 2 audit — Day Trading grading / stats side

Read-only verification of the DA-1..DA-21 / PL-1..PL-15 fixes (diff vs 5576e0fb)
for the grading, logging and stats paths, plus a hunt for new bugs.

Status: IN PROGRESS (findings appended as verified)

## Findings

### R2G-1 (H) - A decided one-minute verdict is silently re-graded on five-minute bars, which can turn a real LOSS into a WIN

- Where: `ui/PortfolioViewModel.kt:7666-7685` (`resolveOneDayTradingEntry`: `cached(1)` -> 1m fetch only while
  `oneMinuteStillAvailable` -> otherwise, or on an EMPTY 1m answer, falls to `res = 5`), `:7714` (writes whatever the
  new grade says, no comparison with the stored one), `:7666`/`:7715` (bars are cached only when `settled`, so a
  mid-session verdict's 1m bars are never kept); `net/DayTradingGrader.kt:481-486` (`needsSettledRegrade` has no
  age limit other than the 55-day `intradayStillAvailable`); `net/DayTradingEval.kt:89` (new PL-13 rule: a 400/422
  on the 1m request returns `emptyList()` at once, without trying query2 - also a route to the 5m fallback).
- Problem: the DA-1 fix re-grades every mid-session WIN/LOSS once after the settle. When that re-grade runs more
  than 29 days after the session (Tj did not open the Day Trading tab or press Check in between) there are no
  1m bars - neither from Yahoo nor from `dt_bars` (a mid-session grade never caches) - so the row is re-graded
  from scratch on 5-minute bars and the new verdict replaces the 1m one. On 5m bars the unseen window after
  `recordedAt` grows from <1 minute to up to 5 minutes, which is exactly where a fill-and-stop-out can hide
  (the grader's own header: "the old grader lost up to five, which could hide a fill-and-stop-out and then
  credit a win on the next bar"). The same replacement happens on any future `VERSION` bump for rows graded
  before `dt_bars` existed (every v2 row graded before this build) once they are past 29 days.
- Failing scenario: Breakout buy-stop 10.50 / stop 10.00 / target 11.50, recorded 10:02:30. 1m bars: 10:03 high
  10.55 (fill 10.50), 10:04 low 9.95 -> LOSS, decided by the 10:15 auto-check with `partial:true`. The stock then
  re-breaks 10.50 at 10:30 and trades 11.60 at 11:00. Tj next opens the tab 31 days later: `needsSettledRegrade`
  = true, `cached(1)` = null, `oneMinuteStillAvailable` = false -> 5m fetch. The first 5m bar starting at or after
  10:02:30 is 10:05, so the 10:00 bar holding the fill AND the stop is excluded; the 10:30 bar fills, the 11:00 bar
  hits the target -> the row is rewritten as WIN (`res:5`), counted in the headline and the account figure. Tj
  really lost ~1R on that order.
- Suggested fix: never let a coarser grade replace a decided finer one. In `resolveOneDayTradingEntry`, when
  `decided` and the stored detail's `res == 1` and the only bars available now are 5m, keep the verdict (for a
  partial row, just stamp `outcome_evaluated_at` so `needsSettledRegrade` stops asking - it stays partial, no
  grid). Optionally also let the 400/422 branch fall through to query2 for the 1m interval before concluding.
  Test: a partial 1m LOSS re-graded with only 5m bars available keeps LOSS/res 1.

### R2G-2 (M) - The new buy-limit "bad LOW print" filter (DA-7) mostly deletes LOSSES: a wick deep enough to count as a spike usually also runs through the stop

- Where: `net/DayTradingGrader.kt:310-318` (`isSpikeLow`), `:392-395` (a spike low is "not traded through", the
  loop moves on to later bars).
- Problem: the spike test needs a wick > 6 median ranges AND > 1.5% of the price below the bar's body. A day
  trade's stop sits well inside that: `risk` is clamped to [minRisk, maxRisk] x the 5-minute ATR, typically
  0.3-1% of the price. So whenever the filter vetoes a pullback fill, the same wick nearly always also reached
  the stop - if the print was real, the order filled AND was stopped out inside that minute (a LOSS). The
  filter turns that into "no fill"; the grade then either finds no later fill (NO_ENTRY - the loss simply
  disappears from every rate) or fills later and can WIN. The header's own principle is that a spike "still
  counts against a STOP - that is the conservative side"; applied to the ENTRY the filter is not conservative,
  because it removes the adverse outcome together with the fill. Whether a one-minute flush on a liquid name
  is a bad tick or a real stop-run cannot be known from bars; the grade resolves that uncertainty in the
  system's favour.
- Failing scenario: Pullback buy-limit 50.00 / stop 49.40 / target 51.20, recorded 10:00:30 with price 50.60;
  quiet 1m bars around 50.60 (median range 0.05). The 10:40 bar is O 50.62 H 50.64 L 49.30 C 50.61 (a real
  flush that recovered within the minute); neighbours' lows are 50.58. `isSpikeLow`: wick 1.31 > 0.30 and
  > 0.76 -> spike -> no fill. Price never trades back to 49.99 -> NO_ENTRY. In real life the resting 50.00
  limit fills and the 49.40 stop triggers in that same minute: about -1.2R after costs that the stats never see.
  Variant: if the price later dips to 49.99 again and then rallies to 51.25, the row is graded a WIN - on an
  order whose real first fill had already been stopped out, so it could not have been open for that rally.
- Suggested fix: read the suspect print against the trade, as the rest of the grader does: when the spike bar
  would fill the limit AND its low is at or below the stop, grade the fill (LOSS, ambiguous) instead of
  skipping it; only skip a spike fill whose wick stays above the stop - and even then only if the trade it
  starts would be profitable (grade both readings and keep the worse). Test: DA-7's scenario with the spike's
  low under the stop -> LOSS, not NO_ENTRY.

### R2G-3 (M) - A settled series that stops short (DA-17) is cached as if it were the whole day, and then used instead of Yahoo for every later grade of that row

- Where: `ui/PortfolioViewModel.kt:7713-7716` (any non-PENDING settled grade -> `db.cacheDayBars(...)`, including a
  `partial` one decided on a series the grader itself judged truncated), `:7666`/`:7673` (`cached(1)` is read
  BEFORE any fetch and a hit is never re-fetched); `data/Db.kt` `createDayBars` doc ("A closed session's bars
  never change").
- Problem: DA-17's rule lets a stop or target inside a truncated settled series still decide the row (correct),
  writing a `partial` detail. But the truncated series is then stored in `dt_bars` exactly like a complete one.
  From then on every grade of that row reads the cut-off copy and never asks Yahoo again: the row can never get
  its grid/hold/run measures (it is "partial" for good even if Yahoo's next answer is the full day), and after
  any future `VERSION` bump the re-grade runs on the same cut-off bars; if the new rules cannot decide from them
  it returns PENDING -> `noBars()` -> the row keeps its old-version verdict and sits in "being re-checked"
  (excluded from the headline) until day 55, then in `legacyExcluded` - a real, decided trade dropped from the
  success rate because a transient short reply was frozen on the phone.
- Failing scenario: settled day, Yahoo's 1m reply ends at 13:40 (transient). The row's stop was hit at 11:05 ->
  LOSS, `partial:true`, bars 09:30-13:40 cached as res 1. Tomorrow Yahoo serves the full day, but nothing asks.
  Next grader bump changes, say, the fill rule so the 11:05 fill is re-examined and the answer needs bars to
  15:50 -> PENDING -> old verdict kept at old version -> not counted, then legacy.
- Suggested fix: cache only a series the grader called complete - e.g. expose `complete`/`partial` on `Graded`
  and write `dt_bars` only when `settled && !fromCache && g.detail?.partial != true` (a partial settled verdict
  can still be written; just do not freeze its bars). Test: a truncated settled WIN leaves `dt_bars` empty.

### R2G-4 (L) - The DA-17 "truncated" test looks at the series AFTER the flat-time cut, so a thin stock with no prints in its last 15 minutes before the flat time is called truncated even when the reply runs to 15:59

- Where: `net/DayTradingGrader.kt:358` (`sorted` drops every bar ending after `flatSec`) then `:364-367`
  (`last = day.lastOrNull()?.t` of that CUT list vs `flatSec - TRUNCATED_SEC`); `ui/PortfolioViewModel.kt:7709-7711`
  (settled + PENDING -> `noBars()` -> DATA_UNAVAILABLE).
- Problem: missing one-minute bars are normal for a thinly traded stock (no trades that minute; `parseBars`
  drops null rows). A reply that plainly reaches the close (prints at 15:52 and 15:58) but has no print between
  15:34 and 15:50 is judged "stops short" because the evidence past the flat time was filtered away first. The
  row becomes DATA_UNAVAILABLE, is re-downloaded once a day for 55 days (always the same answer), then stays
  DATA_UNAVAILABLE for good - a trade that really filled and was open at the flat time never enters the stats.
  Half days are NOT affected (checked: `DayTradingFeatures.flatMs` uses `MarketClock.closeMinuteAt`, so flat is
  12:50 on a 13:00 close and the last kept 1m bar, 12:49, passes). Liquidity floors ($2, 1M avg volume) make
  this rare for the app's own picks; Claude-added picks are not screened by them.
- Failing scenario: 1m series with prints at 15:20, 15:33, 15:52, 15:58; flat 15:50; position filled at 11:00,
  never hit stop or target. `day` ends at 15:33 < 15:35 -> `through` = 15:34 -> not complete -> PENDING ->
  DATA_UNAVAILABLE, although selling at 15:50 at about the 15:33 print was perfectly possible.
- Suggested fix: decide "truncated" from the RAW series: complete when the unfiltered reply has a bar at or after
  `flatSec - TRUNCATED_SEC` (or any bar at/after `flatSec`); a reply reaching past the flat time is the whole
  day whatever its gaps. With that in place `TRUNCATED_SEC` could also be tightened for 1m replies.

