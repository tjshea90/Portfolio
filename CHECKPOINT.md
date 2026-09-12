# CHECKPOINT 687 — read me first, then TASKS.md

**Written:** 2026-09-12T21:16:15Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `f090eb2` (this checkpoint is the commit after it)

## Just done
Recorded Part 10 in TASKS.md: what was fixed, how each fix was verified, why overselling is reported rather than resolved into short accounting, and the two things left deliberately open (split DETECTION is manual, and the python harness's default-on zero-quantity reconciliation noise). Awaiting Tj's go-ahead to ship

## Do this next
Waiting on Tj. If he says ship: bump versionCode AND versionName in app/build.gradle.kts, run ship.sh, trigger the GitHub workflow through the API (android.yml on main, full_build true), and record the release with tools/record-release.sh only once the run is green. Do not relay the APK - he downloads it himself from the Release page.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  c9b335e ckpt 686: Part 10 code review (high effort) over the whole money-accuracy diff found 4 r
  11727e0 ckpt 685: Part 10 (Opus) - overselling and stock splits. Overselling: both replays now r
  10df498 ckpt 684: Part 10 continued: the randomised cross-method property added with the first f
  7a43154 ckpt 683: Part 10 (Opus, money-accuracy): fixed the AVERAGE cost method's same-day pool 
  23b816d ckpt 682: Part 9 sweep batch 5 (final): fixed ReaderScreen's 3 sub-48dp touch targets, d
  02b6065 ckpt 681: Part 9 sweep batch 4 (ViewModel): commitImportAsync and clearIncorrectBuyFees 
  5dd6b42 ckpt 680: Part 9 sweep batch 3 (accessibility): fixed the raw-fill-painted-as-text WCAG 
  d30e68e ckpt 679: Part 9 sweep batch 2 (network layer): Http.postJson now runs the same cooldown
  4c9fe0d ckpt 678: Part 9 sweep batch 1: fixed Long-division truncation in Research.kt's earnings
  24d5bad ckpt 677: Wrote Tj's app-wide audit request (bugs, UI, code, internet efficiency) into T
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
