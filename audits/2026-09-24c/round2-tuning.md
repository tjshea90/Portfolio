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

