package com.tj.portfolio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.util.CrashLog
import com.tj.portfolio.util.Fmt
import com.tj.portfolio.util.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * The two things that exist purely to save the user when something has gone wrong: the
 * rolling snapshot window that protects the portfolio, and the crash log that is the only
 * diagnostic a sideloaded app has. Neither has ever been executed under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageCrashLogTest {

    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Storage.appBackupDir(ctx).listFiles()?.forEach { it.delete() }
        CrashLog.clear(ctx)
    }

    // ------------------------------------------------------ snapshot window

    @Test fun `the snapshot folder keeps the newest fourteen and drops the rest`() {
        // 20 snapshots, each older than the last
        for (i in 1..20) {
            val f = Storage.saveToAppFolder(ctx, "portfolio-autobackup-%02d.json".format(i), """{"n":$i}""")
            assertNotNull(f)
            f!!.setLastModified(1_700_000_000_000L + i * 60_000L)
        }
        val kept = Storage.appBackups(ctx)
        assertEquals("the rolling window is not holding at 14", 14, kept.size)
        assertEquals("the newest snapshot is not first", "portfolio-autobackup-20.json", kept[0].name)
        assertTrue("an old snapshot survived the prune",
            kept.none { it.name == "portfolio-autobackup-01.json" })
        assertTrue("the newest 14 are the ones kept",
            kept.map { it.name }.containsAll((7..20).map { "portfolio-autobackup-%02d.json".format(it) }))
    }

    @Test fun `snapshots come back newest first`() {
        Storage.saveToAppFolder(ctx, "a.json", "{}")!!.setLastModified(1_000_000L)
        Storage.saveToAppFolder(ctx, "b.json", "{}")!!.setLastModified(3_000_000L)
        Storage.saveToAppFolder(ctx, "c.json", "{}")!!.setLastModified(2_000_000L)
        assertEquals(listOf("b.json", "c.json", "a.json"), Storage.appBackups(ctx).map { it.name })
    }

    @Test fun `only json files count as snapshots`() {
        Storage.saveToAppFolder(ctx, "real.json", "{}")
        java.io.File(Storage.appBackupDir(ctx), "stray.txt").writeText("not a backup")
        assertEquals(listOf("real.json"), Storage.appBackups(ctx).map { it.name })
    }

    @Test fun `a snapshot round-trips its exact content`() {
        val json = """{"format":"tj-portfolio-backup","transactions":[{"type":"BUY"}]}"""
        val f = Storage.saveToAppFolder(ctx, "x.json", json)
        assertEquals(json, f!!.readText())
    }

    @Test fun `the timestamp used in file names sorts chronologically`() {
        // "portfolio-backup-2026-09-03-1432.json" - the format has to sort as text, or the
        // Downloads folder stops being browsable in order
        val s = Storage.stamp()
        assertTrue("stamp is not yyyy-MM-dd-HHmm: $s", Regex("""\d{4}-\d{2}-\d{2}-\d{4}""").matches(s))
    }

    // ----------------------------------------------------------- crash log

    @Test fun `no crash means no summary, not a blank card`() {
        assertNull(CrashLog.latestSummary(ctx))
        assertEquals("", CrashLog.read(ctx))
    }

    @Test fun `a crash is recorded and summarised`() {
        crash(RuntimeException("something went bang"))
        val all = CrashLog.read(ctx)
        assertTrue(all.contains("something went bang"))
        assertTrue(all.contains("===== CRASH"))
        val summary = CrashLog.latestSummary(ctx)
        assertNotNull("a recorded crash produced no summary", summary)
        assertTrue("the summary does not name the exception: $summary",
            summary!!.contains("something went bang"))
    }

    @Test fun `the summary describes the NEWEST crash, not the first`() {
        crash(RuntimeException("the old one"))
        crash(IllegalStateException("the new one"))
        val summary = CrashLog.latestSummary(ctx)!!
        assertTrue("the summary is showing a stale crash: $summary", summary.contains("the new one"))
        assertFalse(summary.contains("the old one"))
    }

    @Test fun `the log is capped so it cannot grow without bound`() {
        // 400 crashes with a long message each, well past the 60KB ceiling
        repeat(400) { crash(RuntimeException("failure number $it " + "x".repeat(300))) }
        val size = CrashLog.read(ctx).length
        assertTrue("the crash log grew to $size chars - the cap is not holding", size <= 60_000)
        // and truncation must keep the END, because the newest crash is the one that matters
        assertTrue("truncation kept the wrong end - the newest crash is gone",
            CrashLog.read(ctx).contains("failure number 399"))
        assertNotNull(CrashLog.latestSummary(ctx))
    }

    @Test fun `clearing the log really clears it`() {
        crash(RuntimeException("bang"))
        assertTrue(CrashLog.read(ctx).isNotEmpty())
        CrashLog.clear(ctx)
        assertEquals("", CrashLog.read(ctx))
        assertNull(CrashLog.latestSummary(ctx))
    }

    @Test fun `installing twice does not chain the handler to itself`() {
        CrashLog.install(ctx)
        val first = Thread.getDefaultUncaughtExceptionHandler()
        CrashLog.install(ctx)
        assertTrue("a second install wrapped the handler again - a crash would recurse",
            first === Thread.getDefaultUncaughtExceptionHandler())
    }

    /** Drives the real handler, the way the platform would on an uncaught exception. */
    private fun crash(e: Throwable) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(null)
        CrashLog.install(ctx)
        val h = Thread.getDefaultUncaughtExceptionHandler()!!
        h.uncaughtException(Thread.currentThread(), e)
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    // -------------------------------------------------------------- Fmt odds

    @Test fun `money formatting follows the documented decimal rules`() {
        assertEquals("$1,234.56", Fmt.usd(1234.5649))
        assertEquals("+$0.50", Fmt.usdSigned(0.5))
        assertEquals("-$0.50", Fmt.usdSigned(-0.5))
        // a price at or above a dollar: two decimals, a third only when it is really there
        assertEquals("$7.56", Fmt.price(7.56))
        assertEquals("$7.565", Fmt.price(7.565))
        assertEquals("$230.115", Fmt.price(230.115))
        // sub-dollar prices get three
        assertEquals("$0.423", Fmt.price(0.4231))
        // a change is sized by the STOCK price, not by the change
        assertEquals("+0.13", Fmt.changeFor(89.0, 0.13))
        assertEquals("+0.130", Fmt.changeFor(0.89, 0.13))
        assertEquals("-$1.24", Fmt.changeMoney(268.0, -1.24))
    }

    @Test fun `relative time never reports a future stamp as an age`() {
        val now = System.currentTimeMillis()
        assertEquals("just now", Fmt.relative(now))
        assertEquals("just now", Fmt.relative(now + 5 * 60_000L))   // clock skew, not "-5m ago"
        assertTrue(Fmt.relative(now - 90_000L).endsWith("m ago"))
        assertTrue(Fmt.relative(now - 3 * 3_600_000L).endsWith("h ago"))
        assertTrue(Fmt.relative(now - 3 * 86_400_000L).endsWith("d ago"))
    }

    @Test fun `date parsing accepts the formats a statement uses and rejects junk`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        for (s in listOf("2026-09-04", "09/04/2026", "9/4/2026", "Sep 4, 2026")) {
            val t = Fmt.parseDate(s)
            assertNotNull("could not parse $s", t)
            assertEquals("$s landed on the wrong day", "2026-09-04", Fmt.iso(t!!))
        }
        assertNull(Fmt.parseDate("not a date"))
        assertNull(Fmt.parseDate(""))
        assertNull("a nonsense month was accepted", Fmt.parseDate("2026-13-45"))
    }

    @Test fun `shares keep their fractional precision`() {
        assertEquals("0.1458", Fmt.shares(0.1458))
        assertEquals("1,500", Fmt.shares(1500.0))
        assertEquals("3", Fmt.shares(3.0))
    }
}
