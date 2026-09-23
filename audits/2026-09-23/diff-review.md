# Independent review of today's diff (`git diff 0ed968e -- app/`), 2026-09-23

Scope: bugs INTRODUCED by today's changes (share round trip 2026-09-23b + the S/A/D/U/N fixes).
Read-only review; `python3 tools/checkinit.py` = ok. Test files skipped except where noted.

---

### R-1 [H] Recovery restores the stale "-previous" autosave, losing every transaction entered after a deliberate shrink

Where: `ui/PortfolioViewModel.kt:8111` (write side), `:8166` (read side); used by both recovery
buttons in `ui/PortfolioScreen.kt:589` and `:649` (`restoreAsync(json, replace = false)`).

What's wrong: the A-1 fix keeps the larger autosave whenever a new one has fewer rows, and never
tells a deliberate shrink apart from a wipe:

```kotlin
if (oldJson != null && oldCount > newCount) { ... saveOrReplaceInDownloads(app, AUTOSAVE_PREVIOUS_FILE, oldJson) }
...
if (backupTxnCount(prev) > backupTxnCount(cur)) prev else cur
```

Nothing ever deletes or refreshes `portfolio-autosave-previous.json` - not `wipeTransactions`,
`deleteSymbol`, `deleteTxn` or a Replace restore (grep: the constant is used only at 517/8113/8117/8165).

Failure scenario: 200 rows -> Tj deletes a symbol with 50 rows (or does A-7's "wipe and re-import
cleanly" and ends up with 150 de-duplicated rows). The next autosave shrinks, so the 200-row file
becomes `-previous`. He then adds 20 trades (170 rows). After a reinstall/storage clear, the
recovery card restores the LARGER file - the 200-row one. Result: the 20 newest transactions are
not restored (they are only in the current autosave, which is never read), and the 50 deleted rows
(or the duplicates he cleaned out) come back. The banner even shows the current file's "Written
..." time while restoring the older one. This is the exact situation the autosave exists for.

Minimal fix: in `readAutosave` restore BOTH (merge `cur`, then merge `prev` only if needed), or
simpler and safer: delete `AUTOSAVE_PREVIOUS_FILE` (or skip creating it) whenever the shrink is
deliberate - call a `dropAutosavePrevious()` from `wipeTransactions`, `deleteSymbol`, `deleteTxn`
and the Replace restore - and only create it when the ledger was empty/near-empty relative to the
high-water mark (`LAST_TXN_COUNT`) at the time of the shrink. At minimum, restore `cur` first and
then `prev` (both merge) so nothing newer is dropped.

---

### R-2 [M] A-2 watermark stops repairing genuinely backwards same-day groups (multi-batch imports, hand entry)

Where: `domain/Ledger.kt:210`, watermark set at `ui/PortfolioViewModel.kt:3715`.

What's wrong:

```kotlin
if (group.size > 1 && forward.second > 1e-9 && group.all { it.id < repairBelowId }) {
```

The premise in the comment - "Rows inserted since imports became chronological are already in the
right order" - is only true WITHIN one import batch (`ImportOrder.chronological` orders one
`keep` list). It is false across batches and for manual entry, and the simulation cannot tell the
A-2 case from these (both are `SELL q, BUY q` with nothing held before).

Failure scenario: Tj imports the newest page of his activity screen (contains today's 14:00 SELL
100 XYZ), then the next page (contains the 10:00 BUY 100 XYZ) as a second batch, or shares two
Claude answers. SELL gets the lower id; both are above the watermark, so the group is no longer
reversed: the SELL is reported oversold and the BUY opens a phantom 100-share position (wrong
shares, market value and cost basis). Before today this was repaired. Same for a hand-entered
SELL typed before its same-day BUY.

Minimal fix: only exempt a group whose rows all came from ONE commit (e.g. store an import batch
id / `recordImport` row id on each txn, or treat consecutive ids with the same `source` inserted in
one transaction as a batch); groups that span batches or include MANUAL rows stay repair
candidates. If that is too big, revert the watermark and handle A-2 by keeping the oversold
warning when the reversal happened (surface "order was assumed").

---

### R-3 [M] A Claude Day Trading plan imported onto an older `generated` set is wiped by the next cold start the same day

Where: `ui/PortfolioViewModel.kt:6468` (cold load) + `:6828` (new `researchStale`).

What's wrong: U-1/D-1 made an import count as fresh, so after a share the list is no longer
rebuilt and `generated` stays at the older day:

```kotlin
val freshAt = maxOf(s.generated, s.explained, s.dtExplained)
```

but the cold-start eviction still keys on `generated` only and clears Claude's levels too:

```kotlin
dayTrading = evictStaleDayTradingPlan(whyEvicted.dayTrading, loaded.generated)
// evictStaleDayTradingPlan: if dayKey(generated) != dayKey(now) -> entry/stop/target = 0, planByClaude = false
```

Failure scenario: research last built Monday afternoon. Tuesday 09:00 Tj shares Claude's Day
Trading answer; it applies (plan shown, `dtExplained` = Tuesday), no rebuild happens (not stale).
Android kills the process at lunch; on reopen `loadCachedResearchNow` sees `generated` = Monday
and strips every Claude plan imported that morning - the D-1 data loss, moved to cold start.

Minimal fix: in `evictStaleDayTradingPlan`, keep a row whose `planByClaude && sameTradingDay(row.whyAt, now)`
(the same rule `carryWhy` now uses), or pass `maxOf(generated, dtExplained)` for Claude rows.

---

### R-4 [L] D-8 (evening Claude plan survives to the next session) is undone by the carry rule and cold-start eviction

Where: `ui/PortfolioViewModel.kt:764` and `:898` vs `net/DayTradingBridge.kt` (`afterTodaysClose`,
`answerIsCurrent`).

What's wrong: D-8 and D-5 explicitly treat an import after the close as a plan for the NEXT
session (`answerIsCurrent` accepts yesterday's date before the open), but the new carry keeps a
Claude plan / Claude-added DT row only when `sameTradingDay(p.whyAt, now)`:

```kotlin
if (!dayTrading || !p.planByClaude || !sameTradingDay(p.whyAt, now)) return base
```

Failure scenario: plan imported 21:00 ET Monday. Tuesday 08:00 the Research screen finds the set
older than 30 min -> rebuild -> `whyAt` is Monday -> Claude's levels and any Claude-added rows are
dropped (a cold start drops them too via `evictStaleDayTradingPlan`). The D-8 fix only helps a
warm process that never rebuilds before the open.

Minimal fix: use one "session this plan is for" rule everywhere - e.g. a helper
`planSessionKey(whyAt)` = next trading day when `whyAt` is after that day's close - and compare
that, not `dayKey(whyAt)`, in `carry`, `carryWhy` and `evictStaleDayTradingPlan`.

---

### R-5 [L] Queued shares are only drained by an ACTION_IMPORT intent - a launcher start never imports a leftover

Where: `MainActivity.kt:138`; claim at `ui/PortfolioViewModel.kt` (`importSharedInbox` loop comment)
and `util/ShareFiles.kt` (`ShareInbox.next` doc).

What's wrong:

```kotlin
if (i?.action != com.tj.portfolio.util.ShareInbox.ACTION_IMPORT) return
```

The A-9/U-7 comments say a share left in the inbox by a process death "re-imports on the next
start", but nothing drains the inbox on an ordinary start (the only caller is `importIfShared`).
Also, if `ShareInbox.done` fails (`if (!removed) break`), the same item is re-imported on every
later ACTION_IMPORT (and every rotation of an ACTION_IMPORT activity).

Failure scenario: share arrives, process is killed before the import runs (or the MainActivity
start is blocked), Tj swipes the task away and opens the app from the launcher: the answer silently
never imports - until his NEXT share, when the stale one is imported first (stale transactions
appended to the review, stale advice shown).

Minimal fix: call `importSharedInbox()` once from `PortfolioViewModel.init` (after the properties
it uses; it already waits for `researchCacheReady`), or unconditionally in `MainActivity.onCreate`.

---

### R-6 [L] Tradestie is marked dead for 6 hours on any failure, including cancellation and being offline

Where: `net/Social.kt:36`.

```kotlin
else runCatching { tradestie() }.getOrNull()
    .also { if (it == null) tradestieDeadUntil = now + SOURCE_DEAD_MS }
```

`runCatching` also swallows `CancellationException`, and `refreshFeed` is cancelled outright on
`ON_STOP`; an offline `Http.get` is `!r.ok` -> `null` too. Failure scenario: Tj leaves the app while
a feed pass is fetching, or opens it in a tunnel -> the only sentiment source is disabled for six
hours in this process (moot today while its certificate is expired, but it defeats the "comes back
on its own if fixed" intent). Minimal fix: rethrow `CancellationException`, and only arm the 6-hour
mark for TLS/handshake failures or repeated failures while `online()`.

---

### R-7 [L] Day Trading detail-only loop now polls while the market is closed

Where: `ui/PortfolioViewModel.kt:7524`.

```kotlin
if (online() && (!closed || !dayTradingSweepDone)) { enrichDayTradingVisible() }
```

With `dayTradingLiveOnly != null` (a detail screen), `enrichDayTradingVisible` never sweeps, so
`dayTradingSweepDone` stays false and the one symbol is fetched on every closed-market tick for as
long as the detail screen is open (before today: nothing while closed). Minimal fix:
`(!closed || (!dayTradingSweepDone && dayTradingLiveOnly == null))`.

---

### R-8 [L] `dateEstimated` is sticky: a row whose date Tj later corrects is never "bought today"

Where: `data/Models.kt:112`, used in `domain/Ledger.kt` fifo/averageCost.

```kotlin
val dateEstimated: Boolean get() = note?.contains(DATE_ESTIMATED, ignoreCase = true) == true
```

The note is not cleared when the date is edited. Failure scenario: an imported row with no date is
stamped today with the note; it really was bought today, Tj confirms/edits the date in the editor;
the lot is still treated as held-before, so the Today column shows the move since yesterday's close
instead of since his fill. Minimal fix: strip `DATE_ESTIMATED` from the note when the editor saves
a date, or store the flag as a field cleared on edit.

---

### R-9 [L] D-5 date check is bypassed because the prompt's own `asOf` is not ISO

Where: `net/DayTradingBridge.kt:234` vs `:391/394`.

The bundle sends `put("asOf", Fmt.day(...))` = `"Sep 23, 2026"` while the schema asks for
`YYYY-MM-DD`; `answerIsCurrent` returns `true` whenever the regex does not match. A reply that
echoes the bundle's date (likely) is always "current", so an old answer file still becomes today's
CLAUDE'S PLAN. Minimal fix: send `asOf` as ISO (`Fmt.iso`-style `yyyy-MM-dd`, ET date), and/or
also parse `MMM d, yyyy` in `answerIsCurrent`.

---

### R-10 [L] "Restore the latest automatic snapshot" can now return an older before-copy that lacks a later import

Where: `ui/PortfolioViewModel.kt:513` (`pickUndoSnapshot`).

`commitImportAsync` forces a daily snapshot after every import. Delete a symbol at 10:00 (before
copy), import 30 screenshot rows at 10:05 (forced daily snapshot): the button now restores the
10:00 before-copy - without the 30 rows - under a label that says "latest". Choosing Replace loses
them (recoverable only via the next before-replace copy). Minimal fix: prefer the before-copy only
when no import/other ledger write happened after it (e.g. compare the daily snapshot's
`counts.transactions` with the pre-delete state, or tag forced post-restore snapshots by name and
only let THOSE be shadowed).

---

### R-11 [L] Advice/transaction shares wait behind a research build (up to 90 s)

Where: `ui/PortfolioViewModel.kt:6365`.

`importSharedInbox` waits for `researchCacheReady` and `_researchBusy` to empty before routing any
item, including advice and transactions that never touch `_research`. Failure scenario: share an
Advice answer while a research rebuild or an API "Explain with Claude" call runs - nothing happens
for up to 90 s, then the import lands. Minimal fix: classify first and only await the research
gates for `Kind.RESEARCH` / `Kind.DAY_TRADING`.

---

## Checked and found correct

- `checkinit.py`: ok; `researchCacheReady`, `shareDrain`, `recProvisional`, `feed*Raw` are all above `init`.
- `loadCachedResearch` completes `researchCacheReady` in `finally`, after the Main-thread publish; `loadResearch`/`loadEtfs` deferral can't deadlock (the only completer is always launched from `init`).
- `_researchBusy.first { it.isEmpty() }` on a StateFlow is bounded and fine; `loadResearch` sets busy synchronously on `fgScope` so the drain can't slip in between.
- Manifest: trampoline + NEW_TASK|CLEAR_TOP|SINGLE_TOP -> `onNewIntent`; FileProvider not exported, only `cache/shared/`; our own authority and `file://` rejected in `readShared`; BROWSABLE gone. `ViewModelProvider(this)` returns the same instance as `viewModel()` in `App`.
- `Storage.readText` bounded streaming read decodes once (no split multibyte chars); `return@use null` is correct.
- `PromptShare.chooser` grant on both intents + ClipData; `launchPromptShare` lifecycle check; the four prompt buttons all go through `deliverPrompt`.
- Research/DT section-index mapping (`ResearchSet.SECTIONS` vs `Section.entries`) agree; `researchJump`/`shareNav` are state and are cleared after use; DT buttons are in the always-present "blurb" item.
- `commitImportAsync` null-on-failure: single caller updated; dialog re-enables on null.
- Http N-3 re-check inside the permit returns through `return@withPermit` into `result` correctly.
- `RecentBodies` key strips the host; D1 chart url == DT intraday url (`1d`/`5m`/prePost); `adoptRecentD1` only writes to disk when the held copy was stale.
- Relevance padding: both `squashed()` and `Subject.phrase` are space-padded; the only external `squashedHay` callers use `Relevance.squashed`.
- Insider N-7 stamping, deep-news N-11, chart finality N-6, Recommend S-2/S-7, ResearchScore S-5/S-6/S-8/S-9, D-2 decline streak, D-6 cap, D-7 shown-window logging (same `take(shown)` as `visibleResearch`), A-5/A-6 parsers, A-7 overrides wipe, A-11 mark reset, U-3 advice-cache guard, S-10 ETF reasons merge.
