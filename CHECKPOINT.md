# CHECKPOINT 1669 — read me first, then TASKS.md

**Written:** 2026-09-24T14:16:39Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/full-app-tests-91htyl` · **builds on:** `1fd924bb` (this checkpoint is the commit after it)

## Just done
S-8 fixed: ETF groups exclude strategy/state/HY-band funds; every deduped fund named; Etf suites green

## Do this next
D-2..D-5, then C-2..C-5, then full suite + L items

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md
     M app/src/test/java/com/tj/portfolio/EtfExposureTest.kt

## Last ten checkpoints
```
  36a766f8 ckpt 1668: S-6 (stale Claude fund dropped, stale words expire together) + S-7 (DT parag
  4bf2d7a7 ckpt 1667: S-5 fixed: app's relative earnings phrase rebuilt each time, Claude's words 
  d1de6713 ckpt 1666: S-4 fixed: old Research/Advice answers age from their asOf (answeredAt), not
  acb9c0fe ckpt 1665: S-1 (popup reports scorer's branch/weight for dated voteless panel) + S-2 (d
  026ca08a ckpt 1664: A-2 (merge re-arms repair only for inserted ids), A-3 (dup tolerance = round
  01258ec7 ckpt 1663: U-2 (Feed pull retries empty All-companies insider list) + U-3 (headline swe
  ef549bf9 ckpt 1662: L-2 (quote re-entry judged on job + generation counter) + L-3 (background pr
  004f4a8f ckpt 1661: N-3 (quoteSummary retry only on 401) + N-4 (SEC requests paced 8/s) fixed; F
  0ffc1803 ckpt 1660: D-1 fixed: DT recommendations logged only from the live sweep for rows re-pl
  73878ac1 ckpt 1659: C-1 fixed: zoom into unfetched range keeps last series drawn (bridgedChart) 
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
