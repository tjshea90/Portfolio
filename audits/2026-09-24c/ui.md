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
- Scenario (ordinary luck, not an edge case): 10 decided trades, 7 target hits at +2R and 3 stops at
  -1R. mean +1.10R, sd 1.45, t(9)=2.26 -> 95% range +0.06R..+2.14R -> edgeVerdict "positive" -> "a real
  edge so far, with 95% confidence." directly under the red "10 graded trades - Too few trades to judge -
  results this small can easily be luck either way". 7 of 10 at a true 40% hit rate happens ~5% of the time.
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

### UI-8 (M) - Two "average per trade" rows in different units; profit factor and drawdown unexplained; "losing streak" is not what is measured
- Where: ui/ResearchScreen.kt:1490-1525.
- Problem: "Average result per trade +0.12R" and, four rows later, "Average per trade +0.08%" - near-identical
  labels, different units, different trade sets (the % rows include unfunded trades). R is explained in one
  10sp line; "Profit factor" and "drawdown" are not explained at all, and this card is read by a
  non-expert. "Worst losing streak (drawdown)" is mislabelled: `maxDrawdown` is the largest peak-to-trough
  fall of cumulative R, which can span winning trades, not a run of consecutive losers. And a zero
  drawdown prints `fmtR(-0.0)` = "+0.00R" - a plus sign on a drawdown.
- Fix: labels "Average per trade, in R (risk units)" / "Average per trade, % of the money in it" /
  "Total, same $ in every pick"; one muted line each: "Profit factor - dollars won for every dollar
  lost; above 1 made money", "Largest drop from a high point - the worst run you would have sat
  through, in R". Rename to "Biggest drop from a high (in R)" and print "none yet" when it is 0.

### UI-9 (L) - "+0.00R" for an average loss that does not exist; profit factor decimals vary
- Where: ui/ResearchScreen.kt:1504-1508; DayTradingEval.kt:491-494.
- Problem: with no losing trade, "Average win / average loss" reads "+1.52R / +0.00R" (avgLossR defaults
  to 0.0), and with no win "+0.00R / -1.00R". Profit factor uses `Fmt.priceBare`, which prints 3 decimals
  below 1 ("0.842") and 2 above ("1.35") - a money formatter for a ratio.
- Fix: "none yet" for an empty side; `String.format(Locale.US, "%.2f", pf)` for the ratio.

### UI-10 (M) - After Undo or Revert the card says "so this one can be measured" about a change that is no longer in force
- Where: ui/EngineTuningUi.kt:72-77; net/EngineTuning.kt:111 (`lastApplyAt` includes undone applies), 378-381 (same rule in the review blocker).
- Problem: `lastApplyAt` is the time of the last APPLY even when it was later undone or reverted, so after a
  revert the card reads "4 since the last change - the next change waits for 20, so this one can be
  measured." There is no change in force to measure. The review sheet's blocker then says "The last
  change has only been measured on 4 graded trades since it was applied" about a change Tj already threw
  away. Whether the 20-trade wait should apply after a revert is a policy question (unsure - it may be
  deliberate); the wording is wrong either way.
- Fix (wording, if the wait is intended): when `state.undoable == null && state.lastApplyAt > 0` say
  "The last change (v3) was taken back. A new change still waits for 20 graded trades after it - 4 so far."
  Same branch in the review blocker.

### UI-11 (M) - Settings has Revert but no "Undo last change", and its dialog state is not saveable
- Where: ui/SettingsScreen.kt:405-431; DESIGN.md:91-93 ("Undo last change" and "Revert to original
  engine" - "both confirmed ... available on the Day Trading tab and in Settings").
- Problem: only Revert is in Settings, so the lighter-weight way back is missing where the design put it.
  `confirmRevert` is `remember`, while the tab's equivalent is `rememberSaveable` - the confirm dialog
  vanishes on rotation. "1 settings changed" (no plural handling, line 417).
- Fix: add an "Undo last change" TextButton (enabled on `engine.undoable != null`) with the same confirm
  text as EngineTuningCard; `rememberSaveable` for the dialog flag (a String like the card's `confirm`);
  plural "setting"/"settings".

### UI-12 (M) - Apply runs the whole review on the main thread
- Where: ui/PortfolioViewModel.kt:8262-8267.
- Problem: `importEngineTuning` deliberately runs `EngineTuning.review` on `Dispatchers.Default`
  ("off the main thread"), but `applyEngineReview` calls it again inside `viewModelScope.launch`
  (Main) after `engineEvidenceNow()` returns. `review` calls `Evidence.count` per proposed change;
  a "level:..." basis parses every decided row's `features` JSON (`levelOf`), a "time:..." basis builds a
  ZonedDateTime per row. With several level-based changes and a year of log (~1,000+ decided rows) that
  is thousands of JSON parses on the UI thread at the moment of the tap - an estimated few hundred ms
  freeze on the Moto's small cores (estimate, not measured), with the dialog still up and Apply still
  enabled (see UI-13). Small today (the log began 2026-09-16); grows with the log.
- Fix: `val fresh = withContext(Dispatchers.Default) { EngineTuning.review(...) }` and the same for
  `apply`; cache `levelOf` per row id if it is called repeatedly.

### UI-13 (L) - Apply stays enabled while it works; a double tap applies twice
- Where: ui/EngineTuningUi.kt:254; ui/PortfolioViewModel.kt:8260-8275.
- Problem: `_engineReview` is nulled only after the IO log read and the review, so the sheet stays up
  with Apply enabled; a second tap launches a second coroutine that reviews and applies against the
  same pre-apply `_engine.value` (it is only updated after `saveEngine`'s IO write). Result: the same
  version saved twice, the first history entry overwritten, two toasts, two re-plans. Harmless to the
  params, confusing to watch.
- Fix: set `_engineReview.value = null` (or an `applying` flag that disables the button and shows
  "Applying...") synchronously before `launch`, and restore the review if `next == null`; or guard with a Mutex.

### UI-14 (L) - Review-sheet wording: "Refused: Not applied: ...", "1 graded trades", refused rows look like changes
- Where: ui/EngineTuningUi.kt:190, 207-219; net/EngineTuning.kt:395.
- Problem: (a) a blocker-refused item prints "Refused: Not applied: Only 12 graded trades from the app's own
  plans so far." (two prefixes, and it repeats the red blocker already shown above for every item);
  (b) "1 graded trades from the app's own plans" (no plural handling), and with tier NONE the line reads
  "12 graded trades from the app's own plans - not enough graded trades yet - no changes can be applied.";
  (c) a REFUSED item's heading is "stop.minRiskAtrs: 1.5 -> 2.2", the same shape as an accepted change -
  only the next line says it will not happen; (d) LIMITED is drawn muted and never says it WILL apply
  ("Limited to 1.65 - ..."), while UNCHANGED ("Already 1.5.") is drawn in the green of an accepted change.
- Fix: blocker items -> "Not applied (see above)."; plural; REFUSED heading "stop.minRiskAtrs: 1.5 (Claude
  proposed 2.2)"; LIMITED text "Will apply, limited to 1.65 - ..." in the accepted colour; UNCHANGED muted.

### UI-15 (L) - A non-whole number for a whole-number or on/off setting gets a self-contradicting refusal
- Where: net/EngineTuning.kt:347-352, 400-401.
- Problem: `fmt` rounds INT (`toInt()`) and BOOL (`>= 0.5 -> on`) before printing, so `spec.allows`
  refusing 0.7 for a switch prints "on is outside what this parameter allows (off to on)", and 45.5 for
  `filter.minScore` prints "45 is outside what this parameter allows (1 to 80, or off)".
- Fix: for a kind mismatch print the raw value and the rule: "0.7 - this setting is on/off only (0 or 1)",
  "45.5 - this setting takes whole numbers only".

### UI-16 (M) - The "What worked" breakdown shows 1-to-5-trade slices with the same confidence the headline now avoids
- Where: ui/ResearchScreen.kt:1598-1617 (`DayTradingBreakdown`).
- Problem: the headline was reworked (E10) so a handful of trades cannot read like a verdict, but the
  breakdown directly under it prints every slice at full precision with no caution: "Pullback +0.80R avg,
  75.0% profitable (4 trades)", "Claude's plans 100.0% profitable (1 trade)". These slices are where Tj is
  most likely to draw a conclusion ("pullbacks work, Claude beats the app") and where the samples are
  smallest. Also the engine-version group is sorted by label as a string, so "Tuned engine v10" sorts
  before "Tuned engine v2" (net/DayTradingEval.kt:615).
- Fix: for `decided < SAMPLE_TIERS[0].first`, draw the row muted and append " - too few to judge"
  (or hide avg R below 5 trades); whole-number percentages; sort engine slices by the numeric version.

### UI-17 (L) - Repeated full-log reads for the tuning card's counts
- Where: ui/ResearchScreen.kt:246-248; ui/PortfolioViewModel.kt:8200-8207, 7340/7383/7385.
- Problem: `LaunchedEffect(section, dayTradingStats, engine.version)` calls `refreshEngineEvidence()`,
  a full `db.dayTradingLog()` read (every row with its `features` and `eval_detail` grid, ~1 KB each), on
  every tab entry and every stats emission. `DayTradingStats` is never equal to the previous value
  (`evaluatedAt = now`), so each publish restarts the effect. One auto-grading run already reads the log
  3 times (7340, 7383, 7385) and publishes 2-3 times, so opening the tab costs ~5-6 full reads. Off the
  main thread, and not per 30-second tick, so not jank - but allocation churn (MBs on a year-old log) on a
  mid-range phone for two integers.
- Fix: compute `Evidence(rows, lastApplyAt)` inside `dayTradingStatsOf(rows)` from the rows already read
  and publish both together; key the effect on `engine.version` only (lastApplyAt changes with it).

### UI-18 (L) - Before the first count lands, the tuning card says "0 graded trades"
- Where: ui/PortfolioViewModel.kt:1961 (`MutableStateFlow(0 to 0)`), ui/EngineTuningUi.kt:66-71.
- Problem: until `refreshEngineEvidence` finishes, the card reads "0 graded trades from the app's own
  plans. Claude can review them now, but the engine is not changed until there are 30." - a false count,
  and "Claude can review them now" is odd wording when there are none.
- Fix: make the flow nullable and show "Counting graded trades..." until the first read; with 0 say
  "No graded trades from the app's own plans yet - the engine is not changed until there are 30."

### UI-19 (L) - The tuning card's "Import answer" is gated differently from the identical button at the top, and its errors land elsewhere
- Where: ui/EngineTuningUi.kt:86; ui/ResearchScreen.kt:496-497 vs 716-719.
- Problem: both buttons launch the same picker and the same content router (`importResearchFile`). The
  top one is disabled while a research build runs (RES-7: a Day Trading/research answer applied under a
  running build is overwritten); the card's is enabled then, and is disabled during grading while the
  top one is not. A wrong file picked from the card gets a research-worded error ("No research JSON
  found in that file ...") in the red banner at the TOP of the list, far from the card at the bottom.
- Fix: give the card its own picker that calls `importEngineTuning` and toasts its own error ("That file
  has no engine-tuning answer - share Claude's reply to the tuning prompt"), or at least use the same
  `enabled` rule as the top button.

### UI-20 (L) - Terminology drift between the two adjacent cards
- Where: ui/ResearchScreen.kt:1428-1433 (sample tiers 20/50/100), ui/EngineTuningUi.kt:64-79 (tiers 30/75/150).
- Problem: the success card says "45 graded trades - An early read" (all plans, Claude's included); the card
  directly below says "38 graded trades from the app's own plans ... Small, gradual changes only ... More
  unlocks at 75". Two counts of "graded trades" and two tier ladders side by side; the reconciling split
  (app vs Claude) is only in the breakdown further up. "Graded trade", "tier" and "R" are never defined on
  the tuning card itself.
- Fix: on the success card's first line add "(38 from the app's own plans, 7 from Claude's)"; on the tuning
  card say "graded trades (plans that filled and finished) from the app's own plans".

### UI-21 (L) - Long always-open footnotes; load-bearing sentences in 10sp muted text
- Where: ui/ResearchScreen.kt:1467-1487, 1526-1539, 1543-1570.
- Problem: with the new notes (unfunded, regrading, 5-minute, legacy) the recommendations footnote alone
  runs ~10 lines at 11sp, the account note ~6 lines at 10sp; at 1.6x the card is several screens tall.
  The statements that qualify the headline numbers (unfundable trades, the 95% verdict, the cost model)
  are `labelSmall` (10sp) in `onSurfaceVariant` on `surfaceVariant`, which measures 4.43:1 in the light
  theme (#6B7280 on #F3F5F8) - just under AA 4.5:1 (dark theme is fine at ~6.4:1).
- Fix: keep the headline rows and the sample line open; put the counts footnote, cost model and account
  explanation behind one "Details" expander (the card already has the pattern for grading rules); use
  bodySmall (11sp) minimum for sentences that change how a number should be read.

### UI-22 (L) - Expanders and disabled buttons say nothing to TalkBack
- Where: ui/EngineTuningUi.kt:90-94, 108-112; ui/ResearchScreen.kt:1574-1579.
- Problem: "How does this work?", "Engine history (n)", "How are trades graded?" are clickable Texts with no
  role and no expanded/collapsed state; TalkBack says "double-tap to activate" with nothing about what
  opens. The disabled "Undo last change" / "Revert to original" give no reason. Expander links use
  `accentText`, 4.41:1 on the light surfaceVariant at 14sp (below AA for non-large text; app-wide, not new).
- Fix: `Modifier.clickable(role = Role.Button, onClickLabel = if (open) "collapse" else "expand")` plus
  `semantics { stateDescription = if (open) "Expanded" else "Collapsed" }`; a muted line under the
  Undo/Revert row when both are disabled: "Nothing to undo - this is the original engine."

### UI-23 (L) - Button rows at large font scale
- Where: ui/EngineTuningUi.kt:81-89, 115-119.
- Problem: "Make tuning prompt" / "Import answer" are two weight(1f) OutlinedButtons (~123dp of text each):
  "Make tuning prompt" wraps to 2 lines at ~1.1x and 3 at 1.6x while "Import answer" stays at 1-2, so the
  pair renders at different heights (the Row has no `IntrinsicSize.Min`/`fillMaxHeight`). The Undo/Revert
  row is two unweighted TextButtons around a weighted Spacer: Compose measures Undo first, so at 2.0x
  "Revert to original" is left ~100dp and breaks to 3-4 lines. Still tappable; looks broken.
- Fix: `Row(Modifier.height(IntrinsicSize.Min))` + `fillMaxHeight()` on both buttons; for Undo/Revert use
  a `FlowRow` (or stack them) so each keeps its natural width.

### UI-24 (L, unsure) - A confirm dialog saved inside the lazy "tools" item can pop up later after rotation
- Where: ui/EngineTuningUi.kt:50, 121-142 (confirm state lives in the LazyColumn item "tools").
- Problem: `confirm` is `rememberSaveable` inside a lazy item. If Tj rotates with the Undo/Revert confirm
  open and the restored scroll position leaves the (tall) "tools" item outside the landscape viewport, the
  dialog is not composed; it then appears by itself when he later scrolls down. Not confirmed on device.
- Fix: hoist the confirm flag to ResearchScreen (screen-level `rememberSaveable`) and render the
  AlertDialog there, like `EngineReviewDialog`.

### UI-25 (L) - Engine history: raw keys, and all 200 entries composed at once in one lazy item
- Where: ui/EngineTuningUi.kt:107-114, 145-166; net/EngineTuning.kt:100 (`HISTORY_MAX = 200`).
- Problem: history lines print raw keys ("setup.pullback.minRiskAtrs: off -> 0.8") without the plain-words
  `Spec.doc` the review sheet shows under the same key; "Running the original engine (v5)." after a revert
  can read as "the original is v5". Expanded, every entry (summary up to 600 chars each) is composed inside
  the single "tools" LazyColumn item.
- Fix: show the last 10 entries with "Show all (n)"; add the spec doc as a muted second line; word the
  header "Running the original engine (after 5 engine changes, now v5)."

### UI-26 (L) - Small wording slips in the success card
- Where: ui/ResearchScreen.kt:1544-1549; 1417-1421.
- Problem: "1 recommendations recorded" (no plural handling); zero clauses always printed ("0 never filled
  before their cut-off, 0 still in progress"); a null (never evaluated) row from a past day is counted as
  "still in progress" (DayTradingEval.kt:446) until the next grading run.
- Fix: plural helper; drop zero clauses; call null past-day rows "not checked yet".

### UI-27 (L) - Plain-English plan text says "the target is the next real resistance" even when a tuned cap set it
- Where: ui/DayTradingDetailDialog.kt:103-119 (shown on DetailScreen for every app plan).
- Problem: with `target.capR` (or a per-setup cap) switched on by a tuning, the target can be entry + N R,
  not a resistance level; the explanation still asserts resistance. Also "NOT YET" (a wait, levels still
  valid) is drawn in the same red, error-tinted style as "Skip this one - the plan already fell apart"
  (ResearchScreen.kt:1187-1204, 1281-1289) - a wait reads as a failure.
- Fix: when the engine's cap is active (or the plan carries a `targetCapped` flag) say "the target is the
  next resistance, capped at N R by the tuned engine"; draw NOT YET in the muted/amber tone, not error red.

