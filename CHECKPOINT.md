# CHECKPOINT 597 — read me first, then TASKS.md

**Written:** 2026-09-10T21:25:55Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `132a15c` (this checkpoint is the commit after it)

## Just done
Made the CI versionCode gate distinguish a DOWNGRADE from a REBUILD, so GitHub can build the already-shipped v7.8. The gate was copied from ship.sh as '-le', which is the right test for SHIPPING (a new release whose code is not higher is not a new release) but the wrong test for a workflow whose job is producing an installable artifact of a version that has already been decided. What Android actually rejects is a LOWER versionCode (INSTALL_FAILED_VERSION_DOWNGRADE); an EQUAL one is a reinstall of the same version and is data-preserving as long as the certificate matches, which the keystore gate immediately above has already proved. So CI now fails only on '-lt' and warns on '-eq', while ship.sh keeps '-le' untouched. Recorded the distinction in BRIEF.md rather than silently contradicting its one-line rule: that rule is about shipping and is deliberately stricter than the platform's actual behaviour, and the two gates now differ on purpose rather than by accident.

## Do this next
Push, then trigger the workflow with full_build=true to have GitHub actually build v7.8 - this is the first time the FULL path will run on a runner, so expect the Android SDK package step to be the likeliest thing to need fixing. Verify the produced artifact's certificate, do not assume it.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  90b926f ckpt 596: SHIPPED v7.8 (versionCode 65). All ship gates green: checkinit, build environm
  3adcc05 ship v7.8: Chart axis labels now carry the year when a window spans calendar years - a p
  66e50e1 ckpt 595: Fixed my own broken regression test rather than the code: DetailTabCrashTest's
  5e88705 ckpt 594: ROOT-CAUSED the crash from the device log Tj sent - and it was NOT what I gues
  64b2e98 ckpt 593: Investigated Tj's two reports from the screen recording and fixed both finding
  cd3efec ckpt 592: Wrote Tj's new request into TASKS.md before any code: a chart-vs-SPY compariso
  ea956c3 ckpt 591: Re-verified CI after bumping actions/checkout and actions/setup-java to v5: ru
  ce48c8f ckpt 590: CI IS LIVE AND VERIFIED. Tj added the SIGNING_KEYSTORE_BASE64 secret and run #
  e7eb17f ckpt 589: Restructured the CI workflow so it cannot waste GitHub's free minutes, then va
  1d9b76c ckpt 588: Added GitHub Actions signed-release CI on Tj's decision, and closed the local 
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
