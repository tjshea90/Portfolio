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

- [ ] `net/RatingRecency.kt`: a pure, testable recency model — full weight
      inside 30 days, exponential decay on a 60-day half-life after that,
      rescaled to reach exactly zero at 240 days (no cliff). Dedupe to each
      firm's LATEST action; drop a firm silent past the cutoff entirely.
- [ ] Recency-weighted consensus AND recency-weighted price target built
      from the dated ratings, replacing the undated ones in the scorer when
      the dated ratings are there.
- [ ] `ResearchScore.holding` takes it: the analyst lean is decay-weighted,
      scaled by fresh-equivalent breadth and by how current the panel is at
      all. A panel nobody has revisited in months cannot reach full weight.
- [ ] When there are NO dated ratings the undated consensus is capped, not
      trusted at face value, and the reason line says which it is.
- [ ] `loadRecommendation` gets the dated ratings before it scores (once a
      day, the same day-key gate the recommendation already has).
- [ ] Freshness shown on screen — age of the panel, how many ratings still
      count, and the discount applied — in the recommendation popup.
- [ ] Tests: `AnalystRecencyTest.kt` pins the weights, the cutoff, the
      per-firm dedupe and the "stale panel cannot BUY on its own" case.

### Part 2 — day-trading result tracker (accuracy)

- [ ] Add the CUMULATIVE figure next to the per-trade average, so "how much
      would my portfolio be up" is answered by a number that means it.
- [ ] Model entry/stop slippage instead of assuming perfect fills, and say
      on screen what was assumed.
- [ ] Say how many sessions and how many picks per session the numbers come
      from — an average per trade means nothing without the trade count.
- [ ] Tests for all of it.

### Part 3 — review pass

- [ ] Day-trading data freshness and soundness: confirm the numbers are
      pulled fresh and that thresholds are paced against the session.
- [ ] Code, UI and bug-fix sweep; record anything found but not fixed.

