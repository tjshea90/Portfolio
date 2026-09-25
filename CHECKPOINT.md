# CHECKPOINT 1727 — read me first, then TASKS.md

**Written:** 2026-09-25T01:48:00Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-success-claude-learn-t9ot3u` · **builds on:** `3434d67c` (this checkpoint is the commit after it)

## Just done
Round-2 fixes: VM R2G-1/3/7 (eval_retry_at column), R2P-4/6/8, R2T-1/2/7/9/11/16/22/24; EngineTuning R2T-4/5/6/8/10/12/19/20/21/23; features R2T-3/17; ResearchScore R2T-14 (golden regenerated, 28 lines = only that text) /15; prompt R2T-18 + polish. EngineTuningTest 28/0, LearnUi 11/0, Params 13/0, golden 1/0

## Do this next
Tests: VM-level R2G-1/3/7, R2T-9 merge, R2T-11 double undo, R2T-24 corrupt, R2T-3 obb, R2T-15, R2P-5 wording; tick TASKS; full suite; delete tmp/PromptDumpTest; ship v8.0

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  568768f1 ckpt 1726: Grader round-2 fixes tested: R2G-2/4/5/6/8/9/10, R2P-2/3 (DayTradingGraderTe
  f6be63ae ckpt 1725: Round-2 reports complete (grading 10, platform 8, tuning 24), recorded in TA
  8659e455 ckpt 1724: Round-2 audits were cut off by a usage limit before writing anything; relaun
  6b5a909c ckpt 1723: Full suite 1536/0. Launched round-2 audits (3 read-only agents) writing audi
  4d26e96e ckpt 1722: All DA-1..21 and PL-1..15 fixed + tested (Logging 10/0 incl PL-3 stop-on-fai
  5daf36b7 ckpt 1721: VM engine store (PL-4 files adopted/not overwritten + tmp/rename, PL-9 versi
  13fcf19a ckpt 1720: Tuning guard: DA-3 (group derived from param, min with cited; LEVEL_LABELS s
  d49acd15 ckpt 1719: DayTradingRegradeTest 3/0 (selection rules, PL-2 old verdict kept on 404, PL
  932c31c1 ckpt 1718: DA/PL batch 1: grader DA-1 (partial detail + settled re-grade), DA-6 (grid i
  5576e0fb ckpt 1717: UI audit findings all fixed (UI-1..27, S-1, S-2) with tests (DayTradingLearn
```

(19 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
