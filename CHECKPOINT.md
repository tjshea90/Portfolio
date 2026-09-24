# CHECKPOINT 1718 — read me first, then TASKS.md

**Written:** 2026-09-24T20:58:57Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-success-claude-learn-t9ot3u` · **builds on:** `d4454e84` (this checkpoint is the commit after it)

## Just done
DA/PL batch 1: grader DA-1 (partial detail + settled re-grade), DA-6 (grid in account %), DA-7 (spike-low), DA-8 (known opens), DA-13 (bar-length cut-offs), DA-17 (truncated series); VM DA-12/PL-2, PL-3, PL-6 (dt_bars cache), PL-7, PL-8, PL-13, DA-19 (old rows skipped); GraderTest 24/0, EvalTest 41/0

## Do this next
VM/stat tests for PL-2/PL-13/DA-19 selection; then tuning batch DA-2..5, 10, 11, 14..16, 18, 20, 21, PL-4, 5, 9..12, 14, 15; prompt DA-6 text; full suite; ship v8.0

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  5576e0fb ckpt 1717: UI audit findings all fixed (UI-1..27, S-1, S-2) with tests (DayTradingLearn
  8c2e0bbe ckpt 1716: UI audit report complete (audits/2026-09-24c/ui.md, 27+2 findings); IDs list
  08201d44 ckpt 1715: Prompt trade table capped at 1000 rows (tables still cover all); EngineTunin
  71f26bd3 ckpt 1714: Own finding from reading a generated prompt: a buy-stop filled at an open al
  63de9fe8 ckpt 1713: Full-tests protocol started: floor green (1501/0); audit plan recorded in TA
  6701f184 ckpt 1712: Light pass fixes: tuning review off the main thread (importEngineTuning -> c
  be759bc3 ckpt 1711: Part B complete + full suite 1501/0; fixed: old-rules rows still re-gradable
  4cd2a354 ckpt 1710: DayTradingLearnUiTest 5/0 (success card v2 wording/notes/rules, large font, 
  6a9fe26a ckpt 1709: EngineTuningTest 12/0: parse+routing, <30 trades blocks, Claude/old grades e
  3c63cb86 ckpt 1708: Tuning UI: ui/EngineTuningUi.kt (EngineTuningCard: status, readiness tier, M
```

(14 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
