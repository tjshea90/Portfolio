# 2026-09-24c — read-only audit: platform (network, persistence, lifecycle, threading, routing)

Auditor: subagent (read-only; no gradle, no git, no network). Scope: Db.kt (v10 migration,
day_trading_log, backup/restore), PortfolioViewModel.kt (day-trading grading, live loop, tuning
store/import/apply/undo/revert, share import routing, restore), DayTradingEval/Grader/Technicals,
Http, RecentBodies, SharedAnswer, EngineTuning(+Prompt), Storage, ShareImportActivity, MainActivity.

Severity: H = data loss / crash / wrong numbers / runaway battery or network; M = real but
narrower; L = minor.

(Work in progress — findings appended as confirmed.)

## Findings

### PL-1 (H) — Rows decided MID-SESSION get a permanently truncated counterfactual grid / holdR / runR
**Where:** `ui/PortfolioViewModel.kt:7450-7494` (`resolveOneDayTradingEntry`) + `net/DayTradingGrader.kt:282,333-348` (`grade`).
**Problem.** The auto-eval now runs whenever the Day Trading tab opens (every 15 min while it is used),
so most of today's rows are graded while the session is still open (`settled == false` →
`decidedThroughSec = 0`). A stop or target hit decides the verdict (correct), but `grade()` then
computes `hold` and every grid cell with `runPosition(..., complete = true, ...)` over `day` — which
for today only contains the bars printed SO FAR. So "no target, hold to flat" (`GRID_NONE`), the
wider-stop / bigger-target cells, `holdR` and `mfeFlatR` ("runR") are all measured to the CURRENT
bar and "closed" at its close as if it were the flat time. The row is then final with
`eval_version = VERSION`, so `dayTradingRowsNeedingGrade` never re-grades it after the close.
**Failing scenario.** Plan logged 10:02, target hit 10:40, Tj reopens the tab at 10:45 → graded WIN.
Its 3R/4R/6R/"none" cells and holdR are computed from 10:02-10:45 only; the stock runs another
+5R by 15:50 but the stored grid says "none" ≈ +1.1R. The tuning prompt's grid ("What other stops
and targets would have done") and the runR/holdR columns are systematically biased toward short
holds on exactly the rows Tj watched live — and Claude tunes `targetCapR` etc. from them.
**Fix.** When `!settled`, write the verdict but NOT a final detail: e.g. store outcome with
`eval_version = VERSION` and a detail flag `"partial":true` (or keep `eval_version` below VERSION /
use a separate "needs settle re-grade" marker) and have `dayTradingRowsNeedingGrade` pick up
decided rows whose detail is partial once `sessionSettled` is true; or simply skip grid/hold/
mfeFlat (`withGrid=false`) mid-session and re-grade after settle. Add a test: grade a mid-session
WIN, then settled bars → grid/holdR must reflect the full session.

### PL-2 (M) — Re-grading can overwrite a good legacy verdict with DATA_UNAVAILABLE (permanent loss)
**Where:** `ui/PortfolioViewModel.kt:7444-7447, 7464-7474`; `dayTradingRowsNeedingGrade` (1479-1487).
**Problem.** Every final row with `evalVersion < VERSION` inside the 55-day window is re-graded.
If Yahoo now answers "no bars" (404 → `emptyList()` for both 1m and 5m, e.g. a ticker renamed or
delisted since, or any 404 the chart API returns for that window) the settled branch calls
`setDayTradingOutcome(id, DATA_UNAVAILABLE, null)` — overwriting the old WIN/LOSS and nulling
`outcome_exit_price`, while `eval_version` stays 0. The same happens for an unparseable
`trading_day` (`bounds == null`; `intradayStillAvailable` answers true for those, so a restored
malformed row is always selected). DESIGN.md E9 says a row whose bars are gone "keeps its old
verdict ... flagged legacy"; this path destroys it instead, and the row is then re-requested on
every auto-eval until day 55 and exported to backups as DATA_UNAVAILABLE.
**Failing scenario.** Row `XYZ 20260917` graded WIN by grader v0; XYZ is acquired/renamed on 09-30;
next tab open → both requests 404 → row becomes DATA_UNAVAILABLE, the WIN and its exit price are
gone for good (and leave the backup the next day).
**Fix.** In `resolveOneDayTradingEntry`, when the row is a final stale row (`isFinal(outcome) &&
evalVersion < VERSION`) and the answer is empty / bounds null, leave the row untouched (it then
ages into "legacy" as designed) — only rows that were never decided may become DATA_UNAVAILABLE.
Test: stale WIN + scripted 404 → outcome and exit price unchanged.

### PL-3 (M) — Automatic re-grade burst: up to 180 Yahoo chart requests back-to-back on tab open
**Where:** `ui/PortfolioViewModel.kt:7322-7392` (`DT_AUTO_EVAL_BATCHES = 3` × `DAY_TRADING_EVAL_CAP = 60`,
`Semaphore(MAX_PARALLEL_REQUESTS = 5)`, no pause between batches); triggered from
`startDayTradingLive` (8466), which is also called from `setForeground(true)`, `applyDayTradingAnswer`
and `replanDayTradingNow`.
**Problem.** D-6 capped a press at 60 because "hundreds of Yahoo chart requests at once [were]
enough to arm the host cooldown the quote loop and every chart share". The auto path now runs
3 × 60 sequential batches with no delay, AUTOMATICALLY, at the same moment the live loop's
one-time full sweep starts (≈40 rows × intraday + chart on the same Yahoo hosts). The first open
after this update re-grades the whole log (all within 29 days → one 1m request each, a second
5m request when 1m is empty). When query1 answers 429, `fetchDaySeries` falls through to query2
(`if (r.throttledLocally) continue`), so the burst then trips query2 as well — both Yahoo hosts
in cooldown (30 s escalating to 10 min), quotes fall back to Finnhub/Stooq, the live Day Trading
sweep stalls. After two weeks away the same burst repeats (backlog of settled rows).
**Fix.** In auto mode: (a) stop the run at the first fetch that returned null/throttled or when
`Http.cooldownRemaining` for either Yahoo host is > 0; (b) pause between batches (e.g. 5-10 s) and
use parallelism 2; (c) defer the auto-eval until the list's first sweep finished (or run it
after `dayTradingSweepDone`); (d) the 1m window is 29 days, so spreading the post-update re-grade
over a few tab opens costs nothing. Consider also not trying query2 after query1 was throttled
(the cooldown is a signal about Yahoo, not about one host).

### PL-4 (M) — The engine backup files are overwritten with the ORIGINAL engine at every cold start where settings lack the keys — i.e. exactly when they are needed
**Where:** `ui/PortfolioViewModel.kt:8162-8170` (`loadEngine`) → `writeEngineBackupFiles` (8190-8197).
**Problem.** DESIGN.md: the files in `filesDir/daytrading-engine/` are "a readable record that
survives a corrupt settings table". But `loadEngine()` runs in `init` and unconditionally rewrites
`current.json` and `history.json` from whatever `settings` holds. If the settings rows are gone
(SQLite's default error handler deleted a corrupt DB and made a new one; a cloud-backup restore on
Android 12+ — `backup_rules.xml`/`data_extraction_rules.xml` EXCLUDE the DB but INCLUDE filesDir;
`db.get` throwing → `runCatching` → null) then `EngineTuning.load("", "")` = v0/empty history, and
the first launch replaces the surviving tuned record with v0 and `[]` — before anyone could read it.
Nothing ever reads the files back, so the record is also not used for recovery.
**Failing scenario.** Tj reinstalls; Google restores filesDir (with current.json = v3 and the full
history) but not portfolio.db; first launch → both files now say v0 / no history. If he then
restores an OLD Downloads backup (pre-tuning), the tuning history is unrecoverable.
**Fix.** Only write the files from `saveEngine` (a real change), or in `loadEngine` refuse to
overwrite when the stored state is "smaller" (settings key absent / version lower than the file's)
— and optionally offer to adopt the file's state when settings has no `dt_engine`. Write via
temp + rename (as `Storage.saveToAppFolder` does) so a crash mid-write cannot truncate them.

