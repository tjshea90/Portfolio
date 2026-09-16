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
        const val DB_VERSION = 8
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
        createTxnIndexes(db)
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
        createQuoteIndexes(db)
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
    /**
     * THE TWO INDEXES THE TXNS TABLE CANNOT WORK WITHOUT (Round 66).
     *
     * ---- THE BUG THIS FIXES
     *
     * These were written inline in [onCreate] as plain `CREATE INDEX`, so a database created
     * before they were added never got them - `onUpgrade` did not create them and the `onOpen`
     * repair block did not either. Every install that has been upgraded rather than freshly
     * created has therefore been running the txns table with NO indexes at all, and the code
     * that leans on them says otherwise in its own comments: `findDuplicateId` runs once per
     * incoming row on a restore or an import, and `deleteTxnsForSymbol` claims to be "one
     * statement against `idx_txn_symbol`". Without the index both are full table scans - a
     * 2,000-row backup merge is about four million row visits inside one transaction, which
     * is the exact cost an earlier round's comment claims to have removed.
     *
     * ---- WHY IT IS SHAPED LIKE THIS
     *
     * `IF NOT EXISTS` and its own function, so it can be called from BOTH [onCreate] and the
     * [onOpen] repair block. That heals every existing install on its next launch with no
     * migration step and no version bump, and it cannot fail on a database that already has
     * them. Creating an index on an existing table is a single pass over that table - a few
     * milliseconds on a ledger of this size, once.
     */
    private fun createTxnIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_txn_symbol ON txns(symbol)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_txn_date ON txns(date)")
    }

    /**
     * THE ONE CACHE TABLE THAT SLIPPED THROUGH (Part 9 audit finding).
     *
     * `fundamentals`, `news_cache`, `http_cache` and `chart_cache` each got an index on their
     * own retention column ([idx_fund_fetched] etc.) alongside the `CREATE TABLE`; `quotes`
     * never did, even though [purgeQuotes] filters on this exact column. Same fix, same
     * shape, as [createTxnIndexes]: `IF NOT EXISTS` and its own function so it can be called
     * from both [onCreate] and the [onOpen] repair block, healing every existing install with
     * no migration step.
     */
    private fun createQuoteIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_quote_updated ON quotes(updated)")
    }

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
        // ROUND 66: and the txns indexes, which until now existed only on databases that were
        // freshly created rather than upgraded. See [createTxnIndexes].
        runCatching { createTxnIndexes(db) }
        // Part 9 audit: quotes(updated) never had an index at all. See [createQuoteIndexes].
        runCatching { createQuoteIndexes(db) }
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
        // LOOPED, NOT ONE PASS (Part 9 audit finding). Deleting the oldest 25% BY ROW COUNT
        // once does not guarantee the byte budget is met - a few outsized rows dominating the
        // total might not be among that 25%, and this only runs once per launch, so a table
        // that does not catch up in one pass might never catch up. Bounded at 8 iterations
        // (each pass removes at least a quarter of what's left, so this converges quickly
        // even from a badly overgrown table) so a pathological state can never loop the
        // purge indefinitely on the calling thread.
        for (pass in 0 until 8) {
            val total = db.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM http_cache", null)
                .use { c -> if (c.moveToNext()) c.getLong(0) else 0L }
            if (total <= HTTP_CACHE_MAX_CHARS) break
            val deletedThisPass = db.delete(
                "http_cache",
                "url IN (SELECT url FROM http_cache ORDER BY fetched ASC LIMIT " +
                    "MAX(1, (SELECT COUNT(*) FROM http_cache) / 4))",
                null
            )
            removed += deletedThisPass
            if (deletedThisPass == 0) break // nothing left to delete
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

    /**
     * Rides the EXISTING `fundamentals` table under [Keys.KIND_RECOMMENDATION], same shape and
     * same reasoning as [cacheHoldings] just above: one current snapshot per symbol, replaced
     * wholesale, read by (symbol, kind) - no migration, because `kind` was always a free-form
     * TEXT column. It also inherits [purgeFundamentals]'s retention for free.
     *
     * `fetched` is left as the ordinary write timestamp for that purge to age the row out with
     * everything else - the field that actually decides whether TODAY's recompute is still
     * owed is [com.tj.portfolio.data.Recommendation.dayKey], INSIDE the JSON, read by the
     * caller. "Is this row worth keeping at all" and "is this row still today's answer" are two
     * different questions, on two different fields, on purpose.
     */
    fun cacheRecommendation(r: Recommendation) {
        runCatching {
            writableDatabase.insertWithOnConflict(
                "fundamentals", null,
                ContentValues().apply {
                    put("symbol", r.symbol.uppercase())
                    put("kind", Keys.KIND_RECOMMENDATION)
                    put("json", RecommendationJson.encode(r))
                    put("fetched", if (r.computedAt > 0) r.computedAt else System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    /** Null for a missing row AND for an unreadable one - both must read as "compute it". */
    fun cachedRecommendation(symbol: String): Recommendation? = runCatching {
        readableDatabase.rawQuery(
            "SELECT json FROM fundamentals WHERE symbol=? AND kind=?",
            arrayOf(symbol.uppercase(), Keys.KIND_RECOMMENDATION)
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            RecommendationJson.decode(c.getString(0))
        }
    }.getOrNull()

    /** Rows older than [olderThanMs] are dropped. Called on the same schedule as the news purge. */
    fun purgeFundamentals(olderThanMs: Long = 30L * 86_400_000L): Int = runCatching {
        writableDatabase.delete(
            "fundamentals", "fetched < ?",
            arrayOf((System.currentTimeMillis() - olderThanMs).toString())
        )
    }.getOrDefault(0)

    /**
     * QUOTES THE USER HAS NOT LOOKED AT IN A MONTH (Round 66).
     *
     * ---- THE BUG THIS FIXES
     *
     * Every cache in this app was pruned except this one. There was no `purgeQuotes`, no
     * retention constant and no `DELETE FROM quotes` anywhere - but rows are written for far
     * more than the tracked set: opening any stock from search, from the Research lists or
     * from a headline quotes it and persists it, and `adoptAsSparkline` then writes a full
     * intraday series into that row's `spark` column. Each one is a permanent ~1KB JSON array
     * for a symbol looked at once.
     *
     * That is paid TWICE on the launch path. `cachedQuotes()` reads every row and JSON-parses
     * every spark array, and it runs synchronously from the ViewModel's `init` - so a few
     * months of browsing turns into hundreds of parsed arrays before the first frame - and the
     * resulting map is then held in memory for the life of the process.
     *
     * ---- WHY A MONTH IS SAFE
     *
     * `updated` is stamped on every quote pass, and everything the user actually holds or
     * watches is re-quoted every few seconds while the app is open. A row can only age out by
     * not being tracked, which is exactly the row worth dropping. If one is dropped and the
     * symbol is opened again, it costs a single quote request that was going to be made
     * anyway.
     */
    fun purgeQuotes(
        olderThanMs: Long = 30L * 86_400_000L,
        now: Long = System.currentTimeMillis()
    ): Int = runCatching {
        // `updated > 0` as well as the age test. Every path that writes a quote stamps it, so
        // a zero is not something this can produce - but "undateable" and "a month old" are
        // different facts, and a purge that treats them the same would silently delete a row
        // written moments ago the first time anything ever wrote one without a timestamp.
        // Deleting the wrong quote costs a re-fetch rather than data, which is precisely why
        // it is the kind of mistake that would go unnoticed.
        writableDatabase.delete(
            "quotes", "updated > 0 AND updated < ?",
            arrayOf((now - olderThanMs).toString())
        )
    }.getOrDefault(0)

    /** Row count and total payload size, for the Settings diagnostics card. */
    fun fundamentalsCacheStats(): Pair<Int, Long> = runCatching {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(LENGTH(json)),0) FROM fundamentals", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else 0 to 0L }
    }.getOrDefault(0 to 0L)

    // ---------- import history ----------

    /**
     * Import log rows older than [olderThanMs] are dropped (Part 9 audit finding: every other
     * cache/log table in this file had a purge from the Round 56/58/66 sweeps; this one, in
     * since v2, never got one). Low-impact on its own - one row per screenshot/file import,
     * infrequent - but a real gap relative to the rest of this file's stated retention policy.
     * A year rather than the 30 days most caches use: [lastImport] and the Activity tab's
     * "resume from" hint only ever need the newest row, but the full history is otherwise
     * harmless to keep far longer than a market-data cache that goes stale in days.
     */
    fun purgeImports(olderThanMs: Long = 365L * 86_400_000L): Int = runCatching {
        writableDatabase.delete(
            "imports", "at < ?",
            arrayOf((System.currentTimeMillis() - olderThanMs).toString())
        )
    }.getOrDefault(0)

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

    /**
     * @param exclude ids this caller has already matched or inserted, which must not match
     *   again. See [restoreJson] - without it, a merge restore silently deletes real rows.
     */
    fun findDuplicateId(t: Txn, exclude: Set<Long> = emptySet()): Long? {
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
                // ONE STORED ROW CAN ABSORB ONE FILE ROW, NOT MANY (Round 66 audit, CRX-2).
                if (id in exclude) continue
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

    /**
     * A WRITE-THROUGH CACHE IN FRONT OF THE SETTINGS TABLE (Round 63 sweep).
     *
     * Settings are read from the UI thread constantly and written almost never - the table is
     * a few dozen short rows, and this app asks it questions on a fifteen-second clock. Every
     * `publish()` read the cash-override flag and its value; every tick read the refresh
     * interval; every refresh read the Finnhub key. That is upwards of five hundred
     * synchronous `rawQuery` calls an hour on the main thread to re-learn values that had not
     * changed.
     *
     * WRITE-THROUGH, not write-behind: [set] updates the map and the table in the same call,
     * so a reader immediately after a write sees the new value and a process death loses
     * nothing. `null` in the map means "not present in the table", which is distinct from a
     * stored empty string - [hasSetting] depends on telling those apart.
     *
     * The whole map is dropped by [invalidateSettings], which `restoreJson` calls: a restore
     * rewrites rows in bulk and through paths that do not funnel through [set].
     */
    private val settingsCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Sentinel for "this key is genuinely absent", so absence is cached as well as presence. */
    private val NO_SETTING = "\u0000<absent>"

    /**
     * ---- WHY A LOCK, AND WHY ONLY AROUND THE MISS PATH.
     *
     * A read that misses does two things: it queries the table, and it stores what it read.
     * Between those, a write on another thread can commit - and the read then puts the
     * PRE-WRITE value into the cache, permanently, where before the cache existed every read
     * would have gone to the table and been right. Narrow window, unbounded consequence: the
     * app writes settings from `Dispatchers.IO` (the chart range, the compare toggle, the P/L
     * mode, `FEED_AT`) while the main thread reads the same keys on a fifteen-second tick.
     *
     * Serialising the miss path and the write path against each other closes it. The HIT path
     * takes no lock at all, which is the case that actually runs hundreds of times an hour;
     * `ConcurrentHashMap` makes that read safe on its own.
     */
    private val settingsLock = Any()

    fun invalidateSettings() = synchronized(settingsLock) { settingsCache.clear() }

    fun get(key: String, def: String = ""): String {
        settingsCache[key]?.let { return if (it == NO_SETTING) def else it }
        synchronized(settingsLock) {
            // Re-checked inside the lock: another thread may have filled it while we waited.
            settingsCache[key]?.let { return if (it == NO_SETTING) def else it }
            readableDatabase.rawQuery("SELECT v FROM settings WHERE k=?", arrayOf(key)).use { c ->
                val v = if (c.moveToFirst()) c.getString(0) else null
                settingsCache[key] = v ?: NO_SETTING
                return v ?: def
            }
        }
    }

    fun set(key: String, value: String) = synchronized(settingsLock) {
        writeSetting(writableDatabase, key, value)
        settingsCache[key] = value
    }

    /**
     * The raw row write, WITHOUT the cache lock.
     *
     * ---- THIS EXISTS TO BREAK A DEADLOCK, AND IT IS THE ONLY REASON IT EXISTS.
     *
     * `set` takes `settingsLock` and then reaches for the database connection. `restoreJson`
     * does the opposite: it holds an exclusive transaction on that connection and then, for
     * every settings row in the file, calls `set` - which wants the lock. So a restore running
     * while ANY other thread writes a setting (`stampFeedAt`, `setChartRange`, `setPlMode`,
     * all on IO, all live during a restore because the poll loop keeps ticking) is a
     * lock-order inversion: one thread holds the lock and waits for the connection, the other
     * holds the connection and waits for the lock. Both wedge for good, and the next
     * main-thread settings read ANRs the app.
     *
     * Inside a transaction the cache is being invalidated wholesale afterwards anyway, so the
     * restore writes rows through here and never touches the lock.
     */
    private fun writeSetting(db: SQLiteDatabase, key: String, value: String) {
        val cv = ContentValues().apply { put("k", key); put("v", value) }
        db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Is this key stored at all? Distinct from [get] returning "", which a stored blank does too. */
    fun hasSetting(key: String): Boolean {
        // Answered from the cache when it knows, INCLUDING when what it knows is "absent" -
        // that is the case this function exists to distinguish from a stored empty string.
        settingsCache[key]?.let { return it != NO_SETTING }
        // No lock on the miss here: this is called from Settings and from one-off checks, not
        // from a hot path, and it writes nothing - so it cannot poison the cache.
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
            // ---- THE UPDATE CARRIES THE SUMMARY TOO (Round 66).
            //
            // It set `owned` and nothing else, so a row already on file could never GAIN a
            // blurb - and since `loadNews` was passing an empty summaries map, every row was
            // already on file with a blank one. `COALESCE(NULLIF(?, ''), summary)` writes the
            // new summary only when there is one, so a later pass from a source that supplies
            // no blurb cannot erase a blurb an earlier pass found.
            val upd = db.compileStatement(
                "UPDATE news_cache SET owned=?, summary=COALESCE(NULLIF(?, ''), summary) " +
                    "WHERE id=?"
            )
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
                    upd.bindString(2, summaries[it.id].orEmpty())
                    upd.bindString(3, it.id)
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
        // A restore rewrites rows in bulk and inside a transaction that can roll back, so
        // whatever the settings cache thinks it knows afterwards is not to be trusted.
        // Dropped on the way IN as well as out, because a partial restore that throws still
        // leaves the table changed.
        invalidateSettings()
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
            // Stored ids this restore has already matched or inserted - see the note at the
            // duplicate check below (Round 66 audit, CRX-2).
            val claimed = HashSet<Long>()
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
                // ---- A ROW ALREADY MATCHED CANNOT MATCH AGAIN (Round 66 audit, CRX-2).
                //
                // THE BUG THIS FIXES, and it is the worst kind: silent data loss in the one
                // code path that exists to RECOVER lost data.
                //
                // `txnExists` asked "is there a row in the table that looks like this one?" -
                // against the whole table, including the rows this same restore had just
                // inserted a moment earlier. So any set of genuinely identical transactions in
                // a backup collapsed to exactly one row. Two $500 deposits on the same day
                // become one $500 deposit. Two halves of a partial fill - which Ally lists as
                // separate rows, 45 shares each - become one 45-share buy.
                //
                // Merge is not a corner of the app: it is the primary button of the restore
                // dialog, the "Restore that backup" button on the data-loss recovery card, the
                // Downloads autosave recovery and the paste-JSON path. Its dialog says "Merge
                // adds anything missing... it can never remove anything you already have",
                // and the toast said "Merged 2 transactions (2 duplicate skipped)", which
                // reads like success. Meanwhile net deposits were $500 short, the position was
                // 45 shares and $340.43 of cost basis short, and `totalGain = equity -
                // netDeposits` was wrong on BOTH terms at once. Re-running the restore could
                // never recover them, because the same rule applied again.
                //
                // `claimed` makes the matching one-to-one: a stored row absorbs at most one
                // file row, and a row inserted by this restore is excluded from matching the
                // rows that follow it.
                val dup = if (replace) null else findDuplicateId(t, claimed)
                if (dup != null) { claimed.add(dup); skipped++; continue }
                claimed.add(insertTxn(t)); n++
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
                // NOT `set` - see `writeSetting`. Calling it here, inside the transaction,
                // is the deadlock. The whole cache is dropped in the `finally` below.
                writeSetting(db, k, st.optString(k)); sN++
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
            // ---- AFTER THE TRANSACTION CLOSES, NOT BEFORE IT.
            //
            // Dropped inside the try, a reader in the gap between the clear and
            // `endTransaction()` could re-cache a value the rollback was about to undo - which
            // is the one thing the invalidation exists to prevent. In the `finally` it runs on
            // every exit, commit and rollback alike, and always after the table has settled.
            db.endTransaction()
            invalidateSettings()
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

    /**
     * An explicit allowlist, not a substring match on the constant's NAME (Part 9 audit
     * finding). It used to be `k.contains("key", true)`, which only ever worked because these
     * two happened to be the only setting keys containing "key" - a future setting whose name
     * coincidentally matched (e.g. a hypothetical `sort_key`) would have been silently and
     * permanently dropped from every backup and JSON export, with nothing to flag it.
     */
    private fun isSecret(k: String) = k == Keys.CLAUDE_KEY || k == Keys.FINNHUB_KEY

    /**
     * Market data the app can rebuild from the network, and "when did THIS phone last do X"
     * marks. Never worth a byte of a backup, and actively harmful inside one.
     *
     * ---- THE TWO KINDS, AND WHY BOTH ARE EXCLUDED
     *
     * The caches are simply not worth carrying: the Research payload alone is a couple of
     * hundred KB of screener output that is stale within the hour and rebuilt with one pull.
     *
     * THE TIMESTAMPS ARE THE DANGEROUS ONES. Every one of them answers "how long since this
     * device did something", and the code that reads them treats a recent value as "no need
     * to do it again". Carried into a backup and restored onto a NEW phone, they are a lie
     * that suppresses exactly the work the new phone most needs to do:
     *
     *   * `FEED_AT` - the header reads "Updated 3 minutes ago" over a feed this device has
     *     never fetched, and the refresh-on-open rule stays suppressed until it ages out.
     *   * `FILINGS_AT` - the same for SEC filings (Round 66).
     *   * `AUTOSAVE_AT` / `AUTO_BACKUP_AT` - `autoBackupIfDue` takes the newer of the two and
     *     returns without writing if it is under 24h old. So restoring this morning's autosave
     *     onto a new phone left that phone with NO private snapshot and no uninstall-proof
     *     copy in Downloads for a full day - at the one moment the ledger is least protected
     *     (Round 66).
     *   * `DOWNLOADS_TIDIED` - restored as done, so `tidyDownloadsOnce` never runs on the new
     *     device at all (Round 66).
     */
    private fun isDerivedCache(k: String) =
        k == Keys.RESEARCH_CACHE || k == Keys.INSIDER_CACHE || k == Keys.INSIDER_SKIP ||
            k == Keys.FEED_AT || k == Keys.FILINGS_AT ||
            k == Keys.AUTOSAVE_AT || k == Keys.AUTO_BACKUP_AT || k == Keys.DOWNLOADS_TIDIED

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
     * Whether the detail chart draws the benchmark beside the stock (Round 63).
     *
     * Remembered for the same reason [CHART_RANGE] is: whether you read a chart against the
     * market is a habit, not a per-symbol decision, and re-enabling it on every stock would
     * be the thing that stops it being used. A genuine preference, so it IS in the backup.
     */
    const val CHART_COMPARE = "chart_compare"

    /**
     * Whether profit-and-loss figures lead with dollars or with a percentage (Round 61).
     *
     * A genuine preference rather than a derived cache, so it IS carried in the JSON backup -
     * the same treatment the cost method and the sort order get.
     */
    const val PL_MODE = "pl_mode"

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

    /**
     * Which Research list was open last, as an index into `ResearchSet.SECTIONS`.
     *
     * NOT WRITTEN OUT AS "0 = trending, 1 = best, 2 = worst" any more (Round 66). That list
     * was correct when it was written and wrong twice since - once when the ETF tab was added
     * and once when Worst was removed - and a comment that names indices is guaranteed to rot
     * the next time a section moves. `researchTabMax()` derives the bound from SECTIONS for
     * the same reason.
     */
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
     * When SEC Form 4 filings were last pulled, on THIS device (Round 66).
     *
     * A real field rather than a counter inside the polling coroutine, which is what it used
     * to be. `startAuto()` relaunches that coroutine on every return to the foreground, so the
     * counter restarted at zero every time - meaning the half-hour filings cadence needed half
     * an hour of UNINTERRUPTED foreground and, on a phone used in normal bursts, never fired
     * at all. Excluded from backups for the same reason as [FEED_AT].
     */
    const val FILINGS_AT = "filings_at"

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

    /**
     * The per-holding BUY/HOLD/SELL recommendation. A fourth kind on the same table, same
     * reasoning as [KIND_HOLDINGS] - see `Db.cacheRecommendation`.
     */
    const val KIND_RECOMMENDATION = "recommendation"

    /** Which detail-screen tab was last open, so reopening a stock lands where you left. */
    const val DETAIL_TAB = "detail_tab"
}
