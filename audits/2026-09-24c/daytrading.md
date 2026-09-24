# 2026-09-24c — read-only audit: day-trading grading, logging, stats, tuning loop

Auditor: subagent (read-only). Scope: DayTradingGrader, DayTradingEval, DayTradingFeatures,
DayTradingParams, ResearchScore (day-trading parts), DayTradingTechnicals, MarketClock,
EngineTuning, EngineTuningPrompt, SharedAnswer, DayTradingBridge, PortfolioViewModel (day-trading
paths), Db (day_trading_log), DayTradingLog, and the tests.

Severity: H = wrong numbers / credits a trade that could not happen / crash; M = real but
narrower; L = minor.

(Work in progress — findings appended as confirmed.)

## Findings

NOTE: several files in scope were being edited while this audit ran (mtimes 20:10-20:23 on
2026-09-24: DayTradingGrader.kt gained the "gap-target" branch mid-audit). Every finding below was
re-checked against the file as it stood when the finding was written; line numbers are from that
read and may drift by a few lines.

### DA-1 (H) — A mid-session WIN/LOSS is written as FINAL with its grid / holdR / runR computed from a partial day, and is never re-graded

- Where: `ui/PortfolioViewModel.kt:7489-7493` (`resolveOneDayTradingEntry`: `grade(..., decidedThroughSec = if (settled) MAX else 0L)` then writes `g.detail` with `G.VERSION`);
  `net/DayTradingGrader.kt:333` (`hold = runPosition(..., complete = true, ...)`), `:343` (every grid cell `complete = true`), `:357` (`mfeFlatR = mfeTo(day.size - 1)`);
  `ui/PortfolioViewModel.kt:1479-1487` (`dayTradingRowsNeedingGrade` skips any final row with `evalVersion == VERSION`).
- Problem: the verdict (target / stop) is legitimately final mid-session, but `grade()` also builds the
  counterfactual grid, `holdR` and `mfeFlatR` by running every variant with `complete = true` over the bars
  that exist *so far*. A variant still open at the last available bar is "closed at the flat time" at that
  bar's close. Because the row is then final at the current grader version, nothing ever re-grades it after
  the session settles. The tab's own auto-check (`evaluateDayTradingLog(auto = true)`, every 15 min while the
  tab is open) makes this the COMMON path for today's trades, not an edge case.
- Failing scenario: breakout logged 10:00 (entry 10.50 / stop 10.00 / target 11.50). Target traded through
  at 10:40. Tab opened 11:00 -> auto-eval -> WIN, `eval_version = 2`, detail written. The price then runs to
  13.00 by 15:50. Stored: grid "none" cell and the 3R/4R/6R cells = the 11:00 close (~11.6), `holdR` ~= +2.2R,
  `runR` ~= 2.3R. Truth: "none" ~= +5R, 4R/6R targets hit. The tuning prompt's grid, `runR` and `holdR`
  columns therefore systematically under-state every "let it run / wider target" variant for exactly the
  trades that won early - biasing Claude toward the plan's own target and closer profit-takes. This is the
  evidence rule 2 ("accurate tracking data") says the tuning must rest on.
- Fix: when `!settled` and the verdict is WIN/LOSS, either (a) write the verdict with a detail marked
  `partial: true` (no grid/hold/mfeFlat) and have `dayTradingRowsNeedingGrade` also return final rows whose
  detail is partial once `sessionSettled` is true; or (b) keep writing `eval_version = VERSION - 1`-style
  "provisional" for unsettled days so they are re-graded once after the settle grace. Add a test: grade a row
  with bars to 11:00 (decidedThrough 0), then with the full day, and assert the stored detail is replaced.

### DA-2 (M) — `filter.minScore` is bypassed for exactly the lowest-scored rows (score 0), and is applied to last tick's score

- Where: `ui/PortfolioViewModel.kt:1193` `score = if (row.score > 0) row.score else -1`;
  `net/ResearchScore.kt:767` `if (p.minScoreForPlan > 0 && score in 0 until p.minScoreForPlan)`.
- Problem: the comment says "a score of 0 is a row the app never scored (a Claude-added pick)", but
  `row.score` is the BLENDED score `likelihood x confidence / 100` (`Research.toDayTradingRow`,
  `scoreDayTradingRow`), and confidence is 0 whenever none of the five checks confirm - so an app-scored row
  with real likelihood routinely has `score == 0`. It is passed as -1 ("unknown") and the filter is skipped.
  Separately the plan is computed in `mergeDayTradingTech` BEFORE `scoreDayTradingRow` re-scores the row, so
  the filter reads the previous tick's score while `features.score` logs the new one.
- Failing scenario: tuned `filter.minScore = 20`. Row A: likelihood 45, confidence 0 -> score 0 -> filter
  skipped -> plan -> logged as a tuned-engine plan. Row B: likelihood 50, confidence 20 -> score 10 -> declined.
  The filter lets through the rows it most exists to stop, and the prompt's "By blended score" table will show
  logged plans below the minimum the engine claims to enforce.
- Fix: treat "unscored" as `row.dtLikelihood <= 0` (that is what a Claude-added pick has), i.e.
  `score = if (row.dtLikelihood > 0) row.score else -1`; and compute the blended score for this tick before
  calling `planInternal` (or re-check the filter after `scoreDayTradingRow`) so the gate and the logged
  feature are the same number. Test: minScore 20, row with dtLikelihood 45 / dtConfidence 0 -> no plan.

### DA-3 (M) — The per-group sample rule is satisfied by citing `basis: "all"`; a setup/level switch can rest on a handful of trades

- Where: `net/EngineTuning.kt:405-419` (`val groupN = ev.count(c.basis)`; `groupN < MIN_GROUP_FOR_CHANGE`,
  `isSwitch && groupN < MIN_GROUP_FOR_SWITCH`).
- Problem: the group whose size is checked is whatever string Claude wrote in `basis`; it is re-counted, but
  never tied to the parameter being changed. `"all"` always returns the full total, so the "switches need 30+
  trades in their group" rule (DESIGN B, rule 1) reduces to "30+ trades anywhere".
- Failing scenario: 80 graded app trades (MEDIUM tier). VWAP-reclaim plans have 4 decided trades. Answer:
  `{"param":"setup.reclaim.enabled","from":1,"to":0,"basis":"all"}` -> `groupN = 80` -> ACCEPTED: a whole
  setup is switched off on 4 trades. Same for `level.r2.enabled` 1 -> 0 citing "all" when 2 trades used R2, or
  `setup.pullback.minRiskAtrs` resting on the Breakout-dominated total.
- Fix: derive the evidence group from the key, and use the smaller of (derived, cited):
  `setup.<k>.*` -> `setup:<name>`; `level.<k>.enabled` -> trades whose `features.lvl` is that level's label
  (needs a key -> label map; note live and pre-market labels differ, "the prior session's high" vs "the last
  session's high"); `time.avoidMiddayLull` -> `time:Midday`; `time.earliestEntryMinutes` -> `time:First hour`;
  everything else -> `all`. Test: reclaim switch-off with basis "all" and 4 reclaim trades -> REFUSED.

### DA-4 (M) — A "switch" has no size limit: an off filter / per-setup override can be switched on at its most extreme value

- Where: `net/EngineTuning.kt:403-433` (`isSwitch = BOOL || (offAllowed && (current == 0.0 || c.to == 0.0))`;
  the step limit runs only `if (!isSwitch)`).
- Problem: switching an optional filter ON is treated as a pure on/off decision, so the value it is switched on
  AT is only bounds-checked. Rule 1 ("no major changes on small samples") is then enforced for moving
  `stop.minRiskAtrs` 1.5 -> 2.5 (LIMITED to 2.2 at MEDIUM: 20% of its 3.5 range) but not for switching on
  `setup.breakout.minRiskAtrs = 2.5`, which moves every breakout's stop floor 1.5 -> 2.5 ATR (+67%) in one step
  (passes `consistent()`: 2.5 <= the global 2.5 ceiling).
- Failing scenario (MEDIUM, 75+ trades, basis "all" per DA-3): `filter.minScore` 0 -> 80 (blocks nearly every
  plan), `time.earliestEntryMinutes` 0 -> 120 (no plan before 11:30), `filter.minRewardRisk` 0 -> 4.0,
  `target.capR` 0 -> 0.5 (every target at +0.5R), `setup.breakout.minRiskAtrs` 0 -> 2.5,
  `setup.breakout.maxRiskAtrs` 0 -> 1.5 (ceiling 2.5 -> 1.5, every breakout stop pinned at 1.5 ATR). All ACCEPTED
  unchanged.
- Fix: when turning an `offAllowed` param ON, limit the value to the tier's step measured from the least
  restrictive end (e.g. `minScore <= 1 + step`, `minRewardRisk <= 0.5 + step`, `earliestEntryMinutes <= 1 + step`,
  `capR >= 10 - step`), and for a per-setup override measure the step from the global value it replaces
  (`setup.X.maxRiskAtrs` within `maxStep*range` of `stop.maxRiskAtrs`). Test each case at MEDIUM.

### DA-5 (M) — An answer without `basedOn` / `from` skips both staleness checks

- Where: `net/EngineTuning.kt:369` (`proposal.basedOnVersion != null && ...`), `:396` (`c.from != null && ...`).
- Problem: both "is this answer about the engine as it is now" checks are skipped when the field is absent
  (or unparseable: `"from": "n/a"` -> null). The prompt tells Claude both are required, but the app does not.
- Failing scenario: Tj applies round 1 (v1), later undoes it (v2). He re-shares an older answer file (or a
  Claude reply that dropped `basedOn` and `from`) -> no blocker, no `from` mismatch -> the same changes are
  re-applied against v2 as though they had been reviewed against it. (The 20-trades-since-last-apply cooldown
  limits the timing, not the staleness.)
- Fix: refuse the whole answer when `basedOn.engineVersion` is missing ("this answer does not say which
  engine it was written for - make a new prompt"), and refuse a change whose `from` is missing or non-numeric.

### DA-6 (M) — The tuning objective (net R) is not the account's profit when the 25% position cap binds - which is most trades; the grid text says the opposite

- Where: `net/EngineTuningPrompt.kt:130-133` (goal: "measured by the average net R per trade"),
  `:311-313` (grid: "R is measured in each variant's OWN risk, so cells are comparable under the app's fixed
  1%-risk sizing"); sizing in `net/ResearchScore.kt:1326-1329` (`min(0.01/risk, 0.25/entry)`);
  `net/DayTradingEval.kt:413-423` itself notes the cap is what usually sizes a day trade.
- Problem: shares = min(1% / risk, 25% / entry). The cap binds whenever the stop is under 4% below the entry,
  i.e. essentially every plan (1.5-2.5 five-minute ATRs). With the cap binding, the account result of a trade is
  `0.25 x (net % move)` and does NOT scale with the stop distance, but R = move / risk does. Shrinking stops
  therefore raises "average net R" (and every cell in the 0.5R/0.75R grid rows) with no change - or a loss - in
  money. The prompt tells Claude to maximise exactly that number and that the grid cells are comparable under
  1% sizing, which is false under the cap.
- Failing scenario: entry $50, stop $49.40 (risk $0.60, 1.2%): size = 0.005 sh/$ (cap). A +$0.60 exit is
  +1.0R and +0.30% of the account. Claude lowers `stop.minRiskAtrs` so risk becomes $0.40: size is still
  0.005 sh/$ (cap), the same +$0.60 exit is +1.5R and still +0.30%. More stop-outs at the tighter stop can make
  the account figure worse while "avg net R" improves - the loop would report success on a change that lost money.
- Fix: make the objective the account return per trade (`dayTradeSharesPerEquity(entry, stop) x (got - paid)`,
  already computed in `stats`) in the tables, per-version results and grid (each grid cell sized with its own
  stop through the same capped formula), state in the prompt that the 25% cap binds for stops under 4% of
  price, and keep R as a secondary column.

### DA-7 (M) — The bad-print filter protects only the target; a lone bad LOW print can fill a pullback's buy-limit that never really filled, and a later real rally is credited as a WIN

- Where: `net/DayTradingGrader.kt:297-303` (buy-limit entry: `b.low <= entry - tk || opensBelow`, no spike
  test) vs `:242-243` (spike test on the target only). DESIGN E7 / `RULES_TEXT` say "a single stray price far
  from every trade around it is ignored" without limiting that to the target.
- Problem: the same erroneous tick the target filter rejects (a wick > 6 median ranges and > 1.5% of price that
  no neighbour comes near) opens a position at a limit that never traded. Everything after it is then graded
  as a real trade. Unlike a spike HIGH on a buy-stop entry (which only ever hurts the result), this one can
  turn a NO_ENTRY into a WIN.
- Failing scenario: Pullback, entry 50.00, stop 49.40, target 51.20, price 50.60. 1-minute bar
  (o 50.62, h 50.64, l 49.80, c 50.61), median range 0.05, neighbours' lows ~50.55. Graded: filled at 50.00;
  the stock drifts up to 51.25 at 14:00 -> WIN +~1R. Reality: the limit never filled -> NO_ENTRY.
- Fix: add `isSpikeLow(bars, i, level, median)` (mirror of `isSpikeHigh`: lower wick below the body, neither
  neighbour's low reaches `level`) and skip such a bar as a buy-limit fill (continue scanning). Keep spikes
  counting for the STOP (the conservative side, as now). Test: the scenario above -> NO_ENTRY; a real flush with a
  neighbour also through the entry -> filled.

### DA-8 (M, rare trigger) — When a bar's open is unknown, fills and stop exits are priced at levels the bar never traded

- Where: `net/DayTradingEval.kt:134-135` (`parseBars` sets `open = NaN` when the open is missing OR outside
  the bar's own [low, high]); `net/DayTradingGrader.kt:294` (buy-stop `fill = if (open finite && open >= entry) open else entry`),
  `:240` (gap-stop branch requires a finite open), `:244-247` (stop exit priced at exactly `stop`).
- Problem: all the E2 gap logic hangs off the open. With a valid open every fill/exit price is provably
  reachable; with `open = NaN` the code falls back to the idealised level even when the bar's whole range is
  on the far side of it:
  - buy-stop: a bar with low > entry (gapped completely past the trigger) fills at `entry`;
  - stop: a later bar with high < stop (gapped completely through) exits at `stop`.
  Both are prices no trade printed at - the optimistic direction.
- Failing scenario: entry 10.50 / stop 10.00. Bar 10:31 arrives with an open Yahoo sent as 10.95 while
  high = 10.90 (inconsistent OHLC - `parseBars` drops it to NaN), low 10.70. Graded fill 10.50; every trade in
  that minute was >= 10.70, so +0.20/share (+0.4R) is phantom. Mirror case on the stop: bar h 9.80 / l 9.60,
  open unknown -> exit 10.00 instead of <= 9.80.
- Fix: bound by the bar's own range when the open is unknown: buy-stop `fill = max(entry, b.low)`; buy-limit
  `fill = min(entry, b.high)` when `b.high < entry`; stop exit `min(stop, b.high)`; and in `runPosition` treat
  `!first && b.high < stop` as a gap-stop at `b.high` when `open` is NaN. Test with NaN-open bars on both sides.

### DA-9 (M) — The card can call 2-19 trades "a real edge so far, with 95% confidence" next to "Too few trades to judge"

- Where: `data/DayTradingLog.kt:220-225` (`edgeVerdict` needs only `entriesTriggered >= 2`);
  `ui/ResearchScreen.kt:1493-1499` (prints "a real edge so far, with 95% confidence." / "losing money on
  average, with 95% confidence.").
- Problem: E10's purpose was that a handful of trades cannot read like a verdict. A Student-t interval on a
  few heavily skewed R-multiples can easily exclude zero: four wins at +1.8, +2.1, +1.5, +2.4R give
  mean 1.95, sd 0.39, t(3) = 3.18 -> [+1.33, +2.57] -> "positive". The card then shows, one line apart,
  "4 graded trades - Too few trades to judge" (red) and "a real edge so far, with 95% confidence".
- Fix: `edgeVerdict` returns "" (and the card says "not enough trades to say") below
  `SAMPLE_TIERS[0].first` (20); optionally also below 20 hide the interval, or use a bootstrap. Same guard for
  any per-group interval the prompt prints (it already prints the range, so a note there is enough).

### DA-10 (L) — Claude plans are graded against the app engine's lull cancel-time, which the Claude card never shows

- Where: `ui/PortfolioViewModel.kt:7299-7302` (`DayTradingFeatures.build(..., r.planByClaude, ..., engineParams, ...)`
  for Claude rows too); `net/DayTradingFeatures.kt:46-54,116` (deadline = lull start when `avoidMiddayLull` and
  recorded before 11:30); `ui/PortfolioViewModel.kt:1296-1300` (a standing Claude plan's `planWait` is always "").
- Problem: with `time.avoidMiddayLull = 1`, a Claude plan recorded at 10:45 gets `deadline = 11:30`, but its
  card never turns to "not yet" at 11:30 (only the app's own plans carry `waitReason`). Following the card, Tj's
  order stays live; a fill at 12:10 that wins or loses is graded NO_ENTRY. Only Claude plans (which do not feed
  the tuning evidence) and only on a tuned engine - hence L - but the grade is not "what the card said".
- Fix: for `planByClaude` rows build the deadline from `lastEntryMinutes` only (the rule the Claude card does
  enforce through `tooLateToStart`), i.e. pass `p.with(mapOf(AVOID_LULL to 0.0))` for Claude rows.

### DA-11 (L) — Engine version bookkeeping: identical engines labelled differently, versions reused, a corrupt store hides a tuned history

- Where: `net/DayTradingEval.kt:619-622` (`engineLabel`), `net/EngineTuning.kt:118-127` (`load`),
  `:149-159` (`revert` = `version + 1`), `data/Db.kt:2016-2030` (Replace restore overwrites `dt_engine*`
  while `day_trading_log` is additive).
- Problems: (a) after "Revert to original" the engine is DEFAULTS but plans are labelled `v3` and the card's
  breakdown calls them "Tuned engine v3"; the prompt's per-version table lists v3 as if it were a new engine.
  (b) A Replace restore of an older backup rolls `dt_engine`/history back to, say, v2 while the device's log
  keeps rows labelled v3/v4; the next apply produces a second "v3" with different params, and
  `engine:v3` evidence / the per-version table then mix two engines. (c) If `dt_engine` is unreadable but the
  history is intact, `load` returns DEFAULTS with the history's version: the app silently runs the original,
  and "Revert to original" is disabled (`isOriginal`) while the history says tuned.
- Fix: label by params, not only by counter (e.g. "v3 = original" when `params.isDefault`; or record a short
  params hash in `features`); on load, `version = max(stored, history max, max engine label in the log)`; when
  the engine JSON is unreadable fall back to `history.last().paramsAfter`.

### DA-12 (L) — Re-grading an old verdict whose bars answer "empty" overwrites it with DATA_UNAVAILABLE

- Where: `ui/PortfolioViewModel.kt:7468-7473`.
- Problem: a stale-version final row (e.g. WIN, `eval_version = 0`, day 40 days ago) is re-queued (E9). If both
  the 1m and 5m requests are ANSWERED with no bars (404 for a renamed/delisted ticker, or Yahoo's 5m window
  shorter than the app's 55-day assumption), `setDayTradingOutcome(id, DATA_UNAVAILABLE, null)` replaces the
  old verdict and exit price. The DESIGN (E9) says such a row "keeps its old verdict ... flagged legacy".
- Fix: when `isFinal(entry.outcome) && entry.evalVersion < VERSION` and no bars come back, leave the row
  untouched (it becomes `legacyExcluded` once past the retention window).

### DA-13 (L) — Entry-deadline edge: a plan logged in the last minute before the cut-off can never fill

- Where: `net/ResearchScore.kt:651-652` (`tooLateToStart = minutesLeft in 1 until lastEntry`, integer minutes),
  `net/MarketClock.kt:256-262`, `net/DayTradingGrader.kt:281,290`.
- Problem: at 15:30:40 `minutesLeft` = 30, so the card still says startable and the row is logged, but its
  `deadline` (15:30:00) has already passed; at 15:29:10 the only eligible 1m bar would be 15:29 (excluded - it
  starts before `recordedAt`) and the 15:30 bar is at the deadline. Both are graded NO_ENTRY with certainty.
  Conservative (never a win), but they inflate "never filled before their cut-off" and the fill-rate tables
  Claude is told to use ("a setup ... that seldom fills wastes attention"). Related: on the 5-minute fallback,
  a tuned `lastEntryMinutes`/`flatBeforeCloseMinutes` that is not a multiple of 5 lets the bar that straddles
  the cut-off fill (or keep holding) up to 4 minutes past it - optimistic, but only for rows > 29 days old.
- Fix: in `loggableDayTradingRows` also require `now < deadline - 60 s` (compute from ms, not whole minutes);
  on 5m bars require `b.t + 300 <= entryDeadlineSec` for a fill and `b.t + 300 <= flatSec` for a held bar.

### DA-14 (L) — `algorithm()` text vs the code: four statements that are not what the engine does

- Where: `net/EngineTuningPrompt.kt:350-365, 366-367, 379`; `net/DayTradingParams.kt:222`; code in
  `net/ResearchScore.kt:454` and `:1677` (`rangePos > 0.85`), `net/DayTradingTechnicals.kt:422-433`
  (`atr14` needs >= 5 true ranges), `net/DayTradingFeatures.kt:79-91`, `net/Research.kt:630-641`.
  1. "within 15% of the 52-week high" - the code tests `rangePos > 0.85`, the top 15% of the 52-week RANGE.
     52-week range 50-100: the code needs price > 92.50 (7.5% under the high); the text says >= 85. The same
     wrong sentence is in the `score.nearHighPoints` spec doc and the reason line.
  2. "`vol` = the intraday ATR, or daily ATR x f before any intraday bars exist" - the intraday ATR is 0 until
     SIX regular-session 5-minute bars exist (~09:55), so every plan in the first ~25 minutes is sized on
     `atr14 x 0.10` even though intraday bars exist. Consequence for the data: `DayTradingFeatures.build` reads
     `row.atrIntraday`, which is 0 then, so `atr`, `atrPct`, `riskAtr`, `trigAtr` and `vwapAtr` are simply absent
     for those plans and they silently drop out of the "stop width", "trigger distance" and "price vs VWAP"
     tables - exactly the opening trades `time.earliestEntryMinutes` would be tuned on. Log the `vol` actually
     used (and whether it was the daily fallback) and compute the ATR features from it.
  3. "no plan when the score is below minScore" - true only for rows with a non-zero blended score from the
     previous tick (DA-2).
  4. Minor: the screener also requires market cap >= $50M; "Before the open" is really "whenever the session
     is not live" (evenings too).
- Fix: correct the sentences (1, 2, 4), fix DA-2, and add the ATR-source feature.

### DA-15 (L) — Nothing keeps `time.lastEntryMinutes` above `time.flatBeforeCloseMinutes`

- Where: `net/EngineTuning.kt` `consistent()` (checks only stop floors vs ceilings).
- Problem: bounds allow lastEntry 10..120 and flat 5..60 independently. One LARGE-tier review can move
  `flatBeforeCloseMinutes` 10 -> 29 (35% of 55 = 19.25, floored) and `lastEntryMinutes` 30 -> 10 (within 38.5).
  Then plans are startable (and logged) from 15:31 to 15:50 while the card's own exit line says "be flat by
  15:31" - an instruction already in the past. The grader handles it (no bar before flat -> NO_ENTRY), but the
  card is self-contradictory and the log fills with guaranteed NO_ENTRY rows.
- Fix: in `consistent()` require `lastEntryMinutes >= flatBeforeCloseMinutes + 15` (or similar), and state the
  constraint in the prompt's rules.

### DA-16 (L) — A value with more than 3 decimals can never be changed again: the prompt shows it rounded, and `from` must match to 1e-6

- Where: `net/EngineTuning.kt:355` (`fmt` = 3 decimals, HALF_UP) used by `describe` for the prompt's parameter
  table and algorithm text; `:400` (`abs(c.from - current) > 1e-6` -> REFUSED).
- Problem: an accepted value is stored exactly as Claude wrote it (e.g. `"to": 2.3333`), and LIMITED steps can
  produce 4 decimals themselves (`vol.intradayAtrFromDaily` 0.10 + 0.35 x 0.25 = 0.1875 at the LARGE tier). The
  next prompt shows "0.188"/"2.333"; Claude copies that as `from`; the review refuses it with the message
  "Claude read it as 0.188, but it is 0.188 now - the prompt is out of date" - every round, forever. Fails safe
  (no wrong change), but blocks rule 4's "every round builds on the last" for that parameter.
- Fix: round every applied value to the display precision (3 decimals, or the spec's natural step) before
  `with()`, or compare `from` against `current` with a tolerance of half a display unit (5e-4).

### DA-17 (L, unsure how often Yahoo does this) — A settled day's bar series is treated as complete to the flat time without checking it reaches it

- Where: `net/DayTradingGrader.kt` `grade()` (`complete = decidedThroughSec >= flatSec`, then
  `runPosition(..., complete = true)` closes at `bars.last().close`); `ui/PortfolioViewModel.kt` resolve path.
- Problem: if a settled day's response is truncated or has a long hole (the last bar at 13:40 for a liquid
  $2+/1M-volume name), an open trade is "closed at the flat time" at the 13:40 price, and an entry that would
  have filled later is NO_ENTRY - both written as FINAL at the current grader version, so never re-checked.
- Fix: for a settled day require the last bar to start within a few bar-lengths of `flatSec` (e.g.
  `bars.last().t >= flatSec - 15 * 60`) or of the day's close, else treat the fetch as failed (retry next time)
  rather than decide from it.

### DA-18 (L) — Type leniency in `num()`: a boolean or "on" becomes 1.0 for a NUMBER parameter

- Where: `net/EngineTuning.kt:271-281`.
- Problem: `"to": true` (or `"on"`) on `target.capR`, `filter.minRewardRisk`, `setup.<s>.maxRiskAtrs` ... is
  read as 1.0, which is in bounds - a switch that turns on a 1R target cap / 1.0-ATR stop ceiling that Claude
  never wrote as a number. (MEDIUM tier only, via the switch path, so combined with DA-4 it is not step-limited.)
- Fix: accept booleans / "on" / "off" only for `Kind.BOOL` specs; for NUMBER/INT require a number.

### DA-19 (M) — Rows logged before the E5 gate are re-graded and counted in the headline, including plans the card told Tj to skip

- Where: `ui/PortfolioViewModel.kt` `dayTradingRowsNeedingGrade` (re-queues every stale-version final row) and
  `resolveOneDayTradingEntry` (grades whatever row it is given); `net/DayTradingEval.kt` `stats()` (a re-graded
  row is `evalVersion == VERSION` and counts); the E5 check exists only at logging time
  (`loggableDayTradingRows` -> `planWaiting`).
- Problem: DESIGN E9 notes "the log only began 2026-09-16, so in practice every row is re-graded" - i.e. every
  existing row was logged under the OLD gates, which had no "still waiting" test. Re-grading applies the new
  fill rules but not the new logging rule, so a Claude plan logged while the price was already above its
  target or under its stop (the card said "Too late for this one today - don't buy now" / "Skip this one") is
  graded as a real order and counted. Worse, its direction is inferred from `priceAtRecommendation`, which for
  those old Claude rows was the logging price: price >= target > entry reads as a BUY-LIMIT at the entry, so a
  later dip-and-rally is credited as a WIN on a trade the card explicitly said not to take.
- Failing scenario: old Claude row, entry 20.00 / stop 19.50 / target 21.00, logged with
  `priceAtRecommendation = 21.30` (already past target). Re-grade: `rises = false` (entry < 21.30) -> buy-limit;
  the stock dips to 19.98 at 11:10 and rallies to 21.05 at 14:00 -> WIN, +~1.9R, in the headline and the
  account figure.
- Fix: when re-grading (or in `stats`) a row with no `features` (i.e. logged before 2026-09-24c), apply the
  part of E5 that the row itself can prove: `stop < priceAtRecommendation < target`; a row that fails is
  excluded like a legacy row (count it, say why, never grade it). Test: such a row -> not in `entriesTriggered`.

### DA-20 (L) — `filter.requireBullishOpeningBar` cannot act before 09:35, so the plans it exists to stop are logged in the first five minutes

- Where: `net/ResearchScore.kt` `planInternal` (`requireBullishOpeningBar && sessionLive && tech.or5High > 0.0 && !openingBarBullish`);
  `or5High` is 0 until the 09:30 bar has closed (`DayTradingTechnicals.fetch`, D-5).
- Problem: with the filter on, a plan made 09:30-09:34 is neither declined nor "not yet" - it is logged under the
  filtered engine's version even on a day whose first bar then closes down, which is exactly the trade the
  switch is meant to remove. The per-version results Claude uses to judge the switch are diluted by it.
- Fix: when the filter is on and the opening bar is not complete yet, return the plan with a `waitReason`
  ("waiting for the first 5-minute bar to close") so it is shown but not logged.

### DA-21 (L) — A Claude-added pick's `planPrice` is set by the later quote fill, not by the first live tick that priced it

- Where: `ui/PortfolioViewModel.kt` `mergeDayTradingTech` (`planPrice = if (plan != null) price else keepOrClear(row.planPrice, ...)` -
  a standing Claude plan keeps 0); `fillPricesNow` (`planPrice = if (planByClaude && planPrice <= 0) q.price`);
  `loggedPlanPrice` falls back to `r.price` when `planPrice` is 0.
- Problem: for a Claude pick that arrived without a price, the first live tick prices the row and can LOG it
  (direction from that tick's price), while `planPrice` - which fixes the card's direction (D-9) - is only set
  when the batched quote fill lands, possibly minutes later at a different price. If the price crossed the entry
  in between, the card's "climbs to / drops to" instruction and the logged order direction disagree. Until the
  fill lands, a free-text Claude setup's card direction also flips with the live price.
- Fix: in `mergeDayTradingTech`, when `claudePlanStands && row.planPrice <= 0 && livePrice != null`, set
  `planPrice = livePrice` (the first price the app saw), and let `fillPricesNow` only fill it if still 0.

