# CHECKPOINT 1553 — read me first, then TASKS.md

**Written:** 2026-09-19T03:28:20Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/full-app-tests-3ajf43` · **builds on:** `29fc217` (this checkpoint is the commit after it)

## Just done
Full-tests fixes batch 1: (1) Http.postJson now disconnects the socket on cancellation, mirroring Http.get's documented pattern - was paying for the whole body and holding a per-host permit on a cancelled Claude API call. (2) Fixed EarningsTab off-by-one (Long division truncation toward zero made a past earnings date still show 'In 0 days'). (3) Data-loss fix: promoted TxnEditorDialog's typed fields, EditPositionDialog's shares/cost, and every dialog-open flag (addingTxn/editingTxn-by-id/confirmDelete/pending via a new PendingAction.Saver/showAdd/showImport/importText/pendingRestore/confirmReplace) from remember to rememberSaveable across TxnEditor.kt, RowActions.kt, DetailScreen.kt, ActivityScreen.kt, PortfolioScreen.kt, WatchlistScreen.kt, SettingsScreen.kt - a full-test audit found these all reset silently on process death (not just rotation), losing in-progress typed money entries with no warning. All from the 4-way parallel full-test audit (network/caching/battery + UI subsystems).

## Do this next
Fix the day-trading HIGH finding (DayTradingTechnicals.sessionDay stamped from wall-clock instead of the actual bar date, defeating the stale-session guard on weekends/holidays/pre-market), then the smaller scoring findings (ScreenRow.merge earningsEstimated bug, ResearchScore withAnalyst off-center term, fund-closure dollar figure inconsistency, stale Position KDoc), then re-run full suite and reconcile.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  f90d691 ckpt 1552: Full-tests: floor checks green (checkinit + full Gradle unit suite, BUILD SUC
  a22f2c0 ckpt 1551: Made 'light tests' and 'full tests' permanent knowledge: added a 'Testing on 
  f69cc64 ckpt 1550: Permanently removed the Opus screener: deleted tools/screener.sh, tools/hooks
  2175dcc ckpt 1549: Shipped v7.26 (code 83): analyst-rating recency scoring, day-trading tracker 
  188cc8a ckpt 1548: v7.26 (code 83) gated and pushed; GitHub build run 35375660271 triggered and 
  82a0e39 ckpt 1547: gated v7.26 (code 83) and pushed it: checkinit, the full unit suite and the v
  67ca002 ckpt 1546: Recency scoring + day-trading cost/cumulative stats + retention-gate bug fix 
  2441caa ckpt 1545: Analyst-recency scoring (RatingRecency + holding + Recommend + VM + popup) an
  0c65883 ckpt 1544: Audited the recommendation + day-trading code; wrote Tj's 2026-09-18 request 
  9ad5997 ckpt 1543: Confirmed v7.25 (code 82) build green and Release published; recorded it in B
```

(21 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
