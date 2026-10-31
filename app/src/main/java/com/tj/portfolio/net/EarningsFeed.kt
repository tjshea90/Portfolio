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

    // ------------------------------------------------------------ RECENT REPORTS (2026-10-31)

    /**
     * A report that HAS happened: what was expected, what landed, and how the stock moved.
     * Tj: "show recent earnings reports for the stocks I hold, within the last 1 month ...
     * whether the report was good, neutral, or poor, whether it beat expectations".
     */
    data class Report(
        val dateMs: Long,
        val quarter: String = "",
        val epsActual: Double,
        val epsEstimate: Double,
        /** (actual - estimate) / |estimate| in percent; 0 when there was no estimate. */
        val surprisePct: Double,
        /** Close after the report vs close before it, percent; null when candles do not cover it. */
        val reactionPct: Double? = null,
        val fetched: Long = 0L
    ) {
        val day: LocalDate get() = dayOf(dateMs)
        val hasEstimate: Boolean get() = epsEstimate != 0.0
    }

    /** Good / neutral / poor, from the surprise first and the stock's reaction as a tiebreaker. */
    enum class Verdict { GOOD, NEUTRAL, POOR }

    fun verdict(r: Report): Verdict {
        val s = if (r.hasEstimate) r.surprisePct else if (r.epsActual > 0) 0.0 else -5.0
        val move = r.reactionPct
        return when {
            s >= 3.0 && (move == null || move > -3.0) -> Verdict.GOOD
            s <= -3.0 && (move == null || move < 3.0) -> Verdict.POOR
            s > -3.0 && s < 3.0 && move != null && move >= 5.0 && s >= 0 -> Verdict.GOOD
            s > -3.0 && s < 3.0 && move != null && move <= -5.0 && s <= 0 -> Verdict.POOR
            s >= 1.0 && (move == null || move >= 0) -> Verdict.GOOD
            s <= -1.0 && (move == null || move <= 0) -> Verdict.POOR
            else -> Verdict.NEUTRAL
        }
    }

    /** "Beat" / "Missed" / "In line" - the plain-English half of the verdict. */
    fun beatWord(r: Report): String = when {
        !r.hasEstimate -> "Reported"
        r.surprisePct >= 1.0 -> "Beat"
        r.surprisePct <= -1.0 -> "Missed"
        else -> "In line"
    }

    /** Reports newer than this still show. */
    const val RECENT_DAYS = 30L

    /** Reports are a fact of the past; a few hours old is fresh. Pull-to-refresh bypasses. */
    const val REPORT_TTL_MS = 6 * 3_600_000L

    /**
     * Yahoo's chart endpoint carries `events.earnings` (report date, EPS actual and estimate)
     * next to the candles when asked with `events=earn`; the same candles give the price
     * reaction for free. Null = could not be fetched (keep what is held); empty = none lately.
     */
    suspend fun recent(symbol: String, nowMs: Long = System.currentTimeMillis()): List<Report>? {
        for (host in listOf("query1", "query2")) {
            val url = "https://$host.finance.yahoo.com/v8/finance/chart/" + MarketData.enc(symbol.uppercase()) +
                "?range=6mo&interval=1d&events=earn"
            val r = Http.get(url, mapOf("Accept" to "application/json"))
            if (r.throttledLocally || !r.ok) continue
            val parsed = runCatching { parseReports(r.body, nowMs) }.getOrNull() ?: continue
            return parsed
        }
        return null
    }

    internal fun parseReports(body: String, nowMs: Long): List<Report>? {
        val res = JSONObject(body).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val ev = res.optJSONObject("events")?.optJSONObject("earnings") ?: return emptyList()
        val ts = res.optJSONArray("timestamp")
        val closes = res.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        val out = ArrayList<Report>()
        for (k in ev.keys()) {
            val o = ev.optJSONObject(k) ?: continue
            val secs = o.optLong("date", k.toLongOrNull() ?: 0L)
            if (secs <= 0) continue
            val actual = o.yahooNum("epsActual") ?: continue
            val est = o.yahooNum("epsEstimate") ?: 0.0
            val ms = secs * 1000
            val surprise = if (est != 0.0) (actual - est) / kotlin.math.abs(est) * 100.0 else 0.0
            out.add(Report(ms, o.optString("quarter", ""), actual, est, surprise, reaction(ts, closes, ms), nowMs))
        }
        return out.sortedByDescending { it.dateMs }
    }

    /** Close of the first session after the report day vs the last close before it. */
    internal fun reaction(ts: org.json.JSONArray?, closes: org.json.JSONArray?, reportMs: Long): Double? {
        if (ts == null || closes == null) return null
        val day = dayOf(reportMs)
        var before: Double? = null
        var after: Double? = null
        for (i in 0 until minOf(ts.length(), closes.length())) {
            if (closes.isNull(i)) continue
            val c = closes.optDouble(i, Double.NaN)
            if (c.isNaN() || c <= 0) continue
            val d = dayOf(ts.optLong(i) * 1000)
            if (d.isBefore(day)) before = c
            else if (d.isAfter(day) && after == null) after = c
        }
        return if (before != null && after != null) (after / before - 1.0) * 100.0 else null
    }

    internal fun encodeReports(list: List<Report>): String = org.json.JSONArray().also { a ->
        list.forEach { r ->
            a.put(JSONObject().put("d", r.dateMs).put("q", r.quarter).put("a", r.epsActual)
                .put("e", r.epsEstimate).put("s", r.surprisePct).put("f", r.fetched)
                .also { o -> r.reactionPct?.let { o.put("r", it) } })
        }
    }.toString()

    internal fun decodeReports(s: String?): List<Report>? = runCatching {
        val a = org.json.JSONArray(s ?: return null)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Report(o.getLong("d"), o.optString("q"), o.getDouble("a"), o.getDouble("e"), o.getDouble("s"),
                if (o.has("r")) o.getDouble("r") else null, o.optLong("f"))
        }
    }.getOrNull()
}
