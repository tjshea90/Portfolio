# CHECKPOINT 1720 — read me first, then TASKS.md

**Written:** 2026-09-24T21:08:13Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-success-claude-learn-t9ot3u` · **builds on:** `7cba0938` (this checkpoint is the commit after it)

## Just done
Tuning guard: DA-3 (group derived from param, min with cited; LEVEL_LABELS single source), DA-4 (switch-on step from lenient end/global), DA-5 (basedOn + from required), DA-11 (load: version >= log labels, corrupt engine -> history), DA-15 (lastEntry >= flat+10), DA-16 (3-decimal values, from tol 5e-4), DA-18 (bools only for BOOL), PL-15 undo-a-revert; EngineTuningTest 20/0, golden 1/0

## Do this next
VM: loadEngine logVersion + PL-4 backup files not overwritten + temp/rename, PL-10 mutex + Db.setAll, PL-11 apply re-review off Main, PL-12 count/LaunchedEffect key, PL-14 routing, PL-5 backup cap/compact; DA-2, DA-10, DA-13 logging gate, DA-20, DA-21; prompt DA-6/DA-14 text; full suite; ship v8.0

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  d49acd15 ckpt 1719: DayTradingRegradeTest 3/0 (selection rules, PL-2 old verdict kept on 404, PL
  932c31c1 ckpt 1718: DA/PL batch 1: grader DA-1 (partial detail + settled re-grade), DA-6 (grid i
  5576e0fb ckpt 1717: UI audit findings all fixed (UI-1..27, S-1, S-2) with tests (DayTradingLearn
  8c2e0bbe ckpt 1716: UI audit report complete (audits/2026-09-24c/ui.md, 27+2 findings); IDs list
  08201d44 ckpt 1715: Prompt trade table capped at 1000 rows (tables still cover all); EngineTunin
  71f26bd3 ckpt 1714: Own finding from reading a generated prompt: a buy-stop filled at an open al
  63de9fe8 ckpt 1713: Full-tests protocol started: floor green (1501/0); audit plan recorded in TA
  6701f184 ckpt 1712: Light pass fixes: tuning review off the main thread (importEngineTuning -> c
  be759bc3 ckpt 1711: Part B complete + full suite 1501/0; fixed: old-rules rows still re-gradable
  4cd2a354 ckpt 1710: DayTradingLearnUiTest 5/0 (success card v2 wording/notes/rules, large font, 
```

(11 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
