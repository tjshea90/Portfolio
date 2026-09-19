# CHECKPOINT 1559 — read me first, then TASKS.md

**Written:** 2026-09-19T04:24:54Z · **tests:** all 2 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/full-app-tests-3ajf43` · **builds on:** `c184a74` (this checkpoint is the commit after it)

## Just done
Investigated Tj's '404 on the release link' report: the repo is private, the link was always correct (verified via API and an unauthenticated fetch of the same URL), and the 404 means his browser wasn't logged into tjshea90 on GitHub. While checking, found the actual signing keystore blob still sits in this repo's git history (two pre-2026-09-10 commits, never scrubbed) even though it's absent from the current tree - flagged this before Tj's 'make it public' request could expose it. Asked twice (visibility choice, then the keystore risk specifically) and got his explicit risk-informed decision to go public anyway. Then hit a hard wall: no tool in this session can change GitHub repo visibility (no update_repository call, no gh CLI, no raw API access) - it's a manual step only Tj can do from GitHub's own Settings page. Documented all of this in TASKS.md (status: blocked on Tj) and CLAUDE.md (so a future session recognizes the same 404 correctly, knows about the keystore-in-history risk before ever agreeing to make this repo public, and knows this session's tooling gap).

## Do this next
Nothing pending from this session's side - the ball is in Tj's court (flip visibility himself, or ask for the keystore purge first). If he confirms the repo is public in a future session, drop the 'must be logged in' caveat from how release links are announced.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  35518b0 ckpt 1558: v7.27 (code 84) shipped end to end: gated, GitHub Actions run 35419792350 bui
  29cef66 ckpt 1557: gated v7.27 (code 84) and pushed it: checkinit, the full unit suite and the v
  58bc3c8 ckpt 1556: Logged Tj's ship request in TASKS.md and made it permanent policy in CLAUDE.m
  7b20d97 ckpt 1555: Full tests complete: fixed the DayTradingEval.Costs comment tightening (LOW f
  fc23115 ckpt 1554: Full-tests fixes batch 2: (1) HIGH - DayTradingTechnicals.sessionDay/intraday
  889707a ckpt 1553: Full-tests fixes batch 1: (1) Http.postJson now disconnects the socket on can
  f90d691 ckpt 1552: Full-tests: floor checks green (checkinit + full Gradle unit suite, BUILD SUC
  a22f2c0 ckpt 1551: Made 'light tests' and 'full tests' permanent knowledge: added a 'Testing on 
  f69cc64 ckpt 1550: Permanently removed the Opus screener: deleted tools/screener.sh, tools/hooks
  2175dcc ckpt 1549: Shipped v7.26 (code 83): analyst-rating recency scoring, day-trading tracker 
```

(2 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
