package com.tj.portfolio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.FundHoldings
import com.tj.portfolio.data.Keys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE UPGRADE TJ'S PHONE ACTUALLY PERFORMS: db v6 (shipped as v6.8) -> db v7 (v6.9).
 *
 * `DbTest` already walks a real v1 database up to the current schema, which is the broader
 * guarantee. This one is narrower and more valuable for exactly one reason: **v6 is the
 * version sitting on the phone right now**, and `onUpgrade` is the single place in this app
 * where a mistake destroys data that cannot be recovered - every transaction TJ has ever
 * entered, plus his overrides and watchlist.
 *
 * So this builds the v6 schema as v6.8 actually shipped it, fills it with the kind of rows a
 * real install holds, opens it with today's `Db`, and asserts three separate things:
 *
 *   1. nothing was lost - the ledger, the overrides, the watchlist, the settings and the
 *      import history all survive byte for byte;
 *   2. the ONE new thing works - `chart_cache` exists and round-trips after an upgrade, not
 *      merely after a fresh `onCreate`, which is a different code path;
 *   3. it is idempotent - reopening does not disturb anything, because `onOpen` re-runs
 *      every CREATE TABLE IF NOT EXISTS as a repair for an interrupted upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeV6ToV7Test {

    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() {
        ctx.deleteDatabase(Db.DB_NAME)
    }

    private val txnCount = 16

    /** The schema exactly as db v6 held it: everything v6.8 had, and no chart_cache. */
    private fun buildV6(): Long {
        val f = ctx.getDatabasePath(Db.DB_NAME)
        f.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(f, null)

        raw.execSQL(
            """CREATE TABLE txns(
                id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, symbol TEXT,
                quantity REAL NOT NULL DEFAULT 0, price REAL NOT NULL DEFAULT 0,
                amount REAL NOT NULL DEFAULT 0, fees REAL NOT NULL DEFAULT 0,
                date INTEGER NOT NULL, note TEXT, source TEXT NOT NULL DEFAULT 'MANUAL')"""
        )
        raw.execSQL("CREATE TABLE settings(k TEXT PRIMARY KEY, v TEXT)")
        raw.execSQL("CREATE TABLE overrides(symbol TEXT PRIMARY KEY, avg_cost REAL, shares REAL)")
        // quote_time was added at v3, so a v6 database has it.
        raw.execSQL(
            """CREATE TABLE quotes(symbol TEXT PRIMARY KEY, name TEXT, price REAL,
                prev_close REAL, day_high REAL, day_low REAL, ext_price REAL, ext_label TEXT,
                market_state TEXT, spark TEXT, currency TEXT, updated INTEGER,
                quote_time INTEGER NOT NULL DEFAULT 0)"""
        )
        raw.execSQL("CREATE TABLE watchlist(symbol TEXT PRIMARY KEY, added INTEGER)")
        raw.execSQL(
            """CREATE TABLE imports(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL,
                count INTEGER NOT NULL DEFAULT 0, skipped INTEGER NOT NULL DEFAULT 0,
                min_date INTEGER NOT NULL DEFAULT 0, max_date INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'SCREENSHOT')"""
        )
        raw.execSQL(
            """CREATE TABLE news_cache(id TEXT PRIMARY KEY, kind TEXT NOT NULL DEFAULT 'NEWS',
                symbol TEXT NOT NULL DEFAULT '', title TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '', url TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT '', published INTEGER NOT NULL DEFAULT 0,
                owned INTEGER NOT NULL DEFAULT 0, uid TEXT NOT NULL DEFAULT '',
                summary TEXT NOT NULL DEFAULT '', first_seen INTEGER NOT NULL DEFAULT 0)"""
        )
        raw.execSQL(
            """CREATE TABLE fundamentals(symbol TEXT NOT NULL, kind TEXT NOT NULL,
                json TEXT NOT NULL, fetched INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(symbol, kind))"""
        )
        raw.execSQL(
            """CREATE TABLE http_cache(url TEXT PRIMARY KEY, etag TEXT NOT NULL DEFAULT '',
                last_modified TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '',
                fetched INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0)"""
        )

        // A realistic install: buys, a sell, cash rows with a NULL symbol, an override, a
        // watchlist and the settings that actually change behaviour.
        for (i in 1..txnCount) {
            raw.insert("txns", null, ContentValues().apply {
                put("type", if (i == 5) "SELL" else "BUY")
                put("symbol", "SYM$i")
                put("quantity", i.toDouble()); put("price", 10.0 * i)
                put("amount", -10.0 * i * i); put("fees", 0.01 * i)
                put("date", 1_750_000_000_000L + i * 86_400_000L)
                put("source", "MANUAL")
            })
        }
        // The org.json NULL trap lives on these: cash rows carry a null symbol.
        raw.execSQL(
            "INSERT INTO txns(type,symbol,quantity,price,amount,fees,date,source) " +
                "VALUES('DEPOSIT',NULL,0,0,5000.0,0,1750000000000,'MANUAL')"
        )
        raw.execSQL("INSERT INTO settings(k,v) VALUES('cost_method','FIFO')")
        raw.execSQL("INSERT INTO settings(k,v) VALUES('refresh_secs','15')")
        raw.execSQL("INSERT INTO overrides(symbol,avg_cost,shares) VALUES('SYM3',33.5,12.0)")
        raw.execSQL("INSERT INTO watchlist(symbol,added) VALUES('NVDA',1)")
        raw.execSQL("INSERT INTO watchlist(symbol,added) VALUES('SPY',2)")
        raw.execSQL(
            "INSERT INTO imports(at,count,skipped,min_date,max_date,source) " +
                "VALUES(1750000000000,111,3,1740000000000,1750000000000,'SCREENSHOT')"
        )
        raw.execSQL(
            "INSERT INTO quotes(symbol,name,price,prev_close,day_high,day_low,ext_price," +
                "ext_label,market_state,spark,currency,updated,quote_time) " +
                "VALUES('SYM1','One',10.5,10.0,11.0,9.5,NULL,NULL,'OPEN','[9.9,10.2,10.5]'," +
                "'USD',1750000000000,1750000000000)"
        )

        raw.version = 6
        raw.close()
        return txnCount + 1L    // the cash row too
    }

    @Test
    fun `a v6 database upgrades to v7 without losing anything`() {
        val expected = buildV6()
        val db = Db(ctx)
        try {
            // ---- 1. nothing lost
            assertEquals("the upgrade lost transactions", expected.toInt(), db.allTxns().size)
            assertEquals("FIFO", db.get(Keys.COST_METHOD))
            assertEquals("15", db.get(Keys.REFRESH_SECS))
            assertEquals(33.5, db.overrides()["SYM3"]!!.avgCost!!, 1e-9)
            assertEquals(12.0, db.overrides()["SYM3"]!!.shares!!, 1e-9)
            assertEquals(listOf("NVDA", "SPY"), db.watchlist())
            assertEquals(1, db.importCount())

            // The cash row's NULL symbol must still read as null, not as the string "null" -
            // the org.json trap that once turned all eight of TJ's cash rows into a phantom
            // holding called NULL.
            val cash = db.allTxns().filter { it.type == "DEPOSIT" }
            assertEquals(1, cash.size)
            assertTrue("a null symbol came back as ${cash.first().symbol}",
                cash.first().symbol.isNullOrBlank())

            // The cached quote and its sparkline survive, so the app opens on last prices.
            val q = db.cachedQuotes()["SYM1"]
            assertNotNull(q)
            assertEquals(10.5, q!!.price, 1e-9)
            assertEquals(3, q.spark.size)

            // ---- 2. the new table exists and works AFTER AN UPGRADE, not just onCreate
            assertEquals(0, db.chartCacheStats().first)
            val series = ChartSeries(
                symbol = "SYM1", range = ChartRange.D1,
                points = (0 until 5).map { ChartPoint(1_757_000_000L + it * 300L, 10.0 + it) },
                baseline = 9.9, fetched = 4_000L, regularOnly = true
            )
            db.cacheChart(series)
            val back = db.cachedChart("SYM1", ChartRange.D1)
            assertNotNull("chart_cache was not created by the v6 -> v7 upgrade", back)
            assertEquals(5, back!!.points.size)
            assertTrue(back.regularOnly)

            // Every range, because the column name is the thing most likely to be wrong.
            ChartRange.entries.forEach { db.cacheChart(series.copy(range = it)) }
            assertEquals(ChartRange.entries.size, db.cachedCharts("SYM1").size)

            // ---- and the holdings cache, which rides the existing fundamentals table
            db.cacheHoldings(
                FundHoldings("SPY", isFund = true, quoteType = "ETF", fetched = 9_000L)
            )
            assertNotNull(db.cachedHoldings("SPY"))
        } finally {
            db.close()
        }
    }

    /**
     * `onOpen` re-runs every CREATE TABLE IF NOT EXISTS as a repair for an interrupted
     * upgrade, so reopening has to be free of side effects on data.
     */
    @Test
    fun `reopening after the upgrade changes nothing`() {
        val expected = buildV6()
        Db(ctx).use { first ->
            first.cacheChart(
                ChartSeries(
                    "SYM1", ChartRange.M1,
                    listOf(ChartPoint(1, 10.0), ChartPoint(2, 11.0)), fetched = 1L
                )
            )
        }
        Db(ctx).use { second ->
            assertEquals(expected.toInt(), second.allTxns().size)
            assertEquals(listOf("NVDA", "SPY"), second.watchlist())
            assertEquals(33.5, second.overrides()["SYM3"]!!.avgCost!!, 1e-9)
            assertNotNull("the reopen dropped the chart cache",
                second.cachedChart("SYM1", ChartRange.M1))
        }
    }

    /**
     * Android normally refuses to install a lower versionCode, but if a downgrade ever
     * happened the SQLiteOpenHelper default is to THROW - which would crash on first launch
     * with the user's data intact but unreachable. `onDowngrade` is overridden to a no-op.
     */
    @Test
    fun `a database from a NEWER app version opens instead of crashing`() {
        buildV6()
        val f = ctx.getDatabasePath(Db.DB_NAME)
        SQLiteDatabase.openOrCreateDatabase(f, null).use { it.version = Db.DB_VERSION + 5 }
        Db(ctx).use { db ->
            assertEquals(txnCount + 1, db.allTxns().size)
        }
    }
}
