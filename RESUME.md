# RESUME — READ THIS FIRST  (round 59, saved 2026-09-08 01:05:42 UTC)

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

> Check the shipped v6.9: thorough sweep for UI and code optimizations, verify the app truly sleeps in the background when not in use, and find and fix bugs.

## 3. WHERE THE WORK STOPPED

- **In flight:** (nothing in flight)
- **Next action:** Then T1, the background audit

Uncommitted edits, if any, are shown by `git status`; every checkpoint is a
commit, so `git log --oneline` is the history of this round and
`git show HEAD` is exactly what the last save changed.

## 4. Task ledger — 3/8 done

- [x] T0  Baseline on the shipped tree: release build + 358 tests green before any edit  — release APK + 358/358 green on the shipped tree
- [x] T1  BACKGROUND AUDIT: trace every coroutine, timer, listener and lifecycle path in v6.9 from scratch  — manifest clean (no services/wakelocks/receivers); listeners balanced; 13 fgScope vs 45 viewModelScope sites all classified; 5 findings
- [x] T2  UI SWEEP: every screen rendered and measured - overflow, tap targets, font scale 1.0/1.3/2.0, dark mode  — static UI pass: all maxLines have overflow policies; two fixed-width text clips found (G06, G07)
- [ ] T3  CODE + EFFICIENCY SWEEP: main-thread work, recomposition, allocation, DB queries, request rate
- [ ] T4  BUG HUNT: correctness across the whole app, adversarial not confirmatory
- [ ] T5  Fix every finding without introducing new ones
- [ ] T6  Verify: full suite, checkinit, lint, second-pass review of every fix
- [ ] T7  Ship v7.0 (versionCode 57) + checkpoint delivered

**Resume at T3** (CODE + EFFICIENCY SWEEP: main-thread work, recomposition, allocation, DB queries, request rate).

## 5. Open findings — 7 still open, 0 fixed

- [ ] G01 (high) refresh() launches its network pass into viewModelScope, which OUTLIVES backgrounding. autoJob.cancel() stops the LOOP but not a refresh already in flight, so the batched quote request keeps transferring after the user leaves and its socket is never disconnected - the exact class of work Round 57 moved to fgScope. It also strands loading=true for up to 15s, during which the resume refresh returns early at its own guard and the user comes back to stale prices.
- [ ] G02 (med) ACCESS_NETWORK_STATE is declared in the manifest but nothing in the app ever reads connectivity. Two costs: an install-time permission that buys nothing, and - more importantly - the app fires a full pass of requests while the phone has no network at all, waking the radio, failing every socket and escalating per-host cooldowns, when one cheap check could skip the pass entirely.
- [ ] G03 (high) chartFetchedAt is written and NEVER read. A chart that cannot be fetched - a delisted ticker, a 404, a range Yahoo refuses - leaves no entry in _charts, so the guard falls through and the detail screen re-requests it on EVERY quote tick: ~240 requests an hour to Yahoo for a chart that will never arrive. loadFundamentals uses coreFetchedAt exactly this way; the chart path was modelled on it and then guarded on the wrong thing.
- [ ] G04 (med) Same shape in loadHoldings: holdingsFetchedAt is written and never read, so a fund whose holdings fetch fails is re-requested on every screen open and every ON_START with no throttle at all.
- [ ] G05 (high) REGRESSION FROM MY OWN ROUND-58 FIX (F03). refreshSparklines now removes the sparkAt mark for every symbol that failed, so a TRANSIENT failure retries on the next tick - which is what fixed TJ's missing charts - but a PERMANENT one (a delisted watchlist ticker, a symbol Yahoo has no series for) is now retried every 15 seconds forever instead of every 5 minutes. Two dead symbols is ~480 wasted requests an hour.
- [ ] G06 (med) AdviceScreen numbers each suggested action in a FIXED 22dp-wide Text. Compose's default overflow is Clip, so from ten actions up - or at a large font scale with fewer - the number is silently cut off. This is the v1.6 clipping trap the project has a standing rule about, recurring in a new place.
- [ ] G07 (med) FeedScreen draws the WSB trending rank in a FIXED 34dp-wide Text. '#50' at a 2.0 font scale is wider than that and is silently clipped. Same trap as G06.

## 6. Version

- Shipped: v6.9 (versionCode 56)
- This round ships: v7.0 (versionCode 57)
- Bump `app/build.gradle.kts` before the final APK. Android refuses an install
  whose versionCode is not higher than what is on the phone.

## 7. Recent log

- 2026-09-08 01:01:55 UTC  finding G01: refresh() launches its network pass into viewModelScope, which OUTLIVES backgrou
- 2026-09-08 01:02:17 UTC  T0 -> done  release APK + 358/358 green on the shipped tree
- 2026-09-08 01:02:18 UTC  T1 -> doing  background audit
- 2026-09-08 01:02:57 UTC  finding G02: ACCESS_NETWORK_STATE is declared in the manifest but nothing in the app ever rea
- 2026-09-08 01:04:25 UTC  finding G03: chartFetchedAt is written and NEVER read. A chart that cannot be fetched - a del
- 2026-09-08 01:04:25 UTC  finding G04: Same shape in loadHoldings: holdingsFetchedAt is written and never read, so a fu
- 2026-09-08 01:04:25 UTC  finding G05: REGRESSION FROM MY OWN ROUND-58 FIX (F03). refreshSparklines now removes the spa
- 2026-09-08 01:04:48 UTC  T1 -> done  manifest clean (no services/wakelocks/receivers); listeners balanced; 13 fgScope vs 45 viewModelScope sites all classified; 5 findings
- 2026-09-08 01:04:48 UTC  T2 -> doing  UI sweep
- 2026-09-08 01:05:23 UTC  finding G06: AdviceScreen numbers each suggested action in a FIXED 22dp-wide Text. Compose's 
- 2026-09-08 01:05:23 UTC  finding G07: FeedScreen draws the WSB trending rank in a FIXED 34dp-wide Text. '#50' at a 2.0
- 2026-09-08 01:05:42 UTC  T2 -> done  static UI pass: all maxLines have overflow policies; two fixed-width text clips found (G06, G07)

