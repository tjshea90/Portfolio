# CHECKPOINT 683 — read me first, then TASKS.md

**Written:** 2026-09-12T20:36:31Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `3516147` (this checkpoint is the commit after it)

## Just done
Part 10 (Opus, money-accuracy): fixed the AVERAGE cost method's same-day pool depletion ORDER in Ledger.averageCost. A same-day sell took shares out of today's pool FIRST (minOf(covered, todayShares)) where FIFO - and the broker, and physical reality - consume the OLDEST shares first, so the two methods reported different sharesToday for identical trades: buy 100@10 a month ago, buy 50@12 today, sell 30@13 today gave FIFO 50 shares-bought-today and AVERAGE 20. sharesToday feeds Position.dayPnl, so the app's Today headline silently changed when the cost-basis preference in Settings changed - a number that has nothing to do with cost basis. A sale now only reaches today's pool once it has exhausted everything held from before today. 3 new tests: the two hand-built mixed-pool cases plus a 400-run randomised cross-method property (both methods must always agree on how many HELD shares were bought today - a fact about exposure, not an accounting convention - while deliberately NOT asserting avgCostToday agreement, which legitimately differs). Verified by reverting the one-line fix and confirming all 3 fail against the old code while the 6 pre-existing same-day tests pass either way, which is exactly why the bug survived CRX-1

## Do this next
Part 10 continues: close the ledger test-harness blind spots that let this survive (tools/ledger_port.py's average() has no today-tracking at all and its fifo() carries the pre-CRX-1 buggy side-map that nothing returns; tests/LedgerPropTest.java pins sessionInstant in year 2255 so no generated txn ever lands inside  and boughtTodayCount is always 0). Then make overselling visible (Position.oversold + UI) rather than inventing short-position accounting, then add the SPLIT transaction type.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  23b816d ckpt 682: Part 9 sweep batch 5 (final): fixed ReaderScreen's 3 sub-48dp touch targets, d
  02b6065 ckpt 681: Part 9 sweep batch 4 (ViewModel): commitImportAsync and clearIncorrectBuyFees 
  5dd6b42 ckpt 680: Part 9 sweep batch 3 (accessibility): fixed the raw-fill-painted-as-text WCAG 
  d30e68e ckpt 679: Part 9 sweep batch 2 (network layer): Http.postJson now runs the same cooldown
  4c9fe0d ckpt 678: Part 9 sweep batch 1: fixed Long-division truncation in Research.kt's earnings
  24d5bad ckpt 677: Wrote Tj's app-wide audit request (bugs, UI, code, internet efficiency) into T
  80c96c2 ckpt 676: Shipped v7.19 (code 76) end to end: GitHub Actions run #19 built, signed, veri
  7e1d46e ckpt 675: gated v7.19 (code 76) and pushed it: checkinit, the full unit suite and the ve
  bbf6e26 ckpt 674: Second code-review pass over Part 8b's own fixes is complete and the suite is 
  cf83572 ckpt 673: Recorded where Part 8b actually stands in TASKS.md after the last session was 
```

(6 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
