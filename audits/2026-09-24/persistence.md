# Full-test audit 2026-09-24 — Persistence, data retention and accounting (A-)

Auditor: read-only subagent. Scope: data/Db.kt, domain/Ledger.kt, Fees.kt, ImportOrder.kt,
Models, util/Storage.kt, CrashLog, Json, ShareFiles, ShareImportActivity, SharedAnswer,
TxnEditor, ActivityScreen, Settings backup/restore/wipe, backup rules, VM persistence paths.
All paths below are under `app/src/main/java/com/tj/portfolio/` unless stated.

STATUS: complete. `python3 tools/checkinit.py` = ok; `python3 tests/ledger_props.py 3000` = 3000/3000 clean (see A-Q1 for what that harness no longer covers).

---

### A-1 [H] Opening or sharing a portfolio BACKUP file into the app imports its whole ledger as a "Claude answer", every row re-dated to today
- where: `AndroidManifest.xml` ShareImportActivity filters (SEND and VIEW accept `application/json`); `net/SharedAnswer.kt` `classify` (falls through to `Kind.CLAUDE`); `ui/PortfolioViewModel.kt` `importShared` -> `importAdviceOrTransactions`; `net/ClaudeBridge.kt:401-441` (`parse`); `net/ClaudeBridge.kt:470` (`ADVICE_KEYS` includes `"transactions"`); `data/Db.kt:915` (`findDuplicateIdAnyDate` returns null for a blank symbol)
- what's wrong: the share/"Open with" target accepts JSON, and nothing recognises the app's own backup format (`"format":"tj-portfolio-backup"`). A backup's root object has a `transactions` array, so `ClaudeBridge.findObject` accepts it and `parse` reads every row. Backup dates are epoch-ms NUMBERS (`put("date", t.date)`). `o.optString("date")` gives `"1726156800000"`, `Fmt.parseDate` returns null for that (verified with the exact pattern list on a JVM), so:
  ```kotlin
  val date = parsedDate ?: Fmt.todayMs()
  note = if (parsedDate == null) listOfNotNull(note, Txn.DATE_ESTIMATED).joinToString(" - ") else note,
  ```
  Every row becomes TODAY, "date estimated". SPLIT rows are dropped silently (`IMPORTABLE`). The review dialog opens with the whole ledger, and the toast reads "Found N transactions - review them".
- failure scenario:
  1. New phone (the documented reason to have a backup): Tj taps `portfolio-backup-....json` in Files and picks Portfolio, or shares it to Portfolio. The ledger is empty, so every row is NEW and pre-ticked. He presses "Import selected" (he meant to restore). Result: 200 rows all dated today, first-buy/lot order gone, splits missing (positions wrong by the split ratio), every lot sold "same day". If he then does the proper Settings restore (Merge), `findDuplicateId` is day-scoped and matches nothing, so every trade is inserted again: positions, cash and deposits doubled.
  2. Same phone: trades mostly match through `findDuplicateIdAnyDate` and come up unticked, but DEPOSIT/WITHDRAWAL/INTEREST rows have no symbol, so `findDuplicateIdAnyDate` returns null for them and they are NEW and ticked. Import doubles net deposits and cash.
- suggested fix (minimal): in `SharedAnswer.classify` (or at the top of `importShared`), recognise `format == Db.BACKUP_FORMAT` and route it to the Settings restore dialog (`pendingRestore`), or refuse with "This is a Portfolio backup - use Settings > Restore". Also make `ClaudeBridge.parse` accept a numeric epoch `date` (`o.opt("date") is Number`) rather than estimating it, so any other JSON with real dates keeps them.
- test: `SharedAnswerTest`: `classify(db.exportJson())` is a new `Kind.BACKUP` (or `importShared` returns a message and no rows); `ClaudeBridgeTest`: a row with `"date": 1726156800000` keeps that date and has no "date estimated" note.
- confidence: high (code path traced end to end; parseDate behaviour verified)

### A-2 [M] Every successful MERGE restore moves the replay-repair watermark over the whole existing ledger, undoing yesterday's A-2 fix
- where: `ui/PortfolioViewModel.kt` `restoreAsync` (~8223-8227); `domain/Ledger.kt:210`
- what's wrong: after any restore without an error, merge included:
  ```kotlin
  db.set(Keys.REPLAY_REPAIR_BELOW_ID, (db.maxTxnId() + 1).toString())
  ```
  Every row already on the device, including rows entered or imported in chronological order after the fix, is below the mark again and becomes a reversal candidate. Merge is the primary restore button, the recovery-card action, the "Restore the latest automatic snapshot" default and the paste-JSON path. A merge that adds 0 rows (restoring a file you already have) still moves the mark.
- failure scenario: the ledger starts mid-history (old NVDA lot not on file). Tj imports one day, SELL 100 NVDA @180 then BUY 100 @175, stored chronologically above the watermark. NVDA shows 100 shares plus the "sold without a recorded buy" warning. Later he merges any backup or snapshot (say to undo a symbol delete). The watermark jumps past both rows, `replayOrder` reverses the group, and NVDA drops to 0 shares with no warning. That is the exact A-2 loss, re-armed by a routine action.
- suggested fix: only bump the mark on `replace = true`. For Merge, repair only the id range the restore inserted: record `[firstInsertedId, maxId]` (add `firstInsertedId` to `RestoreResult`) as a second repair range, or skip the bump when `r.transactions == 0`.
- test: a VM-free test on the extracted decision, or a Ledger test: rows with ids >= mark and a merge that inserted nothing must not be reordered.
- confidence: high

### A-3 [M] The duplicate check treats a second same-size fill at a slightly different price as "ALREADY HAVE", so a real trade is unticked by default
- where: `data/Db.kt:848-889` (`findDuplicateId`), used by `ui/PortfolioViewModel.kt` `duplicateOf` -> `ImportDupes.classify` -> review dialog default ticks (`ui/ActivityScreen.kt:337-345`); the same rule is in both import prompts (`net/Claude.kt:186` and `net/ClaudeBridge.kt:198`: "A row matches if date + symbol + quantity are the same")
- what's wrong: the tolerance scales with the trade size, not with rounding error:
  ```kotlin
  val tol = maxOf(0.02, amt * 0.005)          // 0.5% of the trade
  ...
  if (sameQty && q > 0 && kotlin.math.abs(a - amt) <= 1.0) return id
  ```
  Two different fills of the same size on the same day are "the same trade" whenever their prices are within 0.5% (or $1 in total). That is an ordinary intraday move. The rounding the comment worries about (price derived as amount/qty) is at most half a cent per share, i.e. `qty * 0.005`, not 0.5% of the amount.
- failure scenario: 10 NVDA @ 180.00 in the morning is already on file (from one screenshot). Tj imports the afternoon screenshot with 10 NVDA @ 180.80. Amount 1,808 vs 1,800: diff $8, tol $9.04. The row shows "ALREADY HAVE" and is unticked. "Import selected" skips it, so the position is 10 shares and $1,808 of basis short, and cash is $1,808 too high. The prompts' own date+symbol+qty rule makes Claude drop it before the app even sees it when the morning row is in the digest. Day trading makes this pattern common.
- suggested fix: `val tol = maxOf(0.02, kotlin.math.abs(t.quantity) * 0.005 + 0.01)` (half a cent per share plus a cent), and drop the `<= 1.0` fallback or bound it the same way. Say "date + symbol + quantity + amount" in both prompts. `findDuplicateIdAnyDate` uses the same `tol`, so fix both.
- test: `DbTest`: stored BUY 10 @ 180.00 (amount -1800); `findDuplicateId(BUY 10, amount -1808, same day)` is null, and `findDuplicateId(BUY 1000, amount -1004.99)` against a stored -1000.00 at a price rounded from 1.005 still matches.
- confidence: high (arithmetic from the code)

### A-4 [M] After any reinstall, the fixed-name autosave can never be found again: every save makes another "portfolio-autosave (n).json" and recovery finds nothing
- where: `util/Storage.kt` `saveOrReplaceInDownloads` -> `findOwnDownload` (exact `DISPLAY_NAME=?`) -> `saveToDownloads` (plain `insert`); `readOwnDownload` (same exact-name lookup); callers `ui/PortfolioViewModel.kt` `autoBackupIfDue`, `checkForRecoverableBackup`, `readAutosave`
- what's wrong: on Android 10+ an uninstall orphans the app's MediaStore rows (MediaProvider clears `owner_package_name`), so the reinstalled app cannot see `Download/Portfolio/portfolio-autosave.json`. `findOwnDownload` returns null, so `saveToDownloads` inserts a new row with the same DISPLAY_NAME. MediaProvider makes the on-disk name unique and stores the row as `portfolio-autosave (1).json`. The next save looks for the exact `portfolio-autosave.json` again, finds none of its own, and inserts `(2)`, then `(3)`, and so on. Nothing reads back the name MediaStore actually assigned.
- failure scenario: Tj reinstalls (the case this file exists for) and restores. From then on every daily autosave, and every forced one after an import or restore, adds another numbered copy to Downloads/Portfolio, with no bound. `readOwnDownload(AUTOSAVE_FILE)` never matches any of them, so: (a) the A-1 "never shrink without keeping the larger copy" check reads null and never keeps a `-previous`; (b) after a later storage clear, `checkForRecoverableBackup` finds nothing and the one-tap recovery card never appears, although the data is sitting in `(n)` files. `AUTOSAVE_PREVIOUS_FILE` and the fixed-name prompt files behave the same way.
- suggested fix: after the insert, read the row's real `DISPLAY_NAME` back. In `findOwnDownload`, also match own rows whose name is `base + " (" + n + ")" + ext`, use the newest, and delete the older own duplicates. Or store the MediaStore id of the autosave row in a (non-backed-up) setting and update that row directly.
- test: instrumented/manual only (Robolectric's MediaStore does not model orphaning). A pure helper `matchesOwnName("portfolio-autosave (3).json", "portfolio-autosave.json") == true` can be unit-tested.
- confidence: medium. The code path is certain; the orphan-and-rename behaviour is the platform's (MediaProvider) and should be confirmed once on the phone.

### A-5 [L] `SQLiteDatabase.insert` swallows SQL errors (returns -1), and no caller checks it, so restore counts, the manifest check and "Imported N" can all report rows that were never written
- where: `data/Db.kt:732-745` (`insertTxn` uses `insert`); `restoreJson` `claimed.add(insertTxn(t)); n++`; `commitImportAsync` `db.insertTxn(t); n++`; `addTxnRecord`
- what's wrong: Android's `insert()` catches `SQLException` (including `SQLiteFullException`), logs it and returns -1. The Replace manifest check compares `expected` with `n + skipped`, where `n` counts the failed insert, so a Replace that lost rows to a write error still passes the check and commits. A failed manual save still shows "saved".
- fix: use `insertOrThrow` in `insertTxn`, so the existing `catch` / `runCatching` paths roll back and report. Test: `DbTest` with a trigger that `RAISE(ABORT)`s on one row: `restoreJson(replace = true)` returns an error and the old ledger is intact.
- confidence: high (platform API contract); likelihood low (disk full).

### A-6 [L] Two destructive actions in the same minute overwrite the first one's undo snapshot, and a symbol containing "/" gets no snapshot at all
- where: `ui/PortfolioViewModel.kt` `snapshotBefore` (`"portfolio-before-$what-" + Storage.stamp() + ".json"`); `util/Storage.kt` `stamp()` (`yyyy-MM-dd-HHmm`), `saveToAppFolder` (rename-over)
- what's wrong: names have minute resolution and `saveToAppFolder` replaces an existing file. So a second Replace-all within the same minute (a hurried retry after picking the wrong file) replaces `before-replace-<minute>` with the already-replaced state. The original ledger is then in no private snapshot, and the forced autosave after the first replace has overwritten Downloads too, unless the shrink rule happened to keep `-previous`. Separately, `delete-$sym` goes into the filename unsanitised. Symbols are not validated in the editor or the parsers, so "BRK/B" gives a path into a missing subfolder, `writeText` throws, and `runCatching` swallows it. The delete then proceeds without the undo its dialog promises.
- fix: add seconds and a uniqueness suffix to the name (or skip the write if the file exists), and sanitise `what` with `[^A-Za-z0-9._-] -> _` (as `PromptShare.stage` already does). Test: two `snapshotBefore("replace")` calls in one minute leave two files; `snapshotBefore("delete-BRK/B")` writes one.
- confidence: high

### A-7 [L] A screenshot extraction that finishes after a share arrived overwrites the shared rows under review
- where: `ui/PortfolioViewModel.kt` `importScreenshots` (`setImportResult(null)` at the start, `setImportResult(res)` at the end) vs `importAdviceOrTransactions` (A-8's append-to-pending)
- what's wrong: A-8 made a share append to a waiting review, but the screenshot path still assigns. Tj starts a 30-60 s extraction, then shares a Claude answer with transactions (review = shared rows, inbox file deleted by `done()`). The extraction then lands and `setImportResult(res)` replaces the shared rows, and `PENDING_IMPORT` with them. The shared rows are gone and nothing says so.
- fix: on completion, append to a non-empty `_importResult` the same way `importAdviceOrTransactions` does (a small shared `mergePending` helper).
- confidence: high on the code; the timing is uncommon.

### A-8 [L] A backup carrying a non-finite number ("Infinity") restores it, after which every export throws and the daily autosave stops silently
- where: `data/Db.kt` `restoreJson` (`o.optDouble("quantity"/"price"/"amount"/"fees", 0.0)`, DT-log `priceAtRecommendation`, `outcomeExitPrice`); `exportJson` (`JSONObject.put(Double)` throws on NaN/Infinity); `autoBackupIfDue` (`runCatching { db.exportJson() }.getOrNull() ?: return@launch`)
- what's wrong: org.json's `optDouble` parses the string `"Infinity"`. SQLite stores +/-Inf as a REAL. From then on `exportJson` throws on that row: the manual backup says "Couldn't read your data" and the automatic one just stops, with no message. Overrides and the watchlist anchor already guard `isFinite()`. Transactions and the DT log do not. Only a corrupted or hand-edited file can carry this, which is why it is L.
- fix: `.takeIf { it.isFinite() } ?: 0.0` on those reads (or skip the row, which the manifest check then reports). Test: `restoreJson` of a row with `"price":"Infinity"`, then `exportJson()` does not throw.
- confidence: high on the mechanism

### A-9 [L] Nothing serialises the autosave writers, so two forced runs can write the same MediaStore file at once
- where: `ui/PortfolioViewModel.kt` `autoBackupIfDue` (a fresh `viewModelScope.launch(Dispatchers.IO)` per call; triggered from init, `commitImportAsync`, `restoreAsync`, `setAutoBackup`) and `tidyDownloadsOnce` (also writes `AUTOSAVE_FILE` at init)
- what's wrong: each run opens `portfolio-autosave.json` with `"wt"` independently. Two runs overlapping with different ledgers (an import committed while the launch autosave is still writing) interleave truncate-and-write on one file and can leave a mixed, unparseable document in the single uninstall-proof copy. The shrink check (read old, maybe copy to -previous, then write) is also not atomic across runs.
- fix: one `Mutex` (or a conflated single-flight job) around the whole body of `autoBackupIfDue` and the autosave half of `tidyDownloadsOnce`.
- confidence: medium (narrow window)

### A-10 [L] The `onOpen` repair recreates missing tables but never the two added COLUMNS, so a swallowed ALTER is permanent
- where: `data/Db.kt` `addColumn` (`runCatching { ALTER ... }`), `onUpgrade` (v3 `quotes.quote_time`, v8 `watchlist.added_price`), `onOpen`
- what's wrong: `addColumn` swallows every error, not just "duplicate column". If the v8 ALTER fails for a real reason (e.g. SQLITE_FULL mid-upgrade) and the upgrade transaction still commits, the version is 9 without the column. `watchlistEntries()` (`SELECT ... added_price`) then throws inside `recompute()` in `init`, and the app crashes on every launch. `onOpen`'s "belt and braces" block, written for exactly this, only covers tables.
- fix: add `runCatching { addColumn(db, "watchlist", "added_price", "REAL NOT NULL DEFAULT 0") }` and the `quote_time` one to `onOpen`; both are idempotent. Test: a v9-stamped DB whose watchlist lacks `added_price` opens and lists entries.
- confidence: medium (unlikely trigger, severe result)

### A-11 [L] Research-cache writes can land out of order, so an older set (without a just-imported Claude answer) can be what the next cold start loads
- where: `ui/PortfolioViewModel.kt` `persistResearch` (`viewModelScope.launch(Dispatchers.IO) { db.set(Keys.RESEARCH_CACHE, set.toJson().toString()) }`), called from `cacheResearch` by the live Day Trading tick (unthrottled while sweeping, ~7719) and by `applyResearchAnswer`/`applyDayTradingAnswer`
- what's wrong: each publish launches its own IO job, which serialises then writes. Two publishes milliseconds apart run in parallel, and whichever `db.set` runs last wins, not whichever set is newer. `researchPersistOwed` is then false, so nothing re-writes until the next persist.
- fix: a `Mutex` plus a monotonically increasing sequence number (only write if the set is newer than the last written one), or a conflated channel consumed by one writer.
- confidence: medium

---

## Code quality (A-Q)

### A-Q1 The offline ledger harness no longer mirrors Ledger.kt
`tools/ledger_port.py` has no `replayOrder` (same-day reversal, `repairBelowId`), no SPLIT (including SPLIT-first on a date tie and oversold scaling), no `oversold`, and no `dateEstimated` handling. So `tests/ledger_props.py` (3000/3000 clean today) exercises none of the logic changed in the last three audits. Port those four features and add properties: FIFO vs AVERAGE lifetime P/L equality with random SPLITs, and "a group of ids >= repairBelowId is never reordered". Missing Kotlin tests worth adding: `SharedAnswerTest` backup-file case (A-1), watermark-after-merge (A-2), same-qty-different-price dedupe (A-3), `insertOrThrow` rollback (A-5), snapshot name uniqueness/sanitising (A-6).

### A-Q2 Whole-ledger export and a file write on the UI thread
`deleteSymbol` and `wipeTransactions` call `snapshotBefore` synchronously from the dialog's button: `exportJson()` over every table, including the ever-growing DT log, `toString(1)`, plus a file write. Along with `recompute()` that is the heaviest main-thread work left. Move them into `viewModelScope.launch { withContext(IO) { snapshotBefore(); delete } ; recompute() }`, keeping the snapshot before the delete.

### A-Q3 Dead or stale bits
- `Position.firstBuy` is computed in both replays and read nowhere, and it is never reset when a position closes and reopens.
- `Fmt.parseDate` KDoc says "at local midnight"; it stamps local noon, and `replayOrder` depends on that.
- `data/Db.kt` has orphaned or doubled KDoc: the "UPGRADES ARE ADDITIVE ONLY" block sits above `createTxnIndexes`, not `onUpgrade`, and `deleteTxnsForSymbol` carries two KDoc blocks.
- `importSharedInbox` KDoc still says `ShareInbox.take` "deletes the file as it reads it"; it is `next`/`done` now.

### A-Q4 R-8 fix is date-change-only
`updateTxn` strips "date estimated" only when the date changed. Confirming a guessed-but-correct date by pressing Save leaves the row permanently out of "bought today". Strip it on any editor save of that row (the editor shows the date, so saving it is confirming it).

---

## Ideas - need Tj's approval, do NOT implement
1. Recovery from private snapshots: `data_extraction_rules.xml` excludes only the DB from cloud backup, so `files/backups/*.json` (the full ledger, no secrets) comes back with an Android restore while the DB does not. `checkForRecoverableBackup` only looks in Downloads; it could also offer the newest restored snapshot.
2. Export each transaction's `id` (or a per-row "stored chronologically" flag) in backups, so a Replace/new-phone restore does not have to make every row a same-day reversal candidate again (the root of A-2 and R-2).
3. A never-lowered `MAX_TXN_COUNT` next to `LAST_TXN_COUNT`, lowered only by wipe/delete, so a sudden large drop can raise the "data shrank" alarm (carried over from 2026-09-23 A-1; not implemented).
4. A share-transfer / DRIP (reinvest) transaction type. Today a transfer-in has to be entered as a BUY, which moves cash that never moved.

---

## Re-checked from 2026-09-23 (holding)
A-1 (-previous kept on shrink; R-1 recovery reads current only), A-3 (`dateEstimated` in both replays, parity of the three-bucket average), A-4 (`pickUndoSnapshot` 60 s window, before-files pruned separately, button gated on count), A-5/A-6 (parsers drop symbol-less trades, zero fees on cash rows), A-7 (wipe clears overrides), A-8 (share appends to pending; see A-7 above for the screenshot path it missed), A-9/R-5 (inbox is a queue, removed after import, drained on any start), A-10 (null on failure re-enables the dialog), A-11 (`resetMarkIfEmptied`), R-3 (cold-start eviction keyed on `max(generated, dtExplained)`). Schema: `onUpgrade` additive and idempotent from v1 (DbTest/UpgradeV6ToV7Test cover v1->9 and downgrade), WAL, restore in one transaction with the manifest check before commit, settings lock-order fix intact, `backup_rules`/`data_extraction_rules` exclude DB+WAL/SHM from cloud only. Ledger FIFO/average: partial sells, oversell proceeds, fee netting, split scaling, and today-lot tracking all verified by reading. `checkinit.py` ok.

---

## Summary

| Severity | Count | IDs |
|---|---|---|
| H | 1 | A-1 |
| M | 3 | A-2, A-3, A-4 |
| L | 7 | A-5, A-6, A-7, A-8, A-9, A-10, A-11 |
| Quality | 4 | A-Q1 .. A-Q4 |
| Ideas | 4 | (approval needed) |

## END OF REPORT (complete)
