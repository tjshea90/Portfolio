# TASKS — the current job

## Tj's request, 2026-09-10 (his own words, lightly trimmed)

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
