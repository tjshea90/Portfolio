# Full-test audit 2026-09-24 — Persistence, data retention and accounting (A-)

Auditor: read-only subagent. Scope: data/Db.kt, domain/Ledger.kt, Fees.kt, ImportOrder.kt,
Models, util/Storage.kt, CrashLog, Json, ShareFiles, ShareImportActivity, SharedAnswer,
TxnEditor, ActivityScreen, Settings backup/restore/wipe, backup rules, VM persistence paths.
All paths below are under `app/src/main/java/com/tj/portfolio/` unless stated.

STATUS: IN PROGRESS (findings so far below)

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
