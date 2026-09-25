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

### R2G-5 (M) - The DA-1 settled re-grade CAN change a final verdict (the code says it cannot), because the bad-print ruler is the median bar of whatever part of the day existed when the check ran - and one direction erases a real mid-session LOSS

- Where: `net/DayTradingGrader.kt:475-486` (`needsSettledRegrade` doc: "its verdict cannot change"), `:360`
  (`median = medianRange(day)` over every bar present at grading time), `:310-318` / `:214-222` (both spike
  tests scale with that median); `ui/PortfolioViewModel.kt:7714` (the settled re-grade overwrites outcome,
  exit price and detail with no check against the stored verdict).
- Problem: mid-session the median is taken over the morning only (the volatile open, large 1-minute ranges);
  after the settle it is taken over the whole day (quiet midday bars, a much smaller median). A wick that was
  "real" at 10:10 can therefore be a "bad print" at 16:20. For a target that only makes the settled grade
  stricter; but for a buy-limit's fill (DA-7's `isSpikeLow`) it deletes the fill - and with it a LOSS the
  mid-session grade had already recorded. Yahoo also revises bars after the close, so a changed verdict is
  possible in any case; nothing notices or records that it happened.
- Failing scenario: $50 in-play stock, 1m ranges ~0.20 from 09:30 to 10:10, ~0.04 after. Pullback buy-limit
  50.00 / stop 49.40, recorded 09:50. The 10:05 bar is O 50.35 H 50.40 L 49.30 C 50.30, neighbours' lows above
  49.99. 10:10 auto-check: median 0.20, wick 1.00 < 6 x 0.20 -> not a spike -> filled at 50.00, stopped at 49.40
  in the same bar -> LOSS (`partial:true`). 16:20+ re-grade: median ~0.05, wick 1.00 > 0.30 and > 0.75 -> spike
  -> no fill; price never returns to 49.99 -> the row is rewritten NO_ENTRY and the loss leaves the stats.
- Suggested fix: make the verdict independent of when the check ran - measure the spike ruler over a fixed,
  causal window (e.g. the 30 bars before bar i, or the session up to i) so the mid-session and settled grades
  judge a print identically; and in the settled re-grade keep the stored outcome/fill unless the new grade is
  strictly more conservative (log any change), since its job (DA-1) is only to fill in grid/hold/run. Fixing
  R2G-2 removes the loss-erasing direction outright.

### R2G-6 (L) - DA-8's proxy open lands on the FAVOURABLE edge of a gapped bar (best buy-stop fill, best stop exit), unlike the first-bar rule and the header's "read against the trade"

- Where: `net/DayTradingGrader.kt:295-301` (`withKnownOpens`: `prevClose.coerceIn(low, high)`), used by the fill at
  `:383` (`openKnown && b.open >= entry -> b.open`) and the gap-stop at `:257-258`; the test
  `DayTradingGraderTest` "DA-8 with no open, a gap is priced where the bar really traded" pins this.
- Problem: when the open is unknown and the previous close lies OUTSIDE the bar's range (a real gap - a halt,
  news), coercing it clamps to the edge nearest the previous close: for a gap up that is the bar's LOW (the
  cheapest a buy-stop could possibly have filled), for a gap down the bar's HIGH (the best a stop could have
  sold). The true open is anywhere in [low, high]. The day's first bar with no open is read against the trade
  (fill at the high, `ambiguous = true`); these are read for it and not flagged. Every price is one the bar did
  trade at, so DA-8's letter holds, but its intent ("never flatter the system") does not.
- Failing scenario (from the test itself): buy-stop 10.50, previous close 10.40, next bar H 10.90 L 10.70 with no
  open -> fill 10.70; the real open could be 10.90 (20 cents = 0.4R worse on a 0.50 risk). Gap down through
  the 10.00 stop to H 9.80 L 9.60 -> exit 9.80; could be 9.60. Rare (Yahoo seldom sends h/l/c without an open),
  hence L.
- Suggested fix: in `withKnownOpens`, when the previous close is inside [low, high] keep it (continuous trading);
  when it is outside, leave the open NaN and let `grade` price it against the trade (buy-stop fill at the high,
  gap-stop exit at the low) with `ambiguous = true`, the rule the first bar already uses.

### R2G-7 (L) - "Came back empty" is remembered only in memory for DECIDED rows, so a partial or old-version row with a short/missing series is re-downloaded after every process start

- Where: `ui/PortfolioViewModel.kt:7653-7656` (`noBars()`: decided -> `dtEmptyAnswers[id] = now`, nothing
  persisted), `:7510-7511`, `:1563`; contrast `:1565` (an undecided row's DATA_UNAVAILABLE retry is gated on the
  persisted `outcome_evaluated_at`).
- Problem: for a partial row whose settled re-grade keeps returning PENDING (truncated, R2G-3/R2G-4) or EMPTY,
  and for an old-version decided row whose symbol Yahoo no longer serves, the only back-off is the in-memory
  map. Android kills the process whenever the app is in the background, so in practice every cold start with
  the Day Trading tab open re-asks each such row (a 1m and then a 5m request each), for up to 55 days. It is
  bounded (the auto run stops at the first failure and takes at most 180 rows), so this is waste, not a storm.
- Suggested fix: persist the back-off for decided rows too - e.g. a small `eval_retry_at` column, or store the
  attempt time inside `eval_detail` ("tried":ms) and test it in `dayTradingRowsNeedingGrade` (do NOT touch
  `outcome_evaluated_at`: `needsSettledRegrade` reads it).

### R2G-8 (M) - DA-19's exclusion covers two of E5's three "skip" cases; an old row logged when the price was already THROUGH its buy price is re-graded as the opposite order type

- Where: `net/DayTradingEval.kt:142-144` (`notTradeableOldRow` tests only `stop < price < target`), `:273-276`
  (`entryRises` reads direction from `priceAtRecommendation` whenever it is set, never from the setup);
  `ui/PortfolioViewModel.kt:1379-1384` (`planWaiting` - the E5 gate new rows pass - also requires the price on
  the near side of the entry, using `ResearchScore.planEntryRises`, which knows the app's three setups);
  `DayTradingGrader.RULES_TEXT` ("... or already through the buy price) are never recorded").
- Problem: rows logged before 2026-09-24c (blank `features`) had no near-side test, and their
  `priceAtRecommendation` was the logging price (the R2-3 comment in `captureDayTradingRecommendations` records
  exactly this bug for Claude pullbacks: "scored as a breakout - the opposite of what the card told Tj"; the
  mirror case is untouched for old rows). A Breakout / VWAP-reclaim row logged with the price already above
  its entry (a Claude breakout logged after it broke; before D-M3 on 09-22, also an app plan still drawn during a
  decline tick) passes `notTradeableOldRow` and is graded as a BUY-LIMIT at the entry - an order the card never
  showed. The prompt's own rules text says such rows are never recorded.
- Failing scenario: old Claude row, setup "Breakout", entry 20.00 / stop 19.50 / target 21.00,
  `priceAtRecommendation` 20.30, no features. Kept (19.50 < 20.30 < 21.00). `entryRises` -> 20.00 > 20.30 false
  -> buy-limit. The stock dips to 19.99 at 11:00 and rallies to 21.01 at 14:00 -> WIN, +~1.9R, counted - for
  a breakout the card showed as a buy-stop already triggered (a chase it told Tj not to make).
- Suggested fix: extend `notTradeableOldRow` with the part of the near-side test the row can prove: for a blank-
  features row whose setup is one of the app's names (`planEntryRises(setup, entry, 0.0)` non-null), require
  the price on that side of the entry (below it for Breakout / VWAP reclaim, above it for Pullback); a free-text
  Claude setup cannot be checked and stays as is (say so). Test: the scenario above -> `oldSkipped`, not graded.
  Also align the card/prompt wording, which today says "past the target or under the stop" while the rule also
  drops rows with no recorded price (`ResearchScreen.kt:1425-1430`, `EngineTuningPrompt.kt` data header).

