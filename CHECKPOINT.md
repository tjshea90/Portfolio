# CHECKPOINT 1566 — read me first, then TASKS.md

**Written:** 2026-09-19T17:12:43Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/finish-started-test-vxwq2e` · **builds on:** `a7a4ef6` (this checkpoint is the commit after it)

## Just done
Network/UI/day-trading audit fixes, all verified in code first. NETWORK: day-trading sweep re-downloaded 3 months of DAILY candles per symbol every 30s - numbers computed only from CLOSED sessions, so they cannot change intraday; now memoised per (symbol, ET date, side of the 4pm close) and both legs made conditional (304s), and the loop itself now skips entirely when the market is CLOSED or the device is offline (5-min heartbeat when shut) - it previously polled identically at 3am and armed the very Http cooldowns startAuto is careful to avoid. Finnhub fabricated prevClose from today's price (optDouble('pc', c)), rendering a confident +0.00% - the exact thing parseYahoo/stooq refuse by name. Social clock stamped only on success, so an outage meant re-asking every 3 min instead of 15. RetryClock leaked an attemptedAt entry per symbol-range forever. UI: refresh() reported coroutine CANCELLATION as 'Refresh failed: StandaloneCoroutine was cancelled' and could pin it on screen for up to 15 min; deleting a position never navigated back (the lambda closed over a pre-delete UiState so the check was always false); the detail header offered 'Edit position'/'Add transaction' for an unknown symbol because null != true; the money dialogs and the Claude import-review dialog discarded everything on a stray outside tap, and a FAILED import commit threw the whole extraction away too. DAY-TRADING HIGH: a dropped intraday request was indistinguishable from a genuine no-session-today, so effectiveTechnicals zeroed every intraday field - vwap/sessionLow at 0 changes which plan branch runs and swaps the stop's ruler from the 5-min ATR to atr14*0.10, making entry/stop/target flicker between two different plans on a routine dropped request. Added DayTechnicals.intradayFetched to tell the two apart; 6 new tests incl. the 09:31 'never carry yesterday's session' guard.

## Do this next
Remaining audit items: day-trading log capture guards (a pre-open sweep can log YESTERDAY's levels under today's key, and holidays log as real sessions), fees charged on non-trade rows never leave cash, plus the smaller doc/naming findings. Then full suite + ship.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  a110aa1 ckpt 1565: HIGH (found independently by TWO audits): the day-trading recommendation log 
  8f0e38d ckpt 1564: Scoring-audit fixes in (all verified against the code first, not taken on tru
  ca480ee ckpt 1563: Floor re-run: 1191 tests, 1 failure - an existing DbTest case pinned the OLD 
  68cb34e ckpt 1562: Resumed the interrupted full-test session. Verified and finished the in-fligh
  e19866d ckpt 1561: Full-tests floor GREEN: checkinit ok, full Gradle unit suite 1174 tests / 0 f
  18856f7 ckpt 1560: Logged Tj's 'full test the latest version' request in TASKS.md as the current
  ad86005 ckpt 1559: Investigated Tj's '404 on the release link' report: the repo is private, the 
  35518b0 ckpt 1558: v7.27 (code 84) shipped end to end: gated, GitHub Actions run 35419792350 bui
  29cef66 ckpt 1557: gated v7.27 (code 84) and pushed it: checkinit, the full unit suite and the v
  58bc3c8 ckpt 1556: Logged Tj's ship request in TASKS.md and made it permanent policy in CLAUDE.m
```

(15 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
