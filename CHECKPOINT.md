# CHECKPOINT 1711 — read me first, then TASKS.md

**Written:** 2026-09-24T20:15:33Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-success-claude-learn-t9ot3u` · **builds on:** `5ee51fbd` (this checkpoint is the commit after it)

## Just done
Part B complete + full suite 1501/0; fixed: old-rules rows still re-gradable now 'being re-checked' (regrading) not 'expired'; auto-eval takes up to 3 batches; B2-B5 ticked

## Do this next
Light pass on the whole diff (callers of changed signatures, Compose state, nulls), then the full-tests protocol: <=3 parallel read-only audits (daytrading+tuning / network+persistence / UI+lifecycle+perf) writing audits/2026-09-24c/*.md

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  4cd2a354 ckpt 1710: DayTradingLearnUiTest 5/0 (success card v2 wording/notes/rules, large font, 
  6a9fe26a ckpt 1709: EngineTuningTest 12/0: parse+routing, <30 trades blocks, Claude/old grades e
  3c63cb86 ckpt 1708: Tuning UI: ui/EngineTuningUi.kt (EngineTuningCard: status, readiness tier, M
  52138f93 ckpt 1707: Part B core: net/EngineTuning.kt (state+history, load/undo/revert/apply, Evi
  749e9a6a ckpt 1706: Part A complete: success card v2 (sample note, expectancy+CI, PF, drawdown, 
  4754a2ac ckpt 1705: DayTradingGraderTest (16): E1 fill+stop inside first minutes (old rule credi
  7cef5c18 ckpt 1704: Logging gates E5 (planWaiting, price>=1) + E6 (replannedLive: newest bar <=1
  504d1186 ckpt 1703: db v10 (engine/features/eval_version/eval_detail, additive + onOpen repair +
  0bfe1dc0 ckpt 1702: B1 done: DayTradingParams wired into ResearchScore (defaults == original eng
  db990fbe ckpt 1701: Golden fixture captured from the ORIGINAL engine (daytrading_golden_v1.txt, 
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
