# CHECKPOINT 604 — read me first, then TASKS.md

**Written:** 2026-09-10T22:08:08Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `9af42bf` (this checkpoint is the commit after it)

## Just done
Closed the four gaps in the permanent release flow. (1) CLAUDE.md now describes what ship.sh ACTUALLY does - gates and pushes the commit, does NOT push a tag - and states that Claude triggers the build through the GitHub API because a tag push is 403 from a Claude container, with the reason recorded so no future session rediscovers it. (2) resume.sh now detects a release GitHub built that nobody recorded: it reads the TAGS (and now fetches with --tags, without which GitHub's server-side release tags would never arrive) and warns loudly with the exact record-release.sh command, because BUILDLOG gates the next release's versionCode and a missing entry would let the next version reuse a shipped code. (3) The workflow no longer uploads an 8MB APK artifact for 90 days alongside the identical Release asset - it keeps one for 7 days ONLY when a run fails, which is the only case where the binary is not already permanently published. (4) ship.sh now writes its own next step into CHECKPOINT.md via ckpt.sh, so an interruption between the push and the API trigger leaves the instruction on disk rather than only in a terminal nobody will read again. test_resume.sh is at 38 checks, six of them covering these seams.

## Do this next
Simulate an interruption at each stage of a release in a throwaway clone and confirm a cold session recovers from each, then reset TASKS.md.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  1b38c0e ckpt 603: Wrote Tj's 'this is the flow I want forever' request into TASKS.md and found f
  fc37ee5 ckpt 602: GITHUB NOW BUILDS, SIGNS AND PUBLISHES THE APK, END TO END - run #7 green thro
  22103f3 ckpt 601: Fixed a false positive my own change caused in test_resume.sh: the 'CI commits
  f0adaa1 ckpt 600: GITHUB HAS NOW BUILT AN APK - run #6 went green all the way through: unit test
  f1c1ac1 ckpt 599: Restructured releasing so GITHUB BUILDS ALL FUTURE APKS and Claude only writes
  18c477b ckpt 598: GitHub's first FULL build FAILED, and the cause was a real portability bug in 
  1bcc8ad ckpt 597: Made the CI versionCode gate distinguish a DOWNGRADE from a REBUILD, so GitHub
  90b926f ckpt 596: SHIPPED v7.8 (versionCode 65). All ship gates green: checkinit, build environm
  3adcc05 ship v7.8: Chart axis labels now carry the year when a window spans calendar years - a p
  66e50e1 ckpt 595: Fixed my own broken regression test rather than the code: DetailTabCrashTest's
```

(5 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
