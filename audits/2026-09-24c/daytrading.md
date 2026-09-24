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

