# CHECKPOINT 591 — read me first, then TASKS.md

**Written:** 2026-09-10T20:58:20Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `ce48c8f` (this checkpoint is the commit after it)

## Just done
Re-verified CI after bumping actions/checkout and actions/setup-java to v5: run #4 (gates-only dispatch) succeeded in 12 seconds for 0 billable minutes, keystore restored at 2218 bytes and fingerprint matched, and the Node 20 deprecation warnings are gone. Confirmed the trigger design holds under real conditions: across the whole repo there are exactly 2 push-event runs ever, both from the single broken-YAML commit 19c639e, both with ZERO jobs and ZERO billable minutes because GitHub rejects an unparseable workflow before allocating a runner. Since that YAML was fixed, dozens of autosave pushes have produced NO push runs at all. Total GitHub Actions usage to date across all 4 runs: 0 billable minutes. Documented the one quirk in CLAUDE.md - a broken workflow YAML makes GitHub report a failed run per push regardless of triggers, because it cannot read the triggers of a file it cannot parse; test_resume.sh validates the YAML so ckpt.sh catches it, while autosave stays deliberately ungated.

## Do this next
Nothing outstanding. The only unproven path is the FULL build on a runner, which needs versionCode bumped past 64 and will therefore be proven by the next real release. Tj's rule is now enforced by the workflow itself: gates-only runs are free, full builds require full_build=true or a v* tag, and the versionCode gate is fatal on a full build.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md
     M CLAUDE.md

## Last ten checkpoints
```
  ce48c8f ckpt 590: CI IS LIVE AND VERIFIED. Tj added the SIGNING_KEYSTORE_BASE64 secret and run #
  e7eb17f ckpt 589: Restructured the CI workflow so it cannot waste GitHub's free minutes, then va
  1d9b76c ckpt 588: Added GitHub Actions signed-release CI on Tj's decision, and closed the local 
  f42b374 ckpt 587: Ticked TASKS.md 1-5 (all written, tested via tools/test_resume.sh's 19 checks,
  cec13a5 ckpt 586: Built the APK auto-provisioning chain and proved the SDK half of it live. NEW 
  efa40b9 ckpt 585: Checked Tj's GitHub assumption and wrote the APK auto-provisioning request int
  c27799f ckpt 584: Installed the signing keystore and added a keystore check to bootstrap.sh, so 
  cdde919 ckpt 583: Independent re-verification pass of the whole resume system, adversarial rathe
  b51da53 ckpt 582: Finished the resume-system review: 9 defects found, all 9 fixed, all covered b
  a36dba0 ckpt 581: Fixed the four highest-severity resume defects and made the system self-verify
```
