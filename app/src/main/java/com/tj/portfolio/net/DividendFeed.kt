package com.tj.portfolio.net

import com.tj.portfolio.domain.DivEvent
import com.tj.portfolio.util.yahooNum
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * WHERE THE DIVIDEND NUMBERS COME FROM (2026-10-22). Two small Yahoo calls per symbol, both
 * already part of the app's locked source order, neither needing a key:
 *
 *  - [history]: the chart endpoint with `events=div|split`. Five years of monthly candles is a
 *    few KB, and the `events.dividends` block is the full payment history - ex-date and
 *    per-share amount, split-adjusted. The candles themselves are thrown away.
 *  - [calendar]: quoteSummary `calendarEvents,summaryDetail` through [FundamentalsFeed]'s shared
 *    crumb plumbing: the NEXT ex-date and pay date the company has announced, and the forward
 *    annual rate. These are the only dated facts about the future the provider states.
 *
 * Dates are read as UTC calendar days. Yahoo stamps an ex-date at either midnight UTC (the
 * calendar) or the 09:30 ET open (the chart) - UTC and ET agree on the day for the second and
 * ONLY UTC gets the first right (midnight UTC is 8pm the evening before in New York).
 */
object DividendFeed {

    data class Calendar(
        val exDate: LocalDate? = null,
        val payDate: LocalDate? = null,
        val annualRate: Double? = null,
        val yieldFraction: Double? = null,
        val fetched: Long = 0L
    )

    private const val CAL_MODULES = "calendarEvents,summaryDetail"
    private const val CAL_MINIMAL = "calendarEvents"

    /** History is a monthly-ish fact; a few hours old is not stale. Pull-to-refresh bypasses. */
    const val HISTORY_TTL_MS = 6 * 3_600_000L

    /** The calendar is conditional-GET cached by Http, so asking again is nearly free. */
    const val CALENDAR_TTL_MS = 30 * 60_000L

    fun historyUrl(host: String, symbol: String): String =
        "https://$host.finance.yahoo.com/v8/finance/chart/" + MarketData.enc(symbol) +
            "?range=5y&interval=1mo&events=div%7Csplit"

    suspend fun history(symbol: String): List<DivEvent>? {
        for (host in listOf("query1", "query2")) {
            val r = Http.get(historyUrl(host, symbol), mapOf("Accept" to "application/json"))
            if (r.throttledLocally || !r.ok) continue
            // A parse failure is NOT "no dividends": null tells the caller to keep what it has.
            val parsed = runCatching { parseHistory(r.body) }.getOrNull() ?: continue
            return parsed
        }
        return null
    }

    suspend fun calendar(symbol: String): Calendar? {
        val res = FundamentalsFeed.quoteSummary(symbol.uppercase(), CAL_MODULES, CAL_MINIMAL)
            ?: return null
        return runCatching { parseCalendar(res) }.getOrNull()
    }

    internal fun parseHistory(body: String): List<DivEvent>? {
        val result = JSONObject(body).optJSONObject("chart")
            ?.optJSONArray("result")?.optJSONObject(0) ?: return null
        // No `events` at all means "this symbol has paid nothing in the window" - a real,
        // cacheable answer - but only if the response was otherwise a valid chart.
        if (result.optJSONObject("meta") == null) return null
        val divs = result.optJSONObject("events")?.optJSONObject("dividends")
            ?: return emptyList()
        val out = ArrayList<DivEvent>(divs.length())
        val keys = divs.keys()
        while (keys.hasNext()) {
            val o = divs.optJSONObject(keys.next()) ?: continue
            val amount = o.optDouble("amount", 0.0)
            val secs = o.optLong("date", 0L)
            if (amount > 0.0 && amount.isFinite() && secs > 0L) out.add(DivEvent(utcDate(secs), amount))
        }
        // One row per ex-date: Yahoo has been seen to repeat a payment under two keys.
        return out.groupBy { it.exDate }.map { (d, v) -> DivEvent(d, v.maxOf { it.amount }) }
            .sortedBy { it.exDate }
    }

    internal fun parseCalendar(res: JSONObject): Calendar {
        val cal = res.optJSONObject("calendarEvents")
        val sum = res.optJSONObject("summaryDetail")
        val ex = cal.yahooNum("exDividendDate") ?: sum.yahooNum("exDividendDate")
        val pay = cal.yahooNum("dividendDate")
        val rate = sum.yahooNum("dividendRate") ?: sum.yahooNum("trailingAnnualDividendRate")
        val y = sum.yahooNum("dividendYield")
        return Calendar(
            exDate = ex?.takeIf { it > 0 }?.let { utcDate(it.toLong()) },
            payDate = pay?.takeIf { it > 0 }?.let { utcDate(it.toLong()) },
            annualRate = rate?.takeIf { it > 0 },
            // Fraction or percent depending on the Yahoo revision; no real stock yields >100%.
            yieldFraction = y?.let { if (it > 1.0) it / 100.0 else it },
            fetched = System.currentTimeMillis()
        )
    }

    private fun utcDate(epochSecs: Long): LocalDate =
        Instant.ofEpochSecond(epochSecs).atZone(ZoneOffset.UTC).toLocalDate()

    // ------------------------------------------------------------- persistence (settings table)

    internal fun encodeHistory(events: List<DivEvent>, fetched: Long): String =
        JSONObject().put("t", fetched).put("e", JSONArray().apply {
            events.forEach { put(JSONArray().put(it.exDate.toEpochDay()).put(it.amount)) }
        }).toString()

    internal fun decodeHistory(s: String): Pair<Long, List<DivEvent>>? = runCatching {
        val o = JSONObject(s)
        val a = o.getJSONArray("e")
        val ev = (0 until a.length()).map {
            val p = a.getJSONArray(it)
            DivEvent(LocalDate.ofEpochDay(p.getLong(0)), p.getDouble(1))
        }
        o.getLong("t") to ev
    }.getOrNull()

    internal fun encodeCalendar(c: Calendar): String = JSONObject()
        .put("t", c.fetched)
        .put("x", c.exDate?.toEpochDay() ?: JSONObject.NULL)
        .put("p", c.payDate?.toEpochDay() ?: JSONObject.NULL)
        .put("r", c.annualRate ?: JSONObject.NULL)
        .put("y", c.yieldFraction ?: JSONObject.NULL)
        .toString()

    internal fun decodeCalendar(s: String): Calendar? = runCatching {
        val o = JSONObject(s)
        Calendar(
            exDate = if (o.isNull("x")) null else LocalDate.ofEpochDay(o.getLong("x")),
            payDate = if (o.isNull("p")) null else LocalDate.ofEpochDay(o.getLong("p")),
            annualRate = if (o.isNull("r")) null else o.getDouble("r"),
            yieldFraction = if (o.isNull("y")) null else o.getDouble("y"),
            fetched = o.getLong("t")
        )
    }.getOrNull()
}
