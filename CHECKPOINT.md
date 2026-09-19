# CHECKPOINT 1564 — read me first, then TASKS.md

**Written:** 2026-09-19T17:01:43Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/finish-started-test-vxwq2e` · **builds on:** `b9b62e1` (this checkpoint is the commit after it)

## Just done
Scoring-audit fixes in (all verified against the code first, not taken on trust): HIGH - ResearchScore's 52-week term treated a MISSING S&P return as a flat market, so Finviz-filled symbols (Finviz writes change52Week and no S&P companion; core() merges it whenever Yahoo+Nasdaq came back thin) scored ABSOLUTE return as if it were RELATIVE - a stock up 15% in a market up 15% scored +11.25 instead of 0, on a scale where BUY starts at 63. Now scored only when both numbers exist, per the file's own 'a missing field scores ZERO' rule. Also: 'Up -35.00%' for a stock that fell; the undated-consensus reason line took its WORD from Yahoo's 1-5 mean while its POINTS came from the vote counts (so 'Mixed consensus' could sit beside a full +27); Recommend.build reported panel freshness the score never used (a vote-less panel scored via the undated branch at 45-90% but the card said 'counted at 0%'); and DetailTabs' earnings countdown said 'In 0 days'/'In 1 days' where the popup for the same field said 'today'/'in 1 day'. compileDebugKotlin green.

## Do this next
Three of four audits are in (scoring, network, UI). Fix the network + UI findings next: day-trading live loop refetches immutable daily candles every 30s with no TTL/market-phase/online gate (both audits found it independently); day_trading_log is absent from backup/restore entirely (uninstall loses it); saveToAppFolder still truncates before writing; Finnhub fabricates prevClose; plus the UI dialog/cancellation findings. Then re-run the full suite and ship.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  ca480ee ckpt 1563: Floor re-run: 1191 tests, 1 failure - an existing DbTest case pinned the OLD 
  68cb34e ckpt 1562: Resumed the interrupted full-test session. Verified and finished the in-fligh
  e19866d ckpt 1561: Full-tests floor GREEN: checkinit ok, full Gradle unit suite 1174 tests / 0 f
  18856f7 ckpt 1560: Logged Tj's 'full test the latest version' request in TASKS.md as the current
  ad86005 ckpt 1559: Investigated Tj's '404 on the release link' report: the repo is private, the 
  35518b0 ckpt 1558: v7.27 (code 84) shipped end to end: gated, GitHub Actions run 35419792350 bui
  29cef66 ckpt 1557: gated v7.27 (code 84) and pushed it: checkinit, the full unit suite and the v
  58bc3c8 ckpt 1556: Logged Tj's ship request in TASKS.md and made it permanent policy in CLAUDE.m
  7b20d97 ckpt 1555: Full tests complete: fixed the DayTradingEval.Costs comment tightening (LOW f
  fc23115 ckpt 1554: Full-tests fixes batch 2: (1) HIGH - DayTradingTechnicals.sessionDay/intraday
```

(3 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
