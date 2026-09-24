# CHECKPOINT 1663 — read me first, then TASKS.md

**Written:** 2026-09-24T14:01:44Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/full-app-tests-91htyl` · **builds on:** `3a12d03f` (this checkpoint is the commit after it)

## Just done
U-2 (Feed pull retries empty All-companies insider list) + U-3 (headline sweep stops under reader/detail) fixed, test written

## Do this next
A-2, A-3, then S-*, D-2..5, C-2..5; run suite after the batch

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md
     M app/src/test/java/com/tj/portfolio/FullTest0924Test.kt

## Last ten checkpoints
```
  ef549bf9 ckpt 1662: L-2 (quote re-entry judged on job + generation counter) + L-3 (background pr
  004f4a8f ckpt 1661: N-3 (quoteSummary retry only on 401) + N-4 (SEC requests paced 8/s) fixed; F
  0ffc1803 ckpt 1660: D-1 fixed: DT recommendations logged only from the live sweep for rows re-pl
  73878ac1 ckpt 1659: C-1 fixed: zoom into unfetched range keeps last series drawn (bridgedChart) 
  85278c6f ckpt 1658: N-2 fixed (empty well-formed v7 answer no longer disables batch) + A-1 fixed
  3a66627f ckpt 1657: L-1/N-1 fixed: Http cancel hook now fires on cancelling (was on completion =
  6fe3086d ckpt 1656: All 7 audit reports complete (7H/~25M/~50L). U-1 fixed: per-surface spinner 
  f993b5be ckpt 1655: Resumed after usage cap: 7 audit agents died 07:43 with partial reports (net
  d1129bc4 ckpt 1654: T-1 fixed: unit tests offline (Http gate via portfolio.test.offline, loopbac
  60420aee ckpt 1653: Floor green: checkinit ok, suite 1337/0
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
