# CHECKPOINT 603 — read me first, then TASKS.md

**Written:** 2026-09-10T22:05:24Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `fc37ee5` (this checkpoint is the commit after it)

## Just done
Wrote Tj's 'this is the flow I want forever' request into TASKS.md and found four real gaps by inspecting the current state rather than assuming it works. (1) CLAUDE.md is STALE: it still tells a session that ship.sh tags and pushes the tag, which is exactly the thing that returns 403 from a Claude container - a fresh session on another account would follow that and fail. (2) A release interrupted between GitHub's build and tools/record-release.sh leaves BUILDLOG.md without its entry and NOTHING reports it; BUILDLOG is what the next release's versionCode is gated against, so a missed entry lets the next version reuse a shipped code and produce an APK that cannot install. (3) Every full build uploads an 8MB artifact for 90 days AND publishes the same APK as a Release asset - the Release is permanent, so the artifact is a duplicate accumulating against a limited free storage allowance. (4) TASKS.md holding a finished job costs context on every turn of every future session.

## Do this next
Fix 1-3, cover them in test_resume.sh, then verify by SIMULATING an interruption at each stage of a release rather than reasoning about it. Reset TASKS.md last.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  fc37ee5 ckpt 602: GITHUB NOW BUILDS, SIGNS AND PUBLISHES THE APK, END TO END - run #7 green thro
  22103f3 ckpt 601: Fixed a false positive my own change caused in test_resume.sh: the 'CI commits
  f0adaa1 ckpt 600: GITHUB HAS NOW BUILT AN APK - run #6 went green all the way through: unit test
  f1c1ac1 ckpt 599: Restructured releasing so GITHUB BUILDS ALL FUTURE APKS and Claude only writes
  18c477b ckpt 598: GitHub's first FULL build FAILED, and the cause was a real portability bug in 
  1bcc8ad ckpt 597: Made the CI versionCode gate distinguish a DOWNGRADE from a REBUILD, so GitHub
  90b926f ckpt 596: SHIPPED v7.8 (versionCode 65). All ship gates green: checkinit, build environm
  3adcc05 ship v7.8: Chart axis labels now carry the year when a window spans calendar years - a p
  66e50e1 ckpt 595: Fixed my own broken regression test rather than the code: DetailTabCrashTest's
  5e88705 ckpt 594: ROOT-CAUSED the crash from the device log Tj sent - and it was NOT what I gues
```
