---
date: 2026-11-01T03:44:48.551Z
project: Portfolio
session: a30f08ae
schema_version: 1
next_steps_count: 3
has_blockers: false
keywords: portfolio, offline sync, earnings reports, markets indices, sync service, v8.8
headline: Portfolio Android app (Kotlin/Compose, tjshea90/Portfolio, sideloaded). Goal: Tj requests iterated 2026-10-31..11-01 — m
---

# Session Handoff Snapshot
> **Generated:** 11/1/2026, 3:44:48 AM

## 🎯 High-Level Objective
* **Goal:** Portfolio Android app (Kotlin/Compose, tjshea90/Portfolio, sideloaded). Goal: Tj requests iterated 2026-10-31..11-01 — more offline articles, recent earnings, indices/charts, background refresh. Core intent: tablet used mostly offline; everything refreshed online must persist for offline reading.

## 📌 Current State & Next Steps
* **Status:** Done: all four requests built, CI-built, released, recorded. Remaining: nothing pending in TASKS.md; behaviour unverified on device and against live Yahoo/Nasdaq data.
* **Next Action:** Await Tj feedback on v8.8

### Remaining Queue
- [ ] If Earnings last-month still empty: debug EarningsFeed.parseNasdaqReports / which source answered
- [ ] Verify background refresh and Markets charts on device

## 🛠️ Modified Files Delta
* net/OfflinePages.kt: JSON-LD/meta fallback, card never null, MIN_TEXT 350
* net/Http.kt: softBlock param
* ui/OfflineSync.kt: UA retry, more URLs, watch/index charts early, host.indices()
* net/EarningsFeed.kt: Report/verdict/recent()/Nasdaq parse
* ui/IncomeHub.kt: reports, indices, last-date state, disk keys
* ui/EarningsScreen.kt: reports section, ReportRow
* net/Indices.kt + ui/IndicesScreen.kt: new Markets tab with charts
* ui/PortfolioTab.kt: Markets sub-tab
* SyncService.kt + AndroidManifest.xml: foreground service, permissions
* ui/PortfolioViewModel.kt: scopeKeptForSync, offlinePassEnded, service start

## 🚫 Failed Approaches (DO NOT RETRY)
* Approach: ship.sh local gate → Result: 82 Robolectric tests fail, android-all 14 jar download blocked → Lesson: temporarily sed @Config sdk 34→36 in tests, run full suite, git checkout tests; CI runs real gate
* Approach: curl Yahoo/Nasdaq to verify formats → Result: blocked by egress hook → Lesson: live data formats unverified; parse tolerantly

## 🔑 Crucial Context & Implicit Rules
* Build only via bash tools/gradle.sh; run tools/install-hooks.sh first; tools/ckpt.sh after each step
* Release: bump versionCode/Name, ckpt, trigger android.yml full_build on main, record-release.sh after green, post Release page link; never send APK
* Settings-key prefixes for derived caches go in IncomeHub.PREFIXES (excluded from backups)
* tools/checkinit.py: properties must be declared above VM init

## Key Decisions
- Decision: GitHub Actions builds/signs (android.yml full_build on main) — Reason: CLAUDE.md rule; keystore in GitHub Secrets; record-release.sh only after green
- Decision: Nasdaq earnings-surprise as report-date source fallback — Reason: Yahoo chart events=earn unverified, earningsHistory has quarter-end not report date
- Decision: US index charts reuse PriceChart+FullScreenChart with local window state — Reason: user asked to adopt existing chart code
- Decision: foreground service + defer fgScope cancel while offline pass runs — Reason: ON_STOP cancel caused 'Refresh interrupted'; frozen process loses network
- Decision: new Robolectric tests use sdk 36 — Reason: only android-all 36 cached in sandbox

## Summary
Shipped v8.5-v8.8, all recorded in BUILDLOG.md. v8.5: OfflinePages sanitize fallbacks (JSON-LD articleBody, meta description), card() never null, Http.get softBlock (403/503 no host cooldown), 2 UA retries in OfflineSync.savePages; Earnings 'Reported in the last month' (ReportEntry, EarningsFeed.Report/verdict, IncomeHub disk-backed). v8.6: Markets sub-tab (Indices.kt, IndicesScreen.kt, PF_MARKETS). v8.7: US index charts reuse PriceChart/FullScreenChart; Nasdaq earnings-surprise fallback; watch+index charts fetched right after holdings charts in OfflineSync. v8.8: SyncService dataSync foreground service + scopeKeptForSync keeps fgScope alive until offline pass ends.

---
*A short hint surfaces on session start; full context loads only if your next prompt matches a keyword above, or via manual `/handoff-resume`. The same record is available as a sibling `.json` file for external tools.*