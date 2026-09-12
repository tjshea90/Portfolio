# CHECKPOINT 678 — read me first, then TASKS.md

**Written:** 2026-09-12T05:41:18Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `6471830` (this checkpoint is the commit after it)

## Just done
Part 9 sweep batch 1: fixed Long-division truncation in Research.kt's earnings-day math (catalystFor/catalystSoon disagreed and both misread an after-hours release as 'earnings today' for ~24h), deduped a redundant rewardRisk() call and a stale docstring reference in ResearchScore.kt, and fixed TxnEditor.kt accepting literal NaN/Infinity text past validation plus an unvalidated negative fee - all narrow bugs from the 7 parallel area audits, all with new regression tests

## Do this next
Continue Part 9 fixes: network-layer findings (Http.postJson bypassing rate-limit gating, dead code, duplicated Yahoo-number-unwrap helper), then UI accessibility findings (raw fill colors used as text in MetricUi/DetailTabs/AdviceScreen/ResearchScreen). One ledger money-accuracy finding (AVERAGE-cost same-day P&L depletion order bug) is flagged for Tj, not yet fixed - SCREENER.md names cost-basis/ledger explicitly.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  24d5bad ckpt 677: Wrote Tj's app-wide audit request (bugs, UI, code, internet efficiency) into T
  80c96c2 ckpt 676: Shipped v7.19 (code 76) end to end: GitHub Actions run #19 built, signed, veri
  7e1d46e ckpt 675: gated v7.19 (code 76) and pushed it: checkinit, the full unit suite and the ve
  bbf6e26 ckpt 674: Second code-review pass over Part 8b's own fixes is complete and the suite is 
  cf83572 ckpt 673: Recorded where Part 8b actually stands in TASKS.md after the last session was 
  30de4b9 ckpt 672: Fixed all 6 code-review findings on Part 8b: the RVOL gate now scales by elaps
```

(12 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
