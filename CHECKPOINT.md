# CHECKPOINT 598 — read me first, then TASKS.md

**Written:** 2026-09-10T21:44:56Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `ad197c3` (this checkpoint is the commit after it)

## Just done
GitHub's first FULL build FAILED, and the cause was a real portability bug in this repo - not a CI quirk. Run #5: gates all passed (including the Android SDK step I had predicted would be the problem - it took 12s and was fine, so that prediction was wrong), then 'Unit tests' failed after 5m06s with '806 tests completed, 5 failed', and the build/verify/upload/publish steps were all SKIPPED. So GitHub has still never produced an APK. All five failures were ShotTest with java.io.FileNotFoundException at ShotTest.kt:100, whose capture() wrote to File('/home/claude/shots') - a hardcoded absolute path left over from the Cowork container this project migrated out of, the same class of leftover the header of tools/setup-android-sdk.sh describes. It passed here only because this container runs as ROOT and can mkdir under /home; a GitHub runner executes as the unprivileged user 'runner', mkdirs() returns false and the stream throws. Fixed by writing to a module-relative build/shots (Gradle runs unit tests with the module dir as the working directory, and app/build/ is already gitignored), overridable with -Dportfolio.shots.dir, and failing with a clear message rather than an opaque FileNotFoundException if the directory cannot be created. Verified locally: the five PNGs now land in app/build/shots and nothing touches /home/claude.

## Do this next
Push, then re-trigger the workflow with full_build=true. This is the second attempt at the full path; the unit tests should now pass on a runner, after which the APK, signature verification and artifact upload steps run for the first time. Verify the uploaded artifact's certificate rather than assuming it.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  1bcc8ad ckpt 597: Made the CI versionCode gate distinguish a DOWNGRADE from a REBUILD, so GitHub
  90b926f ckpt 596: SHIPPED v7.8 (versionCode 65). All ship gates green: checkinit, build environm
  3adcc05 ship v7.8: Chart axis labels now carry the year when a window spans calendar years - a p
  66e50e1 ckpt 595: Fixed my own broken regression test rather than the code: DetailTabCrashTest's
  5e88705 ckpt 594: ROOT-CAUSED the crash from the device log Tj sent - and it was NOT what I gues
  64b2e98 ckpt 593: Investigated Tj's two reports from the screen recording and fixed both finding
  cd3efec ckpt 592: Wrote Tj's new request into TASKS.md before any code: a chart-vs-SPY compariso
  ea956c3 ckpt 591: Re-verified CI after bumping actions/checkout and actions/setup-java to v5: ru
  ce48c8f ckpt 590: CI IS LIVE AND VERIFIED. Tj added the SIGNING_KEYSTORE_BASE64 secret and run #
  e7eb17f ckpt 589: Restructured the CI workflow so it cannot waste GitHub's free minutes, then va
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
