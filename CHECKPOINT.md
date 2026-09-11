# CHECKPOINT 664 — read me first, then TASKS.md

**Written:** 2026-09-11T20:02:30Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/day-trading-beginner-summaries-8x83la` · **builds on:** `ab96a6a` (this checkpoint is the commit after it)

## Just done
gated v7.17 (code 74) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
send Tj the APK from the Release and then run:
  bash tools/record-release.sh v7.17 "Day Trading score is now a blend of likelihood (the existing in-play momentum score) and confidence (a new fixed 5-item confirmation checklist - heavy volume, a real move, VWAP control, a confirmed opening-range breakout, structural strength), multiplied so both have to be high to reach 100 - per Tj's own request and example. Both halves kept and shown, not just the result: a visible 'Score = likelihood x confidence%' breakdown on the card and a fuller not-a-probability explanation in the detail view. Claude-authored picks are untouched. A code-review pass caught and fixed a real pre-existing bug (a Claude pick whose levels get dropped could have been scored from nothing) plus a redundant-computation cleanup, before shipping."

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  d6bc6ce ckpt 663: Implemented the confidence-blended Day Trading score (Part 6.2, Tj's override 
  03041da ckpt 662: Recorded Tj's 'skip the screener and use the current model' instruction for Pa
  c01e7f8 ckpt 661: gated v7.16 (code 73) and pushed it: checkinit, the full unit suite and the ve
  b2f2dbb ckpt 660: Added ResearchScore.beginnerSummary(): a pure, testable function that restates
  1f52ae5 ckpt 659: Recorded Tj's 'continue all tasks with sonnet' override (unblocks Part 6.2) an
  0ebe5ab ckpt 658: Shipped v7.15 (code 72): Part 6.1 of the Day Trading request - per-stock 1D ch
  c2227da ckpt 657: gated v7.15 (code 72) and pushed it: checkinit, the full unit suite and the ve
  77baaa5 ckpt 656: Recorded Tj's Part 6 request (day-trading charts/tabbed-detail/watchlist butto
  b64d83e ckpt 655: v7.14 (code 71) shipped end to end: run #14 built the correct commit (e1e25b1,
  e1e25b1 ckpt 654: gated v7.14 (code 71) and pushed it: checkinit, the full unit suite and the ve
```

(6 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
