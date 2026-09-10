# CHECKPOINT 599 — read me first, then TASKS.md

**Written:** 2026-09-10T21:51:50Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `43a91e4` (this checkpoint is the commit after it)

## Just done
Restructured releasing so GITHUB BUILDS ALL FUTURE APKS and Claude only writes the code - Tj's rule of 2026-09-10. ship.sh no longer builds or commits an APK: it runs the FULL gate here (checkinit, the whole unit suite, versionCode strictly higher than every code in BUILDLOG.md), then tags the commit and pushes the tag, which fires the workflow to build, sign from GitHub Secrets, verify the certificate on the artifact it just produced, and publish it under Releases. 'ship.sh --local' keeps the old build-here behaviour as a fallback for when GitHub is unavailable. The full suite still runs locally first on purpose: a red suite must never spend a runner's minutes. NEW tools/record-release.sh writes the BUILDLOG line ONLY after the run is green - BUILDLOG is load-bearing (both ship.sh and the workflow gate the next versionCode against it), so a line in it is a claim that a release exists, and writing it inside ship.sh would make the file lie whenever a run failed. Also fixed a bug this would have introduced: ship.sh derived the previous versionCode by globbing releases/*.apk, which returns 0 once APKs stop being committed and would have let a downgrade through; it now reads BUILDLOG.md, the same file the workflow gates against. TWO REAL WINS: the keystore is no longer needed to cut a release, because GitHub signs - so a fresh container on any Claude account can ship without app/sideload.jks ever being present; and the ~8MB-per-version APKs stop being cloned by every future session. test_resume.sh is at 30 checks, six of them guarding this mechanism.

## Do this next
Run #6 (the full build with the ShotTest portability fix) is still running. When it goes green, GitHub will have produced its first APK - verify the artifact's certificate rather than assuming it, then tag a real release through the new ship.sh path to prove the tag-driven route end to end.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  18c477b ckpt 598: GitHub's first FULL build FAILED, and the cause was a real portability bug in 
  1bcc8ad ckpt 597: Made the CI versionCode gate distinguish a DOWNGRADE from a REBUILD, so GitHub
  90b926f ckpt 596: SHIPPED v7.8 (versionCode 65). All ship gates green: checkinit, build environm
  3adcc05 ship v7.8: Chart axis labels now carry the year when a window spans calendar years - a p
  66e50e1 ckpt 595: Fixed my own broken regression test rather than the code: DetailTabCrashTest's
  5e88705 ckpt 594: ROOT-CAUSED the crash from the device log Tj sent - and it was NOT what I gues
  64b2e98 ckpt 593: Investigated Tj's two reports from the screen recording and fixed both finding
  cd3efec ckpt 592: Wrote Tj's new request into TASKS.md before any code: a chart-vs-SPY compariso
  ea956c3 ckpt 591: Re-verified CI after bumping actions/checkout and actions/setup-java to v5: ru
  ce48c8f ckpt 590: CI IS LIVE AND VERIFIED. Tj added the SIGNING_KEYSTORE_BASE64 secret and run #
```

(4 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
