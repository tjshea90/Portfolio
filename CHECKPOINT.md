# CHECKPOINT 1657 — read me first, then TASKS.md

**Written:** 2026-09-24T13:46:10Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/full-app-tests-91htyl` · **builds on:** `096fdf3d` (this checkpoint is the commit after it)

## Just done
L-1/N-1 fixed: Http cancel hook now fires on cancelling (was on completion = never mid-read); disconnect off main thread; HttpCancelTest 3/0 mutation-checked

## Do this next
N-2 (unknown symbol kills v7 batch), then A-1, C-1, D-1, then Ms

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  6fe3086d ckpt 1656: All 7 audit reports complete (7H/~25M/~50L). U-1 fixed: per-surface spinner 
  f993b5be ckpt 1655: Resumed after usage cap: 7 audit agents died 07:43 with partial reports (net
  d1129bc4 ckpt 1654: T-1 fixed: unit tests offline (Http gate via portfolio.test.offline, loopbac
  60420aee ckpt 1653: Floor green: checkinit ok, suite 1337/0
  88cdb9fe ckpt 1652: Recorded Tj follow-up (6h auto check-in); scheduled trig_016i6a9SLnAAgdG46oL
  d75a8027 ckpt 1651: Launched 7 parallel read-only audits -> audits/2026-09-24/*.md; floor gradle
  b8c15e9c ckpt 1650: Recorded Tj request 2026-09-24: full tests
  370bfa4c ckpt 1649: v7.39 shipped: run green on rerun, Release published, BUILDLOG recorded, TAS
  3243632a ckpt 1648: v7.39 run 35894509527 failed in emulator SDK download (infra, before any tes
  a8f3dfb6 ckpt 1647: v7.39 ship.sh passed; build run 35894509527 triggered
```

(5 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
