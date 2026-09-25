# Round 2 platform audit (2026-09-25)

Read-only verification of the PL-1..PL-15 fixes (diff `5576e0fb..HEAD`, app/src/main)
plus a hunt for regressions: threading, battery/network, persistence, UI.

Status: IN PROGRESS - findings are appended as they are verified.

Severity: H = crash / data loss / runaway battery or network / wrong numbers; M; L.

## Findings

### R2P-1 (M) - "Undo last change" confirmation says the opposite of what it now does after a Revert
**Where:** `ui/EngineTuningUi.kt:204-216` (`EngineConfirmDialog`), used by `ResearchScreen.kt:259` and
`SettingsScreen.kt:426`; behaviour from `net/EngineTuning.kt:115` (`undoable` now prefers a trailing
KIND_REVERT) and `undo()` (PL-15 fix). Also `EngineTuningUi.kt:178-183` (`HistoryLine`).
**Problem.** The PL-15 fix made Undo take back a Revert (restoring every tuned value the revert removed).
The confirmation dialog is not state-aware and still reads: "The engine goes back to exactly how it was
before the most recent change Claude made." After a Revert, confirming it does the reverse: it re-installs
ALL of Claude's changes. The success toast ("back to how it was before that change") is also vague here.
Separately, `HistoryLine` marks only an APPLY as "(later taken back)"; an undone revert is still listed as
"reverted to the original" with no marker, so the history reads as if the engine were original.
**Failing scenario.** Engine v3 tuned -> Tj taps "Revert to original" (v4) -> later taps "Undo last change"
expecting (per the dialog) to remove Claude's latest change -> confirms -> the whole tuned engine (every
change) is back in force as v5.
**Fix.** Pass the undo target to `EngineConfirmDialog` (e.g. `kind` + `state.undoable?.kind`) and say
"Undo the revert? The tuned engine from before it (N settings) comes back" when the target is a revert;
tailor the toast the same way; in `HistoryLine` append "(later taken back)" when `h.undoneAt > 0` for any
kind. Add a test on the dialog text builder (pure function) for both targets.

### R2P-2 (M) - Cached bars are rounded, fresh bars are not: a re-grade from `dt_bars` can flip exact-touch verdicts
**Where:** `net/DayTradingEval.kt` `encodeBars` (`BigDecimal(v).setScale(6, HALF_UP)`) vs `parseBars`
(155-185, raw `optDouble`, no rounding); read back in `PortfolioViewModel.resolveOneDayTradingEntry`
(7666-7685, `cached(1)` / `cached(5)`); comparisons in `DayTradingGrader.grade` (380 `b.high >= spec.entry`,
393 `b.low <= spec.entry - tk`) and `runPosition`.
**Problem.** Yahoo's chart JSON carries float32 artifacts (a 12.34 print arrives as 12.34000015258789,
a 12.35 print as 12.350000381469727 - or just below the cent, depending on the value). The first grade of a
settled row uses those raw values; the cache stores them rounded to 6 dp (i.e. back to the exact cent).
Every later re-grade (the DA-1 settled re-grade reads the cache only if a settled grade already wrote it,
but every FUTURE `DayTradingGrader.VERSION` bump re-grades the whole 55-day window from `dt_bars`) compares
different numbers against the same plan levels. At exact touches the answer changes. Measured with the
same arithmetic (Python, float32 -> 6 dp HALF_UP) over cent levels $5.00-$49.99: for a limit entry at a cent
level with a bar low exactly one tick through, fresh and cached disagree in 1,064 of 4,500 levels; for a
buy-stop whose bar high prints exactly at the entry, 1,128 of 4,500. Claude plans use round cent levels,
so these ties are routine for them.
**Failing scenario.** Claude plan, buy-limit 12.35 (needs a low <= 12.34). The day's low prints 12.34:
fresh value 12.34000015 > 12.34 -> graded NO_ENTRY. Next grader bump -> re-graded from `dt_bars`
(12.34 <= 12.34) -> now a filled trade with a win/loss. The headline rate, the per-setup tables and
the tuning evidence all move with no market change. (The fresh path itself is also arbitrary: whether an
exact touch fills depends on which way float32 rounded that particular price.)
**Fix.** Make both paths identical and deterministic: round in `parseBars` exactly as `encodeBars` does
(to 6 dp, or better to 4 dp / the tick), so fresh and cached bars are the same numbers - or store exact
doubles in `encodeBars` (`Double.toString` round-trips) if the raw behaviour is to be kept. The first
option also removes the float32 lottery at exact touches. Test: a bar list with float32-artifact values
graded fresh and after `decodeBars(encodeBars(..))` must give the same outcome and detail.

### R2P-3 (L) - Any 400 on a one-minute request inside the one-minute window permanently downgrades a settled row to a 5-minute grade
**Where:** `net/DayTradingEval.kt:85-89` (`if (r.code == 400 || r.code == 422) return emptyList()`),
`PortfolioViewModel.resolveOneDayTradingEntry` 7675-7685 and 7714-7715; re-grade selection
`dayTradingRowsNeedingGrade` (never re-selects a final, current-version, non-partial row).
**Problem.** The caller only asks for 1m bars while `oneMinuteStillAvailable` (29 days after the close,
i.e. <= ~29.3 days from period1), which is inside Yahoo's 30-day 1m limit - so the 422 this branch was
written for should essentially never reach this caller, and what it does catch is an unexpected refusal
(a 400 from an edge/proxy, a malformed-request blip). That is read as "answered: nothing there": the row
falls back to 5m bars, is written final with `eval_version = VERSION`, and the 5m bars are cached as the
day's series. Nothing ever re-grades it on 1m bars, although they existed. Also, a 400/422 from query1 ends
the loop without asking query2. If the 5m request also gets a 400, an undecided row becomes
DATA_UNAVAILABLE (recoverable after 24 h - fine).
**Failing scenario.** Settled row, 3 days old; query1 answers the 1m request with a transient 400 ->
graded on 5m bars ("any bar that could be read either way was read as a loss") and never revisited.
**Fix.** Treat only a 422 (or a 400 whose body says the window is out of range, e.g. contains
"must be within") as answered-empty, and only let the caller fall back to 5m on it when the row is near or
past the 1m window; otherwise return null (FAILED, asked again next check). Optionally do not cache a
5m series while 1m bars should still exist.

### R2P-4 (L) - The 64 MB backup read cap is nominal: memory, not the cap, is the real limit, and one read path can crash on it
**Where:** `util/Storage.kt:259` (`BACKUP_READ_MAX = 64_000_000`), `readText` 268-284; callers
`readOwnDownload` (239-240), `SettingsScreen.kt:118`, `PortfolioViewModel.backupToDownloads` 9210-9218,
`autoBackupIfDue` shrink guard 9450-9456, `checkForRecoverableBackup` 3042; export side
`Db.exportJson` (whole log as a JSONObject tree, `root.toString(1)` at 1916).
**Problem.** A read of N bytes costs: `ByteArrayOutputStream` grown by doubling (capacity up to ~2N,
with old+new arrays live during each growth), `buf.toByteArray()` (another N), the `String` (N if all
ASCII, 2N otherwise), then `JSONObject(text)` (roughly 2-3N for the tree) - a peak around 5-7N. On a
256 MB app heap (typical `heapgrowthlimit` for a mid-range phone without `largeHeap`) that fails near
N = 35-50 MB, and `exportJson` (log list + JSON tree + `toString(1)` builder doubling) fails in the same
band - so the 64 MB figure is never reached; the PL-5 point (make the log export compact) was not done,
only the cap raised. `readText` catches `Exception`, not `OutOfMemoryError`: `readPickedFile`,
`readOwnDownload`'s callers and `autoBackupIfDue` wrap it in `runCatching` (Throwable - no crash, but the
shrink guard then silently fails open and a smaller autosave overwrites the larger one), whereas
`backupToDownloads`' verification read (9210) is not wrapped: an OOM there escapes `withContext` into
`viewModelScope.launch` and crashes the app when Tj taps Backup.
At ~1.3 KB per graded log row and 10-20 rows per session this is roughly 5+ years away - hence L.
**Fix.** (a) In `readText`, pre-size the buffer from the provider's size (`OpenableColumns.SIZE` /
`available()`) and decode with `buf.toString("UTF-8")` (no `toByteArray()` copy); catch `Throwable` (or at
least `OutOfMemoryError`) and return null. (b) Wrap the verification read in `runCatching`. (c) Do PL-5's
second half: export `features`/`evalDetail` as nested objects (or drop the `toString(1)` indentation),
which roughly halves the per-row bytes. (d) Make the `autoBackupIfDue` shrink guard fail CLOSED (keep
the old file as `-previous` when it cannot be read) rather than open.

### R2P-5 (L) - The success card's "older recommendations not counted" sentence misstates one of its three cases
**Where:** `ui/ResearchScreen.kt:1426-1431` (`dayTradingCounts`) vs `net/DayTradingEval.kt`
`notTradeableOldRow` (`features.isBlank() && !(p > 0 && stop < p && p < target)`) and the
`DayTradingStats.oldSkipped` doc ("past the target, under the stop, or no price recorded").
**Problem.** The card says every such row "was already past the target or under the stop when shown (a
plan the card said to skip)". Rows with no recorded price (`priceAtRecommendation <= 0` - e.g. early
Claude-added picks logged before the price fill, a case the D-9/R2-3 notes describe) are in the same
count, but the card never said to skip those; the app simply cannot tell their direction. When every
old row is of this kind, Tj is told something about his plans that did not happen. Also "at or past"
/ "at or under" (the test is inclusive) reads as strictly past.
**Failing scenario.** 12 pre-09-24c rows, 9 with `priceAtRecommendation = 0` -> "12 older
recommendations ... were already past the target or under the stop when shown ... not counted."
**Fix.** Split the count (`oldSkippedNoPrice` vs `oldSkippedPast`) or word it to cover both: "N older
recommendations recorded before these rules can't be graded fairly (the price when shown was at or past
the target, at or under the stop, or not recorded), so they are not counted." Add the no-price case to
the existing text test.

### R2P-6 (L) - `loadEngine` now full-scans the day-trading log on the main thread at every cold start and after every restore
**Where:** `ui/PortfolioViewModel.kt:8392-8403` (`loadEngine`, called from `init` at 3021 and from
`restoreAsync` at 9561 - both on Main), `data/Db.kt` `dayTradingLogMaxEngineVersion`
(`SELECT DISTINCT engine FROM day_trading_log`, no index on `engine`); plus a possible synchronous
`db.set(Keys.DT_ENGINE, ...)` on Main when the version is moved.
**Problem.** The PL-9 fix is correct, but the scan walks every row's page (rows are ~1-1.5 KB now with
`features` + `eval_detail`), on the UI thread, before the first frame. Negligible today; linear in log
age (a few thousand rows = several MB of pages read cold from flash at launch, tens of ms of jank on a
mid-range phone, on top of the other `init` reads).
**Fix.** Either `CREATE INDEX IF NOT EXISTS idx_dtlog_engine ON day_trading_log(engine)` (the DISTINCT
then reads the index only), or keep the synchronous install from the settings keys and move the
log-version reconciliation (+ its `db.set`) into the existing `viewModelScope.launch(Dispatchers.IO)`
block under `engineMutex`, re-installing only if the version moved.

### R2P-7 (L) - A known-truncated settled series is cached as the day's bars, freezing the truncation for every later re-grade
**Where:** `ui/PortfolioViewModel.kt:7713-7716` (`if (settled && !fromCache) db.cacheDayBars(...)` in the
non-PENDING branch, whatever `g.detail` says); `DayTradingGrader.grade` 364-368 / 439-443 (a settled
series whose last bar is more than `TRUNCATED_SEC` short of the flat time still yields a final verdict
when the stop/target was hit earlier, with a `partial` detail).
**Problem.** DA-17 exists because Yahoo sometimes answers a closed session with a series that stops
early (e.g. at 13:40 for a liquid stock). Such a reply can still decide the row (target hit at 10:40), so
it is written - correctly - with a partial detail, and `needsSettledRegrade` then (by design) never asks
again. But the truncated series is also stored in `dt_bars`, and every later re-grade (the next grader
bump) reads it instead of the network, so the row can never get its grid / hold / run measures even if
Yahoo's next answer would be complete. Before PL-6 a later bump at least re-fetched.
**Failing scenario.** WIN decided on a reply truncated at 13:40 -> cached; grader VERSION bump two weeks
later -> re-graded from the cached 13:40 series -> still partial; the row stays out of every grid/hold
table in the tuning prompt for good.
**Fix.** Cache only a series that reaches the flat time: `if (settled && !fromCache && g.detail?.partial != true)`
(and for a NO_ENTRY, only when the last bar reaches the flat time too). Optionally let
`needsSettledRegrade` retry a settled-partial row once more a day later.

### R2P-8 (L) - A pressed "Check" does not reset the automatic clock, so the tab's own run can repeat the same requests moments later
**Where:** `ui/PortfolioViewModel.kt:7489` / `7496` (`dayTradingAutoEvalAt` is stamped only `if (auto)`),
gate at 7473.
**Problem.** Mid-session every run re-fetches the 1m series of each of today's still-open rows. After a
pressed Check (which just did exactly that), the next trigger - returning from a pick's detail screen,
coming back from another app, an engine apply's `replanDayTradingNow` - starts an automatic run whenever the
LAST AUTOMATIC run finished 15+ minutes ago, re-reading the whole log 2-4 times and re-requesting every
open row of today again. Bounded (no loop, no burst), but pure duplicate traffic.
**Failing scenario.** Last automatic run 10:30. Tj presses Check at 11:00 (20 open plans -> 20 chart
requests), opens a pick at 11:01 and comes back at 11:02 -> automatic run -> the same 20 requests again.
**Fix.** Stamp `dayTradingAutoEvalAt` at the end of a pressed run as well (it did the same work). Also
update `evaluateDayTradingLog`'s KDoc, which still says "No automatic call anywhere near this - it runs
ONLY when pressed".

