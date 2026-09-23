# CHECKPOINT 1616 — read me first, then TASKS.md

**Written:** 2026-09-23T09:13:27Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/work-scheduling-capability-6jeiez` · **builds on:** `c7e94d5` (this checkpoint is the commit after it)

## Just done
2026-09-23b share flow code written (not yet compiled): PromptShare/ShareInbox (util/ShareFiles.kt), SharedAnswer classifier, ShareImportActivity + manifest filters + FileProvider, VM deliverPrompt/importShared/importSharedInbox, all 4 prompt buttons -> share sheet, DT buttons moved to top + blurb deleted

## Do this next
Compile (bash tools/gradle.sh compileDebugKotlin), write SharedAnswerTest + share routing tests, run suite, tick TASKS 2026-09-23b boxes, then full tests

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  0ed968e ckpt 1615: Rescheduled: new trigger trig_01TKwdBrdRvskoXiErydAJff fires 09:04 UTC with t
  a66fb00 ckpt 1614: Tj replaced the scheduled full test with: share-sheet prompt export + share-i
  9973390 ckpt 1613: Tj confirmed 'full text' was a typo for full test; schedule unchanged (09:04 
  a051c93 ckpt 1612: Tj asked for a full test to start automatically 2h from now (07:03 UTC req); 
  fbac630 ckpt 1611: SHIPPED v7.36 (code 93): SPY comparison fixed on zoomed 1D/5D charts; Release
  a9a829c ckpt 1610: gated v7.36 (code 93) and pushed it: checkinit, the full unit suite and the v
  98bb330 ckpt 1609: Fixed the SPY comparison on zoomed 1D/5D charts (fixed anchor for every range
  3f8f7e7 ckpt 1608: Logged Tj's 2026-09-22c request (SPY baseline still jumps when holding and dr
  dfa6002 ckpt 1607: SHIPPED v7.35 (code 92): R8 + app baseline profile + SQLite WAL + list conten
  01877da ckpt 1606: Triggered android.yml run 35774377550 for v7.35 code 92 (first run with the e
```

(13 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
