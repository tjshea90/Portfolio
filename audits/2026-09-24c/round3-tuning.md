# Round 3 — tuning / engine-store / UI fixes review

Scope: `git diff 8659e455 f4d2f8cb` over EngineTuning.kt, EngineTuningPrompt.kt,
DayTradingParams.kt, ResearchScore.kt, EngineTuningUi.kt, SettingsScreen.kt,
ResearchScreen.kt, Db.kt, plus PortfolioViewModel engine store paths.

Done inline by the resuming session (2026-09-25): the two round-3 agents were cut off
by a usage limit after writing only their headers.

Status: COMPLETE

## Findings

### R3T-1 (low, wording) — "to the global X" when the global is off
R2T-8's refusal for switching a per-setup override OFF said "moves this setup from 2 to
the global 10" for `setup.<s>.targetCapR` / `minRewardRisk` while the global setting is
itself off: [onBase] then falls back to the lenient end of the range, which is not "the
global". The limit itself is right (switching a 2R cap off = no cap, as far as the range's
lenient end); the words were wrong, and the prompt's rule line said the same.
FIX: `globalOn()`; the refusal says "no limit at all (the global setting is off too - as
lenient as X)"; the prompt line adds "(or, when the global is off too, to the lenient end of
its range)". Test: `R3T-1 switching an override off when the global is off too says so`.

### Checked, no change needed
- R2T-2 `lastApplyAt`: last history entry whose `paramsAfter == params` (equality is by the
  value map); default params -> 0. Apply / undo / undo-of-revert / revert traced — correct.
- R2T-6/R2T-20 whole-answer consistency loop: terminates (one refusal per pass, `idx < 0`
  breaks); pre-existing conflicts excluded by message. A change refused there does not free
  its slot in the tier's change count — conservative, left.
- R2T-11 `expectVersion`: the dialog's `onConfirm` captures the version it was composed
  with, so a double tap is a no-op; if the engine moves while the dialog is open, the text
  recomposes with it — Tj confirms what he sees.
- R2T-16 log only plans made by the labelled engine: `planEngine` is stamped from the same
  snapshot's `tunedLabel` at merge; unconfirmed declines keep the old label -> not logged.
- R2T-17 `eh` hash: `toJson()` iterates a sorted map — deterministic for equal params.
- R2T-21 `fromJson` clamp; R2T-22 `saveEngine` false -> toast, `applying` reset in `finally`;
  R2T-24 corrupt row -> backup files read, never overwritten; R2T-7 restore under the lock
  (the inner adopt launches its own coroutine - no self-deadlock on the non-reentrant mutex).
- R2T-9 Merge takes the newer engine: saveEngine always writes BOTH keys, so a file with a
  history always carries its engine; `loadEngine` then rewrites the backup files.
- R2T-23 a nameless / valueless change is listed refused. A valueless first entry still
  reserves its key, so a later well-formed duplicate is "listed twice" — malformed-answer
  edge only, left.
- R2T-15 no opening bar by 09:36 -> declined (filter on only); R2T-3 `obb` only once the bar
  closed; the prompt's "shown before the first 5-min bar closed" bucket — correct.

## END OF REPORT (complete)
