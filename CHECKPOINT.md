# CHECKPOINT 1721 — read me first, then TASKS.md

**Written:** 2026-09-24T21:16:20Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-success-claude-learn-t9ot3u` · **builds on:** `2823e7bf` (this checkpoint is the commit after it)

## Just done
VM engine store (PL-4 files adopted/not overwritten + tmp/rename, PL-9 version above log labels persisted, PL-10 mutex + Db.setAll), PL-5 64MB backup reads, PL-12 count, PL-14 routing, DA-2 tick score gate, DA-10 Claude features no lull, DA-11a v0 label for original, DA-13 no logging in last minute, DA-20 opening-bar wait, DA-21 Claude planPrice, PL-15 logged-today set, prompt DA-6 (account % objective/grid) + DA-14 text + new guard rules; full suite 1526/0

## Do this next
Tests for DA-2/10/13/20/21, PL-4/9/10/14; then second audit round (3 parallel agents) on the changed code; fix; full suite; delete tmp/PromptDumpTest; ship v8.0

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  13fcf19a ckpt 1720: Tuning guard: DA-3 (group derived from param, min with cited; LEVEL_LABELS s
  d49acd15 ckpt 1719: DayTradingRegradeTest 3/0 (selection rules, PL-2 old verdict kept on 404, PL
  932c31c1 ckpt 1718: DA/PL batch 1: grader DA-1 (partial detail + settled re-grade), DA-6 (grid i
  5576e0fb ckpt 1717: UI audit findings all fixed (UI-1..27, S-1, S-2) with tests (DayTradingLearn
  8c2e0bbe ckpt 1716: UI audit report complete (audits/2026-09-24c/ui.md, 27+2 findings); IDs list
  08201d44 ckpt 1715: Prompt trade table capped at 1000 rows (tables still cover all); EngineTunin
  71f26bd3 ckpt 1714: Own finding from reading a generated prompt: a buy-stop filled at an open al
  63de9fe8 ckpt 1713: Full-tests protocol started: floor green (1501/0); audit plan recorded in TA
  6701f184 ckpt 1712: Light pass fixes: tuning review off the main thread (importEngineTuning -> c
  be759bc3 ckpt 1711: Part B complete + full suite 1501/0; fixed: old-rules rows still re-gradable
```

(9 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
