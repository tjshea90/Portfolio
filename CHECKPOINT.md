# CHECKPOINT 684 — read me first, then TASKS.md

**Written:** 2026-09-12T20:45:06Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `1ab42a1` (this checkpoint is the commit after it)

## Just done
Part 10 continued: the randomised cross-method property added with the first fix immediately caught a SECOND, deeper divergence in the same code - and the first fix was itself incomplete. Consuming 'everything that is not today' before today's shares is only correct when no transaction is dated AFTER the session window, and one routinely can be: the window is the session the QUOTES describe, which lags the calendar every evening, night and weekend (the exact scenario Ledger.dayBounds exists for). Seed 2444 of the python harness: sell before the window, buy inside it, buy after it, sell again - FIFO correctly consumed the in-window lot first, average consumed the later one and reported 3.66 shares bought today against FIFO's 0. averageCost now keeps three buckets (before the window, inside it, after it) and a sale consumes them in that order, which is exactly the order FIFO's date-sorted lots come out in. Also closed the harness blind spots that hid all of this: ledger_port.py's average() had no same-day tracking at all and its fifo() carried the pre-CRX-1 side-map that nothing returned, ledger_props.py never passed a session window so every transaction fell outside it, and LedgerPropTest.java pinned the session instant in the year 2255 against transactions dated 2025-26 so the whole path plus its own invariant 6 were inert across 20,000 runs. All three now exercise it and report how many histories actually did, so the question is answerable instead of assumed. Verified by reverting each fix in turn: the original bug fails the two hand-built cases, the two-bucket draft passes those and fails only the randomised property. 8000 python histories clean, 100 exercising the same-day path

## Do this next
Part 10 remaining: port the Ally fee-schedule case table from tests/LedgerPropTest.java into a Kotlin FeesTest so it actually gates in ship.sh (it feeds cost basis and the fee-looks-wrong warning but has zero gated coverage today). Then make overselling visible (Position.oversold plus a UI line) rather than inventing short-position accounting, then add the SPLIT transaction type handled in both replays. Note for the report: the 560 reconciliation violations the python harness prints by default are PRE-EXISTING and are a harness artifact, not a ledger bug - they come entirely from its ZEROQ zero-quantity rows, a shape the transaction editor now refuses; with ZEROQ=0 it is 8000 clean.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  7a43154 ckpt 683: Part 10 (Opus, money-accuracy): fixed the AVERAGE cost method's same-day pool 
  23b816d ckpt 682: Part 9 sweep batch 5 (final): fixed ReaderScreen's 3 sub-48dp touch targets, d
  02b6065 ckpt 681: Part 9 sweep batch 4 (ViewModel): commitImportAsync and clearIncorrectBuyFees 
  5dd6b42 ckpt 680: Part 9 sweep batch 3 (accessibility): fixed the raw-fill-painted-as-text WCAG 
  d30e68e ckpt 679: Part 9 sweep batch 2 (network layer): Http.postJson now runs the same cooldown
  4c9fe0d ckpt 678: Part 9 sweep batch 1: fixed Long-division truncation in Research.kt's earnings
  24d5bad ckpt 677: Wrote Tj's app-wide audit request (bugs, UI, code, internet efficiency) into T
  80c96c2 ckpt 676: Shipped v7.19 (code 76) end to end: GitHub Actions run #19 built, signed, veri
  7e1d46e ckpt 675: gated v7.19 (code 76) and pushed it: checkinit, the full unit suite and the ve
  bbf6e26 ckpt 674: Second code-review pass over Part 8b's own fixes is complete and the suite is 
```

(21 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
