# Round 2 platform audit (2026-09-25)

Read-only verification of the PL-1..PL-15 fixes (diff `5576e0fb..HEAD`, app/src/main)
plus a hunt for regressions: threading, battery/network, persistence, UI.

Status: IN PROGRESS - findings are appended as they are verified.

Severity: H = crash / data loss / runaway battery or network / wrong numbers; M; L.

## Findings

### R2P-1 (M) - "Undo last change" confirmation says the opposite of what it now does after a Revert
**Where:** `ui/EngineTuningUi.kt:204-216` (`EngineConfirmDialog`), used by `ResearchScreen.kt:259` and
`SettingsScreen.kt:426`; behaviour from `net/EngineTuning.kt:115` (`undoable` now prefers a trailing
KIND_REVERT) and `undo()` (PL-15 fix). Also `EngineTuningUi.kt:178-183` (`HistoryLine`).
**Problem.** The PL-15 fix made Undo take back a Revert (restoring every tuned value the revert removed).
The confirmation dialog is not state-aware and still reads: "The engine goes back to exactly how it was
before the most recent change Claude made." After a Revert, confirming it does the reverse: it re-installs
ALL of Claude's changes. The success toast ("back to how it was before that change") is also vague here.
Separately, `HistoryLine` marks only an APPLY as "(later taken back)"; an undone revert is still listed as
"reverted to the original" with no marker, so the history reads as if the engine were original.
**Failing scenario.** Engine v3 tuned -> Tj taps "Revert to original" (v4) -> later taps "Undo last change"
expecting (per the dialog) to remove Claude's latest change -> confirms -> the whole tuned engine (every
change) is back in force as v5.
**Fix.** Pass the undo target to `EngineConfirmDialog` (e.g. `kind` + `state.undoable?.kind`) and say
"Undo the revert? The tuned engine from before it (N settings) comes back" when the target is a revert;
tailor the toast the same way; in `HistoryLine` append "(later taken back)" when `h.undoneAt > 0` for any
kind. Add a test on the dialog text builder (pure function) for both targets.

### R2P-2 (M) - Cached bars are rounded, fresh bars are not: a re-grade from `dt_bars` can flip exact-touch verdicts
**Where:** `net/DayTradingEval.kt` `encodeBars` (`BigDecimal(v).setScale(6, HALF_UP)`) vs `parseBars`
(155-185, raw `optDouble`, no rounding); read back in `PortfolioViewModel.resolveOneDayTradingEntry`
(7666-7685, `cached(1)` / `cached(5)`); comparisons in `DayTradingGrader.grade` (380 `b.high >= spec.entry`,
393 `b.low <= spec.entry - tk`) and `runPosition`.
**Problem.** Yahoo's chart JSON carries float32 artifacts (a 12.34 print arrives as 12.34000015258789,
a 12.35 print as 12.350000381469727 - or just below the cent, depending on the value). The first grade of a
settled row uses those raw values; the cache stores them rounded to 6 dp (i.e. back to the exact cent).
Every later re-grade (the DA-1 settled re-grade reads the cache only if a settled grade already wrote it,
but every FUTURE `DayTradingGrader.VERSION` bump re-grades the whole 55-day window from `dt_bars`) compares
different numbers against the same plan levels. At exact touches the answer changes. Measured with the
same arithmetic (Python, float32 -> 6 dp HALF_UP) over cent levels $5.00-$49.99: for a limit entry at a cent
level with a bar low exactly one tick through, fresh and cached disagree in 1,064 of 4,500 levels; for a
buy-stop whose bar high prints exactly at the entry, 1,128 of 4,500. Claude plans use round cent levels,
so these ties are routine for them.
**Failing scenario.** Claude plan, buy-limit 12.35 (needs a low <= 12.34). The day's low prints 12.34:
fresh value 12.34000015 > 12.34 -> graded NO_ENTRY. Next grader bump -> re-graded from `dt_bars`
(12.34 <= 12.34) -> now a filled trade with a win/loss. The headline rate, the per-setup tables and
the tuning evidence all move with no market change. (The fresh path itself is also arbitrary: whether an
exact touch fills depends on which way float32 rounded that particular price.)
**Fix.** Make both paths identical and deterministic: round in `parseBars` exactly as `encodeBars` does
(to 6 dp, or better to 4 dp / the tick), so fresh and cached bars are the same numbers - or store exact
doubles in `encodeBars` (`Double.toString` round-trips) if the raw behaviour is to be kept. The first
option also removes the float32 lottery at exact touches. Test: a bar list with float32-artifact values
graded fresh and after `decodeBars(encodeBars(..))` must give the same outcome and detail.

### R2P-3 (L) - Any 400 on a one-minute request inside the one-minute window permanently downgrades a settled row to a 5-minute grade
**Where:** `net/DayTradingEval.kt:85-89` (`if (r.code == 400 || r.code == 422) return emptyList()`),
`PortfolioViewModel.resolveOneDayTradingEntry` 7675-7685 and 7714-7715; re-grade selection
`dayTradingRowsNeedingGrade` (never re-selects a final, current-version, non-partial row).
**Problem.** The caller only asks for 1m bars while `oneMinuteStillAvailable` (29 days after the close,
i.e. <= ~29.3 days from period1), which is inside Yahoo's 30-day 1m limit - so the 422 this branch was
written for should essentially never reach this caller, and what it does catch is an unexpected refusal
(a 400 from an edge/proxy, a malformed-request blip). That is read as "answered: nothing there": the row
falls back to 5m bars, is written final with `eval_version = VERSION`, and the 5m bars are cached as the
day's series. Nothing ever re-grades it on 1m bars, although they existed. Also, a 400/422 from query1 ends
the loop without asking query2. If the 5m request also gets a 400, an undecided row becomes
DATA_UNAVAILABLE (recoverable after 24 h - fine).
**Failing scenario.** Settled row, 3 days old; query1 answers the 1m request with a transient 400 ->
graded on 5m bars ("any bar that could be read either way was read as a loss") and never revisited.
**Fix.** Treat only a 422 (or a 400 whose body says the window is out of range, e.g. contains
"must be within") as answered-empty, and only let the caller fall back to 5m on it when the row is near or
past the 1m window; otherwise return null (FAILED, asked again next check). Optionally do not cache a
5m series while 1m bars should still exist.

