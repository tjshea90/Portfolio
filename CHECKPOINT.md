# CHECKPOINT 1551 — read me first, then TASKS.md

**Written:** 2026-09-19T03:07:49Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/remove-opus-screener-g4vi3k` · **builds on:** `0311674` (this checkpoint is the commit after it)

## Just done
Made 'light tests' and 'full tests' permanent knowledge: added a 'Testing on request' section to CLAUDE.md (auto-loaded every session regardless of hooks) spelling out the exact trigger phrases and the light-test protocol (checkinit + full unit suite, diff review for bugs/UI-logic issues, ripple-effect grep, fix, re-run once if major findings) and the full-test protocol (no budget limit, whole-app audit for bugs, code/UI quality, network efficiency vs BRIEF.md source order, caching/data-retention gaps, scoring/engine logic vs locked decisions, battery/fgScope correctness, suggests parallel subagents by subsystem, fix everything, re-verify). Logged Tj's request verbatim in TASKS.md.

## Do this next
Nothing pending - await Tj's next request

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  f69cc64 ckpt 1550: Permanently removed the Opus screener: deleted tools/screener.sh, tools/hooks
  2175dcc ckpt 1549: Shipped v7.26 (code 83): analyst-rating recency scoring, day-trading tracker 
  188cc8a ckpt 1548: v7.26 (code 83) gated and pushed; GitHub build run 35375660271 triggered and 
  82a0e39 ckpt 1547: gated v7.26 (code 83) and pushed it: checkinit, the full unit suite and the v
  67ca002 ckpt 1546: Recency scoring + day-trading cost/cumulative stats + retention-gate bug fix 
  2441caa ckpt 1545: Analyst-recency scoring (RatingRecency + holding + Recommend + VM + popup) an
  0c65883 ckpt 1544: Audited the recommendation + day-trading code; wrote Tj's 2026-09-18 request 
  9ad5997 ckpt 1543: Confirmed v7.25 (code 82) build green and Release published; recorded it in B
  17484a6 ckpt 1542: gated v7.25 (code 82) and pushed it: checkinit, the full unit suite and the v
  7106821 ckpt 1541: Fixed 8 real bugs from a 4-way parallel audit (day-trading logic, UI, network
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
