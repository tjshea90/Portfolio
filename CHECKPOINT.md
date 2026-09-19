# CHECKPOINT 1554 — read me first, then TASKS.md

**Written:** 2026-09-19T03:33:52Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/full-app-tests-3ajf43` · **builds on:** `3c31cd6` (this checkpoint is the commit after it)

## Just done
Full-tests fixes batch 2: (1) HIGH - DayTradingTechnicals.sessionDay/intraday fields were stamped from wall-clock 'now' instead of the actual date of the bars Yahoo returned, so a weekend/holiday/pre-4am fetch that got back the last CLOSED session's real VWAP/opening-range/session-high-low was labeled as today's and passed straight through effectiveTechnicals' non-zero direct-pass-through (which never consulted sameSession) - rangeUsed read as a fully-spent day at market open on stocks that hadn't traded. Now gates intraday-derived fields on the bars' own date matching today, zeroing them (and blanking sessionDay) when they don't. (2) Fixed ScreenRow.merge's earningsEstimated being ANDed across both sides regardless of which side's earningsAt actually survived the merge - now reads the flag from the same side the surviving date came from. (3) Fixed an off-center analyst price-target scoring term in ResearchScore.withAnalyst (was -2 at zero upside instead of neutral, unlike its symmetric sibling in holding()) - offset corrected from -12.0 to -10.0. (4) Reconciled the three different 'where issuers close funds' dollar figures across Research.kt/EtfScore.kt (25m/50m/100m) - each already served a genuinely different purpose (admission floor/score ramp/UI warning) so the numbers stayed, but the wording no longer claims each is THE one threshold. (5) Fixed a stale Position KDoc claiming average-cost-only. All from the 4-way parallel full-test audit (recommendation/scoring subsystem).

## Do this next
Reconcile the day-trading LOW finding (entryFill comment vs code, optional) and any remaining minor items, then run the full test suite one more time, review the diff for consistency, and checkpoint as the full-tests session end.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  889707a ckpt 1553: Full-tests fixes batch 1: (1) Http.postJson now disconnects the socket on can
  f90d691 ckpt 1552: Full-tests: floor checks green (checkinit + full Gradle unit suite, BUILD SUC
  a22f2c0 ckpt 1551: Made 'light tests' and 'full tests' permanent knowledge: added a 'Testing on 
  f69cc64 ckpt 1550: Permanently removed the Opus screener: deleted tools/screener.sh, tools/hooks
  2175dcc ckpt 1549: Shipped v7.26 (code 83): analyst-rating recency scoring, day-trading tracker 
  188cc8a ckpt 1548: v7.26 (code 83) gated and pushed; GitHub build run 35375660271 triggered and 
  82a0e39 ckpt 1547: gated v7.26 (code 83) and pushed it: checkinit, the full unit suite and the v
  67ca002 ckpt 1546: Recency scoring + day-trading cost/cumulative stats + retention-gate bug fix 
  2441caa ckpt 1545: Analyst-recency scoring (RatingRecency + holding + Recommend + VM + popup) an
  0c65883 ckpt 1544: Audited the recommendation + day-trading code; wrote Tj's 2026-09-18 request 
```

(8 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
