# Offline refresh + Android 10 Go / MT8168 (2026-10-27)

Request: see TASKS.md (Tj's words). Design as built:

* **Pull-to-refresh** (`PortfolioViewModel.refresh(manual=true)`) runs the old price pass unchanged, then
  `startOfflineSync()` -> `ui/OfflineSync.kt`. A second pull within 5 min of a full pass is only the price pass.
* **Order = priority** (so a dropped connection has already delivered the best tiers):
  1 quotes + charts (all 7 ranges) of holdings -> 2 dividends + earnings -> 3 holdings news (+ saved pages)
  -> 4 fundamentals/ratings + analyst/opinion pieces (`News.analysisAndOpinion`) (+ saved pages)
  -> 5 watch list (charts, news, fundamentals) -> 6 research + ETF rebuild, top-10 picks' news
  -> 7 live-feed headlines + pages. Day trading and the live feed's own loop are untouched.
* **Light mode** (`Keys.LIGHT_REFRESH`, switch on Portfolio/Watch strip + Settings): quotes, charts D1/M1/Y1 for holdings,
  headlines via `refreshFeed(includeInsider=false)`. No pages, no dividends/analysis/research.
* **Storage**: every loader already writes through to SQLite (survives close/reboot). New: `offline_pages` (db v11,
  gzip readable article text, 10-40 KB each). `OfflineBudget` (pure): 50 MB across pinned data (charts, headlines,
  fundamentals, settings blobs) + evictable buckets; eviction order HTTP cache -> feed -> research -> watch -> analysis -> news.
  Prices/charts/dividends/earnings are pinned = never evicted.
* **Stale purge** only after a pass that reached the network: pages not re-confirmed in tiers the pass covered,
  headlines >16 d, charts of untracked symbols, pages >21 d. An interrupted pass deletes nothing.
* **Reader**: offline (or load error) -> `loadDataWithBaseURL` of the saved copy.
* **Go tablet**: `util/DeviceProfile` (`isLowRamDevice`): fewer parallel requests (5->3, sync 3->2), half the heap response cache.
  minSdk 26 already covers Android 10; MediaStore storage path already guards API 29; no dynamic-color/API-31 calls.
* Limits: Google News links are JS redirect stubs, so analyst/opinion *headlines* are saved but their pages usually cannot be;
  Yahoo/Nasdaq article pages are saved.
