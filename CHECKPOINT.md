# CHECKPOINT 1571 — read me first, then TASKS.md

**Written:** 2026-09-21T03:37:56Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/full-app-test-26vbpc` · **builds on:** `9f6b47f` (this checkpoint is the commit after it)

## Just done
Reconciled and fixed all findings from the 4 parallel subsystem audits: HIGH socialAt cadence bug (WSB trending froze after first fetch), MEDIUM Consensus2.label() Sell-mislabel bug, MEDIUM lost Claude import-review on process death (now persisted via Keys.PENDING_IMPORT), MEDIUM restore-Merge clobbering existing overrides, plus 3 LOW fixes (Restore-from-JSON dialog consistency, stale BRIEF.md news-source doc, day-trading-log restore manifest warning). Added 3 regression tests. Full suite: 1207 tests, 0 failures, checkinit ok.

## Do this next
Ship this pass per CLAUDE.md auto-ship policy: bump versionCode 86 / versionName 7.29 in app/build.gradle.kts, run ship.sh, trigger the GitHub Actions build, confirm green, record-release.sh, post the Release link

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  5539101 ckpt 1570: Full-tests floor green (1204 tests) + the un-CI'd compiled-class harnesses (S
  d0b0d69 ckpt 1569: Recorded v7.28 release in BUILDLOG.md (build was green, Release already publi
  934d899 ckpt 1568: gated v7.28 (code 85) and pushed it: checkinit, the full unit suite and the v
  9b9c71c ckpt 1567: Full-tests audit COMPLETE and green: 1204 tests / 0 failures / 0 skipped, che
  10b3209 ckpt 1566: Network/UI/day-trading audit fixes, all verified in code first. NETWORK: day-
  a110aa1 ckpt 1565: HIGH (found independently by TWO audits): the day-trading recommendation log 
  8f0e38d ckpt 1564: Scoring-audit fixes in (all verified against the code first, not taken on tru
  ca480ee ckpt 1563: Floor re-run: 1191 tests, 1 failure - an existing DbTest case pinned the OLD 
  68cb34e ckpt 1562: Resumed the interrupted full-test session. Verified and finished the in-fligh
  e19866d ckpt 1561: Full-tests floor GREEN: checkinit ok, full Gradle unit suite 1174 tests / 0 f
```

(23 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
