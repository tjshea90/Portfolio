# CHECKPOINT 1568 — read me first, then TASKS.md

**Written:** 2026-09-19T17:23:19Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/finish-started-test-vxwq2e` · **builds on:** `daf32fd` (this checkpoint is the commit after it)

## Just done
gated v7.28 (code 85) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.28 "Full-tests deep audit and fix pass (four parallel subsystem audits, every finding re-verified in code). DATA LOSS: the day-trading recommendation log was in no backup this app has ever written and cannot be rebuilt - now exported, restored and verified, never destructively. The private snapshot writer truncated before writing, so a failed write left a truncated file that 'restore latest snapshot' would then pick as the newest. The transaction/position dialogs and the Claude import review discarded everything on a stray tap outside, and a failed import threw away the whole extraction. WRONG NUMBERS: a missing S&P figure made the 52-week term score absolute return as if it were relative (a stock matching a +15% market scored as if it beat it); a dropped intraday request was indistinguishable from a closed session, so entry/stop/target flickered between two different trade plans; Finnhub fabricated a previous close, rendering a confident +0.00%; a fee entered on a deposit or dividend counted as a fee paid but never left cash. BATTERY/DATA: the day-trading tab re-downloaded three months of daily candles per symbol every 30 seconds - data that cannot change during a session - and polled identically at 3am and offline; now cached, conditional and gated on the market clock and connectivity. Also: the day-trading log could record yesterday's levels under today's date and log market holidays as real sessions; a background/foreground flick could pin 'Refresh failed' on screen for 15 minutes; deleting a position never navigated back; the detail header offered position controls for stocks you do not own. 1204 tests, 0 failures."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  9b9c71c ckpt 1567: Full-tests audit COMPLETE and green: 1204 tests / 0 failures / 0 skipped, che
  10b3209 ckpt 1566: Network/UI/day-trading audit fixes, all verified in code first. NETWORK: day-
  a110aa1 ckpt 1565: HIGH (found independently by TWO audits): the day-trading recommendation log 
  8f0e38d ckpt 1564: Scoring-audit fixes in (all verified against the code first, not taken on tru
  ca480ee ckpt 1563: Floor re-run: 1191 tests, 1 failure - an existing DbTest case pinned the OLD 
  68cb34e ckpt 1562: Resumed the interrupted full-test session. Verified and finished the in-fligh
  e19866d ckpt 1561: Full-tests floor GREEN: checkinit ok, full Gradle unit suite 1174 tests / 0 f
  18856f7 ckpt 1560: Logged Tj's 'full test the latest version' request in TASKS.md as the current
  ad86005 ckpt 1559: Investigated Tj's '404 on the release link' report: the repo is private, the 
  35518b0 ckpt 1558: v7.27 (code 84) shipped end to end: gated, GitHub Actions run 35419792350 bui
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
