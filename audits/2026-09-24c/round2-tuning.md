# Round 2 audit — tuning side (EngineTuning / EngineTuningPrompt / engine persistence)

Read-only audit of the fixes for DA-2, DA-3, DA-4, DA-5, DA-6 (prompt part), DA-10, DA-11, DA-14, DA-15, DA-16, DA-18, PL-4, PL-9, PL-10, PL-14, PL-15, plus new issues. Baseline for the diff: `5576e0fb`. HEAD at audit start: `0d77468f`.

Status: in progress (findings appended as verified)

## Findings

### R2T-1 (M): after a revert, "Undo last change" puts the tuned engine back, but the confirm dialog says it does the opposite

- **Where:** `ui/EngineTuningUi.kt:207-215` (`EngineConfirmDialog`, undo text), `ui/PortfolioViewModel.kt:8587` (undo toast), `ui/EngineTuningUi.kt:176-186` (`HistoryLine`), `ui/SettingsScreen.kt:413-422`; behaviour in `net/EngineTuning.kt:115-116` (`undoable` = a trailing revert) and `:167-187` (`undo`).
- **Problem:** since the PL-15 fix, when the last history entry is a revert, `undoable` is that revert, and Undo re-installs the tuned engine the revert replaced (`paramsAfter = revert.paramsBefore`) and puts its applies back in force. The UI was not updated for this:
  - The confirm dialog still says "The engine goes back to exactly how it was before the most recent change Claude made." After a revert the engine already is the original, so Tj reads that as a harmless no-op, or as "go back further". What actually happens is that every change Claude made comes back.
  - The toast says "Undone - the engine is back to how it was before that change". That is also backwards for this case.
  - Settings, right after a revert, shows "The original engine (now v5, after earlier changes were taken back)" with **Undo last change enabled** and no hint of what it would do.
  - In the history list the revert line never shows "(later taken back)", because that marker is drawn only for `KIND_APPLY`, and the undo line reads only "undo". A reader cannot tell the tuned engine is back in force except from the card header.
- **Failing scenario:** v4 is tuned and Tj taps Revert on purpose, giving v5, the original. Days later he taps "Undo last change" in Settings (it is enabled), reads "goes back to how it was before the most recent change Claude made" and confirms. The engine becomes v6 with all of v4's values again. The toast repeats the wrong description. Plans are now made and logged by the tuned engine Tj deliberately switched off, which goes against the intent of rule 3.
- **Fix:** pass the undo target's kind to the dialog and the toast. When `state.undoable?.kind == KIND_REVERT`, word them as "Undo the revert? The tuned engine vN (M settings changed) comes back in force." Show the button as "Undo revert". In `HistoryLine` mark an undone revert "(later taken back)" and show the undo entry's `summary` (it already says "Undid the revert ...").

### R2T-2 (M): after an undo of a revert, "graded trades since the change in force" counts trades made by the ORIGINAL engine, which weakens the 20-trades-between-changes rule and makes the prompt's "since" figure wrong

- **Where:** `net/EngineTuning.kt:122` (`lastApplyAt = applyInForce?.at`), `:182` (undoing a revert resets the applies' `undoneAt` to 0), `:216` (`sinceLastChange = decided.count { recordedAt > since }`), `:460` (blocker), `net/EngineTuningPrompt.kt:174`, `ui/EngineTuningUi.kt:161-166` (`readiness`).
- **Problem:** after an undo of a revert, `applyInForce` is the old apply again, so `lastApplyAt` is that apply's original time. `sinceLastChange` then counts every decided app trade since then, including all trades made while the revert was in force. Those plans were made by the original engine and are labelled `v0`. The rule exists "so each change can be measured on its own", but here most of the count measures a different engine. The prompt then tells Claude "The last change was applied <date>; N graded trades since", which is wrong for the engine now running.
- **Failing scenario:** v4 is applied on 1 Oct and makes 3 graded trades. Tj reverts on 2 Oct, and the original engine makes 25 graded trades over the next week. On 9 Oct he undoes the revert, so v6 = v4's values. `sinceLastChange` = 28 ≥ 20, so a new answer can be applied immediately, although the engine in force has been measured on 3 trades. The card says "28 since the change now in force".
- **Fix:** start the "since" count when the params now in force came into force. For example, keep `lastApplyAt` = `applyInForce.at`, but when the most recent history entry is an undo of a revert, use that undo's `at`. More generally, use the `at` of the latest history entry whose `paramsAfter` equals the current params. Or count only decided rows whose `engine` label matches the current version.

### R2T-3 (M): the "By opening bar direction" table files every plan shown 09:30-09:34 under "did not close up" (wrong data for the one switch it exists to tune)

- **Where:** `net/DayTradingFeatures.kt:121` (`put("obb", row.openingBarBullish)` unconditionally); `ui/PortfolioViewModel.kt:1805-1806` / `net/DayTradingTechnicals.kt:317-323` (`openingBarBullish = or5 != null && close > open`, and `or5` is null until the 09:30 bar has closed); `net/EngineTuningPrompt.kt:230` (the table).
- **Problem:** before 09:35 the opening bar has not closed, so `openingBarBullish` is `false` because the bar does not exist yet, not because it closed down. Every app plan logged in the first five minutes gets `obb:false`. The prompt then counts it under "first 5-min bar did not close up". This slice is the only evidence Claude has for `filter.requireBullishOpeningBar`, and the plans it mislabels come from the busiest few minutes of an ORB day. (The DA-20 fix stops a tuned engine *with the switch on* from logging these plans. The original engine, which produces the evidence for turning the switch on, still logs them.)
- **Failing scenario:** on the original engine, 30 of 100 graded app trades were logged 09:30-09:34, and on 20 of those days the opening bar later closed UP. The table shows "did not close up" with 30 extra trades, which pulls that row's average toward whatever the first-five-minute trades did. Claude may then switch the filter on (or keep it off) on a comparison that mixes two different populations. That breaks rule 2 (tracking data must be accurate).
- **Fix:** only write `obb` when the bar has closed (`row.or5High > 0`), or write a third state such as `"obb": null` / `"obbPending": true`. The table then gets a "shown before the first bar closed" group, not a wrong one.

### R2T-4 (L): the review sheet labels the parameter's own-group count with the group Claude cited, so a correct app count looks like a miscount

- **Where:** `net/EngineTuning.kt:479-483` (`counted = minOf(cited, ownN)`; `groupName` is used only inside the refusal texts), `data class Reviewed` (`groupCount`, no group name); `ui/EngineTuningUi.kt:325-329` (`"Evidence (${c.basis}): N graded trades by the app's count - Claude cited M"`).
- **Problem:** since DA-3, `groupCount` is the smaller of the cited group and the group the parameter acts on, but the sheet still prints it under Claude's `basis`. For an ACCEPTED or LIMITED change resting on a smaller own group, nothing says which group the number belongs to.
- **Failing scenario:** 160 graded trades, 45 of them Pullbacks. Answer: `setup.pullback.minRiskAtrs`, basis `"all"`, `evidenceTrades: 160`. The review accepts it and the sheet shows "Evidence (all): 45 graded trades by the app's count - Claude cited 160". It looks as though the app lost 115 trades, when it actually and correctly counted the Pullback group.
- **Fix:** carry the counted group's name in `Reviewed` (e.g. `groupName`) and print "Evidence: 45 graded trades in setup:pullback (the trades this setting acts on; Claude cited all = 160)".

### R2T-5 (L): the prompt shows switched-off numeric filters as "off" and says to copy `from` "from the table", but the parser now refuses `"off"` for them

- **Where:** `net/EngineTuning.kt:419-423` (`fmt`: an `offAllowed` NUMBER at 0 prints "off"), used by the parameter table's `current` column and by `algorithm()` (`EngineTuningPrompt.kt:258-266, 352`); `SHAPE` (`"from": <number - its CURRENT value, copied from the table>`); `EngineTuning.kt:336-346` (`num(onOff = false)`: `"off"` becomes NaN for a non-BOOL spec); `:489-490` (NaN `from` is refused).
- **Problem:** 14 parameters (`target.capR`, `filter.minRewardRisk`, `filter.maxTriggerAtrs`, and every `setup.<s>.minRiskAtrs/maxRiskAtrs/targetCapR/minRewardRisk`) show "off" as their current value. Before DA-18, `"from": "off"` read as 0. Now it is refused with "Claude's "from" for it is not a number - make a new prompt and ask again". Making a new prompt does not help, because the new prompt shows "off" again. `"to": "off"` likewise gets "Not a number - ... true/on/off are for on/off settings only", although off is exactly what these settings document. The prompt does say "Give numbers as numbers", and the range column says "or 0 = off", so a careful answer writes 0. But switching a filter on is the most common kind of change, and the value it has to copy literally says "off".
- **Failing scenario:** Claude answers `{"param":"filter.minRewardRisk","from":"off","to":1.0,...}`. The change is refused, and the sheet's advice to make a new prompt reproduces the same trap.
- **Fix:** either print `0 (off)` in the table and the algorithm text (so the value to copy is a number), or let `num()` read `"off"`/`false` as 0 for `offAllowed` specs too. That is unambiguous: 0 *is* off for them, and DA-18's concern was only `true`/`"on"` becoming 1.0.

### R2T-6 (L): consistency is checked change by change in the order Claude listed them, so a consistent pair can be half-applied

- **Where:** `net/EngineTuning.kt:544-548` (`inconsistency(candidate)` on the running `params` after each change), `:617-630`.
- **Problem:** `time.lastEntryMinutes`/`time.flatBeforeCloseMinutes` and each floor/ceiling pair are checked against the engine *as built so far*, not against the answer's end state. When both halves of a pair move, whether the answer is accepted depends on the order they are listed in, and the prompt does not tell Claude this. A related case: an engine that is already inconsistent (a pre-DA-15 tuned engine with `lastEntry < flat + 10`) has every unrelated change refused, until a change that repairs it is listed first.
- **Failing scenario (LARGE tier):** `[{flat 10->25}, {lastEntry 30->45}]`. The first change gives lastEntry 30 < 25 + 10, so it is refused. The second is accepted. Only half of Claude's intended pair is installed, and Tj approves an engine Claude did not propose. With the two listed in reverse order, both are accepted. The same happens with `[{stop.minRiskAtrs 1.5->2.7}, {stop.maxRiskAtrs 2.5->3.5}]`: the floor is refused because 2.7 > 2.5.
- **Fix:** run the per-change checks first, then check `inconsistency()` on the final `params`. If it fails, refuse the specific changes that create the conflict, or try the accepted changes in an order-independent way (e.g. apply widening changes before narrowing ones). At minimum, state in the prompt's rules that changes are applied in the order listed.

### R2T-7 (L): a backup restore's `loadEngine()` is not serialised with apply/undo/revert, so an in-flight engine change can overwrite the restored engine

- **Where:** `ui/PortfolioViewModel.kt:9561` (`restoreAsync` calls `loadEngine()` on Main), `:8392-8411` (`loadEngine` sets `_engine.value` and installs without `engineMutex`); `applyEngineReview` / `undoEngineChange` / `revertEngine` (`:8544-8600`) read `_engine.value` inside the mutex, suspend, then `saveEngine(next)`.
- **Problem:** PL-10's mutex covers the three mutators and the file adoption, but not the restore path. If a restore completes while an Apply is between reading `st = _engine.value` and `saveEngine(next)` (during `engineEvidenceNow()` / the `Dispatchers.Default` review), `next` is built on the pre-restore engine. `saveEngine` then writes it over the restored `dt_engine`/`dt_engine_history` and over the backup files. The restored history is silently replaced by the device's old history plus one entry. Needs two user actions at once, hence L.
- **Fix:** have `restoreAsync` call a suspending `loadEngine` that takes `engineMutex` (or re-check inside the mutators that `_engine.value === st` before saving, and abort with "the engine changed - review again").

### R2T-8 (L): switching OFF a per-setup override is an unlimited step when the global value it falls back to has itself been tuned

- **Where:** `net/EngineTuning.kt:510-511` (`isSwitch` covers `c.to == 0.0`; the step limit runs only `if (!isSwitch || turningOn)`, `:536`).
- **Problem:** DA-4 limited switching an override ON. Switching one OFF is still unlimited, and its real effect is to move that setup's value to the *current global*, which may be far away after earlier rounds. It is not necessarily a move back toward the original engine.
- **Failing scenario (MEDIUM, 30+ Pullback trades):** earlier rounds took `stop.minRiskAtrs` to 0.8, and `setup.pullback.minRiskAtrs` = 2.3 is on. Answer: `setup.pullback.minRiskAtrs 2.3 -> 0`. It is accepted as a switch, and every Pullback's stop floor drops 2.3 -> 0.8 ATR (43% of the range) in one import. The same move made as a number would be limited to 0.8 (20% of 4.0).
- **Fix:** when switching off an override, measure the step from the override to the global it falls back to (`onBase`), and refuse or limit when that exceeds `tier.maxStep * spec.range` (e.g. suggest setting the override to the value one step closer instead).

### R2T-9 (M): the PL-4 adoption can lock a STALE engine in, because the recovery card's Merge restore then skips the backup's fresher engine

- **Where:** `ui/PortfolioViewModel.kt:8403-8409` (`loadEngine`: settings empty -> adopt `current.json`/`history.json` -> `saveEngine`, which writes `dt_engine`/`dt_engine_history`); `data/Db.kt:2105-2117` (Merge: `if (!replace && hasSetting(k)) continue`); `ui/PortfolioScreen.kt:636, 697` (the reinstall recovery card restores with `replace = false`); `:9561` (`restoreAsync` -> `loadEngine`).
- **Problem:** the adoption makes the engine keys exist before the user can restore anything. The recovery flow PL-4 was written for is: reinstall, Google restores filesDir but not the DB, then the card offers `portfolio-autosave.json`. That flow restores with **Merge**, and Merge never overwrites an existing setting. So whichever engine the files held wins, even when the Downloads backup carries a later one. Android's own backup and the app's autosave are both roughly daily and either can be the newer. Before the fix, settings were empty at that point and Merge took the backup's engine.
- **Failing scenario:** Google's backup of filesDir was taken Monday night (engine v3). Tj applies v4 on Tuesday, and Tuesday's autosave in Downloads carries `dt_engine` v4 plus the v4 history entry. The phone is replaced on Wednesday. First launch adopts v3 and toasts "Restored the tuned day-trading engine (v3)". Tj taps the recovery card's Restore (Merge). The ledger and log come back, but `dt_engine`/`dt_engine_history` are skipped. `restoreAsync -> loadEngine` sees the log's `v4` rows (`logVersion` 4 > 3), so the engine becomes **v5 with v3's values** and the v4 apply is missing from the history. The next tuning prompt omits the v4 change from "Changes already made" and shows `v4` results with no matching history entry, which breaks rule 4. Nothing tells Tj that his latest engine was dropped.
- **Fix:** record that the engine was adopted from files (e.g. a `dt_engine_adopted` flag, or keep the adopted state unsaved until the recovery card is dismissed). On a Merge restore, take the backup's `dt_engine`/`dt_engine_history` when its history is newer (compare the last history entry's `at`, or its max version) than what is stored. Or have the Merge restore treat the engine keys as a unit and keep the newer of the two by history.

### R2T-10 (L): `towards()` rounds the double's representation error, so some limited steps land 0.001 short (e.g. 2.724, 3.601, 0.076, 3.901)

- **Where:** `net/EngineTuning.kt:580-585` (`BigDecimal.valueOf(from ± maxDelta).setScale(3, FLOOR/CEILING)`).
- **Problem:** `from + sign * maxDelta` is computed in binary floating point and can come out a hair below (going up) or above (going down) the exact decimal step. FLOOR/CEILING then round it a whole 0.001 further toward `from`. This does not break a rule, since the result is always inside the step. But it produces odd values that Tj sees on the sheet and in the history, and that Claude has to copy as `from` next round.
- **Scenarios (IEEE doubles, reproduced):** `stop.minRiskAtrs` 1.5 up at LARGE: 1.5 + 0.35×3.5 = 2.7249999999999996, which rounds to **2.724** (not 2.725). `vol.intradayAtrFromDaily` 0.10 down at SMALL: 0.07500000000000001 rounds to **0.076**. `score.mentionPoints` 12 down at LARGE: 3.6000000000000014 rounds to **3.601**. `filter.maxTriggerAtrs` switched on at LARGE (from 6.0): 3.9000000000000004 rounds to **3.901**.
- **Fix:** compute the step in decimal (`BigDecimal.valueOf(from).add(BigDecimal.valueOf(tier.maxStep).multiply(BigDecimal.valueOf(spec.range)).multiply(sign))`), or first round `v` HALF_EVEN to ~9 decimals and then apply FLOOR/CEILING at 3.

### R2T-11 (L): with the new mutex, a double confirm of "Undo" now undoes TWO changes

- **Where:** `ui/PortfolioViewModel.kt:8581-8590` (`undoEngineChange`: `engineMutex.withLock { undo(_engine.value, ...) }`, with no "which version did Tj confirm" check); the confirm handlers in `ui/EngineTuningUi.kt:207-222` and `ui/SettingsScreen.kt:426-430` / `ResearchScreen.kt:258-261`.
- **Problem:** before PL-10 two overlapping undos built on the same base and collided. Now the mutex runs them one after the other, and the second one takes back the *next* apply in force, a change Tj never confirmed. `applyEngineReview` is protected by `_engineApplying` and the same-decisions re-check. Undo has no such guard. (A second Revert is harmless because it returns "already the original".)
- **Failing scenario:** v3 = apply A (sets `stop.minRiskAtrs`), v4 = apply B (sets `target.capR`). A bounced double tap on the dialog's "Undo" delivers two clicks before the dialog leaves composition. B is undone (v5), then A is undone (v6), leaving the original engine after one confirmation.
- **Fix:** pass the version shown when the dialog opened (`undoEngineChange(expect = engine.version)`) and do nothing, with a toast, when `_engine.value.version != expect` inside the lock. Do the same for revert, for symmetry.

### R2T-12 (L): after DA-3, a setup or level switched OFF can only be switched back ON through tuning while its old trades still count, and a grader bump can remove them for good

- **Where:** `net/EngineTuning.kt:252-263` (`groupFor`: `setup.<s>.*` -> `setup:<s>`, `level.<l>.enabled` -> `level:<l>`), `:208-211` (`Evidence.decided` requires `evalVersion >= DayTradingGrader.VERSION`); `DayTradingEval.intradayStillAvailable` / `DT_BARS_KEEP_DAYS` (re-grading is limited to about 55-60 days).
- **Problem:** a switched-off setup or level produces no new trades, so its own group can only ever shrink. While the grader version stays the same, the 30+ trades that justified switching it off are still counted, so it can be switched back on. After a grader bump, rows older than the bar window cannot be re-graded and drop out of `Evidence`. The group then falls under 20 or 30 permanently, and `setup.<s>.enabled 0 -> 1` is refused forever ("Only 0 graded trades in setup:reclaim"). Undo only reaches the most recent change, and Revert throws away every other change. Before DA-3, citing `all` worked.
- **Failing scenario:** VWAP reclaim was switched off in March on 35 reclaim trades. The grader moves to v4 in June, and March's rows cannot be re-graded (bars gone). In July Claude recommends turning reclaim back on (basis `all`, 400 trades). It is refused: the group count is 0.
- **Fix:** for a switch back toward the ORIGINAL value (`c.to == spec.default`), don't apply the own-group minimum (use `all`), since it is the less risky direction. Or count rows of the group regardless of grader version for this one purpose. State the rule in the prompt.

### R2T-13 (L): `loadEngine` scans the whole day-trading log on the main thread at every cold start and every restore

- **Where:** `ui/PortfolioViewModel.kt:8395` (`db.dayTradingLogMaxEngineVersion()` inside `loadEngine`, called from `init` at `:3021` and from `restoreAsync` on Main at `:9561`); `data/Db.kt:1771-1778` (`SELECT DISTINCT engine FROM day_trading_log`: there is no index on `engine`, so this is a full table scan over rows that carry the `features` and `eval_detail` TEXT columns).
- **Problem:** the scan grows linearly with the log's age (PL-12 estimated ~2.5 KB per row) and blocks the first frame. At a few thousand rows that is tens of MB of page reads before the UI draws.
- **Fix:** move the version read and `load` into the IO coroutine `loadEngine` already launches (installing the settings engine first, then bumping), or add `CREATE INDEX ... ON day_trading_log(engine)` so DISTINCT reads only the index.

### R2T-14 (L): DA-14 item 1 is fixed in the prompt and the spec doc but not in the card's reason line

- **Where:** `net/ResearchScore.kt:456` (`why.add("Within 15% of its 52-week high - breaking out")`), under `rangePos > 0.85`.
- **Problem:** Tj still reads the old, wrong sentence on every row that earns the points. For a 50-100 52-week range the code needs price > 92.50; the card implies >= 85.
- **Fix:** "In the top 15% of its 52-week range - near its high".

### R2T-15 (L): with `filter.requireBullishOpeningBar` on, a stock with no 09:30 bar says "waiting for the first 5-minute bar to close" all day

- **Where:** `net/ResearchScore.kt:1000-1001` (new wait: `p.requireBullishOpeningBar && tech.or5High <= 0.0`); `net/DayTradingTechnicals.kt:317, 638-643` (`or5` = the bar stamped 09:30-09:34, null if Yahoo has none).
- **Problem:** `or5High` is 0 not only before 09:35 but also all day when the opening bar is missing. That happens for a stock halted at the open (LULD or news pending, which is common for the gappers this tab screens) or a thin feed. The wait never ends, and the card shows a live-looking plan with a false "waiting" reason until the close. This is conservative (nothing is logged), hence L.
- **Fix:** wait only while the bar has not been completed (`!openingBarComplete`). Once a 09:35+ bar exists and `or5` is still null, decline with "no opening bar printed - the tuned engine cannot judge its direction", or treat it as not bullish.

### R2T-16 (L): a row is logged with the engine read at CAPTURE time, not the one that planned it

- **Where:** `ui/PortfolioViewModel.kt:1130` (merge reads `DayTradingEngine.current` per row), `:7404-7408` and `:7436-7443` (capture reads it again for `engine`, `cutoffParams` and the features); `row.planEngine` (the plan-time label) is ignored by capture.
- **Problem:** an Apply, Undo or Revert that lands during a sweep tick, between a row's merge and `captureDayTradingRecommendations`, logs a plan made by the old engine under the new label, with the new engine's deadline and flat times. The day's first row per symbol is permanent (`INSERT OR IGNORE`, `dtLoggedToday`), so the mislabel sticks and feeds `engine:vN` evidence and the per-version table.
- **Fix:** in capture, skip a row whose `planEngine` (normalised: "" = `v0`) differs from the snapshot's label. The next tick logs the re-planned row. Or pass one snapshot through the whole tick.

### R2T-17 (L): PL-9 is closed only for "log ahead of the store"; the same number can still name two engines

- **Where:** `net/EngineTuning.kt:147-148` (bump only when `logVersion > v`).
- **Problem:** a Merge restore of another phone's backup (or of a backup from a diverged lineage) adds rows labelled `v3` made by a different `v3` than this device's. With `logVersion == v` nothing is bumped, and `engine:v3`, the per-version table and the card's "Tuned engine v3" slice add up two parameter sets. This is inherent to counter labels.
- **Fix:** record a short params hash in `features` (e.g. `"eh": params.hashCode()`) and group or count by label + hash, or label as `v3-<hash4>`.

### R2T-18 (L): prompt nits that a fresh chat can trip on

- **Where:** `net/EngineTuningPrompt.kt:166-177` (rules), `:192-205` (history), `SHAPE`.
  - "Graded trades" (the unit of every sample-size rule and of `evidenceTrades`) is never tied to a table column. The app counts only filled-and-decided trades, which is the tables' **filled** column, but the CSV is headed "Every graded plan" and includes NO_ENTRY rows. A Claude that cites "plans" overstates every group. Say "graded trades = the *filled* column".
  - INT parameters (`filter.minScore`, `time.*Minutes`) must be whole numbers (`17.5` is refused), but the rules only say "Values are kept to 3 decimals".
  - Undo and revert entries print their description after "Verdict then:" ("Verdict then: Undid the change applied ..."), which reads as if Claude had said it.
  - After a restore bumps the version (R2T-9/PL-9), the engine can be "v5" with no history entry for v3-v5, while the per-version table has rows for them. One line saying "v3-v4 were made on this device before a restore; their changes are not recorded here" would stop Claude from inventing them.

### R2T-19 (L): the parameter table names three levels differently from the labels the app counts `level:` evidence by

- **Where:** `net/DayTradingParams.kt:225-229` (`levelDoc`, printed in the prompt's parameter table: "the pre-market high", "the 30-minute opening-range high", "floor pivot R1/R2") vs `LEVEL_LABELS` (`:169-177`: "the premarket high", "the opening-range high", "pivot R1/R2"); `SHAPE` basis help: `"level:<a level key such as prevHigh, or a level name from the tables>"`.
- **Problem:** the parameter table is one of "the tables". A basis copied from it (`"level:the pre-market high"`) counts 0 trades. Because the review takes `min(cited, own)`, the change is refused with "Only 0 graded trades in "level:the pre-market high"", although the level has plenty of trades. This fails safe but is confusing.
- **Fix:** build `levelDoc` from `LEVEL_LABELS` (plus a description), or let `Evidence.count("level:…")` also accept the doc names. Or tell Claude to use the level KEY (`level:premarketHigh`), which always works.
- **Same pattern, engine group:** the per-version table labels pre-versioning rows `v0 (logged before versions were recorded)` (`EngineTuningPrompt.kt:206-207`), but `Evidence.count("engine:…")` matches the stored `engine` string exactly, and for those rows that string is `""`. Citing that table label counts 0.

### R2T-20 (L): one answer can switch off every setup, leaving an engine that makes no plans (and so produces no further evidence)

- **Where:** `net/EngineTuning.kt:617-630` (`inconsistency` checks stop floors/ceilings and entry-vs-flat only); `net/DayTradingParams.kt` `setupEnabled`.
- **Problem:** at LARGE (8 changes) with 30+ trades in each setup, `setup.breakout/pullback/reclaim.enabled -> 0` are all accepted in one review. Every row is then declined ("The Breakout setup is switched off ..."). No new plans are logged, so the tuning loop has nothing new to learn from, and only Undo or Revert brings the engine back. A similar degenerate engine comes from `earliestEntryMinutes` + `avoidMiddayLull` + `lastEntryMinutes` leaving no window at all.
- **Fix:** add to `inconsistency()`: at least one setup enabled, and a non-empty entry window (open + earliest < 11:30 or 13:30 < close − lastEntry, when the lull is on). State it in the prompt's rules.

### R2T-21 (L): PL-15 residual: a stored value outside a (future, narrower) bound is still dropped silently

- **Where:** `net/DayTradingParams.kt:260-270` (`fromJson`: `if (spec.allows(v) && v != spec.default) m[k] = v`, else the key is skipped).
- **Problem:** if a later build narrows a spec's range, the running engine silently reverts that parameter to its ORIGINAL default (not the nearest allowed value) on the next load. There is no history entry, and the history's `paramsAfter`, parsed the same way, agrees with the new values, so neither the card nor the next prompt shows that anything moved. This is latent until a bound changes.
- **Fix:** clamp to the new bounds instead of dropping, and when `load` finds that a stored value was changed, record a history entry ("engine updated by app vX: key a -> b").

### R2T-22 (L, pre-existing): a failed settings write in Apply/Undo/Revert crashes the app

- **Where:** `ui/PortfolioViewModel.kt:8415-8418` (`saveEngine`: `db.setAll(...)` is not in `runCatching`), called from `applyEngineReview` / `undoEngineChange` / `revertEngine` inside `viewModelScope.launch` without a handler.
- **Problem:** an `SQLiteFullException` or a locked or corrupt DB propagates out of the coroutine and takes the process down. The transaction rolls back, so the engine and history stay consistent (good, thanks to PL-10's `setAll`), but Tj gets a crash instead of "couldn't save - nothing changed". `_engineApplying` is reset by `finally`; undo and revert have nothing to reset.
- **Fix:** wrap `saveEngine`'s DB write, and on failure toast "Couldn't save the engine change - nothing was changed" without installing.

