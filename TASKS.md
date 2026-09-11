# TASKS — the current job

## Tj's request, 2026-09-10 (his own words, lightly trimmed) — request screener

> make a screener that permanently lives in this GitHub project which fields
> all requests. I will be using Claude sonnet 5 regularly. if Claude opus is
> needed for any task for accuracy or difficult tasks, flag me before any
> work is done at all so that I can change the model and start the task.
> this screener should screen every request I send before any work is done
> on the app.

## Design (before writing code)

A `UserPromptSubmit` hook fires on every message, not just at session
start, which is what makes "screen every request" mechanically true rather
than something a session has to remember to do — the same reasoning that
already governs the autosave/resume hooks in this repo. The hook itself
cannot judge "does this need Opus" (that isn't a grep); what it CAN
guarantee is that a short, fixed reminder reaches Claude before every
single request, pointing at the real protocol/criteria in `SCREENER.md`,
plus a cheap keyword hint. Wired through the same `tools/hooks/` multi-repo
coordinator as the other three hooks, so it survives sitting in a container
alongside Tj's other checkpoint-hook repo without clashing, and through
`tools/install-hooks.sh` so it installs automatically like the rest.

- [x] `SCREENER.md` — the protocol Claude runs on every message (read the
      request, check `get_session` if a criterion matches, stop and flag
      rather than proceed on Sonnet) and the actual escalation criteria
      (money-accuracy, irreversible actions, locked architecture, ambiguous
      design, a previously-failed fix, security, or Tj's own words).
- [x] `tools/screener.sh` — the per-repo hook script (mirrors
      `resume.sh`/`autosave.sh`/`toobig.sh`: `--text` mode, JSON mode,
      always exits 0, never blocks the prompt) plus the keyword hint.
- [x] `tools/hooks/screen.sh` + supporting `lib.sh`/`emit.py` changes for a
      `UserPromptSubmit` event, following the existing SessionStart/
      PostToolUse/PreCompact pattern exactly.
- [x] Wire `UserPromptSubmit` into `tools/session-root-hooks.json` (the
      multi-repo template) AND this repo's own `.claude/settings.json`
      (the direct, single-repo path), same as the other four events.
- [x] Hermetic tests (`tools/test_screener.sh`, 11 checks, auto-run by
      `tools/ckpt.sh`) proving the JSON/text output shapes, the keyword
      hint, the multi-repo aggregation, and that `install-hooks.sh`
      actually installs the entry. `test_resume.sh` (37 checks) and
      `checkinit.py` still green — no regressions.
- [x] `CLAUDE.md` gets a short section pointing at `SCREENER.md`, same
      pattern as the existing pointer to `BRIEF.md`.
- [x] Checkpoint after every completed step (ckpt 610, 611).
- [x] Installed live in this session (`tools/install-hooks.sh`) and
      smoke-tested end to end through the real installed command — confirmed
      working, not just unit-tested.
- [x] Applied it once built: the pending BUY/HOLD/SELL scoring-design step
      below matches money-accuracy + ambiguous-design + Tj's own "very
      important" wording. Confirmed via `get_session` this session is still
      on `claude-sonnet-5` — flagged to Tj in chat rather than resumed here.
      Design work on that step has NOT started.

## Previous request, 2026-09-10 (his own words, lightly trimmed)

> for each of the holdings in my portfolio, to the left of the news tab, make
> a tab about the same size as the news tab that says either buy, hold, or
> sell. this will give me a recommendation on each of my holdings. it is very
> important that the advice for three buy hold and sell for each stock is
> well grounded and good advice. so research the best signals and reasons of
> when to buy hold and sell based on each particular stock's current price,
> outlook, professional analysts, target prices, performance, and financial
> data. this must be tailored to be accurate for each individual holding. if
> I click on the buy hold or sell tab for any stock, a pop up should appear
> with the reasoning behind the recommendation and a target price for any
> transaction of the stock. the information and reasoning behind the
> recommendations should be refreshed daily and cached properly so that it
> doesn't refresh throughout each day. it only needs to refresh for each new
> day session.
>
> use as much time and usage as needed... if this is not possible do not make
> the feature. if you find that the feature is plausible and can give
> accurate and sound advice, build it... with a checkpoint system that saves
> all progress frequently... run tests and bug checks and make sure it is
> well optimized and works well without breaking any other part of the app
> and without using so much internet pulls from providers that the providers
> ban or limit my requests. also make sure that the entire app sleeps
> properly when it is backgrounded, and doesn't hog ram or battery or CPU
> when it is not in use.

## Feasibility finding (before writing code)

Plausible, and building on infrastructure that already exists rather than
inventing new risk. `net/Research.kt` already pulls real analyst
buy/hold/sell counts and an average price target per symbol from Nasdaq's
public API (`Research.consensus`, no key needed) and `ResearchScore.kt`
already scores stocks from price/valuation/trend data with visible,
testable reasoning ("app scores, Claude explains" — Round 66 decision). The
per-holding feature reuses that exact data source and scoring philosophy
instead of a new one, which is what makes "well grounded" achievable: every
number behind a recommendation is a real, sourced number (analyst
consensus, price vs. target, forward P/E, moving averages, 52-week range),
not a guess, and the reasoning list already prints in the user's language.

- [x] Design the BUY/HOLD/SELL scoring rule for an OWNED position
      (`ResearchScore.holding` + `ResearchScore.verdictFor`, net/ResearchScore.kt).
      Centered at 50 (HOLD is the honest default for a position already
      owned) rather than starting at 0 like `best()`, which has no HOLD
      state at all — that asymmetry is the whole reason this is a separate
      function. Weighted toward analyst consensus and its price target
      (±30 / ±12.5 of the move from 50) since that is the one input that is
      already three-way buy/hold/sell from professional coverage and is
      literally what Tj asked for by name; valuation (PEG/forward P/E),
      earnings or revenue growth, and a year of performance against the
      S&P 500 fill in the rest; a negative book value, heavy leverage or
      elevated short interest can only ever subtract. Degrades honestly on
      missing data exactly like `best()`/`trending()` do — an absent field
      scores nothing rather than being guessed at, and `confidence` reports
      how much of the picture was actually there.
- [x] Add a per-holding recommendation build — **better than the plan
      above turned out to be possible**: it costs ZERO new network
      requests, not "one Nasdaq call per holding." `Fundamentals.consensus`
      (data/Fundamentals.kt) already carries the same buy/hold/sell counts
      and target price, already fetched for every holding by
      `loadFundamentals` (unconditionally, on every detail-screen open, for
      the Overview/Stats tabs) — so `net/Recommend.kt` just reads it. No new
      provider, no new request volume at all, which is the strongest
      possible answer to "without using so much internet pulls that
      providers ban or limit my requests."
- [x] Cache per TRADING DAY, not device-local calendar date or a rolling
      TTL — `MarketClock.dayKey()` (America/New_York, so it can't drift on
      a device set to a different time zone), checked in
      `PortfolioViewModel.loadRecommendation` before anything recomputes.
      Persisted in the existing hand-rolled SQLite `fundamentals` table
      under a new `kind` (this project does not use Room — see
      `Db.cacheRecommendation`/`cachedRecommendation`), so it survives app
      restart. Deliberately has NO `force` parameter and is not called from
      pull-to-refresh or resume — Tj asked that this specifically "only
      needs to refresh for each new day session," nothing else, so the day
      check is the only thing that can ever trigger a recompute.
- [x] Add the tab: `DetailTab.RECOMMENDATION`, same size as every other tab
      (no per-tab sizing exists in this app — same `Tab{}` composable for
      all of them), immediately to the left of News in the enum order.
      Shows the live verdict word ("Buy"/"Hold"/"Sell") and a traffic-light
      color once computed; "..." as a placeholder before that, never a
      blank tab.
- [x] Tapping the tab opens `RecommendationDialog` (ui/RecommendationDialog.kt)
      with the full reasoning list, the average analyst target price (and
      its high/low range) or an honest "no target published" line rather
      than a fabricated number, and a confidence/coverage caveat when the
      picture is thin — instead of switching the screen's body, which is
      what every other tab does.
- [x] Confirmed on-demand only: `loadRecommendation` runs from a
      `LaunchedEffect` when fundamentals arrive, on the same `fgScope` that
      is already cancelled whenever the app backgrounds
      (`PortfolioViewModel.setForeground(false)`) — no timer, no polling
      loop, no new lifecycle code at all. It inherits the app's existing
      background-sleep behavior for free.
- [x] Unit tests: `RecommendationScoreTest.kt` (17 cases pinning every
      scoring term in isolation, the verdict dead-band boundaries, and the
      0–100 clamp under extreme input), 2 `MarketClock.dayKey` tests in
      `NetLogicTest.kt` (same-ET-day agreement; ET midnight vs. UTC
      midnight, proving the freeze can't drift by time zone), 6 DB
      round-trip tests in `DbTest.kt` (field fidelity, replace-not-duplicate,
      uppercasing, no collision with the other two `fundamentals` kinds,
      purge ages it out like every other kind), and 7 Robolectric UI-render
      tests in `DetailTabsUiTest.kt` for the popup and the live tab label —
      this project's own established substitute for "opened it on a phone"
      when no emulator is attached to the container, per that file's own
      header, and it did catch one real mistake (a bad import) before this
      was called done.
- [x] Full Gradle unit suite green, `tools/checkinit.py` clean: verified in
      a clean final run after every edit — 838 tests, 0 failures, 0 errors.
- [x] Checkpointed after every completed step (ckpt 609, 613, 614, 615),
      not just at the end.
- [x] Shipped: v7.9 (code 66), GitHub Actions run #8 built, signed, verified
      its own certificate, and published the Release; APK downloaded
      (sha256 verified against the Release's own digest), sent to Tj, and
      recorded in BUILDLOG.md.

## The flow (details in CLAUDE.md)

Tj describes what he wants -> Claude codes, tests and checkpoints -> `ship.sh`
gates and pushes -> Claude triggers the workflow through the GitHub API ->
GitHub compiles, signs, verifies the certificate and publishes the Release ->
Claude sends Tj the APK -> `tools/record-release.sh` writes BUILDLOG.
