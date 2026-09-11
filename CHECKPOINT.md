# CHECKPOINT 657 — read me first, then TASKS.md

**Written:** 2026-09-11T17:52:08Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/project-continuation-a037zx` · **builds on:** `9f4b02d` (this checkpoint is the commit after it)

## Just done
gated v7.15 (code 72) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
send Tj the APK from the Release and then run:
  bash tools/record-release.sh v7.15 "Day Trading cards now show each stock's own 1-day chart (reusing PriceChart, no new widget); tapping a card opens the stock's real DetailScreen with the full Stats/Analysts/Earnings/News tabs and full-screen chart, with the existing Day Trading risk-plan content now shown at the top of the Overview tab for any pick; the existing watchlist star on DetailScreen covers the requested watchlist button with no new code. Live chart fetches for visible Day Trading rows go through the same MAX_PARALLEL_REQUESTS semaphore as the rest of the app, and ResearchScreen only subscribes to chart state while the Day Trading tab is actually open."

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  77baaa5 ckpt 656: Recorded Tj's Part 6 request (day-trading charts/tabbed-detail/watchlist butto
  b64d83e ckpt 655: v7.14 (code 71) shipped end to end: run #14 built the correct commit (e1e25b1,
  e1e25b1 ckpt 654: gated v7.14 (code 71) and pushed it: checkinit, the full unit suite and the ve
  534dde0 ckpt 653: pre-ship: Day Trading: the buy price is now a real entry TRIGGER, not the last
  616b01d ckpt 652: Fixed all 9 findings from the /code-review pass (high effort) over the whole R
  87237be ckpt 651: Review pass on the new engine found and fixed three real issues before shippin
```

(19 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
