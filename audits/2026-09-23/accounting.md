# Full-test audit 2026-09-23 - Accounting, ledger, persistence / retention (A-*)

Research only; nothing edited. All paths are under `app/src/main/java/com/tj/portfolio/`.
Yesterday's A-H1..A-L11 were re-read. The fixes are in place, but A-2 below is a hole in
the A-H2 fix and A-4 is a hole in the A-L9 fix.

---

### A-1 [H] The uninstall-proof autosave gets overwritten by a near-empty ledger after data loss, and the alarm clears itself
- where: `ui/PortfolioViewModel.kt` 2488-2500 (`recompute`), 7756-7780 (`autoBackupIfDue`), 3994 (`commitImportAsync`), 2256 (init); `ui/PortfolioScreen.kt` FoundBackupCard "Start fresh instead" / RecoveryCard
- what's wrong: `recompute` sets the high-water mark to whatever count it reads as soon as the ledger has any rows:
  ```kotlin
  if (txns.isNotEmpty()) {
      if (txns.size != known) db.set(Keys.LAST_TXN_COUNT, txns.size.toString())
      _dataMissing.value = false
  ```
  `autoBackupIfDue` only refuses to run on an EMPTY ledger, and then replaces the single Downloads copy without checking what it is replacing:
  ```kotlin
  if (db.txnCount() == 0) return
  ...
  com.tj.portfolio.util.Storage.saveOrReplaceInDownloads(getApplication(), AUTOSAVE_FILE, json)
  ```
  `commitImportAsync` forces it right after any import: `if (n > 0) autoBackupIfDue(force = true)`.
- failure scenario: the app's storage gets cleared, or the DB is lost. Because `clearing storage` keeps the package, the MediaStore autosave is still "own" and can be found. The ledger had 200 rows, and the RecoveryCard says "Nothing has been overwritten - your backups are untouched". Tj imports one new screenshot, or taps "Start fresh instead" and adds a single trade, before he restores. `recompute` lowers LAST_TXN_COUNT from 200 to 1, and the red card disappears. The import's forced auto-backup (or the next launch's daily one, because `AUTO_BACKUP_AT` is gone with the DB) then replaces `portfolio-autosave.json` with a 1-row ledger. The private snapshots were in `filesDir` and were cleared too. All 200 rows are now gone from every copy the app knows about.
- suggested fix (minimal): in `autoBackupIfDue`, before `saveOrReplaceInDownloads`, read the existing autosave's `counts.transactions` (the manifest is at the top of the file). If the new export has fewer rows, first copy the old file to `portfolio-autosave-previous.json` (one fixed name), or skip the overwrite unless the user just did an explicit delete or wipe. Separately, keep a never-lowered `MAX_TXN_COUNT` next to LAST_TXN_COUNT, so a 200 to 1 drop does not silently clear the "missing" state. Only wipe/deleteSymbol should lower it.
- test: VM/Robolectric-free unit test on the extracted decision function `shouldRotateAutosave(oldCount=200, newCount=1) == true`, plus a `recompute` test that 200 then 1 row keeps a "data shrank" flag unless preceded by `wipeTransactions`/`deleteSymbol`.

### A-2 [M] `Ledger.replayOrder` reverses a correctly-ordered day when earlier history is missing, so the position vanishes and the "oversold" warning goes with it
- where: `domain/Ledger.kt` 193-207
- what's wrong: a same-day BUY/SELL group is reversed whenever the stored order oversells and the reversed order oversells less:
  ```kotlin
  if (group.size > 1 && forward.second > 1e-9) {
      val reversed = group.asReversed()
      if (simulate(held, reversed).second < forward.second - 1e-9) {
          chosen = reversed.toList()
  ```
  That rule cannot tell "stored backwards" from "the buy that covers this sale was never recorded". The second case is exactly what `Position.oversold` exists to report (Ledger.kt 20-43). The KDoc promises "an order that is internally consistent is never touched, so a hand-entered history keeps exactly the order it was entered in". That only holds when the earlier holding is on file. `ReplayOrderTest."a consistent same-day order is kept"` passes only because of the `buy(1, 100.0, 10.0, d1)` row it seeds.
- failure scenario: the ledger starts mid-history, so the old NVDA lot is not on file. Tj imports one day: SELL 100 NVDA @ 180 in the morning, then BUY 100 NVDA @ 175 in the afternoon. `ImportOrder` stores them in the correct oldest-first order. Forward order: oversold 100, 100 shares held at $17,500 basis, and the "100 shares sold without a recorded buy" warning shows. Reversed order (the one chosen): oversold 0, **0 shares**. NVDA drops off the Portfolio tab while he holds 100 shares (~$17,500 of market value missing from equity), realized is +$500 instead of +$18,000, and nothing tells him a buy is missing. The same happens with a partial prior holding: 30 recorded, 80 real, sell 50 then buy 50 gives 30 shares instead of 50, with no warning.
- suggested fix (minimal): only repair groups that could have come from the pre-fix screenshot inserter. Two options: (a) restrict to rows with `source` in {SCREENSHOT, CLAUDE_FILE} and ids below an id watermark recorded once at upgrade time (a setting written the first time the new build opens the DB); or (b) require the reversed order to be fully consistent (`simulate(held, reversed).second == 0`) AND the group's ids to be strictly consecutive, i.e. one import batch. Option (a) is the principled one: new imports are already chronological.
- test: `ReplayOrderTest`: `listOf(sell(1,100.0,180.0,d1), buy(2,100.0,175.0,d1))` with source MANUAL (or id above the watermark) should give shares 100, oversold 100, for both methods.

### A-3 [M] Holdings-screen "position snapshot" imports are dated today, so their whole unrealized gain is reported as today's gain
- where: `net/Claude.kt` 131-136 (prompt: holdings row gives a BUY with `date = null`), 235-236; `net/ClaudeBridge.kt` 227-229, 428-429; `domain/Ledger.kt` 371 and 464-466
- what's wrong: an unparseable or null date becomes today, `val date = parsedDate ?: Fmt.todayMs()`, and the ledger flags any lot dated inside the session window as bought today: `q.addLast(Lot(qty, ..., today = t.date in today))` (FIFO) and `if (t.date in today) { a.todayShares += qty ... }` (average). `Position.dayPnl` then measures those shares from their fill price: `val fromFill = fresh * (q.price - avgCostToday)`. For a snapshot row, the "fill price" is the lifetime average cost.
- failure scenario: Tj imports his Ally Holdings screen. NVDA is 100 sh, avg cost $50, price $180, prev close $178. For that whole session the NVDA row and the Portfolio "Today" headline show **+$13,000 (+260%)** instead of +$200 (+1.1%). `boughtTodayCount` counts every holding as "bought today", and `brokerDayGain` disagrees with it by the entire unrealized P/L. It corrects itself the next session, but it is the first number shown right after onboarding.
- suggested fix (minimal): do not treat a row whose date was estimated as bought today. Either the parsers stamp snapshot/undated rows at `Fmt.todayMs() - 1 day` (the dedupe for these already goes through `findDuplicateIdAnyDate` via the "date estimated" note, so the date value is free), or `Ledger` sets `today = t.date in today && t.note?.contains("date estimated", true) != true`. The first keeps Ledger pure.
- test: LedgerTest: a BUY 100 @ 50 dated `sessionInstant` with note "position snapshot - date estimated", quote price 180 / prev 178, gives `dayPnl == 200.0` and `sharesToday == 0`.

### A-4 [M] The A-L9 "undo" snapshot is shadowed right away after a Replace restore (and after any import), and the button that restores it can be hidden
- where: `ui/PortfolioViewModel.kt` 7824-7855 (`restoreAsync`), 3994, 7725-7728 (`latestSnapshotJson`), 7792-7803 (`snapshotBefore`); `util/Storage.kt` 305-313 (`appBackups`/`prune`); `ui/SettingsScreen.kt` 99, 799-814; `ui/RowActions.kt` 102-105
- what's wrong:
  1. Replace: `if (replace) snapshotBefore("replace")` is followed on success by `if (r.warning == null) autoBackupIfDue(force = true)`. That writes a NEWER `portfolio-autobackup-*.json` containing the replaced state, and it also overwrites the Downloads autosave. `latestSnapshotJson()` takes `appBackups(...).firstOrNull()` (newest by mtime), and that is the only snapshot Settings can restore. So the pre-replace copy is never reachable from the UI, and the replaced-away ledger is gone from Downloads too.
  2. Delete symbol: the dialog promises "until the next daily snapshot, Settings > Restore the latest automatic snapshot puts it back". But `commitImportAsync` forces a snapshot after every import, so importing anything right after the delete makes the undo restore the post-delete state.
  3. The restore button only renders `if (snapshotAt > 0)`, where `snapshotAt = vm.lastAutoBackup()` (`AUTO_BACKUP_AT`). `snapshotBefore` never sets that key, so someone with daily auto-backup off never sees the button the delete/wipe dialogs point to.
  4. `prune(keep = 14)` counts `portfolio-before-*` files in the same window. Deleting 14+ symbols in one clean-up session pushes every daily snapshot out, and the first symbol's pre-delete copy with them.
- failure scenario: Replace-all with the wrong backup file (the dialog's most likely mistake). Tj then taps "Restore the latest automatic snapshot" and gets the wrong file's data back again. The right data exists only as `files/backups/portfolio-before-replace-*.json`, which no screen can reach.
- suggested fix (minimal): let `latestSnapshotJson` prefer the newest `portfolio-before-*` file when it is newer than the newest autobackup minus a few minutes, or better, have Settings list the last few snapshots with their names and times. Skip the forced auto-backup's private copy after a Replace, or write it under a name `latestSnapshotJson` ranks below `before-replace`. Gate the button on `snapshotCount > 0`, not `AUTO_BACKUP_AT`. Prune `before-*` files in their own window (e.g. keep 10).
- test: pure helper `pickUndoSnapshot(files)`: given `[autobackup@t+1s, before-replace@t]` it returns the before-replace file. SettingsScreen gate: count>0 with AUTO_BACKUP_AT=0 still shows the button.

### A-5 [L] Import parsers accept a BUY/SELL with no symbol, so cash moves with no position and no audit flag
- where: `net/Claude.kt` 209-231, `net/ClaudeBridge.kt` 408-427; `domain/Ledger.kt` 318 and 429; `ui/PortfolioViewModel.kt` 3651-3655
- what's wrong: the only BUY/SELL rejection is `qty < 1e-9`. A row whose symbol is blank or `"null"` gets `sym = null` and is imported. Both replays skip it (`val sym = t.symbol?.uppercase() ?: continue`), but `Ledger.cash` sums its amount. `auditFees`' "ghost row" check only looks at `abs(it.quantity) < 1e-9`. The editor blocks this (`TxnEditor.kt:221`), but the import review dialog cannot edit a row.
- failure scenario: a truncated ticker gives `{"type":"BUY","symbol":"","quantity":20,"price":250}`. Cash drops $5,000, no holding appears, and "Since you started" shows a $5,000 loss that nothing on any screen explains.
- fix: in both parsers, `if ((type == BUY || type == SELL) && sym == null) { unusable++; continue }` (the message already says "add those by hand"). Also add symbol-less trades to `auditFees().quantityless`. Test: `ClaudeBridge.parse` of a symbol-less BUY returns 0 txns and an "unusable" note.

### A-6 [L] Import parsers keep `fees` on non-trade rows, and `Ledger.fees` then counts them
- where: `net/Claude.kt` 215 and 239-243, `net/ClaudeBridge.kt` 414 and 432-435; `domain/Ledger.kt` 585-586
- what's wrong: `fees = fees` is stored for every type, while `cashEffect` ignores fees for DEPOSIT/DIVIDEND/INTEREST/WITHDRAWAL/FEE. `fees()` is `txns.sumOf { it.fees } + FEE-type abs(amount)`. The editor zeroes this on purpose (`TxnFields.resolve`, TxnEditor.kt 111-116, "would be counted by `Ledger.fees()` while never leaving the cash balance"), but the importers were not given the same treatment.
- failure scenario: a model returns an ADR custody charge as `{"type":"FEE","symbol":"TSM","amount":0.40,"fees":0.40}`, and the "Fees paid" total counts $0.80. A DIVIDEND with a withholding figure in `fees` inflates fees and never reaches cash.
- fix: `val fees = if (type == BUY || type == SELL) importNumber(o, "fees") else 0.0` in both parsers. Test: parse a FEE row carrying fees, and `Ledger.fees` equals its amount once.

### A-7 [L] "Delete all transactions" leaves every manual override in place, and it silently re-pins positions after re-import
- where: `ui/PortfolioViewModel.kt` 7886-7893 (`wipeTransactions` only calls `db.deleteAllTxns()`); `SettingsScreen.kt` ~997-1003 (dialog text); `commitImportAsync` 3937-4005 (no `warnIfOverridden`)
- what's wrong: overrides survive the wipe but are inert while the symbol has no rows, because `applyOverride` only runs over symbols in the lots map. The typical reason to wipe is to re-import the history cleanly. After that, every stale override applies again. `Ledger.applyOverride` holds shares and basis against the new history, and the import path does not show A-H1's override warning (only `addTxnRecord`/`updateTxn` call `warnIfOverridden`).
- scenario: an NVDA override of 50 shares was set as a workaround. Tj wipes and re-imports the full history (80 shares now), and NVDA still shows 50 with no hint why.
- fix: either clear overrides in `wipeTransactions` (and say so in the dialog; they are in the pre-wipe snapshot), or say in the dialog that they are kept. Also have `commitImportAsync` toast the overridden symbols it touched. Test: wipe then insert a BUY gives no override applied (or the dialog-text choice is asserted).

### A-8 [L] A shared-in answer silently replaces an unreviewed (billed) extraction
- where: `ui/PortfolioViewModel.kt` 6105-6106 (`importAdviceOrTransactions` gives `setImportResult(ExtractResult(r.transactions, ...))`), 4022-4026 (`setImportResult` rewrites `PENDING_IMPORT`)
- what's wrong: `setImportResult` overwrites `_importResult` and the persisted `PENDING_IMPORT` without checking for an extraction that is still waiting for review. On the Activity tab the review dialog is modal, but the new share path (`importSharedInbox`) runs from any screen, and the pending extraction can also have been revived from disk by `loadPendingImport`.
- scenario: a screenshot extraction is restored after process death and not yet reviewed. Tj shares a Claude-app answer containing transactions, and the earlier rows are gone with no message.
- fix: when `_importResult.value?.transactions` is non-empty, append the new rows to it (the dialog's duplicate classification already handles overlap), or refuse with a toast. Test: VM-free helper `mergePending(old, new)`.

### A-9 [L] ShareInbox deletes the shared answer before the import is safely stored, and `put` overwrites an unconsumed one
- where: `util/ShareFiles.kt` 104-121; `ui/PortfolioViewModel.kt` 6122-6133
- what's wrong: `take` does `f.readText().also { f.delete() }`, then waits up to 5 s (`withTimeoutOrNull(5_000) { researchCacheReady.await() }`) before `importShared` persists anything. A process death in that window loses the share. `put` replaces the single file, so two shares that arrive before MainActivity drains the first (cold start) lose the first.
- fix: read without deleting, and delete only after `importShared` returns (`r.dest != null` or an explicit rejection). Idempotency on re-delivery is still covered by `SharedAnswer`/duplicate checks, or use a per-share filename (timestamp) and drain all of them.

### A-10 [L] After a failed import commit the review dialog is stuck on "Importing...", so the extraction that was kept to allow a retry can't be retried
- where: `ui/ActivityScreen.kt` 474-485 (`var saving by remember(r)`, set true, never reset); `ui/PortfolioViewModel.kt` 4003-4004 (`if (res != null) setImportResult(null)`: `r` is deliberately left unchanged on failure)
- what's wrong: on failure `r` is the same object, so `saving` stays `true` and the confirm button stays disabled. Only Cancel is enabled, and it calls `clearImport()`, which discards the extraction the failure path worked to keep.
- fix: have `commitImportAsync`'s `onDone` report failure (pass `res` as `Int?`), and reset `saving = false` on null. Test: covered by a small state-holder test, or manual.

### A-11 [L] Deleting the last rows by hand trips the "Your transactions are missing" alarm, and its one button re-adds them
- where: `ui/PortfolioViewModel.kt` 2494-2500 (the mark is lowered only while non-empty), 3584 (`deleteTxn`), 3587-3595 (`deleteSymbol`: no LAST_TXN_COUNT reset, unlike `wipeTransactions` 7891)
- scenario: a ledger holds one symbol only (no deposits). "Delete NVDA", or deleting its rows one by one, leaves `txns.isEmpty()` with `known > 0`, so `_dataMissing = true`. The RecoveryCard says the data "vanished" and offers "Restore from the automatic copy", which merges the just-deleted rows straight back.
- fix: in `deleteSymbol` and `deleteTxn`, when the table becomes empty, set LAST_TXN_COUNT to 0 (same as wipe). Test: `deleteSymbol` of the only symbol leaves `dataMissing == false`.

### A-12 [L] The MediaStore autosave overwrite truncates in place, so a process kill mid-write leaves a partial "uninstall-proof" copy
- where: `util/Storage.kt` 121-135
- what's wrong: `openOutputStream(existing, "wt")` truncates, then writes. The rollback only runs on an *exception*. A process kill (the write runs on `Dispatchers.IO` from `autoBackupIfDue`, often right at launch or after an import, when the user may swipe the app away) skips it and leaves a truncated JSON. Recovery then reads it (`checkForRecoverableBackup` would treat it as absent, and a Merge restore reports "not valid JSON"). The private-folder writer already uses write-beside-then-rename; this one does not.
- fix: insert a new pending MediaStore row (`portfolio-autosave.json.tmp`, IS_PENDING=1), write it, verify it, then delete the old row and rename the new one via `DISPLAY_NAME` update + IS_PENDING=0. Test: manual / instrumented only.

---

## Notes (verified, not raised as findings)
- `Storage.readText` (today's change): the chunked loop is correct. `return@use null` returns null from `use`, `total` is a Long, and the whole buffer is decoded once, so a UTF-8 sequence split across 64 KB chunks is safe. `maxChars * 4 = 8_000_000` does not overflow Int. The same 8 MB default cap applies to `readOwnDownload` (autosave recovery) and `backupToDownloads`' read-back, while the writers are uncapped. A ledger would need about 30k rows to hit it, so this is only worth noting.
- After a real uninstall/reinstall on Android 11+, MediaStore generally does not attribute the old install's Downloads file to the new install, so `findOwnDownload` may not see `portfolio-autosave.json` and the FoundBackupCard would not appear. The file picker restore still works. This could not be verified without a device; it is why A-1 is framed around "clear storage", where ownership persists.

## Checked and fine
- FIFO/average replays: partial sells, oversell booking of full proceeds, split scaling of lots/today/oversold, fee inclusion in lot unit cost, `unitPrice` fee-netting on both sides, zero-qty skip parity, three-bucket same-day pool in average cost.
- A-H1 fix (`PositionFields.toSave`): untouched boxes keep the stored override, an emptied box clears it, and `Fmt.exact` seeds round-trip.
- A-M3 (SPLIT sorted first on a date tie), A-M4 (`ImportDupes.classify` one-to-one plus REPEAT; `force` commits exactly what was ticked), A-L6 (`importNumber`), A-L7 (`parseDate` year < 1900 retry), A-L10 (`deleteTxnsForSymbol` spares ACCOUNT_LEVEL; parsers and editor null the symbol), A-L11 (import-history dedupe on merge).
- `restoreJson`: single transaction; Replace refuses a file without `transactions` and rolls back on a short manifest; Merge is one-to-one via `claimed`; overrides and settings are add-only on Merge; NaN overrides rejected; secrets and derived/per-device keys excluded both ways; the settings write avoids the lock-order deadlock; the cache is invalidated after `endTransaction`; the day-trading log is additive on both modes.
- `exportJson` includes txns, overrides, watchlist (with anchors), settings minus secrets and derived keys, import history, the day-trading log, and a counts manifest. `backupToDownloads` verifies the file by reading it back.
- `commitImportAsync`: one DB transaction, chronological insert, keeps the extraction on failure, `PENDING_IMPORT` persisted.
- Schema: `onUpgrade` is additive and idempotent; `onOpen` repairs tables and indexes; `onDowngrade` is a no-op; WAL enabled.
- `saveToAppFolder`: tmp plus rename, prune after a successful land. The `saveOrReplaceInDownloads` exception path restores prior bytes (A-12 covers the kill path only).
- `tidyDownloadsOnce`: never deletes the root autosave on an empty ledger, and deletes only after a verified read-back.
- ShareInbox lives under `noBackupFilesDir`; PromptShare writes tmp then rename.
- TxnEditor: `rememberSaveable` fields, exact seeds, non-trade types zero qty/price/fees, SPLIT carries only the ratio, dismiss on outside tap disabled.
