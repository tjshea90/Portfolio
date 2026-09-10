# CHECKPOINT 589 — read me first, then TASKS.md

**Written:** 2026-09-10T20:55:14Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `d50a416` (this checkpoint is the commit after it)

## Just done
Restructured the CI workflow so it cannot waste GitHub's free minutes, then validated it. Two protections, both now covered by tools/test_resume.sh: (1) no push trigger, ever - tags and manual dispatch only, because autosave mirrors every commit to main; (2) CHEAP GATES FIRST - checkinit, keystore restore, keystore fingerprint and a versionCode check all run before anything expensive, so a run that is going to fail costs about a minute instead of twelve. Added a full_build input defaulting to FALSE: a manual run does the gates only (~1 min) and a real APK build is opt-in or tag-driven, which is exactly Tj's rule that the free usage is only spent when actually building a new version. The versionCode gate is FATAL on a full build and a warning on a gates-only run - relevant right now because versionCode is still 64 and v7.7 code 64 already shipped, so a full build today would produce an APK that cannot install on the phone. Also caught and fixed a YAML syntax error I introduced (a ': ' inside a single-line 'run:' scalar makes YAML read it as a mapping) - the self-test caught it, not me.

## Do this next
Trigger the workflow with full_build OFF to verify the secret and plumbing cheaply, then read the run. A real release needs versionCode bumped past 64 first.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  1d9b76c ckpt 588: Added GitHub Actions signed-release CI on Tj's decision, and closed the local 
  f42b374 ckpt 587: Ticked TASKS.md 1-5 (all written, tested via tools/test_resume.sh's 19 checks,
  cec13a5 ckpt 586: Built the APK auto-provisioning chain and proved the SDK half of it live. NEW 
  efa40b9 ckpt 585: Checked Tj's GitHub assumption and wrote the APK auto-provisioning request int
  c27799f ckpt 584: Installed the signing keystore and added a keystore check to bootstrap.sh, so 
  cdde919 ckpt 583: Independent re-verification pass of the whole resume system, adversarial rathe
  b51da53 ckpt 582: Finished the resume-system review: 9 defects found, all 9 fixed, all covered b
  a36dba0 ckpt 581: Fixed the four highest-severity resume defects and made the system self-verify
  cbed3ee ckpt 54: Reviewed the whole resume/checkpoint system end-to-end and confirmed 6 real def
  eb593ff ckpt 580: Completeness re-check of the resume system in a genuinely fresh session (new c
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
