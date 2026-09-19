# TASKS — the current job

## Tj's request, 2026-09-19 (his own words)

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
