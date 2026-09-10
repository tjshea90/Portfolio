# CHECKPOINT 592 — read me first, then TASKS.md

**Written:** 2026-09-10T21:05:06Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `ea956c3` (this checkpoint is the commit after it)

## Just done
Wrote Tj's new request into TASKS.md before any code: a chart-vs-SPY comparison bug he spotted while panning the chart, an app crash when opening News on some stocks, and a release of the next APK. He attached a 19MB screen recording; the container has no ffmpeg so frame extraction is being set up separately. Noted explicitly that the video is per-session and cannot be recovered by a future session, so anything learned from it has to be written into TASKS.md.

## Do this next
Extract frames from the video, then investigate: (1) whether the stock series and the SPY comparison line are rebased to the same start point as the visible window changes, (2) the News crash - check util/CrashLog.kt for a recorded stack trace first, this app logs its own crashes.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  ea956c3 ckpt 591: Re-verified CI after bumping actions/checkout and actions/setup-java to v5: ru
  ce48c8f ckpt 590: CI IS LIVE AND VERIFIED. Tj added the SIGNING_KEYSTORE_BASE64 secret and run #
  e7eb17f ckpt 589: Restructured the CI workflow so it cannot waste GitHub's free minutes, then va
  1d9b76c ckpt 588: Added GitHub Actions signed-release CI on Tj's decision, and closed the local 
  f42b374 ckpt 587: Ticked TASKS.md 1-5 (all written, tested via tools/test_resume.sh's 19 checks,
  cec13a5 ckpt 586: Built the APK auto-provisioning chain and proved the SDK half of it live. NEW 
  efa40b9 ckpt 585: Checked Tj's GitHub assumption and wrote the APK auto-provisioning request int
  c27799f ckpt 584: Installed the signing keystore and added a keystore check to bootstrap.sh, so 
  cdde919 ckpt 583: Independent re-verification pass of the whole resume system, adversarial rathe
  b51da53 ckpt 582: Finished the resume-system review: 9 defects found, all 9 fixed, all covered b
```
