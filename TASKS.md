# TASKS — the current job

## Tj's request, 2026-09-18 (his own words)

> Make it so this app doesn't base any buy sell hold recommendations on stale
> analyst ratings. For example, it doesn't make sense to buy a stock based on
> an analyst rating from 2 months ago. Figure out an accurate, reasonable
> timeframe to keep analyst ratings and a logical way to incorporate them in
> the buy hold sell recommendations, whether it is a time cutoff or a blended
> value prioritizing more recent analyst ratings.
>
> Then since this is opus, review important features of the app such as
> recommendations, whether the day trading result tracker truly tracks actual
> results and tells me an accurate number if I were to trade using the day
> trading system, whether the day trading section is based on sound logic and
> numbers and pulls fresh relevant data from the Internet to base its data on,
> etc. look for overall improvements in code and ui and bug fixes

## Screening (SCREENER.md)

MATCHES on three criteria at once: money-accuracy logic (the buy/hold/sell
scorer is named explicitly in SCREENER.md), a locked architecture decision
(the analyst term is the heaviest single input in `ResearchScore.holding`),
and Tj's own words flagging importance. **Confirmed on `claude-opus-5`
via `get_session` before any edit — cleared to proceed.**

## What the audit found (the "why" behind the boxes below)

1. **The staleness hole is real and total.** `Recommend.build` feeds
   `ResearchScore.holding` only `Fundamentals.consensus`, which comes from
   Yahoo's `financialData` + `recommendationTrend[0]`. Neither carries a
   DATE. Yahoo's "0m" bucket is the *standing* consensus — an analyst who
   rated Buy in March and never revisited still counts in it today. So the
   ±30-point analyst term and the ±12.5 target term (±42.5 of a scale
   centred on 50 — by far the heaviest input) can be driven entirely by
   ratings nobody has touched in a year, and nothing on screen says so.
2. **The dated data already exists and is already fetched.** Yahoo's
   `upgradeDowngradeHistory` carries `epochGradeDate` and a per-firm price
   target, parsed into `AnalystRating(firm, date, toGrade, target)`. It is
   only fetched when the Analysts tab opens, but it is behind a conditional
   GET (`Http.get(conditionalKey = true)`) so a repeat fetch is a bodyless
   304 — the cost of using it once per trading day is one 195 KB fetch per
   symbol, ever, not per day.
3. **Day-trading tracker: the headline number is per-trade, read as total.**
   "If you only traded this system" prints `avgReturnPct` — the average of
   each trade's own return. Over 60 trades a genuine +0.5%/trade compounds
   to roughly +35%, and the card shows "+0.5%". The caption says "average
   return per trade", but the row label does not.
4. **Day-trading tracker assumes perfect fills.** `DayTradingEval.evaluate`
   exits at exactly `stop` and exactly `target`. A stop-market becomes a
   market order when touched; a buy-stop fills at or above the trigger.
   Every one of those errors runs the same direction — the measured result
   is optimistic, which is the one direction a "did this actually work"
   number must not be wrong in.

## The boxes

### Part 1 — analyst-rating staleness (the headline ask)

- [x] `net/RatingRecency.kt`: a pure, testable recency model — full weight
      inside 30 days, exponential decay on a 60-day half-life after that,
      rescaled to reach exactly zero at 240 days (no cliff). Dedupe to each
      firm's LATEST action; drop a firm silent past the cutoff entirely.
      *(Tested: `AnalystRecencyTest`, the decay/cutoff/dedupe groups.)*
- [x] Recency-weighted consensus AND recency-weighted price target built
      from the dated ratings, replacing the undated ones in the scorer when
      the dated ratings are there. *(`RatingRecency.panel`.)*
- [x] `ResearchScore.holding` takes it: the analyst lean is decay-weighted,
      scaled by fresh-equivalent breadth AND by how current the panel is at
      all, so more stale coverage cannot buy back freshness.
      *(Tested: "breadth alone cannot buy back currency".)*
- [x] When there are NO dated ratings the undated consensus is capped by
      `undatedTrust`, sharpened for free off the four monthly
      `recommendationTrend` snapshots, and the reason line says which it is.
- [x] `loadRecommendation` gets the dated ratings before it scores, once a
      day, behind the existing TTL + failure backoff.
- [x] Freshness shown on screen in the recommendation popup — panel age,
      how many ratings still count, how many were dropped, the discount.
- [x] Tests: `AnalystRecencyTest`, 27 cases including the headline one —
      the same unanimous BUY panel scored fresh and scored five months old,
      with only the dates different, is a BUY and then is not.

### Part 2 — day-trading result tracker (accuracy)

- [x] Cumulative figures added: `totalReturnPct` (fixed equal stake) and
      `accountReturnPct` (sum of R-multiples at the app's own 1%-per-trade
      sizing) — the second is the one that actually answers "how much would
      my portfolio be up". The per-trade average stays, as what it is.
- [x] `DayTradingEval.Costs` models entry and stop slippage in basis points
      instead of assuming perfect fills; the card shows gross AND net so the
      size of the assumption is visible.
- [x] Sessions and trade counts on the card.
- [x] Tests in `DayTradingEvalTest` — including that costs can only ever
      make a result worse, and that a stopless row cannot produce an
      infinite R-multiple.

### Part 3 — review pass

- [x] **Bug fixed: expired intraday history was re-fetched forever.**
      `DATA_UNAVAILABLE` is deliberately not `isFinal`, so every press of
      "re-check success rate" re-requested a session of 5-minute bars for
      every recommendation ever recorded — including those long past
      Yahoo's ~60-day minute-level retention, where the answer is empty
      every time. Cost rose with the age of the log and bought nothing.
      Gated on `DayTradingEval.intradayStillAvailable`.
- [x] **UI honesty: "vs today's $X" in the recommendation popup** was the
      price at the moment the verdict was computed — first thing in the
      morning, usually — printed next to a live header showing something
      else. Now names the time it was computed at.
- [x] **The Research "Best" list's analyst line now says it is undated.**
      That scorer (`ResearchScore.withAnalyst`) runs over hundreds of
      screened candidates off Nasdaq's consensus endpoint, which carries no
      publication dates, and the dated history is a ~195 KB per-symbol
      payload — affordable once a day for one holding, not at all for a
      screen. Kept at its existing weight (a third of a ranking, not a
      verdict) and labelled, rather than given a fix that does not exist.
- [x] Day-trading data freshness confirmed sound: the candidate universe is
      nine Yahoo screens plus WSB and news on a 30-minute TTL, the live
      technicals (VWAP, opening range, ATR, pivots) re-fetch every 30s for
      visible rows while the tab is open, and every volume threshold is
      paced against the session rather than compared to a whole-day average.

## Known limitations, recorded rather than fixed

- **The Day Trading candidate list can be up to 30 minutes old.** A stock
  that comes into play at 10:05 appears at the next rebuild. Shortening it
  means re-running ~18 requests per rebuild against free feeds the app is
  deliberately careful with, and pull-to-refresh already forces it. Left
  alone on purpose; revisit only if Tj asks for the latency.
- **The success-rate log has a rolling ~60-day horizon**, because that is
  how far back Yahoo serves 5-minute bars. Older rows stay in the log and
  are counted in "recommendations recorded", but can never be resolved.
- **A target exit is still assumed to fill.** `Costs` charges nothing on a
  target because a resting limit at a price that traded gets its price —
  but a level only TICKED may not have filled a real order at all, and no
  bar data can say. The one optimistic corner left standing.
