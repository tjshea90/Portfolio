# 2026-09-24c — UI audit (Day Trading success card, engine tuning, settings row)

Read-only audit. Scope: ui/ResearchScreen.kt (DayTradingSuccessRate, DayTradingBreakdown, fmtR,
DAY_TRADING_GRADING_RULES, TradeLevelsGrid, BeginnerSummaryCard), ui/EngineTuningUi.kt,
ui/SettingsScreen.kt (Day-trading engine row), ui/PortfolioViewModel.kt (engine/engineReview/
engineEvidence flows), shared widgets. Severity: H = wrong info / crash / unexpected action;
M = real usability or perf problem; L = polish.

Status: IN PROGRESS (findings appended as confirmed)

## Findings

### UI-1 (H) - "a real edge so far, with 95% confidence" can print under "Too few trades to judge"
- Where: ui/ResearchScreen.kt:1491-1503 (expectancy note), data/DayTradingLog.kt:220-225 (`edgeVerdict`), 1427-1434 (sample line).
- Problem: `edgeVerdict` is gated only on `entriesTriggered >= 2`, not on the sample tiers. The card's
  first line says, in red, "N graded trades - Too few trades to judge - results this small can easily
  be luck either way", and a few lines lower the same card says "a real edge so far, with 95%
  confidence." Two statements about the same numbers that contradict each other, and the second
  overstates certainty exactly where the first warns against it. The t-interval also assumes roughly
  normal R, which a day-trade R distribution (a cluster near -1R and a cluster at +target) is not at small n.
- Scenario: 6 decided trades, all target hits at +1.4..+1.8R net. mean 1.6, sd ~0.15, t(5)=2.57 ->
  range +1.44R..+1.76R -> "a real edge so far, with 95% confidence." directly under the red
  "6 graded trades - Too few trades to judge ...".
- Fix: below `SAMPLE_TIERS[0].first` (20) never print a verdict: "95% range +1.44R to +1.76R - with
  under 20 trades this range is not reliable yet." At 20-49 soften to "above zero so far (early read)".
  Put the gate in `edgeVerdict` so the prompt and card agree.

### UI-2 (H) - "the cap sized N of them" counts trades that are not in "them"
- Where: ui/ResearchScreen.kt:1467-1477; net/DayTradingEval.kt:423 (`capped++` for every trade) vs 455/500 (funded subset).
- Problem: the sentence starts with the FUNDED count (`entriesTriggered - unfundedTrades`) and then says
  "(the cap sized `cappedTrades` of them ...)". `cappedTrades` is counted over ALL trades, before the
  fundability pass drops the unfunded ones. With tight day-trade stops almost every trade is capped at
  25%, and the unfunded case is exactly "more than four positions at once", so both are common together.
- Scenario: 10 decided, 3 unfunded, all capped -> "7 trades across 4 sessions, each sized ... (the cap
  sized 10 of them ...)". Numbers that cannot add up, on the headline figure's own explanation.
- Fix: count capped inside the funded set (`funded.count { it.capped }`, add a `capped` flag to `Trade`),
  or reword to "the cap sized 10 of all 10". Minor related drift: `entriesTriggered - unfundedTrades`
  also includes zero-risk rows that never enter the account figure (they are skipped at
  DayTradingEval.kt:418) - use `funded.size` for the count.

### UI-3 (H) - Apply can install values the review sheet never showed
- Where: ui/PortfolioViewModel.kt:8260-8280 (`applyEngineReview`), ui/EngineTuningUi.kt:204-258.
- Problem: Apply re-runs `EngineTuning.review` against the log as it is at the tap and applies `fresh`
  without comparing it with the review on screen. Only the `next == null` case is reported. If the
  evidence moved while the sheet was open, a different set of values is installed silently.
- Scenario (realistic, not contrived): Tj shares Claude's answer into the app -> `importShared` ->
  `jumpToResearch(DAY_TRADING)` -> the tab opens -> `startDayTradingLive` -> `evaluateDayTradingLog(auto)`
  grades the rows that settled since last time (up to 180) WHILE the sheet is open. The review was
  computed before that grading. If the count crosses 75 (SMALL->MEDIUM), a change shown as "Limited to
  1.65 - ... at most 10% of its range" is applied at the 20% step; a change shown "Refused: Only 18
  graded trades in setup:Pullback" becomes accepted and is applied although the sheet said refused.
  Re-grading (E9) can also LOWER counts (a WIN re-graded to NO_ENTRY), silently dropping a "Will apply".
  The button said "Apply 1 change"; the toast then says "Engine v4 applied - 2 changes".
- Fix: in `applyEngineReview`, if `fresh.items.map { it.change.key to (it.status to it.applied) }` differs
  from the displayed review's, do not apply: set `_engineReview.value = fresh` and toast "The graded
  trades changed while this was open - check the updated review, then tap Apply again." Optionally also
  re-review when `engineEvidence` changes while `engineReview != null`.

### UI-4 (M) - The review shows Claude's evidence count, not the app's, next to a refusal that uses the app's
- Where: ui/EngineTuningUi.kt:227-228; net/EngineTuning.kt:405-419 (`groupN` computed, not kept).
- Problem: "Why (setup:Pullback, 45 trades): ..." prints `c.evidenceTrades`, the number Claude claimed.
  DESIGN.md says the cited group is "re-counted by the app, not trusted", but the recount (`groupN`) is
  only used for refusals and is never stored in `Reviewed`, so an ACCEPTED change shows only Claude's
  figure, and a refused one shows both numbers, contradicting each other.
- Scenario: "Refused: Only 12 graded trades in "setup:Pullback" - at least 20 are needed ..." followed
  immediately by "Why (setup:Pullback, 45 trades): pullbacks win more often ...".
- Fix: add `val groupCount: Int?` to `Reviewed`, fill it with `groupN`, and print
  "Evidence: setup:Pullback - 12 graded trades by the app's count (Claude cited 45)". Omit Claude's
  number when it matches.

### UI-5 (M) - "Nothing to measure yet - none of the N recorded recommendations has a decided outcome" when N have one
- Where: ui/ResearchScreen.kt:1415-1424 (zero branch); the regrading/legacy notes live only in the
  `else` branch (1553-1567); ui/PortfolioViewModel.kt:7341 and 7332 publish stats from the un-regraded log.
- Problem: every row graded by grader v1 is counted as `regrading` (or `legacyExcluded`), not decided.
  On the first open after this update the auto path publishes stats from the log BEFORE re-grading
  (7341), so `entriesTriggered == 0` and the card tells Tj none of his 40 recommendations has an
  outcome and to "keep using the tab ... then check again" - wrong, and alarming (his results look
  deleted). It lasts the first 60-fetch batch; offline it lasts until he is online (auto is skipped,
  `refreshDayTradingStats` publishes the same stats). If every row is legacy, it reads "none of the 0
  recorded recommendations" and the legacy note is hidden. Same branch, second wrong case: when every
  recorded plan expired unfilled, NO_ENTRY is a final outcome, yet the card says none "has a decided
  outcome" and hides the "N never filled before their cut-off" count that would explain it.
- Fix: in the zero branch append the same regrading/legacy sentences, and when `regrading > 0` lead with
  "N earlier results are being re-checked under the current, stricter rules - they appear here once
  done." When `totalRecommendations == 0`: "No recommendations recorded yet - ...".

### UI-6 (M) - "RISK PLAN (tuned engine vN)" describes the engine running now, not the one that made the plan
- Where: ui/ResearchScreen.kt:1248-1251; ui/PortfolioViewModel.kt:8307-8310 (`replanDayTradingNow`).
- Problem: the label reads the global `DayTradingEngine.params/version` (plain volatiles, not snapshot
  state), and `ResearchRow` carries no engine stamp. After Apply/Undo/Revert with the market closed,
  `replanDayTradingNow` -> `startDayTradingLive` does NOT re-plan (the closed-market sweep is gated on
  `!dayTradingSweepDone`, which is not reset), so rows keep plans from the previous engine. Any card that
  happens to recompose (its chart arrives, "N more reasons" is tapped, the row's price changes) and every
  DetailScreen/DayTradingPlanContent now labels an original-engine plan "tuned engine v4", while
  untouched (skipped) cards keep the old label: mixed labels on one list. After Revert, tuned plans are
  labelled as the original. Evening is the natural time to run a tuning round, so this is the common case.
- Fix: stamp the version on the row when the app plans it (`planEngine = DayTradingEngine.version` in
  `mergeDayTradingTech`, persisted in `ResearchRow.toJson`, cleared with the plan) and build the label from
  `r.planEngine`; in `replanDayTradingNow` also set `dayTradingSweepDone = false` so a closed-market list
  re-plans once under the new engine. That also removes the non-snapshot read from composition.

### UI-7 (M) - The tuning card's four buttons go grey during automatic grading, with no reason shown
- Where: ui/ResearchScreen.kt:719 (`busy = dayTradingStatsLoading`), ui/EngineTuningUi.kt:82-118.
- Problem: every tab open (at most once per 15 min, online) runs up to 3 x 60 chart fetches; for that
  whole time "Make tuning prompt", "Import answer", "Undo last change" and "Revert to original" are
  disabled and nothing on the card says why. The first run after this update re-grades the whole log,
  so this can take a while. Undo/Revert/Import don't conflict with grading: each re-reads evidence
  itself.
- Scenario: Tj opens Day Trading, scrolls to the card to revert a bad change, and finds Revert disabled
  for tens of seconds with no explanation.
- Fix: disable only "Make tuning prompt" (it would miss fresh grades) and show a caption under the row:
  "Grading new results... the prompt is ready when that finishes." Keep Import/Undo/Revert enabled.

