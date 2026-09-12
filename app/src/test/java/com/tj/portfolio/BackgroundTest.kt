package com.tj.portfolio

import com.tj.portfolio.data.Txn
import com.tj.portfolio.data.TxnType
import com.tj.portfolio.net.Relevance
import com.tj.portfolio.net.SymbolSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round 57: what the app does when nobody is looking at it, and the caching that keeps it
 * from asking for the same thing twice.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundTest {

    // ------------------------------------------------ the foreground scope

    /**
     * `fgScope` is private, so its SHAPE is restated here: a supervisor job parented to an
     * outer scope, cancelled and rebuilt on each background/foreground transition.
     *
     * The two properties that matter are the ones that were wrong before it existed - work
     * launched into it must stop when the app goes away, and the scope must be USABLE again
     * afterwards. A cancelled Job stays cancelled forever, so rebuilding is not optional; get
     * that wrong and every screen sits empty after the first app switch.
     */
    private class Scopes {
        // A ROOT OF ITS OWN, not a child of the test's `runBlocking`. `SupervisorJob()` never
        // completes by itself, so parenting it to `runBlocking` makes the test block forever
        // waiting for a job that has nothing left to do - which is exactly what happened the
        // first time this was written, and is worth leaving written down: the same trap
        // applies to any manually created Job attached to a scope something else joins on.
        private val root = Job()
        private val parent = CoroutineScope(root + kotlinx.coroutines.Dispatchers.Default)
        var fg: CoroutineScope = parent + SupervisorJob(root)
        fun background() { fg.coroutineContext[Job]?.cancel() }
        fun foreground() { fg = parent + SupervisorJob(root) }
        fun dispose() { root.cancel() }
    }

    @Test fun leavingTheAppStopsWorkStartedByAScreen() = runBlocking {
        val scopes = Scopes()
        var finished = false
        scopes.fg.launch { delay(10_000); finished = true }
        delay(20)
        scopes.background()
        delay(20)
        // Nothing left to wait for - the work was abandoned, not completed.
        assertFalse("work launched into the foreground scope must not survive", finished)
        scopes.dispose()
    }

    @Test fun comingBackMakesTheScopeUsableAgain() = runBlocking {
        val scopes = Scopes()
        scopes.background()

        // The trap: reusing the cancelled scope silently drops every launch.
        var ranOnDeadScope = false
        scopes.fg.launch { ranOnDeadScope = true }
        delay(20)
        assertFalse("a cancelled scope rejects launches", ranOnDeadScope)

        scopes.foreground()
        var ranOnNewScope = false
        scopes.fg.launch { ranOnNewScope = true }
        delay(20)
        assertTrue("a fresh scope must accept work again", ranOnNewScope)
        scopes.dispose()
    }

    /** One failing fetch must not take its siblings down - hence SupervisorJob. */
    @Test fun oneFailedFetchDoesNotCancelTheOthers() = runBlocking {
        val scopes = Scopes()
        var sibling = false
        scopes.fg.launch { runCatching { throw IllegalStateException("provider down") } }
        scopes.fg.launch { sibling = true }
        delay(20)
        assertTrue(sibling)
        assertTrue("the scope stays alive", scopes.fg.coroutineContext[Job]?.isActive == true)
        scopes.dispose()
    }

    // ------------------------------------------------------ search memo

    /**
     * Type-ahead is a request generator: backspacing to a prefix already typed, or retyping a
     * ticker looked up a minute ago, used to be a fresh Yahoo round trip every time.
     */
    @Test fun aRepeatedSearchTermIsNotAskedForTwice() = runBlocking {
        SymbolSearch.clearMemo()
        // No network in a unit test, so an empty-result memo hit and an empty-result memo
        // MISS (the network path failing in the sandbox) look identical - asserting the two
        // calls are equal here would pass whether or not the memo does anything at all. Seed
        // a KNOWN, non-empty result instead: if the memo is skipped, the second call falls
        // through to the (failing) network path and comes back empty, not equal to the seed.
        val seeded = listOf(com.tj.portfolio.net.SearchHit("NVDA", "NVIDIA Corporation"))
        SymbolSearch.seedMemoForTest("NVDA", seeded)
        val first = SymbolSearch.query("NVDA")
        val second = SymbolSearch.query("nvda") // case-insensitive key, same entry
        assertEquals(seeded, first)
        assertEquals(seeded, second)
        SymbolSearch.clearMemo()
    }

    // --------------------------------------------- relevance, hoisted

    /**
     * The hot loop in `Research.build` is ~500 headlines x ~450 symbols. Round 57 hoisted the
     * per-symbol and per-headline work out of it; these assert the fast path agrees with the
     * original one, case for case, including the traps the original was written to survive.
     */
    @Test fun thePreparedSubjectAgreesWithTheSingleShotMatcher() {
        val cases = listOf(
            Triple("FIVE", "Five Below, Inc.", "Five Below beats on earnings"),
            Triple("FIVE", "Five Below, Inc.", "Upstart (UPST) Stock Looks Expensive After A 90% FIVE YEAR Slump"),
            Triple("NVDA", "NVIDIA Corporation", "Nvidia earnings beat expectations"),
            Triple("NVDA", "NVIDIA Corporation", "AMD gains share in the datacentre"),
            Triple("AAPL", "Apple Inc.", "apple orchards had a good season"),
            Triple("AAPL", "Apple Inc.", "Apple unveils a new iPhone"),
            Triple("F", "Ford Motor Company", "Ford recalls 100,000 trucks"),
            Triple("F", "Ford Motor Company", "A quiet day for the F sector"),
            Triple("MOG.A", "Moog Inc.", "Moog Inc. wins a defence contract"),
            Triple("T", "AT&T Inc.", "Shares of (T) climbed today")
        )
        for ((sym, name, headline) in cases) {
            val slow = Relevance.matches(headline, "", sym, name)
            val subject = Relevance.Subject.of(sym, name)
            val fast = Relevance.matches(subject, headline, "")
            val fastPrecomputed =
                Relevance.matches(subject, headline, "", Relevance.squashed(headline, ""))
            assertEquals("$sym / $headline", slow, fast)
            assertEquals("$sym / $headline (precomputed)", slow, fastPrecomputed)
        }
    }

    /** The precomputed squash must be the same string the slow path would have built. */
    @Test fun theHoistedSquashMatchesTheInlineOne() {
        val subject = Relevance.Subject.of("FIVE", "Five Below, Inc.")
        val title = "Five Below, Inc. raises guidance"
        assertTrue(Relevance.matches(subject, title, "", Relevance.squashed(title, "")))
        assertTrue(Relevance.matches(subject, title, ""))
    }

    // -------------------------------------- the index-friendly duplicate check

    /**
     * `IFNULL(symbol,'')=?` made `idx_txn_symbol` unusable, turning every duplicate check
     * into a full table scan - and `restoreJson` runs one per incoming row. The rewrite has to
     * keep behaving identically, cash rows (which have no symbol) included.
     */
    @Test fun duplicateDetectionStillWorksWithAndWithoutASymbol() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(com.tj.portfolio.data.Db.DB_NAME)
        val db = com.tj.portfolio.data.Db(ctx)

        val day = 1_757_000_000_000L
        val buy = Txn(
            type = TxnType.BUY, symbol = "NVDA", quantity = 10.0,
            price = 100.0, amount = 1000.0, date = day
        )
        db.insertTxn(buy)
        assertTrue("the same trade must be seen as a duplicate", db.txnExists(buy))
        assertNotNull(db.findDuplicateId(buy))

        // A different symbol on the same day is NOT a duplicate.
        assertFalse(db.txnExists(buy.copy(symbol = "AMD")))

        // Cash rows carry no symbol at all - the branch that replaced IFNULL.
        val deposit = Txn(
            type = TxnType.DEPOSIT, symbol = null, quantity = 0.0,
            price = 0.0, amount = 500.0, date = day
        )
        db.insertTxn(deposit)
        assertTrue("a cash row must still match itself", db.txnExists(deposit))
        assertFalse(db.txnExists(deposit.copy(amount = 900.0)))

        // And a symbol row must not collide with the cash row.
        assertFalse(db.txnExists(deposit.copy(symbol = "NVDA", amount = 500.0)))
    }
}
