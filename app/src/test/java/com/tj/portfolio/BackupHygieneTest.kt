package com.tj.portfolio

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Keys
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WHAT A BACKUP MUST NOT CARRY (Round 66).
 *
 * ---- THE BUG THIS PROVES FIXED
 *
 * `isDerivedCache` already excluded `FEED_AT`, with a comment explaining exactly the hazard:
 * a "when did this device last do X" mark, restored onto a DIFFERENT device, is a lie that
 * suppresses the work the new device most needs to do. Three more keys of precisely that kind
 * were being exported anyway.
 *
 * The worst of them is the backup pair. `autoBackupIfDue` takes the newer of `AUTOSAVE_AT`
 * and `AUTO_BACKUP_AT` and returns without writing if it is under 24 hours old. So: move to a
 * new phone, restore this morning's autosave, and for the rest of the day the new phone has
 * NO private snapshot and no uninstall-proof copy in Downloads - at the one moment the ledger
 * is least protected. `DOWNLOADS_TIDIED` restored as done means `tidyDownloadsOnce` never runs
 * on the new device at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupHygieneTest {

    private lateinit var ctx: Context
    private lateinit var db: Db

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(Db.DB_NAME)
        db = Db(ctx)
    }

    @After fun tearDown() { db.close(); ctx.deleteDatabase(Db.DB_NAME) }

    /** Every key that answers "when did THIS device last do something". */
    private val deviceMarks = listOf(
        Keys.FEED_AT, Keys.FILINGS_AT, Keys.AUTOSAVE_AT, Keys.AUTO_BACKUP_AT,
        Keys.DOWNLOADS_TIDIED
    )

    @Test fun `a backup carries no device-local timestamps`() {
        db.insertTxn(Txn(type = TxnType.BUY, symbol = "NVDA", quantity = 1.0, price = 10.0,
            amount = -10.0, date = 1_756_000_000_000L))
        deviceMarks.forEach { db.set(it, "1756000000000") }
        // A real preference, to prove the exclusion is targeted and not blanket.
        db.set(Keys.COST_METHOD, "FIFO")

        val settings = JSONObject(db.exportJson()).getJSONObject("settings")
        for (k in deviceMarks) {
            assertFalse(
                "\"$k\" is in the backup - restoring it onto a new phone suppresses the very " +
                    "work that phone needs to do",
                settings.has(k)
            )
        }
        assertEquals("a real preference was dropped too", "FIFO", settings.optString(Keys.COST_METHOD))
    }

    @Test fun `restoring a backup does not import another phone's marks`() {
        // Build a backup on "phone A", then hand-inject the marks a pre-fix export carried.
        db.insertTxn(Txn(type = TxnType.BUY, symbol = "NVDA", quantity = 1.0, price = 10.0,
            amount = -10.0, date = 1_756_000_000_000L))
        val root = JSONObject(db.exportJson())
        val stolen = root.getJSONObject("settings")
        deviceMarks.forEach { stolen.put(it, "1756000000000") }

        // "Phone B": empty, and it must stay empty of those marks.
        db.close(); ctx.deleteDatabase(Db.DB_NAME); db = Db(ctx)
        val res = db.restoreJson(root.toString(), true)
        assertTrue("the restore itself failed: ${res.error}", res.error == null)
        assertEquals("the transaction did not come across", 1, db.allTxns().size)
        for (k in deviceMarks) {
            assertTrue(
                "\"$k\" was imported from the other phone as \"${db.get(k)}\"",
                db.get(k).isBlank()
            )
        }
    }
}
