# CHECKPOINT 1733 — read me first, then TASKS.md

**Written:** 2026-09-25T02:21:21Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-in-progress-8vmll2` · **builds on:** `3c4cdabe` (this checkpoint is the commit after it)

## Just done
gated v8.0 (code 99) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v8.0 "Day Trading success rate rebuilt to count only trades you could really have made: every recommendation is graded as one real bracket order placed the moment the card showed it (1-minute bars first, nothing before the recommendation, real fills at gaps, stops before targets in an unclear bar, bad prints ignored, costs taken off, the plan's own cancel and be-flat times, only trades the portfolio could have paid for). The card shows real P&L terms - expectancy with a 95% range, profit factor, drawdown, sample-size warnings - and grades automatically when the tab opens. New: Improve the engine with Claude - a Make tuning prompt button shares a full prompt file (goal, engine, every parameter and change so far, graded trades and breakdowns) with Claude; its answer imports back, is checked by the app (small samples blocked or limited, bounds, consistency, 20 trades between changes), shown for your confirmation, and applied. Undo last change and Revert to original any time (also in Settings), with backup files in app storage. Three rounds of audits fixed ~100 findings. 1561 tests, 0 failures."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  ebc9c885 ckpt 1732: C1 done: R3G-2 scoped to realOpens (touch rules keep NaN = no gap); full sui
  5e9f0eb1 ckpt 1731: Round 3 done inline: R3G-1 (422 from both hosts), R3G-2 (straddle gap ambigu
  51ef498a ckpt 1730: Resumed: round-3 agents were cut off with empty reports; recorded resume poi
  f4d2f8cb ckpt 1729: Full suite 1559/0 after round-2 fixes (Improve0924b fixture given features);
  35e4f70b ckpt 1728: All 42 round-2 findings fixed + tested (Regrade 6, Logging 14, Tuning 28, Gr
  e99ad8dd ckpt 1727: Round-2 fixes: VM R2G-1/3/7 (eval_retry_at column), R2P-4/6/8, R2T-1/2/7/9/1
  568768f1 ckpt 1726: Grader round-2 fixes tested: R2G-2/4/5/6/8/9/10, R2P-2/3 (DayTradingGraderTe
  f6be63ae ckpt 1725: Round-2 reports complete (grading 10, platform 8, tuning 24), recorded in TA
  8659e455 ckpt 1724: Round-2 audits were cut off by a usage limit before writing anything; relaun
  6b5a909c ckpt 1723: Full suite 1536/0. Launched round-2 audits (3 read-only agents) writing audi
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
