# Full-test audit 2026-09-23 - UI, battery/lifecycle, and today's share flow (U-*)

Scope: `git diff 0ed968e -- app/` (the 2026-09-23b share flow), MainActivity/App navigation,
ui/*.kt screens touched today, the fgScope/ON_STOP pattern. Research only, no code changed,
no gradle run. Everything below was checked against the source; line numbers are as of b44f94f.

---

### U-1 [H] A Claude answer shared in for Research/Day Trading is wiped by a rebuild a few seconds after the import succeeds
- where: ui/PortfolioViewModel.kt:6122-6135 (`importSharedInbox`), 6144-6167 (`importShared`),
  7104-7125 (`applyDayTradingAnswer`), 6962-6993 (`applyResearchAnswer`: `generated = cur.generated`),
  6549-6553 (`researchStale`), 6562-6600 (`loadResearch`), 674-724 + 822-834 (`carryExplanations`/`carryWhy`);
  ui/ResearchScreen.kt:223-225 (`LaunchedEffect(section, busy)`); MainActivity.kt:359-372 (shareNav).
- what's wrong: the share path imports, then navigates to Research
  (`goToTab(TAB_WATCHLIST); watchSubTab = WATCH_RESEARCH`). That puts ResearchScreen into
  composition and its first effect runs:
  ```kotlin
  LaunchedEffect(section, busy) {
      if (section == Section.ETFS) vm.loadEtfs() else vm.loadResearch()
  }
  ```
  An import does not move the rebuild clock (`generated = cur.generated`, "The rebuild clock is
  NOT advanced by an explanation pass"). So if the last screener build is more than
  `Research.TTL_MS` (30 min) old, `loadResearch()` sees `researchStale()` and starts a full
  rebuild. When it lands it runs `cacheResearch(carryExplanations(_research.value, built))`,
  and `carryWhy` carries only this:
  ```kotlin
  if (p == null || !stillCurrent(p.why, p.whyAt, now)) return r
  return r.copy(why = p.why, whyAt = p.whyAt)
  ```
  Every row Claude ADDED is dropped (it isn't in `built`), every row Claude DROPPED comes back,
  and Claude's entry/stop/target (`planByClaude`) are replaced by the app's own. The
  `dtExplained`/`dtExplainedBy` stamp is carried by `carryExplainedStamp`, so the screen still
  says "Explained just now via Claude app" over a list that is no longer Claude's.
  The second way to get here: the share path never checks `researchBusy`. The "Import answer"
  button is disabled while a build runs for exactly this reason (`importEnabled = busy.isEmpty()`,
  RES-7), but `importSharedInbox` applies the answer while a build is running, and the build's
  `carryExplanations` then overwrites it the same way.
- concrete failure scenario: Tj opens Day Trading (build at 09:40) and taps "Make prompt file".
  Claude does web research, and he shares the answer back at 10:15. Toast: "Day trading rebuilt
  by Claude - 8 picks, 3 new, 2 dropped". The app jumps to Day Trading, the effect fires, the
  09:40 build is stale, and about 5 s later the 3 new picks are gone, the 2 dropped are back and
  every "CLAUDE'S PLAN" is gone. The button path mostly avoids this because ResearchScreen is
  already composed and the effect doesn't re-run. The share path triggers it every time the
  cache is old. A cold start that lands on the Research tab can also start a build before the
  import (see U-2), which gives the busy path.
- suggested fix (minimal): (a) count a Claude import as fresh for rebuild purposes, e.g. in
  `researchStale()` use `maxOf(s.generated, s.dtExplained, s.explained)` against the TTL (or set
  a `researchImportHoldUntil = now + TTL` in `importShared` that `loadResearch(force = false)`
  respects); (b) in `importSharedInbox`, if `_researchBusy.value == BUSY_BUILDING`, wait for the
  build first (`researchJob?.join()` inside the existing bounded `withTimeoutOrNull`), then
  apply. Test (Robolectric, next to ShareFlowTest): seed `RESEARCH_CACHE` with
  `generated = now - TTL - 60_000`, call `importShared(dayTradingAnswerAddingXYZ)`, then call
  `vm.loadResearch()`. Assert `researchBusy` stays `""` and `research.value.dayTrading` still
  contains XYZ with `planByClaude == true`.

### U-2 [M] A cold start onto the Research tab can start a full ~18-request rebuild even when the cached lists are fresh
- where: ui/PortfolioViewModel.kt:6192-6196 (`loadCachedResearch`, async on IO), 6549-6553,
  6562-6564; ui/ResearchScreen.kt:223-225.
- what's wrong: the cache is parsed off the main thread
  (`viewModelScope.launch(Dispatchers.IO) { try { loadCachedResearchNow() } ... }`), but
  ResearchScreen's `LaunchedEffect(section, busy)` runs `vm.loadResearch()` on the first
  composition. If the effect wins the race, `_research.value` is still empty,
  `researchStale()` returns true (`s.isEmpty || ...`) and a rebuild starts. When the cache
  arrives, `loadCachedResearchNow` publishes it (the set is still fully empty) and the rebuild
  then replaces it. It does not wait for `researchCacheReady`, the deferred added today for the
  share path.
- concrete failure scenario: Tj left the app on Watch -> Research and Android killed the process.
  He reopens it 5 minutes later (or a share cold-starts it). The first frame beats the SQLite read
  plus JSON parse of a few hundred rows, so ~18 screener requests go out for lists that are
  5 minutes old. This also resets paging (`resetResearchPaging`) and `dayTradingSweepDone`,
  which triggers a whole-section technicals sweep. With a share it also feeds U-1's
  "build in flight" path. This depends on timing: it doesn't fire on every cold start.
- suggested fix: at the top of `loadResearch` (and `loadEtfs`), return early when
  `!researchCacheReady.isCompleted && !force`, and add `researchCacheReady` (or a
  `researchCacheLoaded` StateFlow) to the effect's keys so it re-runs once the cache is in.
  Test: construct the VM with a fresh cache and call `loadResearch()` before the cache job
  finishes (StandardTestDispatcher, don't advance IO). Assert no build starts and
  `researchBusy == ""`.

### U-3 [L] An Advice answer shared in on a cold start can be replaced in memory by the older cached advice
- where: ui/PortfolioViewModel.kt:4039-4056 (`loadCachedAdvice`), 6122-6135, 6098-6103.
- what's wrong: `importSharedInbox` waits only for the research cache
  (`withTimeoutOrNull(5_000) { researchCacheReady.await() }`). `loadCachedAdvice` reads
  `ADVICE_CACHE` on IO and then assigns without checking anything:
  `withContext(Dispatchers.Main) { _advice.value = a }`. If that read happens before the
  import's `db.set(Keys.ADVICE_CACHE, ...)` and posts after the import's `_advice.value = r.advice`,
  the old advice goes back on screen.
- concrete failure scenario: a cold start from a share with a blank or tiny research cache, so
  `researchCacheReady` completes at once. The toast says "Advice loaded (12 ratings)" and the
  Advice tab opens on the previous review. The DB holds the new one, so it corrects itself on
  the next launch. The window is small.
- suggested fix: in `loadCachedAdvice`, assign only if `_advice.value == null`, as the research
  and feed loaders already do ("a cache read must never overwrite something newer"). Or add an
  `adviceCacheReady` deferred and await both. Test: delay the advice-cache job, run
  `importShared(adviceAnswer)`, release the job, and assert that `advice.value` is the imported one.

### U-4 [L] Any installed app can push an import through the exported share target, and `file://` streams are read with Portfolio's own permissions
- where: AndroidManifest.xml (ShareImportActivity `exported="true"`, VIEW filter with
  `category.BROWSABLE`); util/ShareFiles.kt:133-150 (`readShared`); util/Storage.kt `readText`.
- what's wrong: `readShared` accepts any `EXTRA_STREAM`/`data` Uri scheme and opens it with
  `ctx.contentResolver.openInputStream(uri)`. That opens `file:///data/data/com.tj.portfolio/...`
  inside Portfolio's own process, which is a confused-deputy read of app-private files. The
  content is only imported, never sent anywhere, so nothing leaks. But the import applies with
  no confirmation: advice is overwritten and persisted (`db.set(Keys.ADVICE_CACHE, ...)`), the
  Research/DT lists are merged, and a pending transaction review is replaced (U-5). The
  `BROWSABLE` category serves no real sender (the Claude app and file managers don't need it)
  and only widens who can reach the filter.
- concrete failure scenario: any app on the phone fires `ACTION_SEND` with an `EXTRA_TEXT`
  advice JSON, or with `EXTRA_STREAM=file:///data/data/com.tj.portfolio/files/portfolio-autosave.json`.
  Portfolio comes to the front and its saved Advice is overwritten, or a review sheet of his own
  transactions replaces the one he was working on. On a sideloaded personal phone the risk is low.
- suggested fix: in `readShared`, reject any Uri whose scheme isn't `content`, and reject
  `content://${packageName}.files/...` (our own provider). Drop `BROWSABLE` from the VIEW filter.
  Test: `readShared` with a `file://` EXTRA_STREAM returns null. Also a manifest check in
  ShareFlowTest that the VIEW filter has no BROWSABLE category.

### U-5 [L] A shared transactions answer silently replaces an unconfirmed (possibly paid) import review
- where: ui/PortfolioViewModel.kt:6107-6112 (`importAdviceOrTransactions` ->
  `setImportResult(ExtractResult(...))`), 4022-4026 (`setImportResult` writes `PENDING_IMPORT`).
- what's wrong: `setImportResult` exists to keep a paid API extraction safe across process
  death, and its own comment says so. The share path overwrites both `_importResult` and
  `PENDING_IMPORT` without checking whether a review is already waiting. The button path has the
  same behaviour. What's new is that it can now come from outside the app with no button pressed.
- concrete failure scenario: Tj runs "Import screenshots (uses API key)". 80 rows are waiting
  for review. Before confirming, he switches to Claude and shares back a transactions answer from
  an earlier chat. The 80-row paid extraction is gone.
- suggested fix: if `_importResult.value?.transactions?.isNotEmpty() == true`, append the new rows
  to the existing review and let the dupe flags sort them out, or refuse with a toast ("Finish
  the transaction review that's open first"). Test: seed a pending import, `importShared(txnAnswer)`,
  and assert the original rows are still in `importResult`.

### U-6 [L] A prompt share that finishes after the user has left the app is silently lost, or pops the chooser over another app
- where: ui/PortfolioViewModel.kt:4227-4269 (`preloadNewsForAdvice`: `catch (e: Exception)` then
  `finally { onDone() }`); ui/AdviceScreen.kt:111-121; ui/PromptShareUi.kt:14-24.
- what's wrong: leaving the app cancels `fgScope`. The resulting `CancellationException` is
  caught by `catch (e: Exception)` and `finally { onDone() }` still runs `writeAdvicePrompt`, which
  is on `viewModelScope` and survives. `launchPromptShare` then calls `ctx.startActivity(chooser)`
  from a stopped activity. Android 10+ background-start rules either block that without an
  exception, so `runCatching{}.isSuccess` is true and the fallback toast never shows, or, inside
  the platform's short grace window, the share sheet opens over whatever app Tj switched to.
- concrete failure scenario: he taps "Make prompt file", sees "Gathering news..." for a few
  seconds and swipes home. Either nothing happens and there's no "saved to Downloads" message,
  or a Portfolio share sheet appears on top of his other app.
- suggested fix: in `launchPromptShare`, only start the chooser if the activity is at least
  STARTED (`(ctx as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(STARTED)`); otherwise
  `toast(out.message)`. Rethrow `CancellationException` in `preloadNewsForAdvice` so a cancelled
  preload doesn't go on to build a prompt at all. Test: a pure test of a small
  `shouldOpenChooser(state)` helper.

### U-7 [L] The share hand-off deletes the answer before importing it, and ignores the shared text when the stream can't be read
- where: util/ShareFiles.kt:116-122 (`take` reads and deletes), 133-150 (`readShared`);
  ui/PortfolioViewModel.kt:6124-6131.
- what's wrong: (a) `ShareInbox.take` deletes the file, then `importSharedInbox` waits up to 5 s
  for `researchCacheReady` before importing. If the process dies in that window, the answer is
  lost. Re-sharing recovers it, but the loss is silent. (b) When a SEND carries both a stream and
  `EXTRA_TEXT` and the stream can't be read (revoked or failed grant),
  `Storage.readText(...) ?: return null` returns early and never tries the text that is right there.
- concrete failure scenario: (b) a chat app shares Claude's reply as text plus an attachment
  whose grant has expired. Tj gets "Portfolio couldn't read that share" even though the full
  answer came as text.
- suggested fix: (a) read the inbox without deleting it and delete only after `importShared`
  returns (keep the delete in a `finally`). (b) In `readShared`, fall through to `EXTRA_TEXT`
  when the stream read returns null. Test: `readShared` with an unreadable stream plus
  EXTRA_TEXT returns the text.

---

## Checked and fine
- Trampoline task behaviour: `NEW_TASK|CLEAR_TOP|SINGLE_TOP` to the explicit MainActivity finds
  the app's own task by affinity. A live instance gets `onNewIntent`. A task whose process died
  is recreated from saved state and gets the import through `onNewIntent`. A true cold start
  gets it in `onCreate`. `ShareInbox.take` deleting on read makes re-delivered or recents
  root intents (`ACTION_IMPORT`) no-ops. If the Claude activity was running inside Portfolio's
  task (opened from the chooser), CLEAR_TOP correctly finishes it off the top.
  (`taskAffinity=""` only matters with NEW_TASK, so the trampoline normally sits in the
  sender's task. The comment overstates this, but it's harmless because it finishes at once.)
- URI grant lifetime: the stream is read on the worker thread before `finish()`, while the
  receiving activity (and so the grant) is alive. Read failures are caught and reported.
- `ViewModelProvider(this)[PortfolioViewModel::class.java]` in MainActivity resolves to the same
  instance as `viewModel()` in `App`.
- shareNav/researchJump are state, not events, so a request raised before a screen is composed
  still lands. Both are cleared after handling. `goToTab` keeps tab history and back
  consistent. The jump persists `setResearchTab`, so a freshly composed ResearchScreen also
  starts on the right section. The toast is independent of navigation.
- Intent filters: `text/*` + `application/json` + `application/octet-stream` for SEND, and VIEW
  with the `content` scheme, cover .md shared from the Claude app or a file manager (including
  a sender type of `*/*`). EXTRA_TEXT covers a shared chat reply. These filters put Portfolio in
  every text/link share sheet on the phone; that is a deliberate trade-off (documented in the
  manifest), and junk is rejected with a plain toast and no navigation.
- FileProvider: not exported, `grantUriPermissions`, and exposes only `cache/shared/`. The
  authority `${applicationId}.files` matches `PromptShare.authority` (no applicationIdSuffix).
  The chooser carries the grant via both ClipData and the flag. `EXTRA_STREAM` without
  EXTRA_TEXT is correct. The Downloads copy is still written as the fallback.
- SharedAnswer.classify: the order (prompt marker, then DT, then research, then Claude) matches
  `importClaudeFile`/`importResearchFile`. The binary check is sampled.
  `Storage.readText` now stops at maxBytes instead of reading everything.
- Day Trading: the blurb is deleted (`""` and guarded by `isNotBlank()`). The buttons are at
  the top, under the market-phase line, and not duplicated at the bottom (`if (section != DAY_TRADING)`).
  The how-to text says "at the top". The footnote's "Ask Claude below" still refers to the API
  button, which is still below.
- ClaudeBridge.fileDelivery/SHARE_BACK_LINE are interpolated correctly into all four prompts,
  and every prompt still carries the PROMPT_MARK header, so a prompt shared back is refused.
- Battery: `setForeground(false)` cancels autoJob/feed/insider jobs and the whole `fgScope`.
  The DT live loop is on fgScope, stops on section change or leaving the screen
  (`DisposableEffect(section)`), and restarts on ON_START only if `dayTradingLiveWanted`. autoJob
  also breaks on `!foreground`. Nothing added today polls. The share import's price fills run on
  fgScope while the app is in the foreground.
- ImportReviewDialog state is keyed `remember(r)`, so a replaced result resets the checkboxes
  correctly.
- Lazy lists: the unkeyed `itemsIndexed` in the review dialog is index-stable (fixed list per
  `r`). The Research/DetailTabs lists are keyed and de-duplicated.
