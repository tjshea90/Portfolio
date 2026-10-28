---
date: 2026-10-28T18:39:03.335Z
project: Portfolio
session: d11f8c12
schema_version: 1
next_steps_count: 3
has_blockers: false
keywords: offline articles, google news, portfolio, v8.4
headline: Portfolio Android app (tjshea90/Portfolio, Kotlin+Compose, sideloaded on Android 10 Go MT8168 tablet, rarely online). Go
---

# Session Handoff Snapshot
> **Generated:** 10/28/2026, 6:39:03 PM

## 🎯 High-Level Objective
* **Goal:** Portfolio Android app (tjshea90/Portfolio, Kotlin+Compose, sideloaded on Android 10 Go MT8168 tablet, rarely online). Goal: saved offline articles must open when tapped offline. Intent: Tj reads app offline; comprehensive offline viewing is core use.

## 📌 Current State & Next Steps
* **Status:** DONE: W1-W3 in TASKS.md ticked; v8.4 released. UNVERIFIED: resolver never run against live Google (no device; curl egress blocked by hook), so real-world save rate unknown.
* **Next Action:** Nothing pending; CHECKPOINT.md/TASKS.md current (ckpt 1754)

### Remaining Queue
- [ ] Await Tj feedback on offline article opening on tablet
- [ ] If many show 'Summary only', rework resolveGoogle in ui/OfflineSync.kt and GoogleNewsLink exchange

## 🛠️ Modified Files Delta
* app/src/main/java/com/tj/portfolio/net/GoogleNewsLink.kt: new, legacy id decode + batchexecute exchange
* net/OfflinePages.kt: needsResolving, card fallback
* ui/OfflineSync.kt: NewsItem-based savePages, resolveGoogle, onFinished callback
* data/Db.kt: recentStories, savedPageUrls
* ui/PortfolioViewModel.kt: savedUrls flow, reloadSavedUrls
* ui/DetailScreen.kt + ui/FeedScreen.kt: saved markers
* app/src/test/.../SavedArticlesTest.kt: new tests
* app/build.gradle.kts: 8.4/103; BRIEF.md row

## 🚫 Failed Approaches (DO NOT RETRY)
* Approach: live curl test of Google News → Result: blocked by pre-bash-safety hook (NETWORK_EGRESS) → Lesson: do not bypass; resolver verified by unit tests only
* Approach: init block in PortfolioViewModel to reload saved URLs → Result: checkinit.py failed → Lesson: use callback/LaunchedEffect, not init

## 🔑 Crucial Context & Implicit Rules
* Follow CLAUDE.md: run tools/install-hooks.sh first, tools/ckpt.sh after each step, ship via ship.sh then actions_run_trigger android.yml then record-release.sh
* GitHub builds APKs; never send APK bytes; post Release page link
* Build only via bash tools/gradle.sh

## Key Decisions
- Decision: resolve Google News links to publisher URL before fetch — Reason: they are JS redirects, plain GET gets no article
- Decision: file saved page under list's original URL — Reason: reader looks up target.url, avoids key mismatch
- Decision: fall back to headline+summary card when full page unsavable — Reason: every linked story must open something offline
- Decision: onFinished callback on OfflineSync instead of VM init block — Reason: tools/checkinit.py forbids properties declared below init

## Summary
v8.3 offline refresh shipped earlier but articles never saved: Google News RSS links (most headlines) were skipped by OfflinePages.worthFetching. v8.4 (code 103) shipped+recorded: resolver, summary-card fallback, saved markers. Full unit suite green, GitHub build green, Release v8.4 published, BUILDLOG recorded, ckpt 1754 pushed.

---
*A short hint surfaces on session start; full context loads only if your next prompt matches a keyword above, or via manual `/handoff-resume`. The same record is available as a sibling `.json` file for external tools.*