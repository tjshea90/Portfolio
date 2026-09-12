# CHECKPOINT 688 — read me first, then TASKS.md

**Written:** 2026-09-12T21:51:22Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `22eff79` (this checkpoint is the commit after it)

## Just done
Part 11 regression sweep: 2 parallel agents plus my own direct trace found and fixed 3 real bugs in Part 10's own diff before it shipped. 1) Switching the transaction-type dropdown to SPLIT reused the leftover Shares value as the split ratio unclearead - a 100-share BUY switched to SPLIT proposed a 100-for-1 split with no user input into the ratio box at all; fixed by clearing qty at the dropdown specifically when crossing the SPLIT boundary. 2) Generalized: TxnFields.resolve() parsed qty/price regardless of type, so switching a half-typed BUY to DIVIDEND and saving just an amount baked a leftover share count and a derived price into the row (a 50 dollar dividend recorded as '100 @ 0.50') - never touched a total but permanently corrupted the row's own subtitle; fixed by zeroing both at the source for any non-BUY/SELL/SPLIT type. 3) The oversold warning was invisible on a stock's own page in exactly the case it matters most - sold down to zero - because it read row.position, null for exactly that case; this is the identical bug class DetailScreen already fixed once for realized (Round 66 DET-2), same fix applied via one shared OversoldWarning composable. Also added 2 SPLIT test cases to RestoreMergeTest.kt (ratio survives merge, identical splits dedupe). One test-gap left open (Claude.kt's copy of the IMPORTABLE guard, one line, already verified correct by reading, would need a live-HTTP mock or a refactor to test properly - disproportionate right before shipping). Confirmed no emulator is available in this container - verification is by test suite and trace, not a live run. Full suite green: 1063 tests, 0 failures

## Do this next
Ship v7.20 (code 77): bump versionCode/versionName in app/build.gradle.kts, run ship.sh (gates checkinit + full suite + versionCode-vs-BUILDLOG check), then trigger the GitHub Actions workflow through the API (android.yml on main, full_build true) and wait for it to go green before recording the release with tools/record-release.sh. Do not relay the APK - Tj downloads it himself from the Release page.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  2eef663 ckpt 687: Recorded Part 10 in TASKS.md: what was fixed, how each fix was verified, why o
  c9b335e ckpt 686: Part 10 code review (high effort) over the whole money-accuracy diff found 4 r
  11727e0 ckpt 685: Part 10 (Opus) - overselling and stock splits. Overselling: both replays now r
  10df498 ckpt 684: Part 10 continued: the randomised cross-method property added with the first f
  7a43154 ckpt 683: Part 10 (Opus, money-accuracy): fixed the AVERAGE cost method's same-day pool 
  23b816d ckpt 682: Part 9 sweep batch 5 (final): fixed ReaderScreen's 3 sub-48dp touch targets, d
  02b6065 ckpt 681: Part 9 sweep batch 4 (ViewModel): commitImportAsync and clearIncorrectBuyFees 
  5dd6b42 ckpt 680: Part 9 sweep batch 3 (accessibility): fixed the raw-fill-painted-as-text WCAG 
  d30e68e ckpt 679: Part 9 sweep batch 2 (network layer): Http.postJson now runs the same cooldown
  4c9fe0d ckpt 678: Part 9 sweep batch 1: fixed Long-division truncation in Research.kt's earnings
```

(11 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
