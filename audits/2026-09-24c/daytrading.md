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
  `stop.maxRiskAtrs` 2.5 -> 2.0 (limited to 20% of range at MEDIUM) but not for turning on
  `setup.breakout.maxRiskAtrs = 1.0`, which cuts every breakout stop by 60% in one step.
- Failing scenario (MEDIUM, 75+ trades, basis "all" per DA-3): `filter.minScore` 0 -> 80 (blocks nearly every
  plan), `time.earliestEntryMinutes` 0 -> 120 (no plan before 11:30), `filter.minRewardRisk` 0 -> 4.0,
  `target.capR` 0 -> 0.5 (every target at +0.5R), `setup.breakout.maxRiskAtrs` 0 -> 1.0. All ACCEPTED unchanged.
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

