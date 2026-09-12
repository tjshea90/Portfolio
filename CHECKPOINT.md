# CHECKPOINT 682 — read me first, then TASKS.md

**Written:** 2026-09-12T06:11:52Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `6a836a1` (this checkpoint is the commit after it)

## Just done
Part 9 sweep batch 5 (final): fixed ReaderScreen's 3 sub-48dp touch targets, deduped MainActivity's twice-repeated back-stack pop logic into one popDetail(), fixed Db.kt's isSecret backup-exclusion check (substring match -> explicit allowlist), added the missing index on quotes.updated, looped purgeHttpCache so its byte-budget purge is actually guaranteed to converge, added purgeImports (the one cache/log table with no retention policy since v2), and turned a provably-unreachable dead branch in Ledger.unitPrice into a loud assertion instead of a silent wrong-number fallback. Full Gradle suite green: 1028 tests, 0 failures, 0 errors. TASKS.md Part 9 checklist fully updated with what was fixed per area and the two items flagged for Tj (not fixed on Sonnet): the ledger AVERAGE-cost same-day P&L depletion-order bug (+ short-position and stock-split gaps found alongside it), and API-key plaintext storage. The thorough app-wide audit and fix pass is complete.

## Do this next
Report the full findings/fixes summary to Tj, including the two flagged items awaiting his decision (switch to Opus for the ledger bug, or say proceed on Sonnet; say whether he wants API-key storage hardened). Ask before shipping (v7.20) per his standing UI-preview-before-ship preference, unless he says ship straight through.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  02b6065 ckpt 681: Part 9 sweep batch 4 (ViewModel): commitImportAsync and clearIncorrectBuyFees 
  5dd6b42 ckpt 680: Part 9 sweep batch 3 (accessibility): fixed the raw-fill-painted-as-text WCAG 
  d30e68e ckpt 679: Part 9 sweep batch 2 (network layer): Http.postJson now runs the same cooldown
  4c9fe0d ckpt 678: Part 9 sweep batch 1: fixed Long-division truncation in Research.kt's earnings
  24d5bad ckpt 677: Wrote Tj's app-wide audit request (bugs, UI, code, internet efficiency) into T
  80c96c2 ckpt 676: Shipped v7.19 (code 76) end to end: GitHub Actions run #19 built, signed, veri
  7e1d46e ckpt 675: gated v7.19 (code 76) and pushed it: checkinit, the full unit suite and the ve
  bbf6e26 ckpt 674: Second code-review pass over Part 8b's own fixes is complete and the suite is 
  cf83572 ckpt 673: Recorded where Part 8b actually stands in TASKS.md after the last session was 
  30de4b9 ckpt 672: Fixed all 6 code-review findings on Part 8b: the RVOL gate now scales by elaps
```

(16 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
