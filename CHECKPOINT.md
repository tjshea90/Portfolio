# CHECKPOINT 1597 — read me first, then TASKS.md

**Written:** 2026-09-22T19:01:16Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-app-testing-i6cfun` · **builds on:** `b5eb381` (this checkpoint is the commit after it)

## Just done
Every audit finding now written. LOWs done: D-L8, D-L10, S-L5, S-L6, S-L7 (suite green). Written: S-L8, S-L9, N-L5..N-L8, U-L1..U-L4 (U-L3 = ET calendar-day earnings countdown with tests pinned to fixed moments). run13 running.

## Do this next
When run13 green: tick remaining; adversarial re-read of the whole session diff (git diff 24b6fc9..HEAD) + grep callers of changed signatures; re-run; bump version; ship.sh; trigger GitHub build; record; post link.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  57d9cc1 ckpt 1596: Suite 1286/0: A-L9/A-L10/A-L11, D-L5/D-L6/D-L7 done. Wrote D-L8 (throttled re
  61de483 ckpt 1595: All MEDIUMs done (suite 1281/0). LOWs: A-L6 done; A-L9 (pre-destructive snaps
  14c0bdb ckpt 1594: N-M3 done (suite green). Wrote N-M4 (post-close live loop at 5min), U-M1/U-M2
  05a7ab9 ckpt 1593: Suite 1277/0: S-M4 (reasons toggle + red flag first), N-M1 (blank crumb while
  dc799f8 ckpt 1592: Suite green 1272/0 incl D-H2, S-H1, D-M3, D-M4(+D-L9 NYSE rules calendar). Wr
  4561126 ckpt 1591: Floor green (1261/0). A-H2/A-M3/A-M4/A-L7/D-H1 ticked. Wrote D-H2 (account fi
  c56eca8 ckpt 1590: Wrote A-H2/A-M3/A-M4/A-L7: Ledger.replayOrder (split-first on date ties + ove
  a071192 ckpt 1589: Resumed full-tests fix pass. Finished A-H1 (EditPositionDialog only overrides
  f0245d7 ckpt 1588: All 5 audit agents reported (~45 findings incl. HIGHs in accounting, day-trad
  d816196 ckpt 1587: Found and fixed (own review of v7.33 diff): session rollover left planByClaud
```

(15 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
