# CHECKPOINT 640 — read me first, then TASKS.md

**Written:** 2026-09-11T09:27:16Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `cb3df79` (this checkpoint is the commit after it)

## Just done
Resumed after a container restart into a stale-and-blocking checkpoint, and resolved it. CHECKPOINT 639's 'Do this next' said to WAIT for Tj to either switch models or say proceed on Sonnet, and nothing in Part 4 had been touched. The interrupted change the briefing flagged (1 auto-checkpoint after ckpt 639, TASKS.md only) turned out to be exactly the answer to that wait: a complete, coherent three-line edit recording Tj's 'go ahead with sonnet' as an explicit SCREENER override. Verified it is finished rather than half-written, so per CLAUDE.md's mid-change protocol it needed checkpointing, not repair - left as it was, CHECKPOINT.md would have told every future session to keep waiting for something that already happened. ALSO MATERIAL: get_session reports this session is configured AND currently served by claude-opus-5, so the SCREENER escalation condition is satisfied outright and the Sonnet override is not even load-bearing here - Part 4's money-accuracy logic gets Opus-class judgment, which is what the flag was asking for. Parts 1-3 are complete (10 ticked boxes, no unticked ones). Part 4 is fully specified and unstarted. Also noting three background tasks died with the container restart - all of them were CI waiters from yesterday's GitHub build work, long since irrelevant, so nothing was lost.

## Do this next
Start Part 4. Nothing blocks it: the override is recorded, the model is Opus, the design decisions are locked in TASKS.md and must not be re-litigated. Begin by reading the existing day-trading implementation - net/ResearchScore.kt dayTrading(), net/DayTradingBridge.kt, ui/ResearchScreen.kt, data/ResearchModels.kt - before any research, so the research targets what is actually there.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  cb64d0d ckpt 639: Recorded Tj's new Day Trading research request (Part 4 in TASKS.md) verbatim, 
  252e117 ckpt 638: v7.12 (code 69) fully shipped: recorded in BUILDLOG.md, GitHub Actions run #11
  9dd98b5 ckpt 637: gated v7.12 (code 69) and pushed it: checkinit, the full unit suite and the ve
  5b55903 ckpt 636: Full Gradle unit suite green: 871 tests, 0 failures, 0 errors, 0 skipped (849 
  c1fa285 ckpt 635: Wrote and verified tests for Day Trading: new DayTradingTest.kt (16 cases - Re
  0201c31 ckpt 634: Fixed a real bug found while planning tests: carryExplanations() carried notes
  3333c25 ckpt 633: Built the ResearchScreen.kt Day Trading tab: added Section.DAY_TRADING (key/ta
  f69f120 ckpt 632: Finished the ViewModel wiring for Day Trading: added ResearchSet.dtExplained/d
  cab1920 ckpt 631: Resumed after the interruption bootstrap flagged: verified the uncommitted net
  fcebfd2 ckpt 630: Wrote buildDayTrading() and toDayTradingRow() in Research.kt, restoring compil
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
