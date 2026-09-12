package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A MERGE RESTORE MUST NEVER LOSE A ROW (Round 66 audit, CRX-2).
 *
 * ---- THE BUG THIS PROVES FIXED
 *
 * The merge path asked, for each row in the backup file, "is there a row in the table that
 * looks like this one?" - against the whole table, INCLUDING the rows this same restore had
 * inserted seconds earlier. So any set of genuinely identical transactions in a backup
 * collapsed to exactly one row.
 *
 * Two $500 deposits on the same day became one $500 deposit. Two halves of a partial fill -
 * which brokers list as separate rows - became one. And this is the one code path in the app
 * whose entire purpose is to RECOVER lost transactions: merge is the primary button of the
 * restore dialog, the "Restore that backup" button on the data-loss recovery card, the
 * Downloads autosave recovery and the paste-JSON path. Its dialog promises "it can never
 * remove anything you already have"; the toast read "Merged 2 transactions (2 duplicate
 * skipped)", which looks like success. Net deposits, cost basis and `totalGain` were all
 * wrong afterwards, and re-running the restore could never repair it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreMergeTest {

    private lateinit var app: Application
    private lateinit var db: Db

    /** A fixed trading day, so every row in a case lands in the same day bucket. */
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

    private fun deposit(amount: Double) =
        Txn(type = TxnType.DEPOSIT, amount = amount, date = day)

    private fun buy(sym: String, qty: Double, price: Double, amount: Double) =
        Txn(type = TxnType.BUY, symbol = sym, quantity = qty, price = price,
            amount = amount, date = day)

    /** A split's `quantity` is the ratio - see [TxnType.SPLIT]. Price/amount/fees stay 0. */
    private fun split(sym: String, ratio: Double) =
        Txn(type = TxnType.SPLIT, symbol = sym, quantity = ratio, date = day)

    /** Export what is in the db, wipe it, and merge the export back in. */
    private fun exportWipeAndMerge(): String {
        val json = db.exportJson()
        db.allTxns().forEach { db.deleteTxn(it.id) }
        assertTrue("the wipe did not empty the table", db.allTxns().isEmpty())
        return db.restoreJson(json, replace = false).let { json }
    }

    /** THE CASE FROM THE REPORT: two identical deposits on one day. */
    @Test fun `two identical deposits both survive a merge restore`() {
        db.insertTxn(deposit(500.0))
        db.insertTxn(deposit(500.0))
        exportWipeAndMerge()

        val back = db.allTxns().filter { it.type == TxnType.DEPOSIT }
        assertEquals("one of two identical deposits was swallowed", 2, back.size)
        assertEquals(1000.0, back.sumOf { it.amount }, 1e-9)
    }

    /** Two halves of a partial fill - separate rows on a real statement. */
    @Test fun `both halves of a partial fill survive a merge restore`() {
        db.insertTxn(buy("ONDS", 45.0, 7.565, -340.43))
        db.insertTxn(buy("ONDS", 45.0, 7.565, -340.43))
        exportWipeAndMerge()

        val back = db.allTxns().filter { it.symbol == "ONDS" }
        assertEquals("half the position was lost on restore", 2, back.size)
        assertEquals(90.0, back.sumOf { it.quantity }, 1e-9)
        assertEquals(-680.86, back.sumOf { it.amount }, 1e-6)
    }

    /** Three of a kind, to prove the fix is not a one-off special case. */
    @Test fun `three identical rows all survive`() {
        repeat(3) { db.insertTxn(deposit(250.0)) }
        exportWipeAndMerge()
        assertEquals(3, db.allTxns().count { it.type == TxnType.DEPOSIT })
    }

    /**
     * THE OTHER HALF OF THE CONTRACT, and the reason the matcher exists at all: merging a
     * backup onto a ledger that ALREADY holds those rows must not double them.
     */
    @Test fun `merging a backup onto the same ledger adds nothing`() {
        db.insertTxn(deposit(500.0))
        db.insertTxn(deposit(500.0))
        db.insertTxn(buy("ONDS", 45.0, 7.565, -340.43))
        val json = db.exportJson()

        db.restoreJson(json, replace = false)

        assertEquals("merging a backup onto itself duplicated rows", 3, db.allTxns().size)
        assertEquals(1000.0, db.allTxns().filter { it.type == TxnType.DEPOSIT }.sumOf { it.amount }, 1e-9)
    }

    /** And a partial overlap: one row already there, one missing. Exactly one is added. */
    @Test fun `a partial overlap adds only what is missing`() {
        db.insertTxn(deposit(500.0))
        db.insertTxn(deposit(500.0))
        val json = db.exportJson()
        // Lose one of them, the way a bad sync would.
        db.deleteTxn(db.allTxns().first { it.type == TxnType.DEPOSIT }.id)
        assertEquals(1, db.allTxns().size)

        db.restoreJson(json, replace = false)
        assertEquals("the missing deposit was not restored", 2, db.allTxns().size)
        assertEquals(1000.0, db.allTxns().sumOf { it.amount }, 1e-9)
    }

    /** A full export/restore round trip is lossless for a ledger containing repeats. */
    @Test fun `export and merge is a lossless round trip`() {
        db.insertTxn(deposit(500.0))
        db.insertTxn(deposit(500.0))
        db.insertTxn(buy("ONDS", 45.0, 7.565, -340.43))
        db.insertTxn(buy("ONDS", 45.0, 7.565, -340.43))
        db.insertTxn(buy("NVDA", 10.0, 180.0, -1800.0))
        val before = db.allTxns().map { "${it.type}|${it.symbol}|${it.quantity}|${it.amount}" }.sorted()

        exportWipeAndMerge()

        val after = db.allTxns().map { "${it.type}|${it.symbol}|${it.quantity}|${it.amount}" }.sorted()
        assertEquals(before, after)
    }

    /** `replace = true` was never affected, and must stay exact. */
    @Test fun `a replace restore is exact`() {
        db.insertTxn(deposit(500.0))
        db.insertTxn(deposit(500.0))
        val json = db.exportJson()
        db.insertTxn(buy("JUNK", 1.0, 1.0, -1.0))

        db.restoreJson(json, replace = true)
        assertEquals(2, db.allTxns().size)
        assertTrue("replace must drop rows not in the file", db.allTxns().none { it.symbol == "JUNK" })
    }
}
