# CHECKPOINT 590 — read me first, then TASKS.md

**Written:** 2026-09-10T20:57:14Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `b840d5e` (this checkpoint is the commit after it)

## Just done
CI IS LIVE AND VERIFIED. Tj added the SIGNING_KEYSTORE_BASE64 secret and run #3 (workflow_dispatch, gates-only) SUCCEEDED in 18 seconds for 0 billable minutes: checkinit ok, 'restored 2218 bytes' (the exact size of the local keystore), 'OK signing keystore present and matches the shipped certificate' - so the secret holds the right key - versionCode correctly WARNED (64 vs 64 already shipped) instead of failing because this was not a full build, and the keystore was wiped from the workspace. Also explains the two failed runs that appeared before it: confirmed by inspecting commit 19c639e that they were GitHub reporting my broken workflow YAML, which autosave had committed before I fixed it. They ran zero jobs and consumed zero billable minutes - GitHub rejects an unparseable workflow before allocating a runner. Bumped actions/checkout and actions/setup-java to v5 after the run warned v4 targets deprecated Node 20. Wrote the minutes rule into CLAUDE.md: gates-only manual runs are free and encouraged, a full build costs ~12 min and happens ONLY via full_build=true or a v* tag, cheap gates always run first, and the versionCode gate is fatal on a full build so minutes are never spent on an APK that cannot install.

## Do this next
The FULL build path is still unproven ON A RUNNER - only the gates have executed there. It needs versionCode bumped past 64, so it will be proven by the next real release rather than by a test run. When that happens, expect the Android SDK package step to be the likeliest thing to need fixing.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  e7eb17f ckpt 589: Restructured the CI workflow so it cannot waste GitHub's free minutes, then va
  1d9b76c ckpt 588: Added GitHub Actions signed-release CI on Tj's decision, and closed the local 
  f42b374 ckpt 587: Ticked TASKS.md 1-5 (all written, tested via tools/test_resume.sh's 19 checks,
  cec13a5 ckpt 586: Built the APK auto-provisioning chain and proved the SDK half of it live. NEW 
  efa40b9 ckpt 585: Checked Tj's GitHub assumption and wrote the APK auto-provisioning request int
  c27799f ckpt 584: Installed the signing keystore and added a keystore check to bootstrap.sh, so 
  cdde919 ckpt 583: Independent re-verification pass of the whole resume system, adversarial rathe
  b51da53 ckpt 582: Finished the resume-system review: 9 defects found, all 9 fixed, all covered b
  a36dba0 ckpt 581: Fixed the four highest-severity resume defects and made the system self-verify
  cbed3ee ckpt 54: Reviewed the whole resume/checkpoint system end-to-end and confirmed 6 real def
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
