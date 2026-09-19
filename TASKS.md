# TASKS — the current job

## Tj's request, 2026-09-19 (his own words)

> Full test the latest version of this app

This is the "full tests" protocol from CLAUDE.md's "Testing on request"
section, run against the latest shipped version, **v7.27 (versionCode 84)**.
No budget or time limit; release-quality bar; whole-app deep audit, not
just the current diff. A previous session ran full tests on the v7.26→v7.27
diff; this is a fresh standalone pass over the whole app at v7.27.

### Full-test progress

- [x] Floor: `python3 tools/checkinit.py` + `bash tools/gradle.sh testDebugUnitTest`
      — green (1174 tests, 0 failures) in the first session; re-running now
      over the in-flight fixes below.
- [x] Own audit pass 1 (first session): day-boundary bug class in Explain.kt
      and Research.daysUntilEarnings — plain Long division truncates TOWARD
      ZERO, so a date 1-23h in the PAST divided to 0 and every `d >= 0`
      "is it still ahead?" test stayed true for a whole day after the date
      passed. Fixed with Math.floorDiv at all four sites; pinned by
      ExplainDayBoundaryTest (10 tests).
- [x] Own audit pass 2 (first session, interrupted mid-step — finished this
      session): three data-loss holes on the restore/backup path, all now
      pinned by the new RestoreSafetyTest:
      - `Db.restoreJson(replace = true)` deletes txns/overrides/watchlist/
        imports BEFORE reading the file, so a file carrying only a
        `watchlist` key wiped the whole ledger to restore a few symbols.
        Replace now requires the array it is about to replace.
      - The `counts` manifest cross-check ran AFTER
        `setTransactionSuccessful()`, so a truncated backup restored with
        "Replace all" wiped the ledger, committed whatever subset parsed,
        and reported success with a warning on the toast. The check moved
        ahead of the commit, so a short count on a Replace now rolls the
        whole transaction back. Merge keeps the advisory warning — it only
        ever adds.
      - `restoreAsync` forced an autobackup on any non-error result, so a
        short-read Merge immediately wrote the incomplete ledger over
        `portfolio-autosave.json` — the one copy that survives an
        uninstall. Now skipped when `r.warning != null`.
      - `Storage.saveToDownloads` used `"wt"` / `writeText`, which truncate
        BEFORE writing, so a failed write destroyed the old backup and
        returned a bare null. Both branches now keep the prior bytes and
        put them back if the write throws.
- [ ] Parallel subsystem audits (recommendation/scoring, day-trading,
      network/caching, UI/battery/persistence) — 4 agents running
- [ ] Reconcile findings and fix everything real
- [ ] Re-run the unit suite after fixes; re-check anything a fix touched
- [ ] Checkpoint as work completes
- [ ] Ship the result per CLAUDE.md's auto-ship policy and post the link

---

## Previous requests (history)

## Tj's follow-up, 2026-09-19 (his own words)

> The link gives me a 404. Fix this for all future ships

Investigated: `tjshea90/Portfolio` is a PRIVATE repo, confirmed via
`search_repositories` (`"visibility":"private"`). GitHub returns 404, not
403, to anyone viewing a private repo's Release page without access - so
the link itself was correct (verified: it exists, is published, has its
signed APK attached), but an unauthenticated fetch of the exact same URL
also 404s. This means Tj's browser wasn't logged into the `tjshea90`
GitHub account when he clicked it.

While investigating, found something more serious: **the signing keystore
(`app/sideload.jks`) is present in this repo's git HISTORY** - added in the
old Cowork-era commit `f00c950` (2026-09-07) and again in the `5304cd6`
"Import ... checkpoint 66" commit (2026-09-10), never scrubbed, just later
removed from the working tree. It is NOT in the current HEAD tree, but the
blob is fully fetchable from those commit SHAs today. This matters because
BRIEF.md's whole safety model for this file assumed the repo stayed
private - not that the key was actually absent from history.

Asked Tj how to proceed (AskUserQuestion, twice - once on repo visibility,
once specifically on the keystore-in-history risk after finding it). He
chose: **make the whole repo public, accepting the keystore exposure risk
knowingly.**

Then hit a hard capability wall: **there is no tool in this session that
can change a GitHub repo's visibility.** The GitHub MCP toolset here has no
`update_repository`/settings call, and there's no `gh` CLI or raw API
access in this environment (see the system prompt's GitHub Integration
section). This is not something `ship.sh`/`record-release.sh`/the
`android.yml` workflow can do either - it is a one-time manual step only
Tj can take, from github.com → Settings → General → Danger Zone → Change
repository visibility → Public.

### Status - blocked on Tj, not on more work here

- [x] Diagnosed the 404 (private repo, correct link, needs login OR public)
- [x] Found and disclosed the keystore-in-history exposure risk before
      acting on "make it public"
- [x] Got Tj's explicit, risk-informed decision (public, accepts the risk)
- [ ] **Tj**: flip visibility himself (I cannot do this from here) - OR
      ask a future session to purge the keystore blob from history first,
      which I offered and he declined for now
- [ ] Once the repo is confirmed public (a future session can check via
      `search_repositories`'s `visibility` field), the "log in to see the
      link" caveat can be dropped from how release links are announced

## Tj's earlier request, 2026-09-19 (his own words)

> Do what you need to do to ship it and make the new version APK, and for
> every future update, always push the apk and send me the link to the
> finished apk automatically, without me asking

Two parts:
1. Ship the full-tests fix session below as a real release (bump version,
   `ship.sh`, trigger the GitHub build, confirm green, record it).
2. A standing policy change for every future session: don't wait to be
   asked to ship - after a meaningful unit of work is done, ship it the
   same way and post the release link in chat automatically. This is
   written into CLAUDE.md's "Releasing" section so it survives into new
   sessions, the same way the light/full-test protocols were. It does NOT
   change the 2026-09-11 rule that Claude never sends the raw APK bytes -
   "the link" means the GitHub Release page/asset URL, which
   `get_release_by_tag` already provides; the technical block on
   downloading a private repo's release asset bytes is unrelated and
   still stands.

### Done

- [x] Bumped versionCode 83→84, versionName 7.26→7.27 in app/build.gradle.kts
- [x] `ship.sh` gated (checkinit, full unit suite, versionCode check) and pushed
- [x] Triggered `android.yml` on `main` with `full_build: true` (run 35419792350) - green
- [x] Confirmed Release v7.27 published with its signed APK asset
- [x] `record-release.sh` recorded it in BUILDLOG.md
- [x] Posted the release link in chat
- [x] Made auto-ship-and-notify the standing policy in CLAUDE.md (see above)

## Tj's earlier request, 2026-09-19 (his own words)

> Run full tests of the latest version of this app

This is the "full tests" protocol defined below in CLAUDE.md's "Testing on
request" section. Executing it now: no budget limit, full unit suite as the
floor, then a whole-app audit split across parallel subagents by subsystem
(recommendation/scoring, day-trading, network/caching, UI), fix everything
found, re-verify, checkpoint.

### Full-test progress — DONE

- [x] `checkinit.py` + full Gradle unit suite (floor) — green
- [x] Parallel subsystem audits (recommendation/scoring, day-trading,
      network/caching, UI/battery) — all 4 reported back
- [x] Reconciled findings, fixed everything found:
      - HIGH: `DayTradingTechnicals.sessionDay`/intraday fields were stamped
        from wall-clock time instead of the actual date of the fetched bars,
        so a weekend/holiday/pre-4am fetch that got back the last closed
        session's real numbers was labeled "today" and sailed through
        `effectiveTechnicals`'s non-zero direct pass-through unchallenged -
        `rangeUsed` read as a fully-spent day at market open on a stock
        that hadn't traded. Now gated on the bars' own date.
      - `Http.postJson` didn't disconnect the socket on cancellation
        (unlike `Http.get`), so a cancelled Claude API call paid for the
        whole body and held a per-host permit. Fixed to mirror `get()`.
      - Data-loss risk: every dialog holding typed-but-unsaved data
        (TxnEditorDialog, EditPositionDialog, the Settings backup/restore
        text) and every dialog-open flag used plain `remember`, which does
        not survive Android killing the process while backgrounded (only
        rotation). Promoted to `rememberSaveable` across TxnEditor.kt,
        RowActions.kt, DetailScreen.kt, ActivityScreen.kt,
        PortfolioScreen.kt, WatchlistScreen.kt, SettingsScreen.kt.
      - `EarningsTab` off-by-one: `Long` division truncation toward zero
        made a just-passed earnings date still read "In 0 days."
      - `ScreenRow.merge`'s `earningsEstimated` was ANDed across both
        sides regardless of which side's `earningsAt` actually survived.
      - An off-center analyst price-target term in `ResearchScore.withAnalyst`
        (-2 at zero upside instead of neutral) - offset corrected.
      - Three different "where issuers close funds" dollar figures across
        Research.kt/EtfScore.kt reconciled in wording (each threshold's
        actual use - admission floor/score ramp/UI warning - was already
        deliberately different; only the contradictory phrasing was fixed).
      - A stale `Position` KDoc claiming average-cost-only.
      - Tightened a comment in `DayTradingEval.Costs` that implied a
        buy-stop/buy-limit distinction the code doesn't make.
- [x] Re-ran the full unit suite after every batch — green throughout
- [x] Checkpointed as work completed (ckpt 1552-1554)

## Tj's earlier request, 2026-09-19 (his own words)

> From now on, I will be asking for "light tests" and "full tests" after
> Claude does work on this project. Make permanent knowledge for Claude so
> that when I tell it to run light tests (at any time I ask) or full
> tests, Claude knows exactly what to do with no further explanation from
> me. This must be permanently in Claude awareness so that if I ask, even
> in a brand new code session with no context, Claude knows what to do.
>
> If I ask for light tests (or any similar wording like light test or
> light testing): run low usage, light test on the last version of the
> app or the latest in progress work on the app after all work is
> complete and look for obvious bugs or ui issues and look for any ways
> other parts of the app may have broken or been corrupted by anything
> that was changed in the current session. Then fix any findings. If
> there were any major findings, fix them and when the fixes are
> complete, run another light test to ensure the fixes worked without
> breaking any other part of the app.
>
> If I ask for full tests (or anything similar like full test or full
> testing or comprehensive tests): do a full, comprehensive test suite of
> the entire app. Usage and amount of time spent on the testing is no
> concern, prioritize best effort at app testing and improvement. During
> a full test, look for any improvements in code or ui, improvements in
> network efficiency if Internet is needed, improvements in caching and
> data retention so important data is not lost from the app, improvements
> in logic for systems and engines within the app and make sure they work
> as designed, and search for and fix any bugs or parts of the app that
> have broken or been corrupted from changes. Search for waste of
> resources or battery usage and ensure that the app properly sleeps when
> it is not in use. The goal of this testing is to ensure the final
> release of the app is efficient and well coded and the features and UI
> work well and are intuitive with little to no bugs or data loss.

## Screening

Housekeeping/policy request — writing a permanent protocol into CLAUDE.md,
no app-logic change. No escalation.

## Done

- [x] Added a "Testing on request" section to CLAUDE.md (auto-loaded as
      project instructions every session, independent of hooks or
      `bootstrap.sh` — the most reliable place, so this works even in a
      session that never runs `tools/resume.sh`) spelling out the exact
      "light tests" and "full tests" protocols, worded so no further
      explanation from Tj is ever needed:
      - **Light tests**: run `checkinit.py` + the full unit suite, review
        the session's diff for bugs/UI-logic issues (noting this
        container has no emulator/device, so "UI issues" means reading
        the changed Compose code, not a live visual check), grep for
        other callers of anything changed to catch ripple effects, fix
        findings, and re-run the same light pass once if anything major
        was fixed.
      - **Full tests**: no budget/time limit, full unit suite as the
        floor, then a whole-app audit (bugs/breakage, code+UI quality,
        network efficiency and BRIEF.md source-order compliance, caching
        and data-retention gaps, scoring/engine logic vs. BRIEF.md's
        locked decisions, battery/background-lifecycle correctness via
        the `fgScope` pattern), suggesting parallel subagents by
        subsystem the way past sessions already did (see
        `audits/round66/`, the "4-way parallel audit" checkpoints), fix
        everything found, then re-verify.

## Do this next

Nothing pending — await Tj's next request.
