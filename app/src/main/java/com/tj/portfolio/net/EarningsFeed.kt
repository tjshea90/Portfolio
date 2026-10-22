package com.tj.portfolio.net

import com.tj.portfolio.util.yahooNum
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * THE NEXT REPORT DATE for a symbol, fresh (2026-10-22). `Fundamentals.earningsDate` carries
 * the same fact but rides the six-hour fundamentals TTL; companies confirm dates all the time,
 * so the Earnings screen asks for just `calendarEvents` - one small quoteSummary request,
 * conditional-GET cached by [Http] - whenever it is opened.
 *
 * Yahoo gives either ONE timestamp (a confirmed date and time) or TWO (the start and end of
 * an estimated window), plus an `isEarningsDateEstimate` flag. Both are carried: a screen
 * that prints an estimate as though it were a confirmed date is worse than no date.
 */
object EarningsFeed {

    data class Date(
        val startMs: Long,
        val endMs: Long = startMs,
        val estimated: Boolean = false,
        val fetched: Long = 0L
    ) {
        val day: LocalDate get() = dayOf(startMs)
        val endDay: LocalDate get() = dayOf(endMs)
        val timing: String get() = if (estimated) "" else timingOf(startMs)
    }

    /** Dates are re-asked this often when the screen is opened; pull-to-refresh ignores it. */
    const val TTL_MS = 30 * 60_000L

    suspend fun calendar(symbol: String): Date? {
        val res = FundamentalsFeed.quoteSummary(symbol.uppercase(), "calendarEvents") ?: return null
        return runCatching { parse(res) }.getOrNull()
    }

    internal fun parse(res: JSONObject): Date? {
        val e = res.optJSONObject("calendarEvents")?.optJSONObject("earnings") ?: return null
        val arr = e.optJSONArray("earningsDate") ?: return null
        val secs = (0 until arr.length()).mapNotNull {
            when (val o = arr.opt(it)) {
                is JSONObject -> o.yahooNum("raw")
                is Number -> o.toDouble()
                else -> null
            }
        }.filter { it > 0 }
        if (secs.isEmpty()) return null
        val start = (secs.min() * 1000).toLong()
        val end = (secs.max() * 1000).toLong()
        // Two different timestamps are a window, i.e. not a date the company has fixed.
        val estimated = e.optBoolean("isEarningsDateEstimate", false) || start != end
        return Date(start, end, estimated, System.currentTimeMillis())
    }

    private val ET: ZoneId = ZoneId.of("America/New_York")

    /**
     * The calendar day of a report. A stamp near midnight UTC is a "date only" value (the day
     * IS the UTC day - converting to New York would put it on the evening before); anything
     * else is a real time of day, whose day is the New York one.
     */
    internal fun dayOf(ms: Long): LocalDate {
        val utc = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
        return if (utc.hour < 6) utc.toLocalDate() else Instant.ofEpochMilli(ms).atZone(ET).toLocalDate()
    }

    /** "Before open" / "After close" from a real time of day; blank for a date-only stamp. */
    internal fun timingOf(ms: Long): String {
        val utc = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
        if (utc.hour < 6) return ""
        val et = Instant.ofEpochMilli(ms).atZone(ET)
        val minutes = et.hour * 60 + et.minute
        return when {
            minutes < 9 * 60 + 30 -> "Before open"
            minutes >= 16 * 60 -> "After close"
            else -> "During market hours"
        }
    }

    internal fun encode(d: Date): String =
        "${d.startMs}|${d.endMs}|${if (d.estimated) 1 else 0}|${d.fetched}"

    internal fun decode(s: String): Date? = runCatching {
        val p = s.split("|")
        Date(p[0].toLong(), p[1].toLong(), p[2] == "1", p[3].toLong())
    }.getOrNull()
}
