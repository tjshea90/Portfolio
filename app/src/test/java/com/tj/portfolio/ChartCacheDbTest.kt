package com.tj.portfolio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.ChartPoint
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.FundHolding
import com.tj.portfolio.data.FundHoldings
import com.tj.portfolio.data.SectorWeight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE ROUND 58 CACHES, AGAINST A REAL SQLITE DATABASE.
 *
 * These tests exist because two of the bugs found in this round's self-review were invisible
 * to every other kind of checking. Both compiled, both type-checked, and both would have
 * failed only at runtime, on the phone, silently:
 *
 *   - `chart_cache` originally had a column literally named `range`, which is a RESERVED
 *     WORD in SQLite's window-frame syntax. Whether that parses is a property of the
 *     engine, not of the Kotlin;
 *   - `purgeChartCache` bound an `Int` through `execSQL`'s bind arguments. Android binds
 *     only Long / Double / String / byte[] / null and throws for anything else - and the
 *     call sat inside a `runCatching`, so the purge would have failed on every run without
 *     a single symptom until the table had grown without bound.
 *
 * A model of SQLite agrees with whatever assumption produced it. This runs the engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartCacheDbTest {

    private lateinit var ctx: Context
    private lateinit var db: Db

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(Db.DB_NAME)
        db = Db(ctx)
    }

    @After fun tearDown() {
        db.close()
        ctx.deleteDatabase(Db.DB_NAME)
    }

    private fun series(sym: String, r: ChartRange, n: Int = 5, fetched: Long = 1_000L) =
        ChartSeries(
            symbol = sym,
            range = r,
            points = (0 until n).map { ChartPoint(1_757_000_000L + it * 300L, 100.0 + it) },
            baseline = 99.0,
            currency = "USD",
            fetched = fetched
        )

    // ------------------------------------------------------------- chart cache

    @Test
    fun `a series round trips through the database`() {
        val s = series("AAPL", ChartRange.M6)
        db.cacheChart(s)
        val back = db.cachedChart("AAPL", ChartRange.M6)
        assertNotNull("the row did not come back - check the column names", back)
        back!!
        assertEquals(s.points.size, back.points.size)
        assertEquals(s.baseline, back.baseline, 1e-9)
        assertEquals(s.fetched, back.fetched)
        assertEquals(ChartRange.M6, back.range)
        assertEquals(s.points.last().close, back.points.last().close, 1e-9)
    }

    @Test
    fun `lookups are case-insensitive on the symbol`() {
        db.cacheChart(series("msft", ChartRange.D1))
        assertNotNull(db.cachedChart("MSFT", ChartRange.D1))
        assertNotNull(db.cachedChart("msft", ChartRange.D1))
    }

    /** Every range for a symbol in ONE query - what makes a chart paint before any request. */
    @Test
    fun `cachedCharts returns every stored range for one symbol and no others`() {
        db.cacheChart(series("AAPL", ChartRange.D1))
        db.cacheChart(series("AAPL", ChartRange.Y1))
        db.cacheChart(series("AAPL", ChartRange.OVERNIGHT))
        db.cacheChart(series("MSFT", ChartRange.D1))

        val all = db.cachedCharts("AAPL")
        assertEquals(3, all.size)
        assertTrue(all.containsKey(ChartRange.D1))
        assertTrue(all.containsKey(ChartRange.Y1))
        assertTrue(all.containsKey(ChartRange.OVERNIGHT))
        assertEquals(1, db.cachedCharts("MSFT").size)
        assertTrue(db.cachedCharts("NOSUCH").isEmpty())
    }

    /** EVERY range name must survive being used as a key, reserved words included. */
    @Test
    fun `all eight ranges can be stored and read back`() {
        ChartRange.entries.forEach { db.cacheChart(series("X", it)) }
        val all = db.cachedCharts("X")
        assertEquals(ChartRange.entries.size, all.size)
        ChartRange.entries.forEach {
            assertNotNull("range ${it.name} did not round trip", db.cachedChart("X", it))
        }
    }

    @Test
    fun `writing the same pair again replaces it rather than duplicating`() {
        db.cacheChart(series("AAPL", ChartRange.D1, n = 5, fetched = 1_000L))
        db.cacheChart(series("AAPL", ChartRange.D1, n = 9, fetched = 2_000L))
        assertEquals(1, db.cachedCharts("AAPL").size)
        val back = db.cachedChart("AAPL", ChartRange.D1)!!
        assertEquals(9, back.points.size)
        assertEquals(2_000L, back.fetched)
    }

    /**
     * A failed fetch must leave the last good chart alone. Overwriting it with nothing turns
     * one refused request into a permanently blank chart - the exact failure the cache
     * exists to prevent.
     */
    @Test
    fun `an empty series is never written over a good one`() {
        db.cacheChart(series("AAPL", ChartRange.D1))
        db.cacheChart(ChartSeries("AAPL", ChartRange.D1))          // no points
        assertEquals(5, db.cachedChart("AAPL", ChartRange.D1)!!.points.size)
    }

    /**
     * THE BIND-ARGUMENT BUG. If the purge throws it is swallowed, so the only way to know it
     * runs is to overflow the table and check that rows actually went.
     */
    @Test
    fun `the purge really runs and keeps the newest rows`() {
        // 8 ranges x 60 symbols = 480 rows, comfortably over the 400 cap.
        var t = 1_000L
        val symbols = (0 until 60).map { "SYM$it" }
        symbols.forEach { s ->
            ChartRange.entries.forEach { r -> db.cacheChart(series(s, r, fetched = t++)) }
        }
        assertEquals(480, db.chartCacheStats().first)

        db.purgeChartCache()

        val (rows, newest) = db.chartCacheStats()
        assertTrue("purge did not run - $rows rows left", rows <= 400)
        assertTrue("purge ran but removed nothing", rows in 1..400)
        // Newest-first: the last thing written must still be there.
        assertEquals(t - 1, newest)
        assertNotNull(
            "the purge dropped the newest row instead of the oldest",
            db.cachedChart(symbols.last(), ChartRange.entries.last())
        )
        // ...and the very first thing written must be gone.
        assertNull(db.cachedChart(symbols.first(), ChartRange.entries.first()))
    }

    @Test
    fun `clearing empties the table`() {
        ChartRange.entries.forEach { db.cacheChart(series("X", it)) }
        assertTrue(db.chartCacheStats().first > 0)
        db.clearChartCache()
        assertEquals(0, db.chartCacheStats().first)
        assertTrue(db.cachedCharts("X").isEmpty())
    }

    @Test
    fun `an absent row reads as null rather than as an empty series`() {
        assertNull(db.cachedChart("NOPE", ChartRange.D1))
    }

    // ---------------------------------------------------------- holdings cache

    @Test
    fun `holdings round trip through the shared fundamentals table`() {
        val h = FundHoldings(
            symbol = "SPY", isFund = true, quoteType = "ETF",
            holdings = listOf(FundHolding("NVDA", "NVIDIA", 0.08)),
            sectors = listOf(SectorWeight("Technology", 0.34)),
            category = "Large Blend", family = "Test", expenseRatio = 0.0009,
            stockPct = 0.99, cashPct = 0.01, fetched = 4_000L
        )
        db.cacheHoldings(h)
        val back = db.cachedHoldings("spy")
        assertNotNull(back)
        back!!
        assertTrue(back.isFund)
        assertEquals("ETF", back.quoteType)
        assertEquals(1, back.holdings.size)
        assertEquals("NVDA", back.holdings.first().symbol)
        assertEquals(0.34, back.sectors.first().weight, 1e-9)
        assertEquals(4_000L, back.fetched)
    }

    /**
     * "Not a fund" is a real answer with a real lifetime. Caching it is what stops the app
     * asking Yahoo the same dead question every time an ordinary share is opened, so it has
     * to survive the write/read cycle as a stored NEGATIVE, not as a missing row.
     */
    @Test
    fun `a not-a-fund verdict is stored, not treated as nothing to store`() {
        db.cacheHoldings(FundHoldings("AAPL", isFund = false, quoteType = "EQUITY",
            fetched = 7_000L))
        val back = db.cachedHoldings("AAPL")
        assertNotNull("the negative verdict was not stored", back)
        assertTrue(back!!.isEmpty)
        assertEquals(false, back.isFund)
        assertEquals("EQUITY", back.quoteType)
    }

    /** Holdings share a table with the other two kinds and must not collide with them. */
    @Test
    fun `holdings do not disturb the core and ratings rows for the same symbol`() {
        db.cacheFundamentals("AAPL", com.tj.portfolio.data.Keys.KIND_CORE,
            com.tj.portfolio.data.Fundamentals(symbol = "AAPL",
                values = mapOf("peTrailing" to 30.0), fetched = 1L))
        db.cacheHoldings(FundHoldings("AAPL", isFund = false, fetched = 2L))
        val core = db.cachedFundamentals("AAPL", com.tj.portfolio.data.Keys.KIND_CORE)
        assertNotNull(core)
        assertEquals(30.0, core!!.values["peTrailing"]!!, 1e-9)
        assertNotNull(db.cachedHoldings("AAPL"))
    }

    @Test
    fun `an absent holdings row reads as null`() {
        assertNull(db.cachedHoldings("NOPE"))
    }
}
