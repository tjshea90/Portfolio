# Portfolio — standing rules and build traps

Native Android app (Kotlin + Jetpack Compose) — Tj's personal stock/ETF
portfolio tracker, sideloaded on a Moto G 2026 running Android 16 (API 36).
Package `com.tj.portfolio`. Ships as a signed release APK, not to the Play
Store.

This file is the ported, condensed version of the standing rules the project
accumulated over 66 rounds of development in a Cowork container (round-based
checkpoint tarballs, `RESUME.md`/`state.json`/`CHECKPOINT.md`) before moving
to this GitHub repo and the Claude-Code hook-based checkpoint system in
`tools/`. Full round-by-round history is in `git log` and `audits/`; nothing
below is guesswork, all of it cost real time before.

## The rule that must never be broken: the keystore is irreplaceable

`app/sideload.jks` — all passwords `portfolio`, alias `portfolio`, DN
`CN=Portfolio, OU=Personal, O=TJ`. Cert SHA-256
`2e8c3847 2d1657b7 d2562266 c6d7e1d8 e6f03d76 66bd10e1 cb505b1c f396a9f2`,
identical in every build shipped so far.

Android only performs a **data-preserving in-place update** when the package
name AND the signing certificate both match. A new keystore forces an
uninstall first, which **erases the portfolio** (transactions, overrides,
caches — everything on the phone). So:

- Never `keytool -genkey` a fresh key. Always sign with the same
  `app/sideload.jks`.
- Never change `applicationId`.
- Always bump `versionCode` in `app/build.gradle.kts` before shipping —
  Android refuses to install a build whose versionCode is not higher than
  what's already on the phone.

**This file is deliberately NOT in git** (see `.gitignore`) — committing it
was blocked even in this private repo, so Tj holds the only copy out of
band. This means `git clone` alone does not reproduce a buildable checkout:
`app/sideload.jks` has to be placed at that exact path before
`./gradlew assembleRelease` or `ship.sh` will work. If a session finds it
missing, say so plainly rather than generating a replacement — a fresh
keystore is exactly the "erases the portfolio" failure above.

## The build guard: `tools/checkinit.py`

Kotlin runs property initialisers and `init {}` in source order, so a
property declared textually *after* `init` does not exist yet while `init`
runs — a `val` holding an object reads as null. This has shipped twice:
v4.5 silently wiped the ledger cache (self-healed on refresh, survived
testing); v4.6 crashed on every launch with no way into the app.

`tools/checkinit.py` fails the build if `PortfolioViewModel` declares ANY
property below its `init` block — no analysis, an absolute rule, wired into
Gradle's `preBuild` (`app/build.gradle.kts`, task `checkInitOrder`) so it
can't be forgotten. `tools/ckpt.sh` also runs it on every checkpoint (it's
pure Python regex, well under a second). Never weaken or bypass this check.

## Toolchain — verified building, do not "upgrade" casually

```
JDK 21 (preinstalled)        Gradle wrapper 8.14.3
AGP 8.13.2                   Kotlin 2.3.10
compileSdk/target 36 (Android 16)    minSdk 26
Compose BOM 2025.09.01       activity-compose 1.10.1
lifecycle 2.9.4               core-ktx 1.17.0
coroutines 1.10.2
```

Newer androidx releases (Compose BOM 2026.06.01+, lifecycle 2.11.0+,
activity 1.12.4+, core-ktx 1.19.0+) require compileSdk 37 and AGP 9.1+, and
AGP 9 changes the Kotlin plugin wiring. Building against 36 for an Android
16 device is correct — the libraries are pinned to the last releases that
compile against 36. **If a build suddenly demands "compileSdk 37" or "AGP
9.1.0 or higher", something bumped a version — put it back**, don't chase
the new requirement.

## Build environment traps (each cost real time before)

1. **Never unset or blank `JAVA_TOOL_OPTIONS`.** The container's HTTPS proxy
   and truststore live in it. Clearing it makes Gradle fail with "Plugin
   [id: 'com.android.application'] was not found" — that reads like a
   version error but is a *network* error.
2. **A full Gradle build can exceed a 2-minute foreground command timeout.**
   Background it: `setsid nohup ./gradlew ... > build.log 2>&1 < /dev/null &
   disown`, then poll `tail build.log`. A timed-out foreground run still
   keeps its partial work, so a retry resumes rather than restarting.
3. **Never run two Gradle builds at once, and never kill one mid-flight.**
   Two concurrent builds corrupt the Kotlin incremental caches
   (`app/build/kotlin/...`, `~/.gradle/kotlin`); killing one leaves them in
   a state where the next compile reports **nonsense** — e.g. "Unresolved
   reference" for a symbol plainly in the same file — alongside a "Detected
   multiple Kotlin daemon sessions" warning. It is not a code error. Fix:
   ```bash
   pkill -9 -f KotlinCompileDaemon
   rm -rf app/build/kotlin ~/.gradle/kotlin ~/.kotlin
   ```
4. **`assembleRelease` needs `build-tools;35.0.0` as well as 36.0.0** on a
   cold container — the failure reads "Failed to install the following SDK
   components: build-tools;35.0.0". `tools/setup-android-sdk.sh` installs
   both.
5. **Maven Central can return HTTP 429 on a cold container.** It shows up as
   "Could not resolve `<any dependency>`" or a `lintVitalAnalyzeRelease`
   failure — that's rate limiting, not a real dependency problem; don't
   "fix" a dependency version because of it. Gradle keeps whatever it
   already downloaded, so a plain retry makes progress every time:
   ```bash
   for i in 1 2 3 4 5 6; do ./gradlew :app:assembleRelease && break; sleep 30; done
   ```

## Locked architecture decisions (do not revisit without a real reason)

| Area | Choice | Why |
|---|---|---|
| Broker sync | Not possible (Ally's developer API is retired) | Screenshot import is the path instead |
| Screenshot import | Claude vision via the user's own API key | Chosen deliberately |
| Market data | Yahoo chart v8 (no key) primary; Finnhub optional key; Stooq last resort | |
| Quotes | Yahoo v7 **batched** (one request for every symbol) → per-symbol chart → Finnhub → Stooq | Per-symbol chart polling was ~70% of all app traffic |
| Sparklines | Yahoo chart, own 5-minute clock, visible symbols only | A 64dp line doesn't change in five minutes |
| Response caching | Validators AND bodies in SQLite (`http_cache`), not the heap | Nothing already stored should be downloaded again |
| News | Yahoo Finance RSS → Google News RSS → Finnhub | All free, no key needed |
| Market-wide news | Finance desks only, two-tier relevance filter | General front pages leak non-market content in |
| Insider data | Parse the Form 4 DOCUMENT, not EDGAR's listing title | The listing title is boilerplate, identical on every filing |
| Accounting | Full ledger from transactions + manual override | |
| Cost basis | FIFO default, average cost optional | FIFO reproduced the broker's basis to the cent; average cost drifts on any re-bought symbol |
| Persistence | Raw `SQLiteOpenHelper`, no Room/KSP | Removes annotation-processor version risk |
| JSON | `org.json` (Android framework) | No kotlinx-serialization plugin needed |
| HTTP | `HttpURLConnection` | No OkHttp/Retrofit dependency |
| Navigation | Hand-rolled state stack in `MainActivity` | No navigation-compose dependency |
| Background fetches | `fgScope` — supervisor scope cancelled on `ON_STOP`, rebuilt on `ON_START`; DB writes stay on `viewModelScope` | 42 launch sites were found, only 3 stopped on background |
| Cancelling a request | Disconnect the socket, not just drop the queued read | Otherwise a cancelled fetch still pays for the whole body |
| Chart cache | `chart_cache` (db v7), one row per (symbol, range), disk read before any network request | Switching apps must never blank a chart |
| Chart refresh rate | The range's own candle interval, never faster | Asking more often than the provider updates returns nothing new |
| Memory trims | Nothing released below `TRIM_MODERATE` (60) | `TRIM_MEMORY_UI_HIDDEN` fires on every app switch and was blanking charts |
| Fund holdings | Yahoo `topHoldings` — top holdings only, and the screen says so | No keyless feed publishes a full fund register |

## Manual verification tools (not part of the automated gate)

`tools/insider_sim.py` and `tools/research_sim.py` are plausibility harnesses
that hit **live external services** (SEC EDGAR, Yahoo screeners) — they
can't prove correctness, only that the output still reads as sane against
today's real market. `tests/ledger_props.py` (backed by `tools/ledger_port.py`)
runs thousands of randomised transaction histories through a pure-Python
port of the ledger. None of these are wired into `tools/ckpt.sh` or CI — run
them by hand when touching the code they mirror:

```bash
python3 tools/research_sim.py
python3 tools/insider_sim.py NVDA AAPL INTC F MSFT
python3 tests/ledger_props.py 5000
```

`tests/BridgeTest.java`, `tests/DayPnlTest.java`, `tests/LedgerPropTest.java`,
`tests/ShippedTest.java` are a separate JVM suite that runs against the
**compiled release classes** (`./gradlew :app:assembleRelease` first, then
compile/run with `javac`/`java` against `app/build/tmp/kotlin-classes/release`)
so they can't drift from what ships — full commands in `tests/README.md`.

## Full unit suite

`./gradlew testDebugUnitTest` — 797 tests as of v7.7. This is what `ship.sh`
gates on; `tools/ckpt.sh` does not run it (a full Gradle test pass is
minutes, not the "well under a second" a per-edit checkpoint needs).
