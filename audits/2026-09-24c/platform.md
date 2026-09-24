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

### PL-5 (M) — Backups will outgrow the 8 MB read cap; the restore / verify / recovery paths then fail
**Where:** `data/Db.kt:1793-1815` (each log row now exports `features` + `evalDetail` as ESCAPED JSON
strings inside a `toString(1)` document); `util/Storage.kt:260` `readText(maxBytes = 8_000_000)`, used by
`readPickedFile` (Settings restore picker, 9285-9293), `readOwnDownload` (autosave recovery card,
`autoBackupIfDue`'s shrink guard) and `backupToDownloads`' read-back verification (8902-8904).
**Problem.** A log row was ~350 B in the backup; with `features` (~25 keys, ~480 B escaped) and
`evalDetail` (45-cell grid + fields, ~470 B escaped) it is ~1.3 KB. At the 10-20 plans a day the tab
records, that is ~3-6.5 MB of log per year, on top of the ledger. Once the file passes 8,000,000
bytes: "Backup" reports "Wrote ... but couldn't read it back to verify" every time; the Downloads
autosave can no longer be read back by the one-tap recovery card; the Settings restore picker
returns null ("couldn't read that file"); and the shrink guard reads `oldJson = null`, so a
shrinking autosave is no longer preserved as `-previous`. The copy that survives an uninstall
becomes unrestorable in-app — in roughly 1-3 years of normal use.
**Fix.** Give backup reads their own, much larger cap (e.g. 64 MB; `readPickedFile` for restore,
`readOwnDownload` for AUTOSAVE_FILE, the verification read), and export the day-trading log
compactly (nested objects rather than escaped strings, or `toString()` without indentation for
that array). Add a test that a backup with N log rows stays readable (size per row pinned).

### PL-6 (M) — Closed-session bars are never kept, so every future grader bump re-downloads 55 days and silently DOWNGRADES 1-minute grades to 5-minute ones
**Where:** `net/DayTradingEval.kt:65-98` (`fetchDaySeries`: no disk cache; comment says Yahoo sends
no validator, so `http_cache` never engages), `dayTradingRowsNeedingGrade` (1479-1487),
`DayTradingGrader.VERSION`.
**Problem.** A settled session's bars are immutable, but the 1m series used to grade a row is thrown
away. The design already expects further grader changes ("Bump when the rules above change: every
row graded by an older version is re-graded"). On the next bump every row inside 55 days is
re-fetched (the same 180-per-open burst as PL-3), and every row 30-55 days old can only get
5-minute bars — its accurate 1m grade (and 1m-based grid/MFE) is overwritten by a coarser 5m one.
That is fetched data silently lost, contrary to BRIEF's "nothing already stored should be
downloaded again" and to the whole point of E1.
**Fix.** Persist the parsed OHLC of a SETTLED session per (symbol, day, interval) — compact
(t,o,h,l,c arrays, ~15 KB/day for 1m) in `chart_cache` under a distinct `range_key` or a
purgeable cache table (derived data, excluded from backups, purge after 60 days). Grade from it
first; a re-grade then costs zero requests and never loses resolution. Alternatively, at minimum,
do not re-grade a row whose stored `res == 1` when only 5m bars remain (keep it and mark legacy).

### PL-7 (L) — The auto re-grade keeps running after the Day Trading tab is closed
**Where:** `evaluateDayTradingLog` (7337) launches an untracked `fgScope` job; `stopDayTradingLive`
(8514-8519) cancels only `dayTradingLiveJob`.
**Problem.** Tj's rule for this feature is "only when I have that tab open ... asleep when I'm not
using it". The automatic (not pressed) run of up to 180 chart requests continues after he switches
to Trending/another bottom tab; only ON_STOP stops it. Also, `dayTradingAutoEvalAt` is stamped
BEFORE the launch (7335), so a run that is cancelled by ON_STOP a second later — or a launch into an
already-cancelled `fgScope` (e.g. `applyDayTradingAnswer` → `startDayTradingLive` after a slow Claude
API answer landed while the app was in the background) — still suppresses the next auto run for 15
minutes.
**Fix.** Keep the auto run's Job and cancel it in `stopDayTradingLive` (not a pressed "Check");
stamp `dayTradingAutoEvalAt` only when the run completes (or clear it in the `finally` when the job
was cancelled).

### PL-8 (L) — Uncaught DB exceptions in the auto-eval coroutine crash the app, now on tab open
**Where:** `ui/PortfolioViewModel.kt:7340, 7352-7356, 7383, 7385` (`db.dayTradingLog()` /
`setDayTradingOutcome` without `runCatching`) and `resolveOneDayTradingEntry` (7445, 7472, 7491-7493)
— inside `fgScope.launch`, which has no `CoroutineExceptionHandler`.
**Problem.** Previously only a press could hit this; now `startDayTradingLive()` runs it
automatically. Any persistent SQLite failure (SQLiteFullException on the UPDATE, or the v10 columns
missing after a failed ALTER — `addColumn` swallows the error and `readDayTradingLog` then fails with
"no such column") throws out of a launched coroutine → process crash. Because `RESEARCH_TAB`/
`LAST_TAB` restore the Day Trading section at launch and `dayTradingAutoEvalAt` resets to 0 with
the process, that becomes a crash on every launch. (Same missing-column state also makes
`exportJson` throw → every backup/autosave fails until the onOpen repair succeeds.)
**Fix.** Wrap the body of the eval (and each `resolveOneDayTradingEntry`) in `runCatching`/try-catch,
logging to CrashLog; consider a `CoroutineExceptionHandler` on `newForegroundScope()`.

### PL-9 (L) — Engine version labels are not unique across a restore, so "engine:vN" evidence can mix two different engines
**Where:** `net/EngineTuning.kt:118-127` (`load`: version = max(stored version, history max)),
`Db.restoreJson` settings block (2014-2030), `EngineTuning.Evidence.count("engine:…")` (188),
`EngineTuningPrompt` "Results by engine version" (187-189), `DayTradingEval.breakdown` (608-615).
**Problem.** The log is never cleared (both restore modes are additive), but the engine state is
replaced wholesale by a Replace restore of an older file (or restored onto a phone whose own engine
was tuned independently, on Merge the device keeps its own history while the file's rows keep
theirs). The log can then hold rows labelled `v2`/`v3` made by an engine the current history does
not contain, and the next apply re-issues `v2`. `Evidence.count("engine:v2")`, the per-version
table in the prompt and the "Engine version" slice then add up trades from two different
parameter sets.
**Fix.** On `load`/after a restore, set `version` to at least the highest `vN` label present in
`day_trading_log` (one `SELECT engine … GROUP BY`), or make labels unique (e.g. `v2@<applyAt>`).

### PL-10 (L) — Engine changes are not serialised; `saveEngine` writes its two keys non-atomically
**Where:** `ui/PortfolioViewModel.kt:8173-8181, 8260-8305`; `net/DayTradingParams.kt:260-270`.
**Problem.** `applyEngineReview`, `undoEngineChange` and `revertEngine` each suspend in
`engineEvidenceNow()` (whole-log IO read) and in `saveEngine`'s `withContext(IO)` BEFORE
`_engine.value` is updated, then compute `next` from `_engine.value`. Two taps that interleave
(Apply from the review sheet and Revert from Settings, or a double confirm) both build from the same
base: both produce version N+1, the second `saveEngine` wins, and the first change silently
disappears from state and history although its toast said it was applied. `saveEngine` also writes
`dt_engine` and `dt_engine_history` in two separate transactions: a process death between them
leaves a version whose history entry is missing, so the next "Undo" takes back the wrong change.
`DayTradingEngine.install` likewise sets two separate volatiles (params, then version), so an
off-main reader can see new params with the old version (not "an immutable swap" as DESIGN.md says).
**Fix.** A `Mutex` around the three mutators (re-reading `_engine.value` inside it); write both keys
in one DB transaction (add a `Db.setAll(map)`); hold params+version in one immutable object behind
a single `@Volatile` reference.

### PL-11 (L) — Heavy work on the main thread on Apply; repeated per-row JSON parsing in stats
**Where:** `applyEngineReview` (8266) runs `EngineTuning.review(...)` on Main; for a `level:` basis
`Evidence.count` → `levelOf()` parses every decided row's `features` JSON, once per proposed change
(up to 8 × N parses). `DayTradingEval.stats` → `record()` and `breakdown()` parse the same
`eval_detail` up to ~8 times per decided row (`netOf` is called from both `profitable()` and `rOf()`
for each of 3-4 groupings).
**Problem.** With a year of log (~3-5k rows) the Apply tap does tens of thousands of JSON parses on
the UI thread (visible jank; the review on import already runs on `Dispatchers.Default`). The stats
cost is off-main but is paid ~3 times per auto-eval plus on every restore and prompt.
**Fix.** Run the re-review in `withContext(Dispatchers.Default)` like `importEngineTuning` does; parse
each row's `features`/`eval_detail` once (a map or a lazily parsed field on a wrapper) in `stats`,
`breakdown` and `Evidence`.

### PL-12 (L) — The whole log (with every `features` + `eval_detail` string) is loaded ~7 times per tab open
**Where:** `evaluateDayTradingLog` (7340, 7383, 7385), `engineEvidenceNow` (8201) re-triggered by
`ResearchScreen.kt:246` `LaunchedEffect(section, dayTradingStats, engine.version)` — `DayTradingStats`
carries `evaluatedAt = now`, so every publish is a new key → another full read; `backupToDownloads`
reads the whole log only for `.size` (8924).
**Problem.** Each read materialises every row plus two ~0.5 KB JSON strings (≈2.5 KB of heap per
row as UTF-16). Negligible today, but linear in log age: at 5k rows that is ~12 MB per read and
~80 MB of short-lived garbage per tab open on a mid-range phone.
**Fix.** A projection without `eval_detail` for `Evidence` (it needs source, outcome, evalVersion,
recordedAt, setup, engine, features); `SELECT COUNT(*)` for the backup check; key the
LaunchedEffect on something stable (e.g. `stats?.entriesTriggered`, `engine.version`) or have the
eval publish the evidence itself from the log it already read.

### PL-13 (L) — Perpetual retries: DATA_UNAVAILABLE rows and non-404 1-minute errors
**Where:** `dayTradingRowsNeedingGrade` (1479-1487), `resolveOneDayTradingEntry` (7458-7467),
`dayTradingRowsToResolve` (oldest first, cap 60).
**Problem.** (a) A DATA_UNAVAILABLE row inside 55 days (delisted/renamed symbol) is re-requested on
every auto-eval — 2 requests each time (1m empty → 5m empty) — for ~8 weeks; this used to need a
press, it is now automatic. (b) While `oneMinuteStillAvailable`, a 1m request answered with any
non-OK status other than 404 (e.g. a 4xx "not available" reply) returns `null` from
`fetchDaySeries`, and `if (bars == null) return` never falls back to 5m — the row stays ungraded (2
requests per run) until day 29 (unsure which status Yahoo uses for such a refusal). (c) Because the
batch is "oldest first", a backlog of such perpetual rows is always picked before today's rows; with
>60 of them a pressed "Check" never reaches new rows.
**Fix.** Remember per-row attempts (e.g. `outcome_evaluated_at` + a small back-off: skip a
DATA_UNAVAILABLE row re-checked in the last 24 h); fall back to 5m after a 1m 4xx; order the batch
"never-graded / stale first, retries last".

### PL-14 (L) — Share/import routing nits for tuning answers
**Where:** `importShared` ENGINE_TUNING branch (7043-7048); `importClaudeFile` (6935-6939);
`EngineReviewDialog` is composed only in `ResearchScreen.kt:250-252`.
**Problem.** (a) An unreadable tuning answer (`importEngineTuning` returns the parse error) still calls
`jumpToResearch` and returns `ShareDest.DAY_TRADING`, unlike the DAY_TRADING/RESEARCH branches which
return `dest = null` on error. (b) A tuning answer picked from the Advice or Activity tab's "Import"
is routed to `importEngineTuning` (good) but nothing navigates to Research, where the only review
dialog lives: the toast says "N changes ready for you to approve" over a screen with nothing to
approve. (c) The review exists only in `_engineReview` (memory); the share file is deleted by
`ShareInbox.done` right after, so a process death before Apply loses it (re-sharing from the Claude
chat recovers it — hence L).
**Fix.** Return `dest = null` when `importEngineTuning` reports an error; have `importClaudeFile`
publish `_shareNav = DAY_TRADING` (or `jumpToResearch`) for a tuning answer; optionally persist the
pending proposal text in a non-backed-up setting like `PENDING_IMPORT`.

