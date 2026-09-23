package com.tj.portfolio

import com.tj.portfolio.data.Quote
import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.domain.Ledger
import com.tj.portfolio.net.ClaudeBridge
import com.tj.portfolio.ui.backupTxnCount
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the full-test audit of 2026-09-23 (the reports in audits/2026-09-23) that do not
 * belong to an existing subsystem test file. Each test names the finding it pins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullTest0923Test {

    // ---- A-1: the autosave is never shrunk without keeping the larger copy; that decision
    // rests on reading a backup's transaction count correctly.

    @Test fun `A-1 backup count reads the manifest, falls back to the array, and knows unknown`() {
        assertEquals(200, backupTxnCount("""{"counts":{"transactions":200},"transactions":[]}"""))
        assertEquals(2, backupTxnCount("""{"transactions":[{},{}]}"""))
        assertEquals(-1, backupTxnCount(null))
        assertEquals(-1, backupTxnCount(""))
        assertEquals(-1, backupTxnCount("not json"))
        // A missing old file (-1) is "smaller" than any real one, so nothing is rotated for it.
        assertEquals(true, backupTxnCount("""{"transactions":[{}]}""") > backupTxnCount(null))
    }

    // ---- A-3: a row whose date was guessed is not "bought today".

    @Test fun `A-3 a holdings snapshot row does not report its whole gain as today's`() {
        val session = 1_756_909_800_000L
        val snap = Txn(
            type = TxnType.BUY, symbol = "NVDA", quantity = 100.0, price = 50.0, amount = -5000.0,
            date = session, note = "position snapshot - " + Txn.DATE_ESTIMATED, source = "CLAUDE_FILE"
        )
        val q = Quote(symbol = "NVDA", price = 180.0, prevClose = 178.0)
        listOf(Ledger.FIFO, Ledger.AVERAGE).forEach { m ->
            val p = Ledger.positions(listOf(snap), method = m, sessionInstant = session).single()
            assertEquals("$m: sharesToday", 0.0, p.sharesToday, 1e-9)
            assertEquals("$m: today's move is the price change, not the lifetime gain",
                200.0, p.dayPnl(q), 1e-6)
        }
        // A real trade today is still today's.
        val real = snap.copy(note = null)
        val p = Ledger.positions(listOf(real), method = Ledger.FIFO, sessionInstant = session).single()
        assertEquals(100.0, p.sharesToday, 1e-9)
    }

    // ---- A-5 / A-6: import parsers.

    @Test fun `A-5 a trade with no ticker is refused and A-6 a cash row carries no fee`() {
        val reply = """{"portfolioAppResponse":1,"notes":"","transactions":[
          {"type":"BUY","symbol":"","quantity":20,"price":250,"amount":5000,"fees":0,"date":"2026-09-01"},
          {"type":"FEE","symbol":null,"quantity":0,"price":0,"amount":0.40,"fees":0.40,"date":"2026-09-01"},
          {"type":"BUY","symbol":"AAPL","quantity":2,"price":100,"amount":200,"fees":1,"date":"2026-09-01"}
        ]}"""
        val r = ClaudeBridge.parse(reply)
        assertEquals(listOf(TxnType.FEE, TxnType.BUY), r.transactions.map { it.type })
        assertEquals(0.0, r.transactions[0].fees, 1e-9)
        assertEquals(1.0, r.transactions[1].fees, 1e-9)
        // The fee row counts once, the trade's commission once.
        assertEquals(1.40, Ledger.fees(r.transactions), 1e-9)
    }

    // ---- A-2: a correctly-ordered day with missing earlier history is not reversed.

    @Test fun `A-2 rows newer than the repair watermark keep their stored order`() {
        val session = 1_756_909_800_000L
        val sell = Txn(id = 10, type = TxnType.SELL, symbol = "NVDA", quantity = 100.0, price = 180.0,
            amount = 18_000.0, date = session, source = "SCREENSHOT")
        val buy = Txn(id = 11, type = TxnType.BUY, symbol = "NVDA", quantity = 100.0, price = 175.0,
            amount = -17_500.0, date = session, source = "SCREENSHOT")
        listOf(Ledger.FIFO, Ledger.AVERAGE).forEach { m ->
            // Imported after the fix: kept as stored - 100 shares held, the missing buy reported.
            val kept = Ledger.positions(listOf(sell, buy), method = m, sessionInstant = session,
                repairBelowId = 5).single()
            assertEquals("$m shares", 100.0, kept.shares, 1e-9)
            assertEquals("$m oversold", 100.0, kept.oversold, 1e-9)
            // A legacy row pair (below the watermark) is still repaired, as before.
            val legacy = Ledger.positions(listOf(sell, buy), method = m, sessionInstant = session,
                repairBelowId = 100).single()
            assertEquals("$m legacy repaired", 0.0, legacy.oversold, 1e-9)
        }
    }

    // ---- A-4: the undo restores the state before a destructive action, not the snapshot
    // forced right after it.

    @Test fun `A-4 a before-copy shadowed by a snapshot seconds later is the undo`() {
        val dir = java.nio.file.Files.createTempDirectory("snaps").toFile()
        fun f(name: String, at: Long) = java.io.File(dir, name).apply { writeText("{}"); setLastModified(at) }
        val t = 1_700_000_000_000L
        val before = f("portfolio-before-replace-1.json", t)
        val daily = f("portfolio-autobackup-1.json", t + 2_000)
        assertEquals(before, com.tj.portfolio.ui.pickUndoSnapshot(listOf(daily, before)))
        // A day later the daily snapshot is the newer real state again.
        val later = f("portfolio-autobackup-2.json", t + 86_400_000L)
        assertEquals(later, com.tj.portfolio.ui.pickUndoSnapshot(listOf(later, daily, before)))
        assertEquals(null, com.tj.portfolio.ui.pickUndoSnapshot(emptyList()))
    }

    // ---- S-6: sell votes count in the research-list analyst blend.

    @Test fun `S-6 an all-sell consensus scores below an all-hold one`() {
        val base = com.tj.portfolio.net.ResearchScore.Scored(60, emptyList(), 80)
        val hold = com.tj.portfolio.net.ResearchScore.withAnalyst(
            base, com.tj.portfolio.data.Consensus2(buy = 0, hold = 10, sell = 0), 100.0)
        val sell = com.tj.portfolio.net.ResearchScore.withAnalyst(
            base, com.tj.portfolio.data.Consensus2(buy = 0, hold = 0, sell = 10), 100.0)
        assert(hold.score > sell.score) { "hold ${hold.score} vs sell ${sell.score}" }
    }
}
