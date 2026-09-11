# CHECKPOINT 610 — read me first, then TASKS.md

**Written:** 2026-09-11T00:05:12Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/github-request-screener-kawulf` · **builds on:** `6f6a076` (this checkpoint is the commit after it)

## Just done
Wrote Tj's request-screener ask into TASKS.md verbatim, with a design note (UserPromptSubmit hook via the existing tools/hooks/ multi-repo coordinator, criteria doc in SCREENER.md) before writing any code, per CLAUDE.md.

## Do this next
Build SCREENER.md, tools/screener.sh, tools/hooks/screen.sh, lib.sh/emit.py support, wire into both settings templates, write tools/test_screener.sh, update CLAUDE.md.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  13ca548 ckpt 609: Wrote Tj's buy/hold/sell-per-holding request into TASKS.md verbatim with a fea
  a5254d3 ckpt 608: Final verification of the permanent flow. Chased the one RED that ckpt 607 rec
  2101672 ckpt 607: Verified origin/main is fully restored after my fixture contamination: version
  47d403b ckpt 606: MY MISTAKE, and its cleanup: the end-to-end interruption simulation contaminat
  a3ea6f2 ckpt 605: gated v7.9 (code 66) and pushed it. NOT yet built - GitHub has not been asked.
  0e3effd ckpt 604: Closed the four gaps in the permanent release flow. (1) CLAUDE.md now describe
  1b38c0e ckpt 603: Wrote Tj's 'this is the flow I want forever' request into TASKS.md and found f
  fc37ee5 ckpt 602: GITHUB NOW BUILDS, SIGNS AND PUBLISHES THE APK, END TO END - run #7 green thro
  22103f3 ckpt 601: Fixed a false positive my own change caused in test_resume.sh: the 'CI commits
  f0adaa1 ckpt 600: GITHUB HAS NOW BUILT AN APK - run #6 went green all the way through: unit test
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
