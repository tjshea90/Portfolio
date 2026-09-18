# CHECKPOINT 1547 — read me first, then TASKS.md

**Written:** 2026-09-18T17:39:29Z · **tests:** all 3 fast checks green (gradle suite: see ship.sh)
**Branch:** `claude/analyst-ratings-staleness-review-gt1upk` · **builds on:** `5162d3c` (this checkpoint is the commit after it)

## Just done
gated v7.26 (code 83) and pushed it: checkinit, the full unit suite and the
versionCode check all passed here. NOT yet built - GitHub has not been asked.

## Do this next
TRIGGER THE BUILD: mcp__github__actions_run_trigger, method run_workflow, workflow
android.yml, ref main, inputs {"full_build": "true"}. When that run is green,
confirm the Release is published (get_release_by_tag is enough) and then run:
  bash tools/record-release.sh v7.26 "Buy/hold/sell verdicts no longer treat stale analyst ratings as current: every rating is now weighted by how recently it was written (full weight inside 30 days, halving every 60, dropped entirely past 8 months), one vote per firm, and the price target is recency-weighted too - so a panel nobody has revisited in months can no longer carry a BUY on its own, and the popup says how old the analyst input is and how much it was discounted. Day Trading success rate now answers the portfolio question directly (cumulative account return at the app's own 1%-risk sizing, not just an average per trade) and models entry and stop slippage instead of assuming perfect fills, showing gross and net side by side. Also fixed: expired intraday history was re-fetched on every success-rate press forever, and the recommendation popup's 'vs today's price' now names the time it was computed at. 1174 tests, 0 failures."
Do NOT try to send Tj the APK - he downloads it himself from the Release page
(CLAUDE.md, his rule of 2026-09-11), and this container cannot fetch a private
repo's release asset bytes anyway.

*(resuming? CLAUDE.md's "FIRST ACTION OF EVERY SESSION" comes before "Starting a session" — do that one first, or autosave stays off all session.)*

## Uncommitted right now
     M CHECKPOINT.md

## Last ten checkpoints
```
  67ca002 ckpt 1546: Recency scoring + day-trading cost/cumulative stats + retention-gate bug fix 
  2441caa ckpt 1545: Analyst-recency scoring (RatingRecency + holding + Recommend + VM + popup) an
  0c65883 ckpt 1544: Audited the recommendation + day-trading code; wrote Tj's 2026-09-18 request 
  9ad5997 ckpt 1543: Confirmed v7.25 (code 82) build green and Release published; recorded it in B
  17484a6 ckpt 1542: gated v7.25 (code 82) and pushed it: checkinit, the full unit suite and the v
  7106821 ckpt 1541: Fixed 8 real bugs from a 4-way parallel audit (day-trading logic, UI, network
  4db7ea9 ckpt 1540: Confirmed v7.24 (code 81) build green and Release published; recorded it in B
  379455f ckpt 1538: gated v7.24 (code 81) and pushed it: checkinit, the full unit suite and the v
  59f8c2f ckpt 1536: Fixed both real issues the independent audit agent found in the new Day Tradi
  6632eb6 ckpt 1529: Full Gradle unit suite green after the watchlist bug fixes: 1125 tests, 0 fai
```

(1 automatic checkpoint(s) since the last deliberate one — the
session was still mid-step. `git diff` against it shows what changed.)
