# CHECKPOINT 681 — read me first, then TASKS.md

**Written:** 2026-09-12T06:03:38Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `180f24e` (this checkpoint is the commit after it)

## Just done
Part 9 sweep batch 4 (ViewModel): commitImportAsync and clearIncorrectBuyFees now wrap their SQLite writes in runCatching (matching every other DB write in this file) - an exception during a screenshot-parsed import (the least-trusted input in the app) used to crash the whole app mid-import with the review dialog stuck open; now it toasts and reports 0 rather than propagating. Fixed enrichDayTradingVisible's loadChart call to fetch its Job on Dispatchers.Main instead of the enclosing IO context, closing a real (if narrow) duplicate-fetch window - loadChart's in-flight guard is check-then-launch and relies on Main.immediate running the launch inline, which only happens when called from Main

## Do this next
Continue Part 9 fixes: remaining screens (ReaderScreen 46dp touch targets under the 48dp minimum, MainActivity's duplicated popDetail back-stack logic, Db.kt's isSecret substring-match backup exclusion), then the safe ledger/persistence fixes (quotes.updated missing index, http cache purge not guaranteed single-pass, imports table never purged, 2 confirmed-dead code paths). After that: full Gradle suite, then report status - several lower-priority findings (ViewModel retry-block/prompt-file dedup, recompute() async variant, News.kt/Day-Trading-idle-contract test gaps) are being left as noted remaining work rather than fixed, given diminishing returns for the risk. Two items stay flagged for Tj: the ledger AVERAGE-cost same-day P&L bug and the API-key-storage security question.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
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

(3 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
