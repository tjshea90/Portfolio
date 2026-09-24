# CHECKPOINT 1664 — read me first, then TASKS.md

**Written:** 2026-09-24T14:06:49Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/full-app-tests-91htyl` · **builds on:** `af75f16c` (this checkpoint is the commit after it)

## Just done
A-2 (merge re-arms repair only for inserted ids), A-3 (dup tolerance = rounding, not 0.5%), A-4 (numbered autosave copies recognised) fixed with tests

## Do this next
Run FullTest0924Test; then S-1,S-2,S-4..S-8, D-2..D-5, C-2..C-5

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md
     M app/src/test/java/com/tj/portfolio/FullTest0924Test.kt

## Last ten checkpoints
```
  01258ec7 ckpt 1663: U-2 (Feed pull retries empty All-companies insider list) + U-3 (headline swe
  ef549bf9 ckpt 1662: L-2 (quote re-entry judged on job + generation counter) + L-3 (background pr
  004f4a8f ckpt 1661: N-3 (quoteSummary retry only on 401) + N-4 (SEC requests paced 8/s) fixed; F
  0ffc1803 ckpt 1660: D-1 fixed: DT recommendations logged only from the live sweep for rows re-pl
  73878ac1 ckpt 1659: C-1 fixed: zoom into unfetched range keeps last series drawn (bridgedChart) 
  85278c6f ckpt 1658: N-2 fixed (empty well-formed v7 answer no longer disables batch) + A-1 fixed
  3a66627f ckpt 1657: L-1/N-1 fixed: Http cancel hook now fires on cancelling (was on completion =
  6fe3086d ckpt 1656: All 7 audit reports complete (7H/~25M/~50L). U-1 fixed: per-surface spinner 
  f993b5be ckpt 1655: Resumed after usage cap: 7 audit agents died 07:43 with partial reports (net
  d1129bc4 ckpt 1654: T-1 fixed: unit tests offline (Http gate via portfolio.test.offline, loopbac
```

(8 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
