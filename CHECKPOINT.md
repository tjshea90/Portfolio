# CHECKPOINT 606 — read me first, then TASKS.md

**Written:** 2026-09-10T22:10:01Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `57b1a9a` (this checkpoint is the commit after it)

## Just done
MY MISTAKE, and its cleanup: the end-to-end interruption simulation contaminated the real repository. I built the fixture by cloning the live repo, so it kept the real origin, and tools/record-release.sh legitimately does what it says - it pushed. Two bogus commits reached origin/main: a fake 'ckpt 605: gated v7.9' checkpoint and 'record v7.9 (code 66)', which also left versionCode 66 / versionName 7.9 in app/build.gradle.kts and a v7.9 line in BUILDLOG.md describing a release that was never built. No tag reached GitHub (tag pushes are 403 from here), so no fake Release exists and only v7.8 is on the remote. Reverted in the open by fast-forwarding the fixture's commits in and then restoring versionCode 65 / versionName 7.8 and deleting the v7.9 BUILDLOG line - no force-push, so the history shows both the error and the correction. The simulation itself was still valid and all three stages recovered correctly: a mid-code-change interruption produced the INTERRUPTED MID-CHANGE banner with the exact diff range, a cut between ship.sh and the API trigger left the trigger instruction in CHECKPOINT.md's 'Do this next', and a cut between GitHub publishing and record-release.sh produced the new 'WAS RELEASED BUT NEVER RECORDED' warning, which cleared once recorded and left the next versionCode correctly gated. THE LESSON, for any future session: fixtures for simulations must be built with 'git init' or have their origin removed, never a bare clone of the live repo - tools/test_resume.sh's own fixtures already do this correctly, which is why they have never had this problem.

## Do this next
Verify origin/main is back to the correct state, then reset TASKS.md and measure the per-turn context cost.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  a3ea6f2 ckpt 605: gated v7.9 (code 66) and pushed it. NOT yet built - GitHub has not been asked.
  0e3effd ckpt 604: Closed the four gaps in the permanent release flow. (1) CLAUDE.md now describe
  1b38c0e ckpt 603: Wrote Tj's 'this is the flow I want forever' request into TASKS.md and found f
  fc37ee5 ckpt 602: GITHUB NOW BUILDS, SIGNS AND PUBLISHES THE APK, END TO END - run #7 green thro
  22103f3 ckpt 601: Fixed a false positive my own change caused in test_resume.sh: the 'CI commits
  f0adaa1 ckpt 600: GITHUB HAS NOW BUILT AN APK - run #6 went green all the way through: unit test
  f1c1ac1 ckpt 599: Restructured releasing so GITHUB BUILDS ALL FUTURE APKS and Claude only writes
  18c477b ckpt 598: GitHub's first FULL build FAILED, and the cause was a real portability bug in 
  1bcc8ad ckpt 597: Made the CI versionCode gate distinguish a DOWNGRADE from a REBUILD, so GitHub
  90b926f ckpt 596: SHIPPED v7.8 (versionCode 65). All ship gates green: checkinit, build environm
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
