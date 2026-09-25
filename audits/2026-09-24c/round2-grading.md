# Round 2 audit — Day Trading grading / stats side

Read-only verification of the DA-1..DA-21 / PL-1..PL-15 fixes (diff vs 5576e0fb)
for the grading, logging and stats paths, plus a hunt for new bugs.

Status: IN PROGRESS (findings appended as verified)

## Findings

### R2G-1 (H) - A decided one-minute verdict is silently re-graded on five-minute bars, which can turn a real LOSS into a WIN

- Where: `ui/PortfolioViewModel.kt:7666-7685` (`resolveOneDayTradingEntry`: `cached(1)` -> 1m fetch only while
  `oneMinuteStillAvailable` -> otherwise, or on an EMPTY 1m answer, falls to `res = 5`), `:7714` (writes whatever the
  new grade says, no comparison with the stored one), `:7666`/`:7715` (bars are cached only when `settled`, so a
  mid-session verdict's 1m bars are never kept); `net/DayTradingGrader.kt:481-486` (`needsSettledRegrade` has no
  age limit other than the 55-day `intradayStillAvailable`); `net/DayTradingEval.kt:89` (new PL-13 rule: a 400/422
  on the 1m request returns `emptyList()` at once, without trying query2 - also a route to the 5m fallback).
- Problem: the DA-1 fix re-grades every mid-session WIN/LOSS once after the settle. When that re-grade runs more
  than 29 days after the session (Tj did not open the Day Trading tab or press Check in between) there are no
  1m bars - neither from Yahoo nor from `dt_bars` (a mid-session grade never caches) - so the row is re-graded
  from scratch on 5-minute bars and the new verdict replaces the 1m one. On 5m bars the unseen window after
  `recordedAt` grows from <1 minute to up to 5 minutes, which is exactly where a fill-and-stop-out can hide
  (the grader's own header: "the old grader lost up to five, which could hide a fill-and-stop-out and then
  credit a win on the next bar"). The same replacement happens on any future `VERSION` bump for rows graded
  before `dt_bars` existed (every v2 row graded before this build) once they are past 29 days.
- Failing scenario: Breakout buy-stop 10.50 / stop 10.00 / target 11.50, recorded 10:02:30. 1m bars: 10:03 high
  10.55 (fill 10.50), 10:04 low 9.95 -> LOSS, decided by the 10:15 auto-check with `partial:true`. The stock then
  re-breaks 10.50 at 10:30 and trades 11.60 at 11:00. Tj next opens the tab 31 days later: `needsSettledRegrade`
  = true, `cached(1)` = null, `oneMinuteStillAvailable` = false -> 5m fetch. The first 5m bar starting at or after
  10:02:30 is 10:05, so the 10:00 bar holding the fill AND the stop is excluded; the 10:30 bar fills, the 11:00 bar
  hits the target -> the row is rewritten as WIN (`res:5`), counted in the headline and the account figure. Tj
  really lost ~1R on that order.
- Suggested fix: never let a coarser grade replace a decided finer one. In `resolveOneDayTradingEntry`, when
  `decided` and the stored detail's `res == 1` and the only bars available now are 5m, keep the verdict (for a
  partial row, just stamp `outcome_evaluated_at` so `needsSettledRegrade` stops asking - it stays partial, no
  grid). Optionally also let the 400/422 branch fall through to query2 for the 1m interval before concluding.
  Test: a partial 1m LOSS re-graded with only 5m bars available keeps LOSS/res 1.

