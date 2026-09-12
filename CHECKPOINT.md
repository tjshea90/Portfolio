# CHECKPOINT 690 — read me first, then TASKS.md

**Written:** 2026-09-12T22:02:38Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `ea387d7` (this checkpoint is the commit after it)

## Just done
v7.20 (code 77) shipped end to end: GitHub Actions run #20 built, signed, verified its own certificate and published the Release; recorded in BUILDLOG.md only after the run was green. Part 10 (the ledger money-accuracy fixes: AVERAGE same-day depletion order, overselling reported via Position.oversold, the SPLIT transaction type) and Part 11 (the regression sweep that found and fixed 3 more bugs in that diff before it shipped) are both complete.

## Do this next
Nothing outstanding - wait for Tj's next request, and write it into TASKS.md in his own words before writing any code. One known remaining test-gap, noted in TASKS.md, not blocking: Claude.kt's copy of the TxnType.IMPORTABLE guard (the screenshot API path) has no dedicated test, unlike ClaudeBridge's copy of the same one-line guard.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  948287b ckpt 689: gated v7.20 (code 77) and pushed it: checkinit, the full unit suite and the ve
  0b9d76f ckpt 688: Part 11 regression sweep: 2 parallel agents plus my own direct trace found and
  2eef663 ckpt 687: Recorded Part 10 in TASKS.md: what was fixed, how each fix was verified, why o
  c9b335e ckpt 686: Part 10 code review (high effort) over the whole money-accuracy diff found 4 r
  11727e0 ckpt 685: Part 10 (Opus) - overselling and stock splits. Overselling: both replays now r
  10df498 ckpt 684: Part 10 continued: the randomised cross-method property added with the first f
  7a43154 ckpt 683: Part 10 (Opus, money-accuracy): fixed the AVERAGE cost method's same-day pool 
  23b816d ckpt 682: Part 9 sweep batch 5 (final): fixed ReaderScreen's 3 sub-48dp touch targets, d
  02b6065 ckpt 681: Part 9 sweep batch 4 (ViewModel): commitImportAsync and clearIncorrectBuyFees 
  5dd6b42 ckpt 680: Part 9 sweep batch 3 (accessibility): fixed the raw-fill-painted-as-text WCAG 
```
