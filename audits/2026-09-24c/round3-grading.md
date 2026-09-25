# Round 3 — grading/logging/stats fixes review (read-only)

Scope: `git diff 8659e455 f4d2f8cb` on DayTradingGrader.kt, DayTradingEval.kt,
DayTradingFeatures.kt, data/, util/Storage.kt, plus the day-trading grade/eval
functions in PortfolioViewModel.kt. Tests: DayTradingGraderTest.kt,
DayTradingRegradeTest.kt.

Done inline by the resuming session (2026-09-25): the two round-3 agents were cut off
by a usage limit after writing only their headers.

Status: COMPLETE

## Findings

### R3G-1 (medium, accuracy) — one host's 422 was read as "no one-minute bars"
`DayTradingEval.fetchDaySeries` set `refused` on the FIRST 422 and returned `emptyList()`
whenever the loop ended with it set — so query1's 422 beside query2's 5xx, network error
or local cooldown read as "Yahoo has no one-minute bars". The caller then graded the row
on five-minute bars and stamped it with the current grader version: graded on the coarser
bars for good — exactly what R2P-3 set out to prevent, and contrary to the code's own
comment ("once BOTH hosts have said so").
FIX: count refusals; `emptyList()` only when every host answered 422, else `null`
(asked again next time). Test: `R3G-1 one host's 422 beside the other's failure is not yet an answer`.

### R3G-2 (low, accuracy/consistency) — a gap bar straddling the stop was not flagged
`runPosition`: R2G-6 prices a no-open gap bar WHOLLY under the stop at its low (flagged).
A no-open gap bar (previous close above its high) that STRADDLES the stop was exited at the
stop and NOT flagged, though it may have opened under the stop. The entry side's straddle
(buy-stop, unknown open) is "at the level, flagged ambiguous"; the stop side now mirrors it.
FIX: `ambiguous` also when `!first && !b.open.isFinite()`. Test:
`R3G-2 a gap bar with no open that straddles the stop is marked ambiguous`.

### Checked, no change needed
- R2G-2 both readings of a suspect low: `worseOf` compares per-share after-cost values —
  valid, both readings are sized on the same entry/stop. A second suspect print later in the
  day is skipped without its own reading (the first reading is already the earlier, and the
  alternative is graded against the trade) — rare, pessimistic side only.
- R2G-3 cache only complete days: the raw reply is cached (not the flat-filtered list),
  one row per symbol-day (UNIQUE), so completeness judged on that row's flat time is sound.
- R2G-4 `through` from the whole reply; R2G-5 causal ruler (bars before a bar never change);
  R2G-6 unknown opens; R2G-8 old rows through their buy price; R2G-9 VERSION 3 — correct.
- R2G-10 sub-bar window -> PENDING -> settled `noBars()` -> DATA_UNAVAILABLE. The label says
  "no price history" for a day whose bars exist but are too coarse; it is excluded from every
  figure either way and re-checked from the cached bars (no request). Only reachable on
  five-minute bars (the logging gate keeps every plan >= 1 minute before its cut-off), i.e. a
  row first graded after one-minute bars expired. Left as is.
- R2G-7 `eval_retry_at`: created in the table AND in `ensureDayTradingLogV10`, which `onOpen`
  runs — so v7.41 installs gain the column before the first read. Not in backups (by design).
- R2G-1 a 1m verdict never re-decided on 5m: only rows with a 1m `eval_detail` take the path;
  every row on the v7.41 phone is v0 with no detail, so the coming re-grade is unaffected.
- R2P-4 `readText` pre-size `coerceIn(64 KiB, maxBytes)`: every caller passes >= 64 KiB.
- `evaluateResolved` (touch rules) is test-only now; R2G-10's early return does not reach the app.

## END OF REPORT (complete)
