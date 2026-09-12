# CHECKPOINT 686 — read me first, then TASKS.md

**Written:** 2026-09-12T21:15:32Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `ed3c8ef` (this checkpoint is the commit after it)

## Just done
Part 10 code review (high effort) over the whole money-accuracy diff found 4 real issues, all fixed. 1) Adding SPLIT to TxnType.ALL silently widened the accept-list BOTH Claude-fed import paths use, so a model reply paraphrasing 'Stock split - 90 shares' into a SPLIT row with quantity 90 would have been applied as a ninety-fold split - the share-count sanity guards are scoped to BUY and SELL and would not have caught it. New TxnType.IMPORTABLE excludes SPLIT and is used by Claude.kt and ClaudeBridge.kt, while ALL still serves the editor and our own backup restore, which must accept it. 2) The oversold warning I added to DetailScreen was unreachable in the case that matters most: rows are built from positions filtered to shares greater than zero, and an uncovered sale closes the holding by definition, so 'sold 10, only ever bought 4' had nowhere to appear. Now also reported in the Settings data-health card next to the existing fee and ghost-row audits, which sees every symbol open or closed. 3) DetailScreen carried a second copy of the Activity row renderer that I had not updated, so a split read as 10 at zero dollars there; the part that drifted is now one shared txnSubtitle function. 4) tests/LedgerPropTest.java did not COMPILE against the current Ledger - a Kotlin default argument is not a default from Java - so every invariant I had just added to it was dead code; fixed, and the session instant now comes from a generated row rather than a redraw, taking same-day coverage from 0 to 5159 of 20000 runs. Also caught while writing the import-guard test: LedgerTest was not a Robolectric test, so org.json was the stubbed android.jar version where every parse returns empty - the test would have passed vacuously, 'proving' the guard while actually dropping every row. Verified: 20000 Java histories clean, 3000 python histories clean with ZEROQ=0, full Kotlin suite 1060 tests 0 failures

## Do this next
Report the whole of Part 10 to Tj and ask before shipping - his standing preference is to review UI changes first, and this round adds two visible ones (a SPLIT type in the transaction editor and two new data-health warnings). Do not run ship.sh without his go-ahead. If he says ship: bump versionCode AND versionName in app/build.gradle.kts, run ship.sh, then trigger the GitHub workflow through the API and record the release only once the run is green.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  11727e0 ckpt 685: Part 10 (Opus) - overselling and stock splits. Overselling: both replays now r
  10df498 ckpt 684: Part 10 continued: the randomised cross-method property added with the first f
  7a43154 ckpt 683: Part 10 (Opus, money-accuracy): fixed the AVERAGE cost method's same-day pool 
  23b816d ckpt 682: Part 9 sweep batch 5 (final): fixed ReaderScreen's 3 sub-48dp touch targets, d
  02b6065 ckpt 681: Part 9 sweep batch 4 (ViewModel): commitImportAsync and clearIncorrectBuyFees 
  5dd6b42 ckpt 680: Part 9 sweep batch 3 (accessibility): fixed the raw-fill-painted-as-text WCAG 
  d30e68e ckpt 679: Part 9 sweep batch 2 (network layer): Http.postJson now runs the same cooldown
  4c9fe0d ckpt 678: Part 9 sweep batch 1: fixed Long-division truncation in Research.kt's earnings
  24d5bad ckpt 677: Wrote Tj's app-wide audit request (bugs, UI, code, internet efficiency) into T
  80c96c2 ckpt 676: Shipped v7.19 (code 76) end to end: GitHub Actions run #19 built, signed, veri
```

(22 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
