package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Override
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import org.json.JSONArray
import org.json.JSONObject
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
 * A REPLACE RESTORE MUST NEVER CLEAR THE LEDGER FOR A FILE THAT CANNOT REFILL IT.
 *
 * ---- THE TWO BUGS THIS PROVES FIXED
 *
 * `restoreJson(replace = true)` opens by deleting txns, overrides, watchlist and imports
 * before it reads a single row out of the file. Two different files walked straight through
 * that door and left the ledger empty.
 *
 * 1. A WATCHLIST-ONLY FILE. The entry gate only asked whether the file had ANY of
 *    transactions / watchlist / overrides in it - so a file carrying just a `watchlist` key
 *    passed, the four deletes ran, and a few symbols were restored over the top of the whole
 *    transaction history. Merging off such a file is a perfectly reasonable thing to want;
 *    replacing off it never is.
 *
 * 2. A TRUNCATED BACKUP. `exportJson` writes a `counts` manifest precisely so a restore can
 *    prove it read the whole file, but the check ran AFTER `setTransactionSuccessful()` -
 *    once the rows were already committed, the only thing it could still do was phrase a
 *    warning. So a half-copied backup wiped the ledger, put back whatever subset happened to
 *    parse, committed, and reported success with a note appended to the toast.
 *
 *    The loss did not stop at the database: `restoreAsync` then forces a safety copy, and
 *    `autoBackupIfDue` declines only a COMPLETELY empty ledger, so the 3-of-200 rows were
 *    immediately written over `portfolio-autosave.json` in Downloads - the one copy
 *    documented as surviving an uninstall. Both copies gone inside a second.
 *
 * Both now fail the restore outright. Returning before `setTransactionSuccessful()` lets the
 * `finally` roll the transaction back, so the user keeps exactly what they had.
 *
 * MERGE keeps its old, softer behaviour on purpose: it only ever adds rows, so an incomplete
 * file can still be worth taking, and a warning is the honest answer rather than a refusal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreSafetyTest {

    private lateinit var app: Application
    private lateinit var db: Db

    private val day = 1_756_900_000_000L

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
        db = Db(app)
    }

    @After fun tearDown() {
        db.close()
        app.deleteDatabase(Db.DB_NAME)
    }

    private fun seedLedger(): Int {
        db.insertTxn(Txn(type = TxnType.DEPOSIT, amount = 5_000.0, date = day))
        db.insertTxn(Txn(type = TxnType.BUY, symbol = "ONDS", quantity = 45.0,
            price = 7.565, amount = -340.43, date = day))
        db.insertTxn(Txn(type = TxnType.BUY, symbol = "VTI", quantity = 10.0,
            price = 250.0, amount = -2_500.0, date = day))
        db.insertTxn(Txn(type = TxnType.SELL, symbol = "ONDS", quantity = 20.0,
            price = 9.0, amount = 180.0, date = day))
        return db.allTxns().size
    }

    /** A backup whose `transactions` array has been cut short but whose manifest still isn't. */
    private fun truncatedBackup(keep: Int): String {
        val root = JSONObject(db.exportJson())
        val full = root.getJSONArray("transactions")
        val cut = JSONArray()
        for (i in 0 until keep) cut.put(full.get(i))
        root.put("transactions", cut)
        // `counts` deliberately left describing the FULL file - that disagreement is the
        // only signal a restore gets that the file it is holding is incomplete.
        assertTrue("the fixture did not actually truncate anything",
            root.getJSONObject("counts").getInt("transactions") > cut.length())
        return root.toString()
    }

    // ---- 1. the watchlist-only file

    @Test fun `replace from a file with no transactions is refused and keeps the ledger`() {
        val before = seedLedger()
        val watchOnly = JSONObject().put("watchlist", JSONArray().apply {
            put(JSONObject().put("symbol", "AAPL").put("added", day).put("addedPrice", 0.0))
        }).toString()

        val r = db.restoreJson(watchOnly, replace = true)

        assertNotNull("a replace off a file with no transactions was allowed", r.error)
        assertEquals("the ledger was cleared anyway", before, db.allTxns().size)
    }

    @Test fun `merge from a watchlist-only file is still allowed`() {
        val before = seedLedger()
        val watchOnly = JSONObject().put("watchlist", JSONArray().apply {
            put(JSONObject().put("symbol", "AAPL").put("added", day).put("addedPrice", 0.0))
        }).toString()

        val r = db.restoreJson(watchOnly, replace = false)

        assertNull("merging a watchlist-only file should still work: ${r.error}", r.error)
        assertEquals("a merge removed transactions", before, db.allTxns().size)
    }

    // ---- 2. the truncated backup

    @Test fun `replace from a truncated backup is refused and rolls the whole thing back`() {
        val before = seedLedger()
        val json = truncatedBackup(keep = 1)

        val r = db.restoreJson(json, replace = true)

        assertNotNull("a short-count backup was allowed to replace the ledger", r.error)
        assertEquals("the rollback did not restore the original rows",
            before, db.allTxns().size)
        assertEquals("the original ledger did not survive intact",
            5_000.0, db.allTxns().filter { it.type == TxnType.DEPOSIT }.sumOf { it.amount }, 1e-9)
    }

    @Test fun `a refused replace leaves the watchlist and overrides alone too`() {
        seedLedger()
        db.addWatch("AAPL")
        val watchBefore = db.watchlist().size
        assertTrue("fixture needs a watchlist row", watchBefore > 0)

        db.restoreJson(truncatedBackup(keep = 1), replace = true)

        assertEquals("the replace's up-front deletes were not rolled back",
            watchBefore, db.watchlist().size)
    }

    @Test fun `a merge never clobbers an override this device already has`() {
        // full-tests audit, 2026-09-21: setOverride() ran unconditionally for every override
        // in the file on BOTH modes, so merging an older backup that happened to carry a
        // stale override for a symbol silently replaced today's value with no skip and no
        // warning - the one table in restoreJson that didn't follow the settings block's
        // own "if (!replace && hasSetting(k)) continue" pattern, and a direct contradiction
        // of the restore dialog's own promise that merge can never remove or replace what
        // this device already has.
        db.setOverride(Override("AAPL", avgCost = 150.0, shares = 10.0))
        val root = JSONObject(db.exportJson())
        val ov = JSONArray()
        ov.put(JSONObject().apply {
            put("symbol", "AAPL"); put("avgCost", 90.0); put("shares", 5.0)
        })
        root.put("overrides", ov)

        db.restoreJson(root.toString(), replace = false)

        val after = db.overrides()["AAPL"]
        assertEquals("merge overwrote a live override instead of skipping it",
            150.0, after?.avgCost)
        assertEquals(10.0, after?.shares)
    }

    @Test fun `merge from a truncated backup still restores what it can, with a warning`() {
        seedLedger()
        val json = truncatedBackup(keep = 1)
        db.allTxns().forEach { db.deleteTxn(it.id) }

        val r = db.restoreJson(json, replace = false)

        assertNull("a merge should not be refused for a short count: ${r.error}", r.error)
        assertNotNull("the short count was not reported at all", r.warning)
        assertEquals("the rows that DID parse were not merged in", 1, db.allTxns().size)
    }

    // ---- 3. the ordinary case still works

    @Test fun `a complete backup still replaces normally`() {
        val before = seedLedger()
        val json = db.exportJson()
        db.insertTxn(Txn(type = TxnType.DEPOSIT, amount = 99.0, date = day))
        assertEquals(before + 1, db.allTxns().size)

        val r = db.restoreJson(json, replace = true)

        assertNull("a complete backup was refused: ${r.error}", r.error)
        assertNull("a complete backup produced a short-count warning", r.warning)
        assertEquals("replace did not restore the backup's exact row count",
            before, db.allTxns().size)
        assertTrue("the row added after the backup survived a replace",
            db.allTxns().none { it.amount == 99.0 })
    }

    // ---- 4. the day-trading log travels with the backup

    /**
     * IT WAS NOT IN THE BACKUP AT ALL, AND IT CANNOT BE REBUILT.
     *
     * `exportJson` carried transactions, overrides, watchlist, settings and imports - and not
     * `day_trading_log`. Nothing lost it on the device (the table is append-only and no purge
     * touches it), so the gap stayed invisible until the one moment it mattered: a reinstall
     * or a new phone restored the ledger in full and started the recommendation history at
     * zero. The rows are not re-fetchable - each is a plan made against live screener state
     * that no longer exists, plus an outcome measured against intraday bars Yahoo serves for
     * about 55 days.
     */
    private fun logPick(sym: String, day: String, outcome: String? = null): Unit {
        db.logDayTradingRecommendation(
            symbol = sym, tradingDay = day, setup = "Breakout",
            entry = 10.0, stop = 9.5, target = 11.0,
            priceAtRecommendation = 9.9, source = "APP", recordedAt = 1_757_000_000_000L
        )
        if (outcome != null) {
            val row = db.dayTradingLog().first { it.symbol == sym && it.tradingDay == day }
            db.setDayTradingOutcome(row.id, outcome, 11.0)
        }
    }

    @Test fun `the day-trading log survives an export and restore onto a clean install`() {
        seedLedger()
        logPick("ONDS", "2026-09-15", outcome = "CLOSED_PROFIT")
        logPick("NVDA", "2026-09-16")
        val json = db.exportJson()

        // A NEW PHONE: same app, nothing in it yet.
        db.close()
        app.deleteDatabase(Db.DB_NAME)
        db = Db(app)
        assertTrue("fixture did not start clean", db.dayTradingLog().isEmpty())

        val r = db.restoreJson(json, replace = true)

        assertNull("the restore failed: ${r.error}", r.error)
        assertEquals("the day-trading log did not come back", 2, db.dayTradingLog().size)
        assertEquals("the restore did not report the rows it put back", 2, r.dayTrading)
        val closed = db.dayTradingLog().first { it.symbol == "ONDS" }
        assertEquals("2026-09-15", closed.tradingDay)
        assertEquals("Breakout", closed.setup)
        assertEquals(10.0, closed.entry, 1e-9)
        assertEquals(9.5, closed.stop, 1e-9)
        assertEquals(11.0, closed.target, 1e-9)
        assertEquals("the measured outcome was lost", "CLOSED_PROFIT", closed.outcome)
        assertEquals(11.0, closed.outcomeExitPrice!!, 1e-9)
        // An unresolved row must come back unresolved, not as the string "null".
        assertNull("a pending row restored with a fabricated outcome",
            db.dayTradingLog().first { it.symbol == "NVDA" }.outcome)
    }

    @Test fun `a truncated day-trading section warns instead of silently under-restoring`() {
        // full-tests audit, 2026-09-21: `counts.dayTradingLog` was written on every export
        // and never once compared against what actually restored, unlike `counts.transactions`
        // three lines up - so a truncated day-trading section came back with fewer rows than
        // the file claimed and no signal that anything was missing. Still a warning, never a
        // refusal: the table is additive-only, so nothing on this device was ever at risk.
        seedLedger()
        logPick("ONDS", "2026-09-15")
        logPick("NVDA", "2026-09-16")
        val root = JSONObject(db.exportJson())
        val full = root.getJSONArray("dayTradingLog")
        assertTrue("fixture needs at least 2 day-trading rows", full.length() >= 2)
        root.put("dayTradingLog", JSONArray().put(full.get(0)))
        // `counts` deliberately left describing the full file, same fixture shape as
        // `truncatedBackup` above.

        db.close(); app.deleteDatabase(Db.DB_NAME); db = Db(app)
        val r = db.restoreJson(root.toString(), replace = true)

        assertNull("a short day-trading count must not refuse the restore: ${r.error}", r.error)
        assertNotNull("the short count was not reported at all", r.warning)
        assertEquals(1, db.dayTradingLog().size)
    }

    @Test fun `restoring the same backup twice does not duplicate day-trading rows`() {
        seedLedger()
        logPick("ONDS", "2026-09-15")
        val json = db.exportJson()

        db.restoreJson(json, replace = false)
        db.restoreJson(json, replace = false)

        assertEquals("the append-only log gained a duplicate", 1, db.dayTradingLog().size)
    }

    @Test fun `a restore never deletes a day-trading row this device already had`() {
        seedLedger()
        val json = db.exportJson()          // exported BEFORE the pick was recorded
        logPick("ONDS", "2026-09-15")

        db.restoreJson(json, replace = true)

        assertEquals("Replace all destroyed measured history the backup predated",
            1, db.dayTradingLog().size)
    }

    @Test fun `a v3 backup with no day-trading key still restores`() {
        seedLedger()
        val root = JSONObject(db.exportJson())
        root.remove("dayTradingLog")
        root.getJSONObject("counts").remove("dayTradingLog")
        root.put("version", 3)

        val r = db.restoreJson(root.toString(), replace = true)

        assertNull("an older backup was refused: ${r.error}", r.error)
        assertEquals(0, r.dayTrading)
    }

    @Test fun `a file that is not a backup at all is still refused for both modes`() {
        val before = seedLedger()
        val junk = JSONObject().put("hello", "world").toString()

        assertNotNull(db.restoreJson(junk, replace = true).error)
        assertNotNull(db.restoreJson(junk, replace = false).error)
        assertEquals(before, db.allTxns().size)
    }
}
