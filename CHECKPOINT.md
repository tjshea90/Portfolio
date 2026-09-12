# CHECKPOINT 680 — read me first, then TASKS.md

**Written:** 2026-09-12T05:59:46Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/app-audit-optimization-2k79df` · **builds on:** `78d821a` (this checkpoint is the commit after it)

## Just done
Part 9 sweep batch 3 (accessibility): fixed the raw-fill-painted-as-text WCAG contrast bug at every remaining call site app-wide - MetricUi.verdictColor/bucketColor, DetailTabs consensus rating + 2 Chip rows, AdviceScreen's action-word chip, RecommendationDialog's placeholder fallback, ResearchScreen's 3 colorScheme.error text usages, plus a strengthened ContrastTest lint (a bare 'when'-branch return, not just 'color = ') that itself caught two previously-unflagged instances (InsiderUi.insiderColor, both Insider/Feed tags) and a new amberText accessor for a third color family (Amber) with the same bug; also swapped ~20 more color=Accent-as-text call sites across ActivityScreen/AdviceScreen/DayTradingDetailDialog/DetailScreen/FeedScreen/InsiderUi/MetricUi/PortfolioScreen/ResearchScreen/RowActions/SettingsScreen/TxnEditor for accentText (identical in light theme, fixes dark-theme's 3.39:1 failure) - all verified with a targeted test run, no regressions

## Do this next
Continue Part 9 fixes: ViewModel findings (commitImportAsync/clearIncorrectBuyFees missing runCatching, loadChart Main-thread dispatch gap for enrichDayTradingVisible), then chart test-gaps (pinch/zoom continuous-gesture test, OVERNIGHT+compare+gesture test) and DetailScreen's duplicated range-chip callback, then remaining-screens fixes (ReaderScreen 46dp touch targets, MainActivity popDetail dedup, Db.kt isSecret explicit allowlist), then the safe ledger/persistence fixes (quotes.updated index, http cache purge loop, imports table purge, 2 dead-code removals). Two items stay flagged for Tj: the ledger AVERAGE-cost same-day P&L bug and the API-key-storage (EncryptedSharedPreferences) question - both money-accuracy/security-sensitive per SCREENER.md, not fixed on Sonnet.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  d30e68e ckpt 679: Part 9 sweep batch 2 (network layer): Http.postJson now runs the same cooldown
  4c9fe0d ckpt 678: Part 9 sweep batch 1: fixed Long-division truncation in Research.kt's earnings
  24d5bad ckpt 677: Wrote Tj's app-wide audit request (bugs, UI, code, internet efficiency) into T
  80c96c2 ckpt 676: Shipped v7.19 (code 76) end to end: GitHub Actions run #19 built, signed, veri
  7e1d46e ckpt 675: gated v7.19 (code 76) and pushed it: checkinit, the full unit suite and the ve
  bbf6e26 ckpt 674: Second code-review pass over Part 8b's own fixes is complete and the suite is 
  cf83572 ckpt 673: Recorded where Part 8b actually stands in TASKS.md after the last session was 
  30de4b9 ckpt 672: Fixed all 6 code-review findings on Part 8b: the RVOL gate now scales by elaps
```

(19 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
