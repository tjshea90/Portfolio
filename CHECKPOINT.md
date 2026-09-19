# CHECKPOINT 1550 — read me first, then TASKS.md

**Written:** 2026-09-19T02:55:28Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/remove-opus-screener-g4vi3k` · **builds on:** `9f16612` (this checkpoint is the commit after it)

## Just done
Permanently removed the Opus screener: deleted tools/screener.sh, tools/hooks/screen.sh, tools/test_screener.sh, SCREENER.md; dropped the UserPromptSubmit hook block from both tools/session-root-hooks.json and .claude/settings.json; cleaned the stray comment reference in tools/hooks/lib.sh; replaced CLAUDE.md's screener section with a one-line model note; re-ran install-hooks.sh which also stripped the already-installed UserPromptSubmit entry from the live session-root settings file. Sessions now just use whatever model is running, no flag/stop step.

## Do this next
Nothing pending - await Tj's next request

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
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

(6 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
