# CHECKPOINT 595 — read me first, then TASKS.md

**Written:** 2026-09-10T21:18:38Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/resume-function-claude-code-q3cbja` · **builds on:** `5c13a30` (this checkpoint is the commit after it)

## Just done
Fixed my own broken regression test rather than the code: DetailTabCrashTest's second case called rule.setContent inside a nested loop, and createComposeRule permits exactly one call per rule, so it threw 'has already set content' - a test-authoring fault, not a fault in the tab strip. Rewritten to use ONE setContent driven by mutable state, iterating by mutating isFund and the selection. Confirmed from the second run that BackgroundTest passes on its own: its earlier failure was the Robolectric MavenArtifactFetcher IOException documented in BRIEF.md as a network/Maven trap, not a code failure - so it is not something this round introduced. 806 tests now, 1 failing purely on that test-authoring bug.

## Do this next
Third suite run in flight. When green: bash ship.sh 'v7.8' to gate, build and publish, then SEND THE APK TO TJ IN CHAT - that is the part of his request that is not done until the file is delivered.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  5e88705 ckpt 594: ROOT-CAUSED the crash from the device log Tj sent - and it was NOT what I gues
  64b2e98 ckpt 593: Investigated Tj's two reports from the screen recording and fixed both finding
  cd3efec ckpt 592: Wrote Tj's new request into TASKS.md before any code: a chart-vs-SPY compariso
  ea956c3 ckpt 591: Re-verified CI after bumping actions/checkout and actions/setup-java to v5: ru
  ce48c8f ckpt 590: CI IS LIVE AND VERIFIED. Tj added the SIGNING_KEYSTORE_BASE64 secret and run #
  e7eb17f ckpt 589: Restructured the CI workflow so it cannot waste GitHub's free minutes, then va
  1d9b76c ckpt 588: Added GitHub Actions signed-release CI on Tj's decision, and closed the local 
  f42b374 ckpt 587: Ticked TASKS.md 1-5 (all written, tested via tools/test_resume.sh's 19 checks,
  cec13a5 ckpt 586: Built the APK auto-provisioning chain and proved the SDK half of it live. NEW 
  efa40b9 ckpt 585: Checked Tj's GitHub assumption and wrote the APK auto-provisioning request int
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
