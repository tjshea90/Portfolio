# CHECKPOINT 588 — read me first, then TASKS.md

**Written:** 2026-09-10T20:45:26Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `6014dd8` (this checkpoint is the commit after it)

## Just done
Added GitHub Actions signed-release CI on Tj's decision, and closed the local build chain. CORRECTION TO MY EARLIER FRAMING: Tj was right that GitHub can build APKs - my finding was only ever that THIS repo had no CI configured (no .github/, empty across 559 commits), not a claim about GitHub's capabilities. Now added .github/workflows/android.yml: checkinit + 797 unit tests, signed :app:assembleRelease, artifact signature verified, uploaded as a workflow artifact and attached to a GitHub Release on v* tags. CRITICAL TRIGGER DESIGN: tags and workflow_dispatch ONLY, never push - autosave mirrors every commit to main (48 in one two-hour session, measured), so a push trigger would start a run every few seconds and exhaust the 2000 free private-repo minutes almost immediately; there is now a test asserting this can't be reintroduced. The workflow commits nothing, so it can't retrigger the autosave/mirror machinery or race a live session; ship.sh stays the release of record for versionCode/BUILDLOG/releases/. NEW tools/verify-apk.sh checks the built ARTIFACT's certificate, and checkkeystore.sh --expected publishes the fingerprint so CI and local share one copy. Project-specific catch documented: the usual 'start with assembleDebug, no secrets needed' advice does NOT apply here because app/build.gradle.kts signs BOTH build types with the sideload key. LOCAL CHAIN NOW FULLY PROVEN: bash tools/gradle.sh :app:assembleRelease succeeded in 4m49s from the auto-provisioned SDK and apksigner confirms the APK carries 2e8c3847...f396a9f2. test_resume.sh is at 24 checks.

## Do this next
TWO THINGS ARE OPEN AND BOTH NEED TJ. (1) He must add the repository secret SIGNING_KEYSTORE_BASE64 himself - Claude cannot create secrets - or the workflow fails by design at 'Restore the signing keystore'. (2) The workflow has NEVER RUN; treat it as unverified until the first workflow_dispatch, and expect the Android SDK package step to be the likeliest thing the runner disagrees with. Do not add a push trigger under any circumstances.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  f42b374 ckpt 587: Ticked TASKS.md 1-5 (all written, tested via tools/test_resume.sh's 19 checks,
  cec13a5 ckpt 586: Built the APK auto-provisioning chain and proved the SDK half of it live. NEW 
  efa40b9 ckpt 585: Checked Tj's GitHub assumption and wrote the APK auto-provisioning request int
  c27799f ckpt 584: Installed the signing keystore and added a keystore check to bootstrap.sh, so 
  cdde919 ckpt 583: Independent re-verification pass of the whole resume system, adversarial rathe
  b51da53 ckpt 582: Finished the resume-system review: 9 defects found, all 9 fixed, all covered b
  a36dba0 ckpt 581: Fixed the four highest-severity resume defects and made the system self-verify
  cbed3ee ckpt 54: Reviewed the whole resume/checkpoint system end-to-end and confirmed 6 real def
  eb593ff ckpt 580: Completeness re-check of the resume system in a genuinely fresh session (new c
  c6b6dbf ckpt 576: Fixed a gap in the handoff itself: CHECKPOINT.md's own pointer told a resuming
```

(5 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
