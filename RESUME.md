# RESUME — READ THIS FIRST  (round 66, saved 2026-09-09 15:03:00 UTC)

You are picking up a long-running Android project that was interrupted.
Everything you need is on disk. Do NOT re-read CHECKPOINT.md end to end —
it is 240 KB of round history. This file plus `state.json` is the live state;
CHECKPOINT.md sections 0-5 (lines 1-530) are the only part worth reading cold,
and only if you need the architecture.

## 1. Bring the container back up

```bash
cd /home/claude && tar xzf <the checkpoint tarball>   # if the tree is missing
bash /home/claude/portfolio/setup-env.sh              # Android SDK, ~2 min, once
export ANDROID_HOME=/root/android-sdk
bash /home/claude/portfolio/watchdog.sh &             # restart the 3-min autosave
./ck status                                           # where the work stopped
```

Build traps that have cost real time before are in CHECKPOINT.md lines 22-60.
The short version: never blank `JAVA_TOOL_OPTIONS`; never run two Gradle builds
at once or kill one mid-flight; always background the build with
`setsid nohup ./gradlew ... > /home/claude/build.log 2>&1 < /dev/null & disown`.

## 2. The request this round is answering

> Whole-app round: code efficiency, features working as designed, cache/refresh balance (cache big, refresh liberally where it helps), bug hunt. Thicker separator bars between stocks. Research accuracy. ETF section must rank genuinely healthy, strong-buy ETFs best-first using multiple sources. Worst section: keep only stocks with a buyable companion short vehicle, or delete the section entirely.

## 3. WHERE THE WORK STOPPED

- **In flight:** T4: Stock research accuracy audit
- **Next action:** (pick the first unchecked task below)

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 5/9 done

- [x] T0  Baseline: v7.6 tree green in this container  — v7.6 tree green in this container
- [x] T1  Thicker separator bars between stocks  — separator 3dp -> 5dp with 7dp of air either side; RowLayoutUiTest floor raised 18dp -> 26dp so a revert is caught
- [x] T2  ETF section: accurate, multi-source, healthy strong-buy funds ranked best-first  — ETF ranking: one fund per exposure (Schwab/Saxo both say comparison is only meaningful within an exposure group), youth no longer penalised twice with a three-year floor against performance-chasing, and the blurb now says what the feed cannot see
- [x] T3  Worst section: keep only stocks with a buyable companion short vehicle, or delete the section  — Worst section deleted: tab, scorer, ShortVehicle, the inverse-ETF enrichment and its Claude prompt sections. Measured 16/20 momentum mega-caps have a US single-stock inverse fund vs 2/40 beaten-down names, one of those foreign-listed only
- [>] T4  Stock research accuracy audit  — research accuracy
- [x] T5  Cache and refresh policy: cache as big as needed, refresh liberally where it helps  — cache/refresh audit produced A02 (cadences never fired), A05 (quotes never pruned) and A07 (marks travelling in backups)
- [ ] T6  Whole-app parallel review: bugs, efficiency, UI, features working as designed
- [ ] T7  Fix every confirmed finding
- [ ] T8  REGRESSION + ship v7.7

**Resume at T4** (Stock research accuracy audit).

## 5. Open findings — 7 still open, 5 fixed

- [x] A01 (high) Db.kt:38 txns indexes are created only in onCreate and are not IF NOT EXISTS, so any upgraded database has none - findDuplicateId then full-scans txns once per imported row  — createTxnIndexes with IF NOT EXISTS, called from onCreate and the onOpen repair block, so every upgraded install heals on next launch; DbTest proves the legacy fixture gains both indexes
- [x] A02 (high) PortfolioViewModel.kt:2283 feed and Form-4 cadences are counters local to the poll coroutine, restarted by every setForeground(true), so they measure uninterrupted foreground seconds - the 30-minute insider refresh effectively never fires, and the feed pass can run twice within seconds  — feed and filings cadences are now wall-clock marks (_feedAt, Keys.FILINGS_AT) that survive startAuto being relaunched and the process dying; the decision is the pure passDue() with six tests, and filings can come due independently of the feed
- [x] A03 (high) TxnEditor.kt:55 seeds price and quantity from display formatters, so opening a transaction and pressing Save with no edit re-rounds it and silently changes the recorded cash  — TxnFields extracted from the dialog: seeds use Fmt.exact (round-trips through the editor's own parser) and Save shares one computation with the preview. Pure tests, no flaky dialog rendering
- [x] A04 (med) PortfolioViewModel.kt:1085 onTrimMemory clears _insider but not insiderAt, so the Form 4 section is blank for up to 30 minutes after a memory trim even though the filings are still in memory and on disk  — insiderAt is cleared with _insider on a memory trim, matching coreFetchedAt and ratingsFetchedAt beside it
- [ ] A05 (med) Db.kt:1007 the quotes table is never pruned and is read whole, parsing every spark blob, synchronously on the main thread at launch
- [ ] A06 (med) PortfolioViewModel.kt:2455 a headline's summary is dropped when the story is cached, so reopening a stock loses every blurb
- [x] A07 (med) Db.kt:1429 a restore imports the old phone's AUTOSAVE_AT/AUTO_BACKUP_AT/DOWNLOADS_TIDIED, so a new phone skips its first safety copy for 24 hours  — AUTOSAVE_AT, AUTO_BACKUP_AT, DOWNLOADS_TIDIED and the new FILINGS_AT are excluded from backups, and restoreAsync forces a safety copy of what it just restored
- [ ] A08 (med) PortfolioViewModel.kt:304 spinnerShouldShow does not know about the research/ETF build, so the poll loop retracts the pull indicator mid-build
- [ ] A09 (low) PortfolioViewModel.kt:762 two KDocs claim the quote wave survives backgrounding; it runs on fgScope and is cancelled
- [ ] A10 (low) PortfolioViewModel.kt:1117 restoreSparklines runs a second full quote-cache read on the launch path that provably cannot change anything
- [ ] A11 (low) PortfolioViewModel.kt:2385 two different caps for the same per-symbol news list - the feed pass truncates 60 headlines to 40, removing stories the user is scrolling
- [ ] A12 (low) Format.kt:69 changeFor/changeMoney document four decimals for sub-dollar stocks and give three

## 6. Version

- Shipped: v7.4 (versionCode 61)
- This round ships: v7.5 (versionCode 62)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-09 14:21:57 UTC  finding A10: PortfolioViewModel.kt:1117 restoreSparklines runs a second full quote-cache read
- 2026-09-09 14:21:57 UTC  finding A11: PortfolioViewModel.kt:2385 two different caps for the same per-symbol news list 
- 2026-09-09 14:21:57 UTC  finding A12: Format.kt:69 changeFor/changeMoney document four decimals for sub-dollar stocks 
- 2026-09-09 14:39:44 UTC  A01 fixed: createTxnIndexes with IF NOT EXISTS, called from onCreate and the onOpen repair block, so every upgraded install heals on next launch; DbTest proves the legacy fixture gains both indexes
- 2026-09-09 14:39:46 UTC  A03 fixed: TxnFields extracted from the dialog: seeds use Fmt.exact (round-trips through the editor's own parser) and Save shares one computation with the preview. Pure tests, no flaky dialog rendering
- 2026-09-09 14:44:13 UTC  A02 fixed: feed and filings cadences are now wall-clock marks (_feedAt, Keys.FILINGS_AT) that survive startAuto being relaunched and the process dying; the decision is the pure passDue() with six tests, and filings can come due independently of the feed
- 2026-09-09 14:44:15 UTC  A07 fixed: AUTOSAVE_AT, AUTO_BACKUP_AT, DOWNLOADS_TIDIED and the new FILINGS_AT are excluded from backups, and restoreAsync forces a safety copy of what it just restored
- 2026-09-09 14:44:18 UTC  T5 -> done  cache/refresh audit produced A02 (cadences never fired), A05 (quotes never pruned) and A07 (marks travelling in backups)
- 2026-09-09 14:53:27 UTC  T3 -> done  Worst section deleted: tab, scorer, ShortVehicle, the inverse-ETF enrichment and its Claude prompt sections. Measured 16/20 momentum mega-caps have a US single-stock inverse fund vs 2/40 beaten-down names, one of those foreign-listed only
- 2026-09-09 15:00:12 UTC  T2 -> done  ETF ranking: one fund per exposure (Schwab/Saxo both say comparison is only meaningful within an exposure group), youth no longer penalised twice with a three-year floor against performance-chasing, and the blurb now says what the feed cannot see
- 2026-09-09 15:00:13 UTC  T4 -> doing  research accuracy
- 2026-09-09 15:03:00 UTC  A04 fixed: insiderAt is cleared with _insider on a memory trim, matching coreFetchedAt and ratingsFetchedAt beside it

