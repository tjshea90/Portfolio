# CHECKPOINT 1709 — read me first, then TASKS.md

**Written:** 2026-09-24T20:11:23Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-success-claude-learn-t9ot3u` · **builds on:** `aa60f925` (this checkpoint is the commit after it)

## Just done
EngineTuningTest 12/0: parse+routing, <30 trades blocks, Claude/old grades excluded, SMALL tier (3 changes, 10% step limiting, no switches, unknown/out-of-bounds/wrong-from refused), switch rules (75+, group>=30), stale version + cooldown blockers, stop floor<=ceiling, apply/undo/revert chain back to original, corrupt store, prompt contents (every param, history, data, schema, rules), algorithm names every param, VM import->apply->restart->revert + backup files

## Do this next
UI tests (success card v2 + engine card + review dialog render), then run the FULL unit suite; then B tick + light pass; then full-tests protocol

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  3c63cb86 ckpt 1708: Tuning UI: ui/EngineTuningUi.kt (EngineTuningCard: status, readiness tier, M
  52138f93 ckpt 1707: Part B core: net/EngineTuning.kt (state+history, load/undo/revert/apply, Evi
  749e9a6a ckpt 1706: Part A complete: success card v2 (sample note, expectancy+CI, PF, drawdown, 
  4754a2ac ckpt 1705: DayTradingGraderTest (16): E1 fill+stop inside first minutes (old rule credi
  7cef5c18 ckpt 1704: Logging gates E5 (planWaiting, price>=1) + E6 (replannedLive: newest bar <=1
  504d1186 ckpt 1703: db v10 (engine/features/eval_version/eval_detail, additive + onOpen repair +
  0bfe1dc0 ckpt 1702: B1 done: DayTradingParams wired into ResearchScore (defaults == original eng
  db990fbe ckpt 1701: Golden fixture captured from the ORIGINAL engine (daytrading_golden_v1.txt, 
  87a2c1a2 ckpt 1700: A1 audit done + design written: audits/2026-09-24c/DESIGN.md (10 accuracy de
  40eb93e4 ckpt 1699: Recorded Tj's 09-24c request (accurate day-trading success tracking + Claude
```

(3 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
