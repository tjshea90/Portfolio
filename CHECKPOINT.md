# CHECKPOINT 585 — read me first, then TASKS.md

**Written:** 2026-09-10T20:30:40Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `c27799f` (this checkpoint is the commit after it)

## Just done
Checked Tj's GitHub assumption and wrote the APK auto-provisioning request into TASKS.md before any code. FINDING: there is no CI in this repo and there never has been - no .github/ directory, and 'git log --all -- .github' is empty across the entire 559-commit history. Every APK including the shipped v7.7 was built by ship.sh inside a Claude container and committed to releases/, so nothing is built outside Claude today. Verified this container can provision itself instead: dl.google.com SDK zip 200, services.gradle.org 200, repo1.maven.org 200, 30GB free. So in-container auto-provisioning is the efficient answer and GitHub Actions is not needed - it would also require uploading the irreplaceable keystore into GitHub Secrets, moving it out of Tj's sole custody, which is his call and not something to do unasked.

## Do this next
Build tools/ensure-build-env.sh (items 1-6 in TASKS.md), wire it into ship.sh and a new tools/gradle.sh wrapper, then actually provision this container and run a real Gradle build to prove it.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M TASKS.md

## Last ten checkpoints
```
  c27799f ckpt 584: Installed the signing keystore and added a keystore check to bootstrap.sh, so 
  cdde919 ckpt 583: Independent re-verification pass of the whole resume system, adversarial rathe
  b51da53 ckpt 582: Finished the resume-system review: 9 defects found, all 9 fixed, all covered b
  a36dba0 ckpt 581: Fixed the four highest-severity resume defects and made the system self-verify
  cbed3ee ckpt 54: Reviewed the whole resume/checkpoint system end-to-end and confirmed 6 real def
  eb593ff ckpt 580: Completeness re-check of the resume system in a genuinely fresh session (new c
  c6b6dbf ckpt 576: Fixed a gap in the handoff itself: CHECKPOINT.md's own pointer told a resuming
  9b2c169 ckpt 574: CRITICAL FIX, live-verified: found and fixed the root cause of why autosave ne
  4e814ae ckpt 569: Added auto-merge-from-main to resume.sh (when origin/main has newer commits th
  a30bb1f ckpt 568: Second efficiency/robustness pass on the resume logic: added a stale-git-lock 
```
