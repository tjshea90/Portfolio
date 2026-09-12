# CHECKPOINT 685 — read me first, then TASKS.md

**Written:** 2026-09-12T20:55:28Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `488ce43` (this checkpoint is the commit after it)

## Just done
Part 10 (Opus) - overselling and stock splits. Overselling: both replays now record the shares a sale could not cover on Position.oversold, and DetailScreen says so in plain words next to the affected numbers. Deliberately NOT resolved into short-position accounting - the ledger cannot know whether an uncovered sale means a missing buy (overwhelmingly likelier in a screenshot-fed ledger, and the later buy really is a new long) or a short (where the same buy is a cover that should close to zero), and guessing either way produces confident wrong numbers in the other case; short selling is nowhere in this app's scope. The interpretation the app takes is now pinned by a test rather than implicit. An override corrects the position but does not clear the flag, because the records still disagree with themselves. Splits: new SPLIT transaction type with the ratio carried in quantity, handled in both replays - every open lot scales, total cost basis is untouched, no cash moves, nothing is realized - plus editor support (symbol and ratio only, its own validation, a plain-English preview) and an Activity row that reads as a split rather than 10 shares at zero dollars. Before this a ten-for-one split left the ledger reporting a tenth of the position forever and the manual override could not fix it, because overriding the share count carries the calculated average across and multiplies the basis by the same ratio. Ratios that are zero, negative or non-finite are ignored rather than applied, since multiplying a holding by one would destroy it. 15 new ledger tests, 1 new editor test, plus a new FeesTest putting Ally's fee schedule inside the gate for the first time - it feeds cost basis and the fee-looks-wrong warning but every one of its cases lived only in a hand-compiled Java file ship.sh never runs

## Do this next
Full unit suite is running now. When it is green: run a high-effort code review over the whole Part 10 diff (Ledger, Models, TxnEditor, ActivityScreen, DetailScreen and the three harnesses), fix anything it finds, then report to Tj and ask before shipping. Do not ship without asking - his standing preference is to review UI changes first. Remaining known gaps, all deliberate and worth naming in the report: split detection is manual (nothing tells the user a split happened), the screenshot importer is deliberately not taught SPLIT, and the python harness still prints 560 pre-existing reconciliation violations by default that come entirely from its own zero-quantity rows, a shape the editor now refuses.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  10df498 ckpt 684: Part 10 continued: the randomised cross-method property added with the first f
  7a43154 ckpt 683: Part 10 (Opus, money-accuracy): fixed the AVERAGE cost method's same-day pool 
  23b816d ckpt 682: Part 9 sweep batch 5 (final): fixed ReaderScreen's 3 sub-48dp touch targets, d
  02b6065 ckpt 681: Part 9 sweep batch 4 (ViewModel): commitImportAsync and clearIncorrectBuyFees 
  5dd6b42 ckpt 680: Part 9 sweep batch 3 (accessibility): fixed the raw-fill-painted-as-text WCAG 
  d30e68e ckpt 679: Part 9 sweep batch 2 (network layer): Http.postJson now runs the same cooldown
  4c9fe0d ckpt 678: Part 9 sweep batch 1: fixed Long-division truncation in Research.kt's earnings
  24d5bad ckpt 677: Wrote Tj's app-wide audit request (bugs, UI, code, internet efficiency) into T
  80c96c2 ckpt 676: Shipped v7.19 (code 76) end to end: GitHub Actions run #19 built, signed, veri
  7e1d46e ckpt 675: gated v7.19 (code 76) and pushed it: checkinit, the full unit suite and the ve
```

(29 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
