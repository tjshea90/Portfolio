package com.tj.portfolio.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val ctx: Context = context.applicationContext

    companion object {
        const val DB_NAME = "portfolio.db"
        /** Bump only alongside an additive block in onUpgrade. */
        const val DB_VERSION = 7
        const val BACKUP_FORMAT = "tj-portfolio-backup"
        const val BACKUP_VERSION = 2
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE txns(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                symbol TEXT,
                quantity REAL NOT NULL DEFAULT 0,
                price REAL NOT NULL DEFAULT 0,
                amount REAL NOT NULL DEFAULT 0,
                fees REAL NOT NULL DEFAULT 0,
                date INTEGER NOT NULL,
                note TEXT,
                source TEXT NOT NULL DEFAULT 'MANUAL'
            )"""
        )
        db.execSQL("CREATE INDEX idx_txn_symbol ON txns(symbol)")
        db.execSQL("CREATE INDEX idx_txn_date ON txns(date)")
        db.execSQL("CREATE TABLE settings(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE overrides(symbol TEXT PRIMARY KEY, avg_cost REAL, shares REAL)")
        db.execSQL(
            """CREATE TABLE quotes(
                symbol TEXT PRIMARY KEY, name TEXT, price REAL, prev_close REAL,
                day_high REAL, day_low REAL, ext_price REAL, ext_label TEXT,
                market_state TEXT, spark TEXT, currency TEXT, updated INTEGER,
                quote_time INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE TABLE watchlist(symbol TEXT PRIMARY KEY, added INTEGER)")
        createImports(db)
        createNews(db)
        createFundamentals(db)
    }

    /**
     * The fundamentals and analyst-ratings cache (db v5).
     *
     * WHY IT EXISTS. Opening a stock fires a request that can return 200KB of analyst
     * history, and the numbers behind it change once a quarter. Without a cache, every
     * open of every stock re-fetched all of it - which is both slow to look at and the
     * fastest way to get an IP rate-limited by a provider that has no idea the app is one
     * person's phone. With it, the screen paints instantly from disk and the network is
     * only asked when the row is genuinely stale.
     *
     * Two kinds share one table because they have different lifetimes: [KIND_CORE] is the
     * valuation and balance-sheet numbers, good for hours, and [KIND_RATINGS] is the
     * analyst actions, which arrive through the trading day.
     *
     * The payload is a JSON blob rather than columns - see FundamentalsJson for why.
     */
    private fun createFundamentals(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS fundamentals(
                symbol TEXT NOT NULL,
                kind TEXT NOT NULL,
                json TEXT NOT NULL,
                fetched INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(symbol, kind)
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fund_fetched ON fundamentals(fetched)")
    }

    /** Additive, and safe to re-run: a duplicate-column error is the success case. */
    private fun addColumn(db: SQLiteDatabase, table: String, col: String, decl: String) {
        runCatching { db.execSQL("ALTER TABLE $table ADD COLUMN $col $decl") }
    }

    /**
     * The headline cache (db v4).
     *
     * WHY THIS EXISTS. Until v6.3 every headline the app had ever shown lived only in memory.
     * Three consequences TJ hit directly:
     *   - leaving the app and coming back showed an EMPTY feed, because the memory-trim
     *     handler released the list and there was nothing to restore it from;
     *   - every refresh re-fetched and re-published the whole feed rather than adding what
     *     was new, so the list flickered and the work was repeated;
     *   - nothing survived the process being killed, which for a backgrounded app is normal
     *     rather than exceptional.
     * With the rows on disk the feed is drawn from the database and the network only ever
     * ADDS to it.
     *
     * `id` is [FeedItem.id] - the same expression used for de-duplication and as the
     * LazyColumn key - so INSERT OR IGNORE gives exact de-duplication against everything
     * ever seen, not just against the current page. That is what makes "only fetch what is
     * new" true rather than approximate.
     *
     * `first_seen` is when THIS APP first saw the story, which is deliberately not
     * `published`: retention has to be driven by something monotonic that the app controls,
     * because publishers emit wrong dates (a v5.9 bug had Investing.com four hours in the
     * future) and a story dated 1970 would otherwise be purged the instant it arrived.
     */
    private fun createNews(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS news_cache(
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL DEFAULT 'NEWS',
                symbol TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT '',
                published INTEGER NOT NULL DEFAULT 0,
                owned INTEGER NOT NULL DEFAULT 0,
                uid TEXT NOT NULL DEFAULT '',
                summary TEXT NOT NULL DEFAULT '',
                first_seen INTEGER NOT NULL DEFAULT 0
            )"""
        )
        // The feed is always read newest-first, and the purge is always by first_seen.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_news_published ON news_cache(published)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_news_seen ON news_cache(first_seen)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_news_symbol ON news_cache(symbol)")
    }

    /**
     * THE HTTP RESPONSE CACHE (db v6, Round 56).
     *
     * TJ's rule for this round, in his own words: *"any data pulled from online should be
     * cached when needed, storage is not a concern, nothing should need to be reloaded from
     * the Web if it's already stored in the app"*.
     *
     * Until now the conditional-GET cache - the ETag and Last-Modified validators that turn a
     * re-fetch into a bodyless 304 - lived in a bounded in-memory `LinkedHashMap` inside
     * `Http`. Three things followed from that, all of them bad:
     *
     *   - it was sized for 48 entries against a real working set of 24 symbols x up to 3 RSS
     *     feeds plus 7 market feeds = 79 URLs, so it thrashed and most feeds never got to
     *     send a validator at all;
     *   - `onLowMemory` dropped the lot, which is the correct thing to do with a heap cache
     *     and means every feed came back in full afterwards;
     *   - and NOTHING survived the process being killed, which for a backgrounded Android app
     *     is the normal case rather than the exception. Every cold start re-downloaded every
     *     feed in full.
     *
     * On disk none of that applies. A validator is a few dozen bytes and a feed body is tens
     * of kilobytes; a generous cache here is a few megabytes against a phone with gigabytes,
     * and it converts a cold start from "re-download everything" into "ask seven servers
     * whether anything changed".
     *
     * `url` is the primary key because that is exactly the identity a conditional GET has.
     * `fetched` drives retention; `bytes` lets the size bound be enforced without measuring
     * every row.
     */
    private fun createHttpCache(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS http_cache(
                url TEXT PRIMARY KEY,
                etag TEXT NOT NULL DEFAULT '',
                last_modified TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '',
                fetched INTEGER NOT NULL DEFAULT 0,
                bytes INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_http_fetched ON http_cache(fetched)")
    }

    /**
     * THE PRICE-CHART CACHE (db v7, Round 58).
     *
     * TJ: *"when I switched apps from the tracker then switched back, the stock charts
     * disappeared... if they reload every time, this is unnecessary. cache data that can be
     * cached and only periodically refresh automatically, but refresh every time I gesture
     * pull down."* This table is the "cache" half of that sentence; the TTLs on
     * [com.tj.portfolio.data.ChartRange] are the "periodically", and `loadChart(force = true)`
     * from pull-to-refresh is the "every time I pull down".
     *
     * WHY A TABLE AND NOT A SETTINGS ROW. Unlike the research and insider caches - one
     * document each, replaced wholesale - this is queried by (symbol, range) and there can
     * be one row per tracked symbol per range. It also has to be purgeable oldest-first,
     * which needs an index on `fetched`.
     *
     * WHY IT IS SAFE UNDER THE PROJECT'S UPGRADE RULE. It is purely additive: a CREATE TABLE
     * IF NOT EXISTS with no bearing on any table holding user data. Everything in it is
     * derived - the worst a total loss can cost is one re-fetch per chart - so it is excluded
     * from the JSON backup for the same reason the other derived caches are.
     *
     * A five-year monthly series is a few hundred points; a full intraday day is about four
     * hundred. At roughly 6 KB a row and a couple of hundred rows in the worst case, the
     * whole table is single-digit megabytes, which is the trade TJ asked for explicitly:
     * storage is not a concern, re-downloading something already held is.
     */
    // NOT NAMED `range`. That is a reserved word in SQLite's window-frame syntax, and
    // whether an unquoted one parses is a property of whichever SQLite the device ships,
    // not of this file. The table is new, so there is no migration cost to simply not
    // finding out the hard way on a future Android release.
    private fun createChartCache(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chart_cache(
                symbol TEXT NOT NULL,
                range_key TEXT NOT NULL,
                json TEXT NOT NULL,
                fetched INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(symbol, range_key)
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chart_fetched ON chart_cache(fetched)")
    }

    private fun createImports(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS imports(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                skipped INTEGER NOT NULL DEFAULT 0,
                min_date INTEGER NOT NULL DEFAULT 0,
                max_date INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'SCREENSHOT'
            )"""
        )
    }

    /**
     * UPGRADES ARE ADDITIVE ONLY. Installing a newer APK over an older one keeps every
     * row: nothing here drops, renames or recreates a table that holds user data.
     * Each step is also idempotent, so a half-applied upgrade can safely run again.
     *
     * Rule for future versions: bump DB_VERSION, add another `if (oldV < n)` block that
     * only CREATEs or ALTER-TABLE-ADD-COLUMNs. Never DROP.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) createImports(db)
        if (oldV < 3) addColumn(db, "quotes", "quote_time", "INTEGER NOT NULL DEFAULT 0")
        if (oldV < 4) createNews(db)
        if (oldV < 5) createFundamentals(db)
        if (oldV < 6) createHttpCache(db)
        if (oldV < 7) createChartCache(db)
        // future: if (oldV < 8) { ...additive changes only... }
    }

    /**
     * Android normally refuses to install an older versionCode, but if a downgrade ever
     * happens the default behaviour is to THROW and crash on first launch. Doing nothing
     * keeps the existing data readable instead.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldV: Int, newV: Int) { /* keep the data */ }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // belt and braces: make sure every table exists even if an upgrade was interrupted
        runCatching { createImports(db) }
        runCatching { createNews(db) }
        runCatching { createFundamentals(db) }
        runCatching { createHttpCache(db) }
        runCatching { createChartCache(db) }
    }

    // ---------- HTTP response cache (Round 56) ----------

    /**
     * Total characters of cached body allowed on disk.
     *
     * ~24 MB of UTF-8 text at the outside, against a table that pays for itself the first
     * time a cold start answers 304 instead of re-downloading eighty feeds. TJ's instruction
     * for this round was that storage is not a concern; this is still bounded, because an
     * unbounded cache is a bug rather than a feature, but the bound is set by what is useful
     * rather than by what a heap can hold.
     */
    private val HTTP_CACHE_MAX_CHARS = 24_000_000L

    /** Entries older than this are dropped: a validator for a feed nobody reads is dead weight. */
    private val HTTP_CACHE_RETENTION_MS = 30L * 86_400_000L

    /** Validators and the last body for one URL, or null. */
    fun httpCached(url: String): HttpCacheRow? = runCatching {
        readableDatabase.rawQuery(
            "SELECT etag,last_modified,body FROM http_cache WHERE url=?", arrayOf(url)
        ).use { c ->
            if (!c.moveToNext()) null
            else HttpCacheRow(
                etag = c.getString(0).orEmpty(),
                lastModified = c.getString(1).orEmpty(),
                body = c.getString(2).orEmpty()
            )
        }
    }.getOrNull()

    fun httpStore(url: String, etag: String, lastModified: String, body: String) {
        runCatching {
            writableDatabase.insertWithOnConflict(
                "http_cache", null,
                ContentValues().apply {
                    put("url", url)
                    put("etag", etag)
                    put("last_modified", lastModified)
                    put("body", body)
                    put("fetched", System.currentTimeMillis())
                    put("bytes", body.length)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    /**
     * Touch a row that answered 304, so retention measures LAST USE rather than last change.
     *
     * Without this a feed that is genuinely stable - which is exactly the kind worth caching -
     * ages out of the table after a month of successful 304s and has to be downloaded again
     * in full, which is the opposite of what the cache is for.
     */
    fun httpTouch(url: String) {
        runCatching {
            writableDatabase.execSQL(
                "UPDATE http_cache SET fetched=? WHERE url=?",
                arrayOf<Any>(System.currentTimeMillis(), url)
            )
        }
    }

    /** Age and size bounds, run once per launch alongside the other purges. */
    fun purgeHttpCache(now: Long = System.currentTimeMillis()): Int = runCatching {
        val db = writableDatabase
        var removed = db.delete(
            "http_cache", "fetched < ?", arrayOf((now - HTTP_CACHE_RETENTION_MS).toString())
        )
        val total = db.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM http_cache", null)
            .use { c -> if (c.moveToNext()) c.getLong(0) else 0L }
        if (total > HTTP_CACHE_MAX_CHARS) {
            // Oldest-first until the total is back under budget. One statement rather than a
            // loop: SQLite can do the running total itself, and the alternative is a cursor
            // walk over a table that may hold thousands of rows.
            removed += db.delete(
                "http_cache",
                "url IN (SELECT url FROM http_cache ORDER BY fetched ASC LIMIT " +
                    "MAX(1, (SELECT COUNT(*) FROM http_cache) / 4))",
                null
            )
        }
        removed
    }.getOrDefault(0)

    fun httpCacheStats(): Pair<Int, Long> = runCatching {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(bytes),0) FROM http_cache", null
        ).use { c -> if (c.moveToNext()) c.getInt(0) to c.getLong(1) else 0 to 0L }
    }.getOrDefault(0 to 0L)

    /** Drop one URL whose stored validators can no longer be trusted. See `Http.get`. */
    fun httpForget(url: String) {
        runCatching { writableDatabase.delete("http_cache", "url=?", arrayOf(url)) }
    }

    fun clearHttpCache() { runCatching { writableDatabase.delete("http_cache", null, null) } }

    // ---------- fundamentals / analyst ratings cache ----------

    /**
     * Write one provider result for a symbol. `INSERT OR REPLACE` because unlike headlines
     * this is a single current snapshot, not an accumulating list - last write wins.
     */
    fun cacheFundamentals(symbol: String, kind: String, f: Fundamentals) {
        runCatching {
            writableDatabase.insertWithOnConflict(
                "fundamentals", null,
                ContentValues().apply {
                    put("symbol", symbol.uppercase())
                    put("kind", kind)
                    put("json", FundamentalsJson.toJson(f))
                    put("fetched", if (f.fetched > 0) f.fetched else System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    /**
     * Read back one cached result. Returns null when there is no row, and ALSO when the row
     * will not parse - a corrupt blob has to look like a cache miss, not like an empty
     * answer, or the screen would show "no data reported" forever and never refetch.
     */
    fun cachedFundamentals(symbol: String, kind: String): Fundamentals? = runCatching {
        readableDatabase.rawQuery(
            "SELECT json, fetched FROM fundamentals WHERE symbol=? AND kind=?",
            arrayOf(symbol.uppercase(), kind)
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val f = FundamentalsJson.fromJson(c.getString(0)) ?: return@use null
            val fetched = c.getLong(1)
            if (f.fetched > 0) f else f.copy(fetched = fetched)
        }
    }.getOrNull()

    /**
     * The fund-holdings cache (Round 58).
     *
     * Rides the EXISTING `fundamentals` table under its own [Keys.KIND_HOLDINGS] kind rather
     * than adding a fifth table. It has exactly the same shape as the two rows already there
     * - one current snapshot per symbol, replaced wholesale, read by (symbol, kind) - so a
     * new table would have bought a schema migration and nothing else, and `onUpgrade` is
     * the one part of this app where a mistake destroys user data.
     *
     * It also inherits the retention and purge the other kinds already have, for free.
     */
    fun cacheHoldings(h: FundHoldings) {
        runCatching {
            writableDatabase.insertWithOnConflict(
                "fundamentals", null,
                ContentValues().apply {
                    put("symbol", h.symbol.uppercase())
                    put("kind", Keys.KIND_HOLDINGS)
                    put("json", HoldingsJson.encode(h))
                    put("fetched", if (h.fetched > 0) h.fetched else System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    /** Null for a missing row AND for an unreadable one - both must read as "fetch it". */
    fun cachedHoldings(symbol: String): FundHoldings? = runCatching {
        readableDatabase.rawQuery(
            "SELECT json, fetched FROM fundamentals WHERE symbol=? AND kind=?",
            arrayOf(symbol.uppercase(), Keys.KIND_HOLDINGS)
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val h = HoldingsJson.decode(c.getString(0)) ?: return@use null
            if (h.fetched > 0) h else h.copy(fetched = c.getLong(1))
        }
    }.getOrNull()

    /** Rows older than [olderThanMs] are dropped. Called on the same schedule as the news purge. */
    fun purgeFundamentals(olderThanMs: Long = 30L * 86_400_000L): Int = runCatching {
        writableDatabase.delete(
            "fundamentals", "fetched < ?",
            arrayOf((System.currentTimeMillis() - olderThanMs).toString())
        )
    }.getOrDefault(0)

    /** Row count and total payload size, for the Settings diagnostics card. */
    fun fundamentalsCacheStats(): Pair<Int, Long> = runCatching {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(LENGTH(json)),0) FROM fundamentals", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else 0 to 0L }
    }.getOrDefault(0 to 0L)

    // ---------- import history ----------

    /** Records one screenshot/file import so the app can say where to resume from. */
    fun recordImport(count: Int, skipped: Int, minDate: Long, maxDate: Long, source: String) {
        val cv = ContentValues().apply {
            put("at", System.currentTimeMillis())
            put("count", count); put("skipped", skipped)
            put("min_date", minDate); put("max_date", maxDate)
            put("source", source)
        }
        writableDatabase.insert("imports", null, cv)
    }

    data class ImportLog(
        val at: Long, val count: Int, val skipped: Int,
        val minDate: Long, val maxDate: Long, val source: String
    )

    fun lastImport(): ImportLog? {
        readableDatabase.rawQuery("SELECT * FROM imports ORDER BY at DESC LIMIT 1", null).use { c ->
            if (!c.moveToFirst()) return null
            return ImportLog(
                c.getLong(c.getColumnIndexOrThrow("at")),
                c.getInt(c.getColumnIndexOrThrow("count")),
                c.getInt(c.getColumnIndexOrThrow("skipped")),
                c.getLong(c.getColumnIndexOrThrow("min_date")),
                c.getLong(c.getColumnIndexOrThrow("max_date")),
                c.getString(c.getColumnIndexOrThrow("source")) ?: ""
            )
        }
    }

    fun importCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM imports", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Newest transaction date on record - the real "you have data through here" marker. */
    fun latestTxnDate(): Long {
        readableDatabase.rawQuery("SELECT MAX(date) FROM txns", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    // ---------- transactions ----------

    fun insertTxn(t: Txn): Long {
        val cv = ContentValues().apply {
            put("type", t.type)
            put("symbol", t.symbol?.uppercase())
            put("quantity", t.quantity)
            put("price", t.price)
            put("amount", t.amount)
            put("fees", t.fees)
            put("date", t.date)
            put("note", t.note)
            put("source", t.source)
        }
        return writableDatabase.insert("txns", null, cv)
    }

    fun updateTxn(t: Txn) {
        val cv = ContentValues().apply {
            put("type", t.type)
            put("symbol", t.symbol?.uppercase())
            put("quantity", t.quantity)
            put("price", t.price)
            put("amount", t.amount)
            put("fees", t.fees)
            put("date", t.date)
            put("note", t.note)
            put("source", t.source)
        }
        writableDatabase.update("txns", cv, "id=?", arrayOf(t.id.toString()))
    }

    fun deleteTxn(id: Long) = writableDatabase.delete("txns", "id=?", arrayOf(id.toString()))

    fun deleteAllTxns() = writableDatabase.delete("txns", null, null)

    /**
     * Every transaction for one symbol, in a single indexed DELETE.
     *
     * The caller used to read the entire transactions table into memory, filter it in Kotlin
     * and then issue one DELETE per matching id - each its own implicit transaction. This is
     * one statement against `idx_txn_symbol`, and it returns the row count the caller needs.
     *
     * Compared with a bare `symbol=?` so the index is actually usable; every write path
     * ([insertTxn], [updateTxn], and restore through them) stores the symbol uppercased, so
     * there is nothing in the column for a case-insensitive comparison to catch.
     */
    fun deleteTxnsForSymbol(symbol: String): Int =
        writableDatabase.delete("txns", "symbol=?", arrayOf(symbol.uppercase()))

    /** Cheap existence/size check - allTxns() materialises every row. */
    fun txnCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM txns", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun allTxns(): List<Txn> {
        val out = ArrayList<Txn>()
        readableDatabase.rawQuery("SELECT * FROM txns ORDER BY date ASC, id ASC", null).use { c ->
            while (c.moveToNext()) out.add(c.toTxn())
        }
        return out
    }

    private fun Cursor.toTxn() = Txn(
        id = getLong(getColumnIndexOrThrow("id")),
        type = getString(getColumnIndexOrThrow("type")),
        symbol = getStringOrNull("symbol"),
        quantity = getDouble(getColumnIndexOrThrow("quantity")),
        price = getDouble(getColumnIndexOrThrow("price")),
        amount = getDouble(getColumnIndexOrThrow("amount")),
        fees = getDouble(getColumnIndexOrThrow("fees")),
        date = getLong(getColumnIndexOrThrow("date")),
        note = getStringOrNull("note"),
        source = getString(getColumnIndexOrThrow("source"))
    )

    private fun Cursor.getStringOrNull(col: String): String? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getString(i)
    }

    /**
     * Does this transaction already exist?
     *
     * Exact matching alone is not enough. The same trade read from two different Ally
     * screenshots can come back with a price that rounds differently (Ally shows only the
     * net amount, so price is derived as amount/quantity), or an amount off by a cent. A
     * strict comparison then treats it as new and the position silently doubles.
     *
     * So a row counts as already-present when the things that genuinely identify a trade
     * match: same type, same symbol, same calendar day, same share count, and a money
     * amount within a small tolerance. Cash movements match on type, day and amount.
     */
    fun txnExists(t: Txn): Boolean = findDuplicateId(t) != null

    fun findDuplicateId(t: Txn): Long? {
        val sym = (t.symbol ?: "").uppercase()
        val dayStart = startOfDay(t.date)
        val dayEnd = dayStart + 86_400_000L
        val amt = kotlin.math.abs(t.amount)
        // a cent of slack, or 0.5% on larger trades, whichever is bigger
        val tol = maxOf(0.02, amt * 0.005)

        // `IFNULL(symbol,'')=?` MADE `idx_txn_symbol` UNUSABLE. Wrapping an indexed column in
        // a function forces SQLite to evaluate it for every row, so this was a full table
        // scan - and `restoreJson` calls it once per incoming row, which turns merging a
        // 2,000-transaction backup into roughly four million row visits inside a single
        // transaction. `deleteTxnsForSymbol` already carried a comment about using a bare
        // `symbol=?` "so the index is actually usable"; these two never got the same
        // treatment. The NULL case is handled by an explicit OR rather than by a function, so
        // the index is used for the common path and correctness is unchanged.
        //
        // Also: the old `StringBuilder` was never appended to, and the query selected `price`
        // (column 2) which the loop below never reads.
        val symClause = if (sym.isEmpty()) "(symbol=? OR symbol IS NULL)" else "symbol=?"
        readableDatabase.rawQuery(
            "SELECT id, quantity, amount FROM txns WHERE type=? " +
                "AND $symClause AND date>=? AND date<?",
            arrayOf(t.type, sym, dayStart.toString(), dayEnd.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val q = c.getDouble(1)
                val a = kotlin.math.abs(c.getDouble(2))
                val sameQty = kotlin.math.abs(q - kotlin.math.abs(t.quantity)) < 0.0001
                val sameAmt = kotlin.math.abs(a - amt) <= tol
                if (sameQty && sameAmt) return id
                // a share-count match on the same day for the same symbol is already a
                // strong signal; accept it when the amounts are within a dollar
                if (sameQty && q > 0 && kotlin.math.abs(a - amt) <= 1.0) return id
            }
        }
        return null
    }

    /**
     * Duplicate check that ignores the DATE, for rows whose date the importer had to guess.
     *
     * A screenshot row with no readable date - and every row from a holdings/positions
     * screen, which has no date at all - is stamped with today. Re-importing that same
     * screenshot on a later day produced a different "today", so the day-scoped check above
     * saw a brand new transaction and the position silently doubled.
     *
     * Deliberately stricter per row than [findDuplicateId]: a symbol is required and both
     * the share count and the money have to line up. There is no one-dollar fallback here,
     * because without a date that would be too eager.
     */
    fun findDuplicateIdAnyDate(t: Txn): Long? {
        val sym = (t.symbol ?: "").uppercase()
        if (sym.isBlank()) return null
        val amt = kotlin.math.abs(t.amount)
        val tol = maxOf(0.02, amt * 0.005)
        readableDatabase.rawQuery(
            // Same index fix as [findDuplicateId]. `sym` is non-blank here (checked above),
            // so a bare equality is both correct and index-usable.
            "SELECT id, quantity, amount FROM txns WHERE type=? AND symbol=?",
            arrayOf(t.type, sym)
        ).use { c ->
            while (c.moveToNext()) {
                val q = c.getDouble(1)
                val a = kotlin.math.abs(c.getDouble(2))
                val sameQty = kotlin.math.abs(q - kotlin.math.abs(t.quantity)) < 0.0001
                if (sameQty && kotlin.math.abs(a - amt) <= tol) return c.getLong(0)
            }
        }
        return null
    }

    private fun startOfDay(ms: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------- settings ----------

    fun get(key: String, def: String = ""): String {
        readableDatabase.rawQuery("SELECT v FROM settings WHERE k=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) ?: def else def
        }
    }

    fun set(key: String, value: String) {
        val cv = ContentValues().apply { put("k", key); put("v", value) }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Is this key stored at all? Distinct from [get] returning "", which a stored blank does too. */
    fun hasSetting(key: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM settings WHERE k=? LIMIT 1", arrayOf(key)).use { c ->
            return c.moveToFirst()
        }
    }

    fun getD(key: String, def: Double): Double = get(key).toDoubleOrNull() ?: def
    fun getB(key: String, def: Boolean): Boolean = get(key).let { if (it.isEmpty()) def else it == "1" }
    fun setB(key: String, v: Boolean) = set(key, if (v) "1" else "0")

    // ---------- overrides ----------

    fun overrides(): Map<String, Override> {
        val m = HashMap<String, Override>()
        readableDatabase.rawQuery("SELECT * FROM overrides", null).use { c ->
            while (c.moveToNext()) {
                val sym = c.getString(0)
                m[sym] = Override(
                    sym,
                    if (c.isNull(1)) null else c.getDouble(1),
                    if (c.isNull(2)) null else c.getDouble(2)
                )
            }
        }
        return m
    }

    fun setOverride(o: Override) {
        val cv = ContentValues().apply {
            put("symbol", o.symbol.uppercase())
            if (o.avgCost != null) put("avg_cost", o.avgCost) else putNull("avg_cost")
            if (o.shares != null) put("shares", o.shares) else putNull("shares")
        }
        writableDatabase.insertWithOnConflict("overrides", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearOverride(symbol: String) =
        writableDatabase.delete("overrides", "symbol=?", arrayOf(symbol.uppercase()))

    // ---------- quote cache ----------

    fun cacheQuote(q: Quote) {
        val cv = ContentValues().apply {
            put("symbol", q.symbol); put("name", q.name); put("price", q.price)
            put("prev_close", q.prevClose); put("day_high", q.dayHigh); put("day_low", q.dayLow)
            if (q.extPrice != null) put("ext_price", q.extPrice) else putNull("ext_price")
            put("ext_label", q.extLabel); put("market_state", q.marketState)
            put("spark", JSONArray(q.spark).toString()); put("currency", q.currency)
            put("quote_time", q.quoteTime)
            put("updated", q.updated)
        }
        writableDatabase.insertWithOnConflict("quotes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Whole refresh batch in one transaction instead of one write per symbol. */
    // ---------- headline cache (db v4) ----------

    /** How long a cached headline is kept. TJ asked for "anything over a month". */
    private val NEWS_RETENTION_MS = 31L * 24 * 60 * 60 * 1000

    /**
     * Add headlines, keeping everything already stored.
     *
     * INSERT OR IGNORE on [FeedItem.id] is the whole trick: the id is the same expression the
     * feed de-duplicates and keys its list by, so a story the app has seen before is silently
     * skipped and one it has not is added. That makes an incremental refresh exact rather
     * than approximate - the network result is merged into the cache instead of replacing it,
     * which is what stops the list being rebuilt from nothing every three minutes.
     *
     * `owned` is deliberately UPDATEd on a conflict while everything else is left alone: a
     * story does not change, but whether the user holds the symbol does, and a stale flag
     * would mis-file it under "My stocks".
     *
     * @return how many rows were genuinely new.
     */
    fun cacheNews(
        items: List<FeedItem>,
        summaries: Map<String, String> = emptyMap(),
        /** Injectable so retention can be tested without waiting a month. */
        now: Long = System.currentTimeMillis()
    ): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            val ins = db.compileStatement(
                "INSERT OR IGNORE INTO news_cache" +
                    "(id,kind,symbol,title,detail,url,source,published,owned,uid,summary,first_seen)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            val upd = db.compileStatement("UPDATE news_cache SET owned=? WHERE id=?")
            for (it in items) {
                if (it.title.isBlank()) continue
                ins.clearBindings()
                ins.bindString(1, it.id)
                ins.bindString(2, it.kind)
                ins.bindString(3, it.symbol)
                ins.bindString(4, it.title)
                ins.bindString(5, it.detail)
                ins.bindString(6, it.url)
                ins.bindString(7, it.source)
                ins.bindLong(8, it.published)
                ins.bindLong(9, if (it.owned) 1L else 0L)
                ins.bindString(10, it.uid)
                ins.bindString(11, summaries[it.id].orEmpty())
                ins.bindLong(12, now)
                if (ins.executeInsert() > 0) added++ else {
                    upd.clearBindings()
                    upd.bindLong(1, if (it.owned) 1L else 0L)
                    upd.bindString(2, it.id)
                    upd.executeUpdateDelete()
                }
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            // A cache is never worth crashing over; the feed still has its in-memory copy.
        } finally {
            runCatching { db.endTransaction() }
        }
        return added
    }

    /**
     * Everything cached, newest first, capped so a long-lived install cannot hand the UI an
     * unbounded list. [limit] is well above what any screen shows.
     */
    fun cachedNews(limit: Int = 600): List<FeedItem> {
        val out = ArrayList<FeedItem>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT * FROM news_cache ORDER BY published DESC, first_seen DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                val ki = c.getColumnIndexOrThrow("kind")
                val si = c.getColumnIndexOrThrow("symbol")
                val ti = c.getColumnIndexOrThrow("title")
                val di = c.getColumnIndexOrThrow("detail")
                val ui = c.getColumnIndexOrThrow("url")
                val soi = c.getColumnIndexOrThrow("source")
                val pi = c.getColumnIndexOrThrow("published")
                val oi = c.getColumnIndexOrThrow("owned")
                val uidi = c.getColumnIndexOrThrow("uid")
                while (c.moveToNext()) {
                    out.add(
                        FeedItem(
                            kind = c.getString(ki) ?: FeedItem.NEWS,
                            symbol = c.getString(si).orEmpty(),
                            title = c.getString(ti).orEmpty(),
                            detail = c.getString(di).orEmpty(),
                            url = c.getString(ui).orEmpty(),
                            source = c.getString(soi).orEmpty(),
                            published = c.getLong(pi),
                            owned = c.getLong(oi) != 0L,
                            uid = c.getString(uidi).orEmpty()
                        )
                    )
                }
            }
        }
        return out
    }

    /** Cached headlines for one symbol, newest first - what a detail screen opens with. */
    fun cachedNewsFor(symbol: String, limit: Int = 40): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT title,url,source,published,summary FROM news_cache " +
                    "WHERE symbol=? AND kind=? ORDER BY published DESC LIMIT ?",
                arrayOf(symbol.uppercase(), FeedItem.NEWS, limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        NewsItem(
                            symbol = symbol.uppercase(),
                            title = c.getString(0).orEmpty(),
                            url = c.getString(1).orEmpty(),
                            source = c.getString(2).orEmpty(),
                            published = c.getLong(3),
                            summary = c.getString(4).orEmpty()
                        )
                    )
                }
            }
        }
        return out
    }

    /**
     * Drop headlines the app first saw more than a month ago.
     *
     * Measured against `first_seen`, not `published`, on purpose - see [createNews]. A
     * publisher emitting a wrong date must not be able to delete a story the moment it
     * arrives, nor keep one forever.
     *
     * @return how many rows were removed.
     */
    fun purgeOldNews(now: Long = System.currentTimeMillis()): Int = runCatching {
        writableDatabase.delete(
            "news_cache", "first_seen < ?", arrayOf((now - NEWS_RETENTION_MS).toString())
        )
    }.getOrDefault(0)

    /** Row count and the oldest thing held, for the Settings diagnostics card. */
    fun newsCacheStats(): Pair<Int, Long> = runCatching {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(first_seen) FROM news_cache", null
        ).use { c -> if (c.moveToNext()) c.getInt(0) to c.getLong(1) else 0 to 0L }
    }.getOrDefault(0 to 0L)

    fun clearNewsCache() { runCatching { writableDatabase.delete("news_cache", null, null) } }

    fun cacheQuotes(quotes: List<Quote>) {
        if (quotes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            quotes.forEach { cacheQuote(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * COLUMN INDICES RESOLVED ONCE, NOT PER ROW (Round 57).
     *
     * `getColumnIndexOrThrow` is a linear scan of the column-name array, and this was calling
     * it twelve times PER ROW - and it runs synchronously in `init`, on the main thread,
     * before the first frame. `cachedNews` already hoists its lookups out of the loop and
     * says so; this one had been missed.
     */
    fun cachedQuotes(): Map<String, Quote> {
        val m = HashMap<String, Quote>()
        readableDatabase.rawQuery("SELECT * FROM quotes", null).use { c ->
            val iSym = c.getColumnIndexOrThrow("symbol")
            val iName = c.getColumnIndexOrThrow("name")
            val iPrice = c.getColumnIndexOrThrow("price")
            val iPrev = c.getColumnIndexOrThrow("prev_close")
            val iHigh = c.getColumnIndexOrThrow("day_high")
            val iLow = c.getColumnIndexOrThrow("day_low")
            val iExt = c.getColumnIndexOrThrow("ext_price")
            val iExtLabel = c.getColumnIndexOrThrow("ext_label")
            val iState = c.getColumnIndexOrThrow("market_state")
            val iSpark = c.getColumnIndexOrThrow("spark")
            val iCur = c.getColumnIndexOrThrow("currency")
            val iUpdated = c.getColumnIndexOrThrow("updated")
            // Added in db v3, so it may genuinely be absent on a very old row set.
            val iQuoteTime = c.getColumnIndex("quote_time")
            while (c.moveToNext()) {
                val sparkStr = c.getString(iSpark) ?: "[]"
                val arr = try { JSONArray(sparkStr) } catch (e: Exception) { JSONArray() }
                val spark = ArrayList<Double>(arr.length())
                for (i in 0 until arr.length()) spark.add(arr.optDouble(i, 0.0))
                val sym = c.getString(iSym)
                m[sym] = Quote(
                    symbol = sym,
                    name = c.getString(iName) ?: "",
                    price = c.getDouble(iPrice),
                    prevClose = c.getDouble(iPrev),
                    dayHigh = c.getDouble(iHigh),
                    dayLow = c.getDouble(iLow),
                    extPrice = if (c.isNull(iExt)) null else c.getDouble(iExt),
                    extLabel = c.getString(iExtLabel),
                    marketState = c.getString(iState) ?: "",
                    spark = spark,
                    currency = c.getString(iCur) ?: "USD",
                    quoteTime = if (iQuoteTime >= 0) c.getLong(iQuoteTime) else 0L,
                    updated = c.getLong(iUpdated),
                    stale = true
                )
            }
        }
        return m
    }

    // ---------- price-chart cache (db v7, Round 58) ----------

    /**
     * Total rows kept. Beyond this the oldest are purged.
     *
     * Sized from the real working set: ~24 tracked symbols x 8 ranges is 192, and only the
     * ranges actually opened are ever stored. 400 leaves headroom for a browsing session
     * through search results without letting the table grow without bound.
     */
    private val CHART_CACHE_MAX_ROWS = 400

    /** Read one cached series. Null when absent or unreadable - never an exception. */
    fun cachedChart(symbol: String, range: ChartRange): ChartSeries? = runCatching {
        readableDatabase.rawQuery(
            "SELECT json FROM chart_cache WHERE symbol=? AND range_key=? LIMIT 1",
            arrayOf(symbol.uppercase(), range.name)
        ).use { c -> if (c.moveToNext()) ChartJson.decode(c.getString(0)) else null }
    }.getOrNull()

    /**
     * Every cached series for one symbol, keyed by range.
     *
     * Read in ONE query rather than one per range. Opening a stock paints its chart from
     * disk before any network call, and doing that as eight separate queries on the main
     * thread was the shape of problem this project has fixed twice already.
     */
    fun cachedCharts(symbol: String): Map<ChartRange, ChartSeries> = runCatching {
        val out = HashMap<ChartRange, ChartSeries>()
        readableDatabase.rawQuery(
            "SELECT range_key, json FROM chart_cache WHERE symbol=?",
            arrayOf(symbol.uppercase())
        ).use { c ->
            while (c.moveToNext()) {
                val s = ChartJson.decode(c.getString(1)) ?: continue
                out[ChartRange.byName(c.getString(0))] = s
            }
        }
        out
    }.getOrDefault(emptyMap())

    /**
     * Store one series, replacing whatever was there.
     *
     * An EMPTY series is never written. A failed fetch must leave the last good chart on
     * disk: overwriting it with nothing turns one bad request into a permanently blank
     * chart, which is the exact failure this cache exists to prevent.
     */
    fun cacheChart(series: ChartSeries) {
        if (series.isEmpty) return
        runCatching {
            writableDatabase.insertWithOnConflict(
                "chart_cache", null,
                ContentValues().apply {
                    put("symbol", series.symbol.uppercase())
                    put("range_key", series.range.name)
                    put("json", ChartJson.encode(series))
                    put("fetched", series.fetched)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    /** Drop the oldest rows once the table is over [CHART_CACHE_MAX_ROWS]. */
    fun purgeChartCache() {
        runCatching {
            writableDatabase.execSQL(
                "DELETE FROM chart_cache WHERE rowid NOT IN " +
                    "(SELECT rowid FROM chart_cache ORDER BY fetched DESC LIMIT ?)",
                arrayOf<Any>(CHART_CACHE_MAX_ROWS)
            )
        }
    }

    fun clearChartCache() {
        runCatching { writableDatabase.delete("chart_cache", null, null) }
    }

    /** Rows held and the newest fetch time, for the Settings storage card. */
    fun chartCacheStats(): Pair<Int, Long> = runCatching {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), IFNULL(MAX(fetched),0) FROM chart_cache", null
        ).use { c -> if (c.moveToNext()) c.getInt(0) to c.getLong(1) else 0 to 0L }
    }.getOrDefault(0 to 0L)

    // ---------- watchlist (symbols with no position) ----------

    fun watchlist(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT symbol FROM watchlist ORDER BY added ASC", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun addWatch(symbol: String) {
        val cv = ContentValues().apply {
            put("symbol", symbol.uppercase()); put("added", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("watchlist", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeWatch(symbol: String) =
        writableDatabase.delete("watchlist", "symbol=?", arrayOf(symbol.uppercase()))

    // ---------- backup ----------

    /**
     * A complete, self-describing snapshot of everything the user has entered:
     * every transaction, every manual override, the watchlist, the import history and
     * all preferences. API keys are deliberately excluded.
     *
     * Read straight from the database at the moment of the call, so it is always current.
     */
    fun exportJson(): String {
        val txns = allTxns()
        val ovs = overrides().values.toList()
        val watch = watchlist()
        val imps = allImports()

        val root = JSONObject()
        root.put("format", BACKUP_FORMAT)
        root.put("version", BACKUP_VERSION)
        root.put("exported", System.currentTimeMillis())
        root.put("app", JSONObject().apply {
            put("package", ctx.packageName)
            put("versionName", appVersionName())
            put("dbVersion", DB_VERSION)
        })

        val arr = JSONArray()
        for (t in txns) {
            arr.put(JSONObject().apply {
                put("type", t.type)
                if (t.symbol.isNullOrBlank()) put("symbol", JSONObject.NULL) else put("symbol", t.symbol)
                put("quantity", t.quantity); put("price", t.price); put("amount", t.amount)
                put("fees", t.fees); put("date", t.date); put("note", t.note ?: "")
                put("source", t.source)
            })
        }
        root.put("transactions", arr)

        val ov = JSONArray()
        for (o in ovs) {
            ov.put(JSONObject().apply {
                put("symbol", o.symbol)
                put("avgCost", o.avgCost ?: JSONObject.NULL)
                put("shares", o.shares ?: JSONObject.NULL)
            })
        }
        root.put("overrides", ov)
        root.put("watchlist", JSONArray(watch))

        val st = JSONObject()
        readableDatabase.rawQuery("SELECT k,v FROM settings", null).use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0)
                if (isSecret(k)) continue // never export API keys
                // Nor derived market data. The Research payload is up to a couple of hundred
                // KB of screener output that is stale within the hour and rebuilt with one
                // pull - carrying it into a backup would multiply the size of the file that
                // matters (the transactions) for something worth nothing on restore.
                if (isDerivedCache(k)) continue
                st.put(k, c.getString(1) ?: "")
            }
        }
        root.put("settings", st)

        val im = JSONArray()
        for (l in imps) {
            im.put(JSONObject().apply {
                put("at", l.at); put("count", l.count); put("skipped", l.skipped)
                put("minDate", l.minDate); put("maxDate", l.maxDate); put("source", l.source)
            })
        }
        root.put("imports", im)

        // a manifest the restore step checks itself against
        root.put("counts", JSONObject().apply {
            put("transactions", arr.length())
            put("overrides", ov.length())
            put("watchlist", watch.size)
            put("settings", st.length())
            put("imports", im.length())
        })
        return root.toString(1)
    }

    data class RestoreResult(
        val transactions: Int = 0,
        val skipped: Int = 0,
        val overrides: Int = 0,
        val watchlist: Int = 0,
        val settings: Int = 0,
        val imports: Int = 0,
        val replaced: Boolean = false,
        val error: String? = null,
        val warning: String? = null
    ) {
        fun summary(): String = error ?: buildString {
            append(if (replaced) "Replaced with " else "Merged ")
            append(transactions).append(" transaction")
            if (transactions != 1) append("s")
            if (skipped > 0) append(" (").append(skipped).append(" duplicate skipped)")
            if (overrides > 0) append(", ").append(overrides).append(" override(s)")
            if (watchlist > 0) append(", ").append(watchlist).append(" watchlist symbol(s)")
            if (settings > 0) append(", ").append(settings).append(" setting(s)")
            if (imports > 0) append(", ").append(imports).append(" import record(s)")
            if (warning != null) append(". ").append(warning)
        }
    }

    /**
     * Restores a backup. [replace] wipes transactions, overrides, the watchlist and the
     * import history first, so the result is exactly what the file holds - that is what
     * you want when moving to a new device. Merge keeps what is here and skips duplicates.
     */
    fun restoreJson(json: String, replace: Boolean): RestoreResult {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return RestoreResult(error = "That file isn't valid JSON.")
        }

        val txnArr = root.optJSONArray("transactions")
        if (txnArr == null && !root.has("watchlist") && !root.has("overrides")) {
            return RestoreResult(
                error = "That file doesn't look like a portfolio backup - no transactions, " +
                    "watchlist or overrides in it."
            )
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replace) {
                db.delete("txns", null, null)
                db.delete("overrides", null, null)
                db.delete("watchlist", null, null)
                db.delete("imports", null, null)
            }

            var n = 0
            var skipped = 0
            val arr = txnArr ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("type").uppercase()
                if (type !in TxnType.ALL) continue
                // NOTE: optString on a JSON null returns the literal string "null" in
                // org.json, which used to turn cash rows into a phantom "NULL" symbol.
                val sym = if (o.isNull("symbol")) null
                else o.optString("symbol").trim().ifBlank { null }?.takeIf { !it.equals("null", true) }
                val t = Txn(
                    type = type,
                    symbol = sym,
                    quantity = o.optDouble("quantity", 0.0),
                    price = o.optDouble("price", 0.0),
                    amount = o.optDouble("amount", 0.0),
                    fees = o.optDouble("fees", 0.0),
                    date = o.optLong("date", 0L),
                    note = if (o.isNull("note")) null else o.optString("note").ifBlank { null },
                    source = o.optString("source", "RESTORE").ifBlank { "RESTORE" }
                )
                if (!replace && txnExists(t)) { skipped++; continue }
                insertTxn(t); n++
            }

            var ovN = 0
            val ov = root.optJSONArray("overrides") ?: JSONArray()
            for (i in 0 until ov.length()) {
                val o = ov.optJSONObject(i) ?: continue
                val sym = o.optString("symbol").trim()
                if (sym.isBlank()) continue
                // org.json's single-argument optDouble returns NaN when the value is not a
                // number. A NaN override propagates straight into the cost basis, and from
                // there into every portfolio total - the whole summary reads "$NaN" and no
                // amount of refreshing clears it. Only finite values are accepted.
                setOverride(
                    Override(
                        sym,
                        if (o.isNull("avgCost")) null
                        else o.optDouble("avgCost").takeIf { it.isFinite() },
                        if (o.isNull("shares")) null
                        else o.optDouble("shares").takeIf { it.isFinite() }
                    )
                )
                ovN++
            }

            var wN = 0
            val wl = root.optJSONArray("watchlist") ?: JSONArray()
            for (i in 0 until wl.length()) {
                val sym = wl.optString(i).trim()
                if (sym.isNotBlank()) { addWatch(sym); wN++ }
            }

            var sN = 0
            val st = root.optJSONObject("settings")
            if (st != null) for (k in st.keys()) {
                if (isSecret(k)) continue
                // Symmetrical with the export filter: a file written by a build that did
                // carry one must not restore a stale market snapshot over live data.
                if (isDerivedCache(k)) continue
                // Merge promises to ADD what is missing. It used to overwrite live
                // preferences with the file's, so merging a month-old backup silently
                // flipped the cost-basis method, reset the refresh interval and rewrote
                // the "last backup" date to a stale one. Replace still takes the file
                // wholesale - that is the device-transfer path.
                if (!replace && hasSetting(k)) continue
                set(k, st.optString(k)); sN++
            }

            var iN = 0
            val im = root.optJSONArray("imports") ?: JSONArray()
            for (i in 0 until im.length()) {
                val o = im.optJSONObject(i) ?: continue
                val cv = ContentValues().apply {
                    put("at", o.optLong("at")); put("count", o.optInt("count"))
                    put("skipped", o.optInt("skipped"))
                    put("min_date", o.optLong("minDate")); put("max_date", o.optLong("maxDate"))
                    put("source", o.optString("source", "RESTORE"))
                }
                db.insert("imports", null, cv); iN++
            }

            db.setTransactionSuccessful()

            // cross-check against the manifest the export wrote
            val expected = root.optJSONObject("counts")?.optInt("transactions", -1) ?: -1
            val warning = if (expected >= 0 && expected != n + skipped)
                "Warning: the file lists $expected transactions but ${n + skipped} were readable."
            else null

            return RestoreResult(n, skipped, ovN, wN, sN, iN, replace, null, warning)
        } catch (e: Exception) {
            return RestoreResult(error = "Restore failed: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    fun allImports(): List<ImportLog> {
        val out = ArrayList<ImportLog>()
        readableDatabase.rawQuery("SELECT * FROM imports ORDER BY at ASC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ImportLog(
                        c.getLong(c.getColumnIndexOrThrow("at")),
                        c.getInt(c.getColumnIndexOrThrow("count")),
                        c.getInt(c.getColumnIndexOrThrow("skipped")),
                        c.getLong(c.getColumnIndexOrThrow("min_date")),
                        c.getLong(c.getColumnIndexOrThrow("max_date")),
                        c.getString(c.getColumnIndexOrThrow("source")) ?: ""
                    )
                )
            }
        }
        return out
    }

    private fun isSecret(k: String) = k.contains("key", true)

    /** Market data the app can rebuild from the network; never worth a byte of a backup. */
    private fun isDerivedCache(k: String) =
        k == Keys.RESEARCH_CACHE || k == Keys.INSIDER_CACHE || k == Keys.INSIDER_SKIP

    private fun appVersionName(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

}

/** One row of the HTTP response cache. See `Db.createHttpCache`. */
data class HttpCacheRow(val etag: String, val lastModified: String, val body: String)

object Keys {
    const val CLAUDE_KEY = "claude_api_key"
    const val CLAUDE_MODEL = "claude_model"
    const val FINNHUB_KEY = "finnhub_api_key"
    const val CASH_OVERRIDE = "cash_override"
    const val USE_CASH_OVERRIDE = "use_cash_override"
    const val REFRESH_SECS = "refresh_secs"
    const val WEB_SEARCH = "advice_web_search"
    const val SORT_MODE = "sort_mode"
    const val ADVICE_CACHE = "advice_cache"
    const val LAST_TAB = "last_tab"
    const val WATCH_SORT = "watch_sort"
    const val ADVICE_SOURCE = "advice_source"
    const val LAST_BACKUP = "last_backup"
    const val AUTO_BACKUP = "auto_backup"
    const val COST_METHOD = "cost_method"
    const val AUTOBAK_CLEANED = "autobackup_cleaned"
    const val AUTO_BACKUP_AT = "auto_backup_at"
    const val IN_APP_READER = "in_app_reader"

    /**
     * The price-chart range the user last selected, by [ChartRange] name (Round 58).
     *
     * Remembered so the choice survives closing a stock and the app: someone who reads on a
     * one-year view should not have to re-pick it on every symbol they open. Not a derived
     * cache - it is a genuine preference - so it IS carried in the JSON backup.
     */
    const val CHART_RANGE = "chart_range"

    /**
     * The whole Research payload as JSON (Round 54).
     *
     * A settings row rather than a new table: it is ONE document, it is replaced wholesale on
     * every rebuild, and nothing ever queries inside it. A table would have bought a schema
     * migration - and `onUpgrade` is the one part of this app where a mistake destroys user
     * data - in exchange for nothing at all. Research is derived data: losing it costs one
     * refresh.
     */
    const val RESEARCH_CACHE = "research_cache"

    /**
     * The last month of parsed Form 4 filings, as JSON.
     *
     * A settings row for the same reasons as [RESEARCH_CACHE]: one document, replaced
     * wholesale, never queried inside, and a new table would have cost an `onUpgrade`
     * migration - the one part of this app where a mistake destroys the user's own
     * transaction history. Excluded from backups by `isDerivedCache`, because a month-old
     * snapshot of insider activity restored over live data is worse than no snapshot.
     *
     * What it buys is real: the tab is populated the instant it opens, offline included, and
     * a filing that has already been read is never downloaded from EDGAR twice.
     */
    const val INSIDER_CACHE = "insider_cache"

    /**
     * Accession numbers of SEC filings that were fetched once and contained nothing usable -
     * a Form 3 or 5 sharing the schema, or a holdings-only Form 4 with no transaction table.
     *
     * A filed document is immutable, so this verdict never expires and the entry is worth
     * keeping across launches: without it the same documents are re-downloaded on every
     * insider pass, twice an hour, for the whole 31-day window, for the life of the app.
     * Stored as a comma-separated list; derived data, so excluded from backups.
     */
    const val INSIDER_SKIP = "insider_skip"

    /** Which half of the Watch tab was open last: 0 = watchlist, 1 = research. */
    const val WATCH_SUBTAB = "watch_subtab"

    /** Which Research list was open last: 0 = trending, 1 = best, 2 = worst (Round 56). */
    const val RESEARCH_TAB = "research_tab"

    /** Block ad, tracker and pop-up hosts in the in-app reader. On by default. */
    const val BLOCK_ADS = "block_ads"

    /** Strip overlays and registration panels, and give the page its scroll back. */
    const val DECLUTTER = "declutter"

    /** Jump straight to the text-only article when a page has one. */
    const val AUTO_READER = "auto_reader"

    /** When the feed last completed a NETWORK pass, so the header survives a restart. */
    const val FEED_AT = "feed_at"

    /**
     * How many transactions were on file the last time the ledger was replayed successfully.
     *
     * The app has no way to tell "you have not entered anything yet" from "your data has
     * gone" - both are an empty table and an empty Portfolio tab. This is the difference:
     * if the table is empty and this says it used to hold rows, something is wrong and the
     * app says so loudly instead of quietly drawing an empty portfolio.
     */
    const val LAST_TXN_COUNT = "last_txn_count"

    /** When the uninstall-proof copy in Downloads was last written. */
    const val AUTOSAVE_AT = "autosave_at"

    /**
     * Set once the v5.6 tidy-up has moved the app's files into Downloads/Portfolio and
     * cleared what earlier builds left loose in the root. Deliberately NOT set when the
     * autosave move could not be verified, so a failed attempt is retried next launch.
     */
    const val DOWNLOADS_TIDIED = "downloads_tidied"

    /**
     * Cache kinds for the `fundamentals` table.
     *
     * They are separate rows rather than one because they have very different lifetimes and
     * very different sizes: the numbers change once a quarter and are a few KB, while the
     * analyst history changes through the day and can be 200KB. Fetching them together
     * would mean either refreshing the big one far too often or the small one far too
     * rarely.
     */
    const val KIND_CORE = "core"
    const val KIND_RATINGS = "ratings"

    /**
     * What a fund holds (Round 58). Shares the `fundamentals` table with the two kinds
     * above - see `Db.cacheHoldings` for why it is a third KIND and not a fourth table.
     */
    const val KIND_HOLDINGS = "holdings"

    /** Which detail-screen tab was last open, so reopening a stock lands where you left. */
    const val DETAIL_TAB = "detail_tab"
}
