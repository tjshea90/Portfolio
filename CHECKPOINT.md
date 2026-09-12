# CHECKPOINT 679 — read me first, then TASKS.md

**Written:** 2026-09-12T05:49:47Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `1011933` (this checkpoint is the commit after it)

## Just done
Part 9 sweep batch 2 (network layer): Http.postJson now runs the same cooldown/gate/rate-meter machinery Http.get does (was skipping it entirely - a host in cooldown got hit anyway, a 429 from a POST armed no cooldown); removed 2 confirmed-dead functions (ChartFeed.allHostsCooling, Insider.forSymbol); wired Http.totalLastHour() into Settings' request-meter card instead of leaving it caller-less; hoisted the duplicated Yahoo raw/bare/empty-object number unwrap (FundamentalsFeed.num/HoldingsFeed.num) and the duplicated NaN-guarded optDouble (Screener.d/EtfScreener.d) into util/Json.kt; parallelized FundamentalsFeed.nasdaq()'s two independent requests; fixed a dead always-false branch in Social.merge and a stale doc comment in SymbolSearch; fixed a memoization test that passed whether or not the memo worked (BackgroundTest) via a new seedMemoForTest test seam

## Do this next
Continue Part 9 fixes: UI accessibility findings (raw fill colors used as text in MetricUi.verdictColor/DetailTabs consensus/AdviceScreen RatingCard/ResearchScreen error color/Chip+bucketColor), then TxnEditor fee validation already done, then ViewModel findings (commitImportAsync missing runCatching, loadChart Main-thread dispatch gap), then chart test-gaps and DetailScreen callback dedup, then remaining screens fixes (ReaderScreen touch targets, MainActivity popDetail dedup, Db.kt isSecret allowlist), then the safe ledger/persistence fixes (quotes.updated index, http cache purge loop, imports purge, dead code). Two items stay flagged for Tj, not fixed here: the ledger AVERAGE-cost same-day P&L bug and the API-key-storage security question (both money-accuracy/security-sensitive per SCREENER.md).

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  4c9fe0d ckpt 678: Part 9 sweep batch 1: fixed Long-division truncation in Research.kt's earnings
  24d5bad ckpt 677: Wrote Tj's app-wide audit request (bugs, UI, code, internet efficiency) into T
  80c96c2 ckpt 676: Shipped v7.19 (code 76) end to end: GitHub Actions run #19 built, signed, veri
  7e1d46e ckpt 675: gated v7.19 (code 76) and pushed it: checkinit, the full unit suite and the ve
  bbf6e26 ckpt 674: Second code-review pass over Part 8b's own fixes is complete and the suite is 
  cf83572 ckpt 673: Recorded where Part 8b actually stands in TASKS.md after the last session was 
  30de4b9 ckpt 672: Fixed all 6 code-review findings on Part 8b: the RVOL gate now scales by elaps
```

(23 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
