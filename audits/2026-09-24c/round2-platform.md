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

