# CHECKPOINT 1565 — read me first, then TASKS.md

**Written:** 2026-09-19T17:04:14Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/finish-started-test-vxwq2e` · **builds on:** `b152870` (this checkpoint is the commit after it)

## Just done
HIGH (found independently by TWO audits): the day-trading recommendation log was absent from every backup this app has ever written. Nothing lost it on-device (append-only, no purge), so the gap was invisible until the one moment it mattered - a reinstall or new phone restored the ledger in full and started the recommendation history at zero, unrecoverably: each row is a plan made against live screener state that no longer exists plus an outcome measured against intraday bars Yahoo only serves ~55 days. Now exported and restored (BACKUP_VERSION 4), additive on BOTH replace and merge via INSERT OR IGNORE on the existing UNIQUE(symbol,trading_day) - so a device's own rows are never destroyed by a restore and restoring twice is a no-op. Count reported in the toast. Five new tests pin it: clean-install round trip incl. outcomes, pending rows not fabricating an outcome, double-restore, replace-never-deletes, and a v3 file with no such key still restoring.

## Do this next
Continue the audit fixes: day-trading live loop (30s refetch of immutable daily candles, no TTL/market-phase/online gate), Finnhub fabricating prevClose, saveToAppFolder truncating before write, effectiveTechnicals wiping same-session intraday on a failed fetch, and the UI dialog/cancellation findings. Then full suite + ship.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  8f0e38d ckpt 1564: Scoring-audit fixes in (all verified against the code first, not taken on tru
  ca480ee ckpt 1563: Floor re-run: 1191 tests, 1 failure - an existing DbTest case pinned the OLD 
  68cb34e ckpt 1562: Resumed the interrupted full-test session. Verified and finished the in-fligh
  e19866d ckpt 1561: Full-tests floor GREEN: checkinit ok, full Gradle unit suite 1174 tests / 0 f
  18856f7 ckpt 1560: Logged Tj's 'full test the latest version' request in TASKS.md as the current
  ad86005 ckpt 1559: Investigated Tj's '404 on the release link' report: the repo is private, the 
  35518b0 ckpt 1558: v7.27 (code 84) shipped end to end: gated, GitHub Actions run 35419792350 bui
  29cef66 ckpt 1557: gated v7.27 (code 84) and pushed it: checkinit, the full unit suite and the v
  58bc3c8 ckpt 1556: Logged Tj's ship request in TASKS.md and made it permanent policy in CLAUDE.m
  7b20d97 ckpt 1555: Full tests complete: fixed the DayTradingEval.Costs comment tightening (LOW f
```

(3 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
