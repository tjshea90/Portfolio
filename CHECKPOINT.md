# CHECKPOINT 1567 — read me first, then TASKS.md

**Written:** 2026-09-19T17:21:37Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/finish-started-test-vxwq2e` · **builds on:** `3e78a72` (this checkpoint is the commit after it)

## Just done
Full-tests audit COMPLETE and green: 1204 tests / 0 failures / 0 skipped, checkinit ok, randomised ledger harness clean. Final batch: confined transaction fees to TRADES (a fee on a DEPOSIT/DIVIDEND counted as a fee paid via Ledger.fees() but cashEffect never subtracted it, so cash was permanently overstated by that amount; a FEE row with both double-counted itself) and updated the TxnEditorTest assertion that had pinned the old contract; the backup's own read-back verification now covers the day-trading log too, so 'backup verified' can never be reported over a copy that silently lost the one section that cannot be rebuilt; day-trading capture now requires the row's OWN sessionDay to be today, which closes three log-poisoning routes at once (a pre-open sweep logging YESTERDAY's levels under today's key and INSERT OR IGNORE making it permanent, market holidays logging as real sessions that are DATA_UNAVAILABLE forever and inflate the session count, and a priceless Claude row whose buy-limit entry gets read as a buy-stop); DayTradingEval now fetches closed sessions conditionally and still tries the second host on an unparseable answer; plus the scoring doc/constant corrections (leanLabel's false equivalence claim, the PEG term's unreachable +20, the unnamed 0.3R target standoff) and the 'upper bound' stats wording that was only true while the number was positive.

## Do this next
Ship it: bump versionCode/versionName, ship.sh, trigger android.yml, confirm green, record-release.sh, post the link.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  10b3209 ckpt 1566: Network/UI/day-trading audit fixes, all verified in code first. NETWORK: day-
  a110aa1 ckpt 1565: HIGH (found independently by TWO audits): the day-trading recommendation log 
  8f0e38d ckpt 1564: Scoring-audit fixes in (all verified against the code first, not taken on tru
  ca480ee ckpt 1563: Floor re-run: 1191 tests, 1 failure - an existing DbTest case pinned the OLD 
  68cb34e ckpt 1562: Resumed the interrupted full-test session. Verified and finished the in-fligh
  e19866d ckpt 1561: Full-tests floor GREEN: checkinit ok, full Gradle unit suite 1174 tests / 0 f
  18856f7 ckpt 1560: Logged Tj's 'full test the latest version' request in TASKS.md as the current
  ad86005 ckpt 1559: Investigated Tj's '404 on the release link' report: the repo is private, the 
  35518b0 ckpt 1558: v7.27 (code 84) shipped end to end: gated, GitHub Actions run 35419792350 bui
  29cef66 ckpt 1557: gated v7.27 (code 84) and pushed it: checkinit, the full unit suite and the v
```

(9 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
