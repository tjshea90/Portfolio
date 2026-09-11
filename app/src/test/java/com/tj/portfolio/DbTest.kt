package com.tj.portfolio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.FeedItem
import com.tj.portfolio.data.Keys
import com.tj.portfolio.data.Override
import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.Recommendation
import com.tj.portfolio.data.TradeVerdict
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * data/Db.kt against a REAL SQLite database.
 *
 * This file is the point of the round. Db is 741 lines holding every transaction the user
 * owns, and until now the only thing that had ever exercised it was a Python model of
 * sqlite3 written to resemble it. A model agrees with whatever assumption produced it; this
 * runs the actual class, on the actual database engine, through the actual upgrade path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DbTest {

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

    private fun day(y: Int, m: Int, d: Int, h: Int = 12): Long {
        val c = Calendar.getInstance()
        c.set(y, m - 1, d, h, 0, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun buy(sym: String, qty: Double, px: Double, date: Long, fees: Double = 0.0) = Txn(
        type = TxnType.BUY, symbol = sym, quantity = qty, price = px,
        amount = Txn.cashEffect(TxnType.BUY, qty, px, 0.0, fees), fees = fees, date = date
    )

    // ------------------------------------------------------------ round trip

    @Test fun `export then restore reproduces every table exactly`() {
        db.insertTxn(buy("NVDA", 3.0, 227.44, day(2026, 9, 2)))
        db.insertTxn(Txn(type = TxnType.DEPOSIT, symbol = null, amount = 1000.0, date = day(2026, 9, 1)))
        db.insertTxn(Txn(type = TxnType.DIVIDEND, symbol = "CSCO", amount = 4.20, date = day(2026, 8, 15)))
        db.setOverride(Override("BA", avgCost = 180.5, shares = null))
        db.setOverride(Override("SOXQ", avgCost = null, shares = 12.0))
        db.addWatch("AAPL"); db.addWatch("MSFT")
        db.set(Keys.COST_METHOD, "FIFO")
        db.set(Keys.CLAUDE_KEY, "sk-ant-secret")
        db.recordImport(3, 1, day(2026, 8, 1), day(2026, 9, 2), "SCREENSHOT")

        val json = db.exportJson()

        // a secret must never leave the device in the app's own export
        assertFalse("API key leaked into the backup", json.contains("sk-ant-secret"))

        val fresh = Db(ctx.also { it.deleteDatabase(Db.DB_NAME) })
        val r = fresh.restoreJson(json, replace = true)
        assertNull(r.error)
        assertEquals(3, r.transactions)
        assertEquals(2, r.overrides)
        assertEquals(2, r.watchlist)
        assertEquals(1, r.imports)

        assertEquals(3, fresh.allTxns().size)
        assertEquals(setOf("AAPL", "MSFT"), fresh.watchlist().toSet())
        assertEquals(180.5, fresh.overrides()["BA"]!!.avgCost!!, 1e-9)
        assertNull(fresh.overrides()["BA"]!!.shares)
        assertEquals(12.0, fresh.overrides()["SOXQ"]!!.shares!!, 1e-9)
        assertEquals("FIFO", fresh.get(Keys.COST_METHOD))
        assertEquals("", fresh.get(Keys.CLAUDE_KEY))   // filtered on the way in as well
        fresh.close()
    }

    @Test fun `a cash row keeps its null symbol through a restore`() {
        // The v1.9 bug: org.json optString on a JSON null returns the STRING "null", which
        // turned every DEPOSIT into a phantom position called NULL.
        db.insertTxn(Txn(type = TxnType.DEPOSIT, symbol = null, amount = 500.0, date = day(2026, 9, 1)))
        db.insertTxn(Txn(type = TxnType.WITHDRAWAL, symbol = null, amount = 200.0, date = day(2026, 9, 2)))
        val json = db.exportJson()
        val fresh = Db(ctx.also { it.deleteDatabase(Db.DB_NAME) })
        fresh.restoreJson(json, replace = true)
        assertTrue("a cash row came back with a symbol", fresh.allTxns().all { it.symbol == null })
        fresh.close()
    }

    @Test fun `restore rejects a NaN override rather than poisoning every total`() {
        val json = JSONObject().apply {
            put("format", Db.BACKUP_FORMAT); put("version", Db.BACKUP_VERSION)
            put("transactions", JSONArray())
            put("overrides", JSONArray().put(JSONObject().apply {
                put("symbol", "NVDA"); put("avgCost", "not a number"); put("shares", 5)
            }))
        }.toString()
        db.restoreJson(json, replace = true)
        val ov = db.overrides()["NVDA"]
        assertNotNull(ov)
        assertNull("a NaN avgCost was stored and will render every total as NaN", ov!!.avgCost)
        assertEquals(5.0, ov.shares!!, 1e-9)
    }

    @Test fun `merge does not clobber a live preference with a stale one`() {
        db.set(Keys.REFRESH_SECS, "15")
        db.set(Keys.COST_METHOD, "FIFO")
        val stale = JSONObject().apply {
            put("transactions", JSONArray())
            put("settings", JSONObject().apply {
                put(Keys.REFRESH_SECS, "600"); put(Keys.COST_METHOD, "AVERAGE")
                put(Keys.WATCH_SORT, "symbol")            // this one is genuinely missing
            })
        }.toString()
        db.restoreJson(stale, replace = false)
        assertEquals("merge overwrote a live setting", "15", db.get(Keys.REFRESH_SECS))
        assertEquals("merge overwrote a live setting", "FIFO", db.get(Keys.COST_METHOD))
        assertEquals("merge failed to add a missing setting", "symbol", db.get(Keys.WATCH_SORT))
    }

    @Test fun `replace clears stale overrides watchlist and imports, not just transactions`() {
        db.insertTxn(buy("NVDA", 1.0, 100.0, day(2026, 9, 1)))
        db.setOverride(Override("STALE", avgCost = 1.0, shares = null))
        db.addWatch("STALE"); db.recordImport(1, 0, 0, 0, "OLD")
        val empty = JSONObject().apply {
            put("transactions", JSONArray()); put("overrides", JSONArray())
            put("watchlist", JSONArray()); put("imports", JSONArray())
        }.toString()
        db.restoreJson(empty, replace = true)
        assertEquals(0, db.allTxns().size)
        assertTrue("a stale override survived Replace all", db.overrides().isEmpty())
        assertTrue("a stale watchlist entry survived Replace all", db.watchlist().isEmpty())
        assertEquals("a stale import record survived Replace all", 0, db.importCount())
    }

    @Test fun `a corrupt file is refused without touching the data`() {
        db.insertTxn(buy("NVDA", 1.0, 100.0, day(2026, 9, 1)))
        assertNotNull(db.restoreJson("{ this is not json", replace = true).error)
        assertEquals("a bad file destroyed the ledger", 1, db.allTxns().size)
        assertNotNull(db.restoreJson("""{"hello":"world"}""", replace = true).error)
        assertEquals("an unrelated JSON file destroyed the ledger", 1, db.allTxns().size)
    }

    @Test fun `the manifest cross-check warns when the file is short`() {
        val json = JSONObject().apply {
            put("transactions", JSONArray().put(JSONObject().apply {
                put("type", "BUY"); put("symbol", "NVDA"); put("quantity", 1)
                put("price", 100); put("amount", -100); put("date", day(2026, 9, 1))
            }))
            put("counts", JSONObject().apply { put("transactions", 5) })
        }.toString()
        val r = db.restoreJson(json, replace = true)
        assertNotNull("a truncated backup restored silently", r.warning)
        assertTrue(r.summary().contains("5"))
    }

    // ------------------------------------------------------------- dedupe

    @Test fun `duplicate detection tolerates the rounding two screenshots disagree on`() {
        val d = day(2026, 9, 2)
        db.insertTxn(Txn(type = TxnType.BUY, symbol = "NVDA", quantity = 3.0, price = 227.44,
            amount = -682.32, date = d))
        // exact repeat
        assertTrue(db.txnExists(Txn(type = TxnType.BUY, symbol = "NVDA", quantity = 3.0,
            price = 227.44, amount = -682.32, date = d)))
        // a cent out, as a second read of the same screenshot can be
        assertTrue(db.txnExists(Txn(type = TxnType.BUY, symbol = "NVDA", quantity = 3.0,
            price = 227.44, amount = -682.33, date = d)))
        // 40c out - still the same trade, caught by the one-dollar share-count rule
        assertTrue(db.txnExists(Txn(type = TxnType.BUY, symbol = "NVDA", quantity = 3.0,
            price = 227.31, amount = -681.92, date = d)))
        // a genuinely different quantity on the same day is a DIFFERENT trade
        assertFalse("a second trade the same day was swallowed as a duplicate",
            db.txnExists(Txn(type = TxnType.BUY, symbol = "NVDA", quantity = 4.0,
                price = 227.44, amount = -909.76, date = d)))
        // the same shape on another day is not a duplicate
        assertFalse(db.txnExists(Txn(type = TxnType.BUY, symbol = "NVDA", quantity = 3.0,
            price = 227.44, amount = -682.32, date = day(2026, 9, 3))))
        // and neither is a SELL of the same size
        assertFalse(db.txnExists(Txn(type = TxnType.SELL, symbol = "NVDA", quantity = 3.0,
            price = 227.44, amount = 682.32, date = d)))
    }

    @Test fun `the date-independent check catches an undated screenshot re-imported later`() {
        db.insertTxn(Txn(type = TxnType.BUY, symbol = "ONDS", quantity = 45.0, price = 7.565,
            amount = -340.43, date = day(2026, 9, 2), note = "position snapshot - date estimated"))
        val sameRowNextWeek = Txn(type = TxnType.BUY, symbol = "ONDS", quantity = 45.0,
            price = 7.565, amount = -340.43, date = day(2026, 9, 9),
            note = "position snapshot - date estimated")
        assertNull("the day-scoped check should NOT see this", db.findDuplicateId(sameRowNextWeek))
        assertNotNull("the date-independent check missed it - the position would double",
            db.findDuplicateIdAnyDate(sameRowNextWeek))
    }

    @Test fun `deleting a symbol spares cash rows and look-alike tickers`() {
        db.insertTxn(buy("NVDA", 1.0, 100.0, day(2026, 9, 1)))
        db.insertTxn(buy("NVDAX", 1.0, 50.0, day(2026, 9, 1)))
        db.insertTxn(Txn(type = TxnType.DEPOSIT, symbol = null, amount = 500.0, date = day(2026, 9, 1)))
        assertEquals(1, db.deleteTxnsForSymbol("nvda"))     // lower case must still match
        val left = db.allTxns()
        assertEquals(2, left.size)
        assertTrue(left.any { it.symbol == "NVDAX" })
        assertTrue(left.any { it.symbol == null })
    }

    // --------------------------------------------------------- the upgrade path

    /**
     * A REAL v1 database, built with the schema v1.0 actually shipped, then opened by
     * today's Db so onUpgrade runs for real. Previous rounds simulated this in Python.
     */
    /**
     * ROUND 66. The quotes table was the one cache in this app that was never pruned.
     *
     * There was no `purgeQuotes`, no retention constant and no `DELETE FROM quotes` anywhere -
     * but rows are written for far more than the tracked set: every stock opened from search,
     * from the Research lists or from a headline is quoted and persisted, and a full intraday
     * spark series is written into its row. `cachedQuotes()` then reads every row and parses
     * every one of those arrays, synchronously, on the launch path.
     */
    @Test fun `quotes older than the retention window are dropped`() {
        val now = 1_800_000_000_000L
        val month = 30L * 86_400_000L
        db.cacheQuote(Quote(symbol = "HELD", price = 10.0, updated = now - 1_000L))
        db.cacheQuote(Quote(symbol = "GLANCED", price = 20.0, updated = now - month - 1_000L))
        assertEquals(2, db.cachedQuotes().size)

        val removed = db.purgeQuotes(olderThanMs = month, now = now)
        assertEquals(1, removed)
        val left = db.cachedQuotes()
        assertEquals("the tracked symbol was dropped", setOf("HELD"), left.keys)
    }

    @Test fun `an undateable quote is left alone rather than deleted`() {
        // "Undateable" and "a month old" are different facts. A purge that treated them the
        // same would silently delete a row written moments ago, and the cost - a re-fetch,
        // not lost data - is exactly why nobody would notice.
        val now = 1_800_000_000_000L
        db.cacheQuote(Quote(symbol = "NOSTAMP", price = 5.0, updated = 0L))
        assertEquals(0, db.purgeQuotes(olderThanMs = 30L * 86_400_000L, now = now))
        assertEquals(setOf("NOSTAMP"), db.cachedQuotes().keys)
    }

    /**
     * ROUND 66. The two txns indexes were written inline in `onCreate` as plain
     * `CREATE INDEX`, so a database that was UPGRADED rather than freshly created never had
     * them - `onUpgrade` did not make them and the `onOpen` repair block did not either.
     *
     * That is not cosmetic. `findDuplicateId` runs once per incoming row on a restore or a
     * screenshot import and `deleteTxnsForSymbol` runs on every position deletion; both have
     * comments claiming they use `idx_txn_symbol`, and on every upgraded install they were
     * full table scans instead. This fixture is the exact shape TJ's phone has: the schema
     * v1.0 actually shipped, with no indexes on txns at all.
     */
    @Test fun `an upgraded database gets the txns indexes it never had`() {
        ctx.deleteDatabase(Db.DB_NAME)
        buildLegacyV1(Db.DB_NAME)

        // Prove the fixture really is the un-indexed shape, or the test proves nothing.
        val bare = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(Db.DB_NAME), null)
        val before = indexNames(bare, "txns")
        bare.close()
        assertTrue("the fixture already had indexes - it cannot show the fix", before.isEmpty())

        val upgraded = Db(ctx)
        upgraded.allTxns()          // force the helper to open and run onUpgrade/onOpen
        val after = indexNames(upgraded.writableDatabase, "txns")
        assertTrue(
            "idx_txn_symbol is still missing after the upgrade - findDuplicateId is a full " +
                "table scan on every imported row. Found: $after",
            "idx_txn_symbol" in after
        )
        assertTrue("idx_txn_date is still missing after the upgrade. Found: $after",
            "idx_txn_date" in after)
        // And the data survived the extra statements.
        assertEquals(12, upgraded.allTxns().size)
        upgraded.close()
    }

    @Test fun `creating the indexes twice is harmless`() {
        // `onOpen` runs on EVERY open, so the second launch re-runs the same statements.
        ctx.deleteDatabase(Db.DB_NAME)
        Db(ctx).also { it.allTxns(); it.close() }
        val second = Db(ctx)
        assertTrue("idx_txn_symbol", "idx_txn_symbol" in indexNames(second.writableDatabase, "txns"))
        assertEquals(0, second.allTxns().size)
        second.close()
    }

    /** The user-defined index names on one table, straight out of sqlite_master. */
    private fun indexNames(db: SQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?", arrayOf(table)
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    private fun buildLegacyV1(name: String): Long {
        val f = ctx.getDatabasePath(name)
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
        raw.execSQL(
            """CREATE TABLE quotes(symbol TEXT PRIMARY KEY, name TEXT, price REAL,
                prev_close REAL, day_high REAL, day_low REAL, ext_price REAL, ext_label TEXT,
                market_state TEXT, spark TEXT, currency TEXT, updated INTEGER)"""
        )
        raw.execSQL("CREATE TABLE watchlist(symbol TEXT PRIMARY KEY, added INTEGER)")
        for (i in 1..12) {
            raw.insert("txns", null, ContentValues().apply {
                put("type", "BUY"); put("symbol", "SYM$i"); put("quantity", i.toDouble())
                put("price", 10.0 * i); put("amount", -10.0 * i * i); put("fees", 0.0)
                put("date", day(2026, 1, 1) + i * 86_400_000L); put("source", "MANUAL")
            })
        }
        raw.execSQL("INSERT INTO settings(k,v) VALUES('cost_method','AVERAGE')")
        raw.execSQL("INSERT INTO overrides(symbol,avg_cost,shares) VALUES('SYM3',33.0,NULL)")
        raw.execSQL("INSERT INTO watchlist(symbol,added) VALUES('TSLA',1)")
        raw.version = 1
        raw.close()
        return 12L
    }

    @Test fun `a v1 database upgrades to v4 without losing a row`() {
        db.close()
        ctx.deleteDatabase(Db.DB_NAME)
        val expected = buildLegacyV1(Db.DB_NAME)

        val upgraded = Db(ctx)
        assertEquals("the upgrade lost transactions", expected.toInt(), upgraded.allTxns().size)
        assertEquals("AVERAGE", upgraded.get(Keys.COST_METHOD))
        assertEquals(33.0, upgraded.overrides()["SYM3"]!!.avgCost!!, 1e-9)
        assertEquals(listOf("TSLA"), upgraded.watchlist())
        // v2 added the imports table
        assertEquals(0, upgraded.importCount())
        upgraded.recordImport(1, 0, 0, 0, "TEST")
        assertEquals(1, upgraded.importCount())
        // v3 added quotes.quote_time - a cached quote must round-trip through it
        upgraded.cacheQuote(
            Quote(symbol = "SYM1", name = "One", price = 12.5, prevClose = 12.0,
                spark = listOf(1.0, 2.0, 3.0), quoteTime = 1_756_000_000_000L, updated = 99L)
        )
        val q = upgraded.cachedQuotes()["SYM1"]
        assertNotNull(q)
        assertEquals(1_756_000_000_000L, q!!.quoteTime)
        assertEquals(listOf(1.0, 2.0, 3.0), q.spark)
        assertEquals(12.0, q.prevClose, 1e-9)

        // v4 added news_cache. A database that predates it must be able to write and read
        // headlines immediately after the upgrade - and, more importantly, must still hold
        // every transaction that was there before, which the assertions above cover.
        assertEquals(0, upgraded.cachedNews().size)
        val item = FeedItem(
            kind = FeedItem.NEWS, symbol = "SYM1", title = "Something happened",
            url = "https://example.com/1", source = "Test", published = 1_756_000_000_000L
        )
        assertEquals(1, upgraded.cacheNews(listOf(item)))
        assertEquals(1, upgraded.cachedNews().size)
        assertEquals(12, upgraded.allTxns().size)   // the upgrade did not disturb the ledger
        upgraded.close()
    }

    // --------------------------------------------------------- the headline cache (v4)
    //
    // TJ: "every refresh reloads all news over again", "when I go to the news feed tab then
    // switch apps and go back, the news is gone", and "delete any cached news that is old".
    // The cache is what answers all three, so its identity rule and its retention rule are
    // the things worth pinning down.

    private fun news(
        id: String, sym: String = "NVDA", pub: Long = 1_756_000_000_000L,
        kind: String = FeedItem.NEWS, owned: Boolean = true
    ) = FeedItem(
        kind = kind, symbol = sym, title = "Headline $id", url = "https://example.com/$id",
        source = "Test", published = pub, owned = owned
    )

    @Test fun `caching the same story twice adds it once`() {
        // This is what makes an incremental refresh exact: the primary key is FeedItem.id,
        // the same expression the feed de-duplicates and keys its list by.
        assertEquals(2, db.cacheNews(listOf(news("a"), news("b"))))
        assertEquals(0, db.cacheNews(listOf(news("a"), news("b"))))
        assertEquals(1, db.cacheNews(listOf(news("a"), news("c"))))
        assertEquals(3, db.cachedNews().size)
    }

    @Test fun `a refresh keeps what was already cached`() {
        // The behaviour TJ asked for in one assertion: the second pass adds, it does not
        // replace. Before v6.3 the feed was assigned from whatever one pass returned.
        db.cacheNews(listOf(news("old1"), news("old2")))
        db.cacheNews(listOf(news("new1")))
        val ids = db.cachedNews().map { it.title }.toSet()
        assertEquals(setOf("Headline old1", "Headline old2", "Headline new1"), ids)
    }

    @Test fun `cached headlines come back newest first`() {
        db.cacheNews(listOf(
            news("mid", pub = 2_000L), news("new", pub = 3_000L), news("old", pub = 1_000L)
        ))
        assertEquals(
            listOf("Headline new", "Headline mid", "Headline old"),
            db.cachedNews().map { it.title }
        )
    }

    @Test fun `the owned flag is refreshed but the story is not duplicated`() {
        // Whether the user holds a symbol changes; the story does not. A stale flag would
        // mis-file the headline under "My stocks".
        db.cacheNews(listOf(news("a", owned = false)))
        db.cacheNews(listOf(news("a", owned = true)))
        val rows = db.cachedNews()
        assertEquals(1, rows.size)
        assertTrue("owned should have been updated in place", rows[0].owned)
    }

    @Test fun `news older than a month is purged and newer news is kept`() {
        val now = 1_756_000_000_000L
        // Retention is measured on first_seen, not published, so the story is aged by the
        // clock it was STORED under rather than by back-dating the headline itself.
        db.cacheNews(listOf(news("fresh")), now = now)
        db.cacheNews(listOf(news("stale")), now = now - 40L * 24 * 3600 * 1000)
        assertEquals(2, db.cachedNews().size)
        assertEquals("only the month-old row should go", 1, db.purgeOldNews(now))
        assertEquals(listOf("Headline fresh"), db.cachedNews().map { it.title })
    }

    @Test fun `a story just under the retention limit survives`() {
        val now = 1_756_000_000_000L
        db.cacheNews(listOf(news("edge")), now = now - 30L * 24 * 3600 * 1000)
        assertEquals(0, db.purgeOldNews(now))
        assertEquals(1, db.cachedNews().size)
    }

    @Test fun `retention ignores a wrong published date`() {
        // A publisher emitting 1970 (or four hours in the future, which v5.9 actually found)
        // must not be able to delete its own story on arrival, nor keep it forever.
        val now = 1_756_000_000_000L
        db.cacheNews(
            listOf(news("ancient", pub = 0L), news("future", pub = 4_000_000_000_000L)),
            now = now
        )
        assertEquals("neither should be purged the moment it arrives", 0, db.purgeOldNews(now))
        assertEquals(2, db.cachedNews().size)
    }

    @Test fun `per-symbol cached news is scoped and excludes filings`() {
        db.cacheNews(listOf(
            news("n1", sym = "NVDA"), news("n2", sym = "FIVE"),
            news("f1", sym = "NVDA", kind = FeedItem.INSIDER)
        ))
        val nvda = db.cachedNewsFor("NVDA")
        assertEquals(1, nvda.size)
        assertEquals("Headline n1", nvda[0].title)
    }

    @Test fun `a blank title is never cached`() {
        val blank = news("x").copy(title = "")
        assertEquals(0, db.cacheNews(listOf(blank)))
        assertEquals(0, db.cachedNews().size)
    }

    @Test fun `the cache reports its size and can be emptied`() {
        db.cacheNews(listOf(news("a"), news("b")))
        assertEquals(2, db.newsCacheStats().first)
        db.clearNewsCache()
        assertEquals(0, db.newsCacheStats().first)
        assertEquals(0, db.cachedNews().size)
    }

    @Test fun `caching an empty list is a no-op`() {
        assertEquals(0, db.cacheNews(emptyList()))
        assertEquals(0, db.cachedNews().size)
    }

    @Test fun `reopening an upgraded database is idempotent`() {
        db.close(); ctx.deleteDatabase(Db.DB_NAME)
        buildLegacyV1(Db.DB_NAME)
        Db(ctx).also { it.allTxns(); it.close() }
        val second = Db(ctx)
        assertEquals(12, second.allTxns().size)
        assertEquals(0, second.importCount())
        second.close()
    }

    @Test fun `a downgrade keeps the data instead of throwing`() {
        db.insertTxn(buy("NVDA", 1.0, 100.0, day(2026, 9, 1)))
        db.close()
        // stamp a FUTURE schema version on the file, as an older APK would find
        val raw = SQLiteDatabase.openDatabase(
            ctx.getDatabasePath(Db.DB_NAME).path, null, SQLiteDatabase.OPEN_READWRITE
        )
        raw.version = 99
        raw.close()
        val reopened = Db(ctx)      // the SQLiteOpenHelper default would throw here
        assertEquals("a downgrade lost the ledger", 1, reopened.allTxns().size)
        reopened.close()
    }

    // ------------------------------------------------------------- quote cache

    @Test fun `a null extended price survives the cache instead of becoming zero`() {
        db.cacheQuote(Quote(symbol = "NVDA", price = 100.0, prevClose = 99.0, extPrice = null))
        assertNull(db.cachedQuotes()["NVDA"]!!.extPrice)
        db.cacheQuote(Quote(symbol = "NVDA", price = 100.0, prevClose = 99.0, extPrice = 101.5))
        assertEquals(101.5, db.cachedQuotes()["NVDA"]!!.extPrice!!, 1e-9)
        // and back to null again - CONFLICT_REPLACE must clear the old value, not keep it
        db.cacheQuote(Quote(symbol = "NVDA", price = 100.0, prevClose = 99.0, extPrice = null))
        assertNull("a stale extended price survived the next refresh",
            db.cachedQuotes()["NVDA"]!!.extPrice)
    }

    @Test fun `cacheQuotes writes the whole batch`() {
        db.cacheQuotes((1..20).map { Quote(symbol = "S$it", price = it.toDouble()) })
        assertEquals(20, db.cachedQuotes().size)
    }

    @Test fun `every cached quote comes back marked stale`() {
        db.cacheQuote(Quote(symbol = "NVDA", price = 100.0, stale = false))
        assertTrue(db.cachedQuotes()["NVDA"]!!.stale)
    }

    // --------------------------------------------------------------- settings

    @Test fun `hasSetting tells a stored blank from an absent key`() {
        assertFalse(db.hasSetting(Keys.CASH_OVERRIDE))
        db.set(Keys.CASH_OVERRIDE, "")
        assertTrue("a stored blank read as absent - merge would overwrite it",
            db.hasSetting(Keys.CASH_OVERRIDE))
        assertEquals("", db.get(Keys.CASH_OVERRIDE))
    }

    @Test fun `getB and getD fall back cleanly on junk`() {
        assertTrue(db.getB(Keys.AUTO_BACKUP, true))
        db.set(Keys.AUTO_BACKUP, "nonsense")
        assertFalse(db.getB(Keys.AUTO_BACKUP, true))     // anything but "1" is false
        db.setB(Keys.AUTO_BACKUP, true)
        assertTrue(db.getB(Keys.AUTO_BACKUP, false))
        db.set(Keys.CASH_OVERRIDE, "not a number")
        assertEquals(42.0, db.getD(Keys.CASH_OVERRIDE, 42.0), 1e-9)
    }

    @Test fun `latestTxnDate is the newest date, and 0 on an empty ledger`() {
        assertEquals(0L, db.latestTxnDate())
        db.insertTxn(buy("A", 1.0, 1.0, day(2026, 1, 5)))
        db.insertTxn(buy("B", 1.0, 1.0, day(2026, 9, 4)))
        db.insertTxn(buy("C", 1.0, 1.0, day(2026, 4, 4)))
        assertEquals(day(2026, 9, 4), db.latestTxnDate())
    }

    @Test fun `symbols are stored uppercase however they arrive`() {
        db.insertTxn(buy("nvda", 1.0, 100.0, day(2026, 9, 1)))
        assertEquals("NVDA", db.allTxns()[0].symbol)
        db.addWatch("aapl")
        assertEquals(listOf("AAPL"), db.watchlist())
        db.setOverride(Override("csco", avgCost = 50.0, shares = null))
        assertNotNull(db.overrides()["CSCO"])
    }

    // ------------------------------------------------------ recommendation cache

    private fun rec(symbol: String, dayKey: String, verdict: TradeVerdict = TradeVerdict.HOLD) =
        Recommendation(
            symbol = symbol, verdict = verdict, score = 55,
            reasons = listOf("a reason", "another reason"), confidence = 80,
            targetMean = 123.45, targetHigh = 150.0, targetLow = 100.0, analystCount = 12,
            price = 111.11, dayKey = dayKey, computedAt = 1_757_000_000_000L
        )

    @Test fun `a cached recommendation round-trips every field`() {
        db.cacheRecommendation(rec("NVDA", "20260911"))
        val back = db.cachedRecommendation("NVDA")!!
        assertEquals("NVDA", back.symbol)
        assertEquals(TradeVerdict.HOLD, back.verdict)
        assertEquals(55, back.score)
        assertEquals(listOf("a reason", "another reason"), back.reasons)
        assertEquals(80, back.confidence)
        assertEquals(123.45, back.targetMean, 1e-9)
        assertEquals(150.0, back.targetHigh, 1e-9)
        assertEquals(100.0, back.targetLow, 1e-9)
        assertEquals(12, back.analystCount)
        assertEquals(111.11, back.price, 1e-9)
        assertEquals("20260911", back.dayKey)
    }

    @Test fun `a missing recommendation reads as null, not as an exception`() {
        assertNull(db.cachedRecommendation("GHOST"))
    }

    @Test fun `caching again for the same symbol replaces, not duplicates`() {
        db.cacheRecommendation(rec("AAPL", "20260910", TradeVerdict.SELL))
        db.cacheRecommendation(rec("AAPL", "20260911", TradeVerdict.BUY))
        val back = db.cachedRecommendation("AAPL")!!
        assertEquals("20260911", back.dayKey)
        assertEquals(TradeVerdict.BUY, back.verdict)
    }

    @Test fun `symbols are uppercased on write and on read`() {
        db.cacheRecommendation(rec("tsla", "20260911"))
        assertNotNull(db.cachedRecommendation("TSLA"))
        assertNotNull(db.cachedRecommendation("tsla"))
    }

    @Test fun `the recommendation kind does not collide with holdings or core fundamentals`() {
        db.cacheRecommendation(rec("SPY", "20260911"))
        assertNull(db.cachedFundamentals("SPY", Keys.KIND_CORE))
        assertNull(db.cachedHoldings("SPY"))
        assertNotNull(db.cachedRecommendation("SPY"))
    }

    @Test fun `purgeFundamentals ages out a stale recommendation like any other kind`() {
        val stale = rec("OLD", "20260101").copy(computedAt = 1L)
        db.cacheRecommendation(stale)
        assertNotNull(db.cachedRecommendation("OLD"))
        db.purgeFundamentals(olderThanMs = 1000L)
        assertNull(db.cachedRecommendation("OLD"))
    }
}
