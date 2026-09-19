package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
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

    @Test fun `a file that is not a backup at all is still refused for both modes`() {
        val before = seedLedger()
        val junk = JSONObject().put("hello", "world").toString()

        assertNotNull(db.restoreJson(junk, replace = true).error)
        assertNotNull(db.restoreJson(junk, replace = false).error)
        assertEquals(before, db.allTxns().size)
    }
}
