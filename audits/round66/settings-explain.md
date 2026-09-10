# Round 66 — SettingsScreen.kt + Explain.kt audit (read-only)

Scope: `ui/SettingsScreen.kt` (986 lines) and `ui/Explain.kt` (2066 lines), checked
against `PortfolioViewModel.kt`, `data/Db.kt`, `data/Fundamentals.kt`, `net/*.kt`.
Every claim below quotes the prose and the code that contradicts it.
Ranked most severe first.

---

## ID: EXP-1
**SEVERITY:** MEDIUM
**WHERE:** `app/src/main/java/com/tj/portfolio/ui/SettingsScreen.kt:339`
**BUG:** The "Requests in the last hour" card tells the user to pull down to update the
figures, but pull-to-refresh cannot update them — the value is cached in a `remember` whose
keys pull-to-refresh never changes, so the numbers are frozen at whatever they were when the
Settings tab was opened.

**FAILURE:**
The prose (`SettingsScreen.kt:338-341`):
> "Counted since the app started, per provider, over a rolling hour. Pull down on this screen to update the figures."

The value it describes (`SettingsScreen.kt:239-241`):
```kotlin
val rate = remember(infoTick, refresh) {
    com.tj.portfolio.net.Http.requestsLastHour()
}
```
Its only keys are `infoTick` and `refresh`. Pull-to-refresh on this screen runs
`Refreshable(refreshing = ui.pulling(PULL_PRICES), onRefresh = { vm.refresh(manual = true) })`
(`SettingsScreen.kt:102`), and `vm.refresh(manual = true)`
(`ui/PortfolioViewModel.kt:1859`) writes `_ui` state only — it never touches `infoTick` (bumped
only by the three "Clear ..." buttons, the fee-fix button and the backup buttons) and never
touches the `refresh` text field. The `_ui` write recomposes `SettingsScreen`, but a
`remember` with unchanged keys returns its cached list, so the host counts shown are exactly
the ones captured on entry. The one thing this card exists to answer — "how hard are we
actually hitting the providers right now?" (its own comment,
`SettingsScreen.kt:234-237`) — cannot be re-measured the way the screen says to re-measure it.
The user's only real way to update it is to leave the tab and come back, or to type a
character into the auto-refresh field.

**FIX:** Bump the tick on the pull: give `Refreshable` `onRefresh = { vm.refresh(manual = true); infoTick++ }`
— `Http.requestsLastHour()` is an in-memory 60-bucket scan (`net/Http.kt:117-124`) with no
disk work, so it is safe on that path (unlike the snapshot/crash-log remembers, which is why
the comment at line 199 warns off `infoTick++` in the text field). Otherwise drop the last
sentence.

**CONFIDENCE:** certain

## ID: EXP-2
**SEVERITY:** MEDIUM
**WHERE:** `app/src/main/java/com/tj/portfolio/ui/SettingsScreen.kt:208` (text) /
`ui/PortfolioViewModel.kt:2512-2521` (`refreshStatus`)
**BUG:** The live status line claims the app is "updating every Ns" at the exact moment it is
guaranteed not to be — the Settings tab is `VisibleScope.None`, where the polling loop skips
the quote pass entirely — and the paragraph six lines below it says so, so the screen
contradicts itself.

**FAILURE:**
The status line (`SettingsScreen.kt:205-214`), whose own comment says
> "What the app is ACTUALLY doing right now."

renders `vm.refreshStatus()` (`ui/PortfolioViewModel.kt:2512`):
```kotlin
val secs = currentQuoteIntervalSecs()
val every = if (secs >= 60) "${secs / 60} min" else "${secs}s"
val phase = MarketClock.label()
return if (secs == user) "$phase - updating every $every."
```
It consults only the clock and the user's interval. It never consults `visibleScope` or
`pricesAreFinal()` — the two things that actually decide whether a pass runs
(`ui/PortfolioViewModel.kt:2465`):
```kotlin
if (visibleScope != VisibleScope.None && !pricesAreFinal() && haveNetwork) {
    refresh()
}
```
and Settings is `None` by construction (`VisibleScope.choose`, `ui/PortfolioViewModel.kt:288-292`:
"Feed shows headlines, not prices; Activity, Advice and Settings show none." → `else -> None`;
`symbolsFor(None) = emptyList()`). So during market hours a reader on the Settings tab is told
"Open - updating every 15s." while zero quote passes are being made, and the next paragraph
(`SettingsScreen.kt:216-219`) tells him the opposite and is the correct one:
> "the Activity, Advice and Settings tabs show no prices, so the app stops asking for them entirely while you are on those."

The same gap swallows `pricesAreFinal()`: at 3am on a Sunday with post-close quotes already
held, automatic polling stops completely, but the line still reads "Closed - updating every
15 min instead of 15s...". That is precisely the "why are prices not ticking at 3am"
confusion the comment says the line exists to prevent.

**FIX:** Have `refreshStatus()` report the paused state it is in, e.g. before the interval
sentence: `if (visibleScope == VisibleScope.None) return "$phase - paused while you are on this tab; prices resume the moment a price is on screen."`
and `if (pricesAreFinal()) return "$phase - prices are final for the session, so nothing is being requested."`

**CONFIDENCE:** certain

## ID: EXP-3
**SEVERITY:** MEDIUM
**WHERE:** `app/src/main/java/com/tj/portfolio/ui/Explain.kt:1466-1470` and `1495-1498`
**BUG:** The 52-week high and low "i" sheets compute the distance from the live price without
clamping, so a stock that has just made a new high (or a new low) is described with a NEGATIVE
percentage inside a sentence whose wording only makes sense for a positive one — the app's own
ETF and Research models clamp the identical expression, this one does not.

**FAILURE:**
`fiftyTwoWeekHigh` (`Explain.kt:1462-1477`):
```kotlin
val off = (c.v - p) / c.v
when {
    off < 0.03 -> "At ${Fmt.price(p)} the stock is within ${pf(off)} of its " +
        "52-week high of ${Fmt.price(c.v)} - trading at the top of its yearly range."
```
`p` is `row?.price` — the live quote, re-fetched every few seconds
(`ui/DetailScreen.kt:176`, `:814` → `Explain.of(key, f, price)`). `c.v` is
`fiftyTwoWeekHigh` out of the fundamentals cache, whose TTL is six hours
(`net/FundamentalsFeed.kt:44`, `CORE_TTL_MS = 6 * 3_600_000L`) and which Yahoo itself
publishes off the previous close (`summaryDetail.fiftyTwoWeekHigh`,
`net/FundamentalsFeed.kt:337`). So on any intraday breakout `p > c.v`, `off` is negative, and
the sheet reads:

> "At $210.00 the stock is within **-4.2%** of its 52-week high of $201.50 - trading at the top of its yearly range."

`fiftyTwoWeekLow` fails the same way on a new low (`up = (p - c.v) / c.v` at `Explain.kt:1495`):
> "At $18.10 the stock is only **-3.1%** above its 52-week low of $18.68 - near the bottom of its yearly range."

That the rest of the app guards this exact expression is the proof it is an oversight rather
than a choice: `data/EtfModels.kt:79-80` writes
`((fiftyTwoWeekHigh - price) / fiftyTwoWeekHigh * 100.0).coerceAtLeast(0.0)` and
`data/ResearchModels.kt:55-56` writes `.coerceIn(0.0, 1.0)` for the range position.

**FIX:** Handle the crossing explicitly rather than clamping it away — it is the most
interesting state the number has:
```kotlin
val off = (c.v - p) / c.v
when {
    off <= 0.0 -> "At ${Fmt.price(p)} the stock is trading ABOVE its last recorded " +
        "52-week high of ${Fmt.price(c.v)} - a new high." to Verdict.NEUTRAL
    off < 0.03 -> ...
```
and symmetrically `up <= 0.0 -> "...trading BELOW its last recorded 52-week low ... - a new low."`

**CONFIDENCE:** certain
