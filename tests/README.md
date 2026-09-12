# Test harnesses (Rounds 46-47)

These run against the **compiled release classes**, not a port of them, so they cannot drift
away from what ships. `Fmt`, `Txn`, `Ledger`, `Position`, `Fees`, `Claude`, `ClaudeBridge`
and `News` all load outside Android.

```bash
cd <repo root>                                        # wherever this checkout lives
export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
./gradlew :app:assembleRelease                       # produces the classes below

CLS=app/build/tmp/kotlin-classes/release
STDLIB=$(find /root/.gradle/caches -name "kotlin-stdlib-2.3.10.jar" | head -1)
# BridgeTest additionally needs an org.json implementation (Android ships one; the JVM does not)
curl -sLo /tmp/json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar

cd tests
javac -cp "../$CLS:$STDLIB"            ShippedTest.java LedgerPropTest.java DayPnlTest.java
javac -cp "../$CLS:$STDLIB:/tmp/json.jar" BridgeTest.java
java  -cp ".:../$CLS:$STDLIB"            ShippedTest
java  -cp ".:../$CLS:$STDLIB"            LedgerPropTest
java  -cp ".:../$CLS:$STDLIB"            DayPnlTest
java  -cp ".:../$CLS:$STDLIB:/tmp/json.jar" BridgeTest
```

| file | what it asserts |
|---|---|
| `ShippedTest.java` | `Fmt.parseRss` on every format the live feeds emit; `Txn.unitPriceFromTotal` fee handling; the fee-heavy-sale `unitPrice` case; `Claude.preferredModel` ordering |
| `LedgerPropTest.java` | 20,000 randomised histories: both methods agree on lifetime P/L, share counts AND `sharesToday`, no position claims more bought-today than it holds, no negative shares or basis, no NaN in `totals()`, full cash reconciliation, and the day-figure/`boughtTodayCount` invariant the summary card depends on. Plus the Ally fee schedule |
| `BridgeTest.java` | the offline bridge: quantity-less trades refused and reported, the fee not double-charged, the v5.2 template defences intact, and `News.stripHtml` entity decoding |
| `DayPnlTest.java` | 200,000 randomised portfolios over the same-session day-P/L split |
| `ledger_props.py` | the Python port's own property run, kept from Round 44 |

**Every one of these was written because a defect got past a careful read-through.** Extend
them rather than doing a sixth read of the same 34 files.


---

## Round 47: the JVM test suite inside the project

Robolectric runs the real Android framework on the JVM, which reaches everything the plain
suites above cannot - SQLite, MediaStore-free file IO, and the Compose UI itself.

```bash
cd <repo root>
export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
./gradlew :app:test          # all 74, or --tests "com.tj.portfolio.DbTest"
#   :app:test and :app:testDebugUnitTest are the same thing - the release unit-test
#   variant is disabled on purpose, see the note in app/build.gradle.kts
```

Results land in `app/build/test-results/testDebugUnitTest/*.xml` and
`app/build/reports/tests/`.

| suite | what it asserts |
|---|---|
| `DbTest` | the real Db against real SQLite: a v1 database upgraded to v3, a downgrade, export/restore round trip, secret filtering, NaN rejection, merge vs replace, fuzzy duplicate detection, per-symbol delete |
| `StorageCrashLogTest` | the rolling 14-snapshot window, the crash log's 60KB cap keeping the newest end, handler double-install, and `Fmt`'s money/date/relative rules |
| `NetLogicTest` | the transport back-off state machine (including that one burst does not escalate, and a user refresh clears it), every MarketClock phase boundary, headline de-duplication, WSB momentum |
| `UiTest` | the holding row RENDERED and MEASURED: nothing clips at fontScale 1.0/1.3/2.0 or on a 320dp screen, tap targets are 48dp, the extended-hours column appears only when it should, dark mode |
| `FeedIdentityTest` | what counts as the same story, and that every surviving row still has a unique list key |

**Two things Robolectric cannot do here**, so nobody re-derives them:

- A dialog containing `OutlinedTextField`s never reaches idle, so `TxnEditorDialog` cannot be
  rendered under test. This is the harness, not the app - proved with a dialog containing no
  app code. Its logic is covered by `ShippedTest.java` instead.
- `PortfolioViewModel` starts a polling loop in `init`, so no test using the Compose rule can
  settle with it on screen. Full-screen tests need that loop behind a seam first.
