package com.tj.portfolio.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Records the stack trace of a crash into the app's own private folder before the process
 * dies, so a crash on a sideloaded phone leaves something readable behind.
 *
 * WHY THIS EXISTS: this app is sideloaded, so there is no Play Console, no crash reporting
 * service and no practical way to read logcat from the phone. A crash was simply "the app
 * closed" - no message, nothing to look at, nothing to send on. The one that prompted this
 * (a duplicate list key thrown a few seconds after a stock's news loaded) took a careful
 * read of the whole feed path to find, because there was no trace of it anywhere.
 *
 * The handler chains to whatever Android installed before it, so the system still does its
 * normal job of ending the process - nothing here tries to keep a dead app alive.
 */
object CrashLog {

    private const val FILE = "crash-log.txt"

    /** Keep the file small; the newest entry is the one that matters. */
    private const val MAX_BYTES = 60_000

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // installing twice would chain this handler to itself
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    private class Handler(
        private val ctx: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            // Every step is best-effort: failing to WRITE a crash report must never
            // interfere with the crash actually being reported to the system.
            runCatching { append(ctx, t, e) }
            previous?.uncaughtException(t, e)
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun append(ctx: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val entry = buildString {
            append("\n===== CRASH ").append(Fmt.day(System.currentTimeMillis()))
            append(' ').append(Fmt.clock(System.currentTimeMillis()))
            append("  thread=").append(t.name).append(" =====\n")
            append(sw.toString())
        }
        val f = file(ctx)
        val existing = if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
        val merged = (existing + entry).let {
            if (it.length <= MAX_BYTES) it else it.takeLast(MAX_BYTES)
        }
        f.writeText(merged)
    }

    /** Everything recorded so far, newest at the bottom. Empty when nothing has crashed. */
    fun read(ctx: Context): String =
        runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    /** First line of the most recent crash, for a one-line status in Settings. */
    fun latestSummary(ctx: Context): String? {
        val all = read(ctx)
        if (all.isBlank()) return null
        val block = all.split("===== CRASH ").lastOrNull()?.trim() ?: return null
        val header = block.lineSequence().firstOrNull().orEmpty()
            .removeSuffix("=====").trim()
        val cause = block.lineSequence().drop(1).firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return listOf(header, cause).filter { it.isNotBlank() }.joinToString("  -  ")
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}
