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

- [ ] Design the BUY/HOLD/SELL scoring rule for an OWNED position (distinct
      question from Research's "is this worth buying fresh" — same inputs,
      different framing: a HOLD is a legitimate, common answer here, where
      Research's `best()` doesn't have a hold state).
- [ ] Add a per-holding recommendation build: one Nasdaq consensus call per
      OWNED symbol (small count — actual holdings, not a 450-name screener
      universe) plus the price/fundamentals data already flowing through the
      app for the position, so no new provider is added and no new
      screener-scale request volume is introduced.
- [ ] Cache per calendar day (device-local date), not a rolling TTL —
      persisted (Room) so it survives app restart and does not refetch until
      the next day, matching "only needs to refresh for each new day
      session." Pull-to-refresh (if the app has one) should still force it.
- [ ] Add the tab: same size as News, immediately to its left, in the
      holding detail screen's tab row.
- [ ] Tapping the tab opens a popup with the full reasoning list and the
      target price for a transaction (buy/sell), matching the app's
      existing dialog pattern.
- [ ] Confirm the fetch happens on-demand (viewing the tab / normal
      refresh), never on a background timer — app must sleep fully when
      backgrounded, no added RAM/battery/CPU cost while not in use.
- [ ] Unit tests for the new scoring function (mirrors `ResearchTest.kt`'s
      style — pure function, no network in tests).
- [ ] Full Gradle unit suite green, `tools/checkinit.py` clean, before
      shipping.
- [ ] Checkpoint after every completed step (`tools/ckpt.sh`), not just at
      the end — this is an explicit ask, not the usual habit.
- [ ] Ship following CLAUDE.md's normal release flow once done and tested.

## The flow (details in CLAUDE.md)

Tj describes what he wants -> Claude codes, tests and checkpoints -> `ship.sh`
gates and pushes -> Claude triggers the workflow through the GitHub API ->
GitHub compiles, signs, verifies the certificate and publishes the Release ->
Claude sends Tj the APK -> `tools/record-release.sh` writes BUILDLOG.
