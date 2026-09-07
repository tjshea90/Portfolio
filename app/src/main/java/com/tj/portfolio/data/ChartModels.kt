package com.tj.portfolio.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE CHART RANGES, AND WHY EACH ONE IS SHAPED THE WAY IT IS.
 *
 * TJ's report was that the detail screen drew a price line with nothing anywhere saying how
 * much time it covered. It was always the same thing - one day at five-minute candles - so
 * there was no way to ask for anything else and no caption to say what you were looking at.
 *
 * Each range is a triple of (what Yahoo calls the window, what candle size to ask for, and
 * how long the answer stays good for). The candle size is chosen so a range comes back with
 * roughly 60-400 points: fewer than about 40 and the line is visibly a polygon, more than
 * about 500 and every extra point is drawn under a pixel already occupied.
 *
 * [ttlMs] IS THE WHOLE CACHING ARGUMENT. A 5-year monthly chart cannot change today; asking
 * for it again because the user tapped back into the screen is pure waste. An intraday chart
 * changes every candle. So the two are cached for wildly different lengths, and a
 * pull-to-refresh overrides all of it - see `PortfolioViewModel.loadChart(force = true)`.
 *
 * THE INTRADAY TTL IS THE CANDLE INTERVAL, NOT SOMETHING SHORTER. Asking Yahoo for a
 * 5-minute chart more often than every five minutes cannot return a point that did not exist
 * on the last call - it is the same reasoning that put the sparkline on a five-minute clock
 * in Round 56. Freshness at the right-hand edge is not bought with requests: the UI replaces
 * the final point with the live quote price it already has, so the line's tip is current to
 * the second while the series behind it is fetched twelve times an hour instead of sixty.
 */
enum class ChartRange(
    val label: String,
    /** Yahoo's `range` parameter. */
    val yRange: String,
    /** Yahoo's `interval` parameter. */
    val interval: String,
    /** Ask for pre/post-market candles too. */
    val prePost: Boolean,
    /** How long a fetched series stays good for, in ms. */
    val ttlMs: Long,
    /** What the caption under the chart says this window is. */
    val caption: String
) {
    D1("1D", "1d", "5m", true, 5 * 60_000L, "Today, 5-minute candles"),
    D5("5D", "5d", "30m", false, 5 * 60_000L, "Five trading days, 30-minute candles"),
    M1("1M", "1mo", "1d", false, 30 * 60_000L, "One month, daily closes"),
    M6("6M", "6mo", "1d", false, 6 * 3_600_000L, "Six months, daily closes"),
    Y1("1Y", "1y", "1d", false, 12 * 3_600_000L, "One year, daily closes"),
    Y5("5Y", "5y", "1wk", false, 24 * 3_600_000L, "Five years, weekly closes"),
    MAX("All", "max", "1mo", false, 24 * 3_600_000L, "Every year on record, monthly closes"),

    /**
     * ONLY the trading that happens outside 09:30-16:00 ET.
     *
     * Asked for as five days of five-minute candles WITH pre/post included, then filtered
     * down to the points that fall outside a regular session - so what is drawn is last
     * night's after-hours, the overnight session and this morning's pre-market, with the
     * regular day taken out from between them. The baseline is the last regular close before
     * the stretch, because that is what an overnight move is measured from.
     *
     * A one-day window is not enough: before 09:30 the only extended points that exist are
     * this morning's, and the after-hours session they follow on from belongs to yesterday.
     */
    OVERNIGHT("After hrs", "5d", "5m", true, 5 * 60_000L, "After-hours and overnight only");

    /** True when the x-axis is a clock rather than a calendar. */
    val intraday: Boolean get() = interval.endsWith("m") || interval.endsWith("h")

    companion object {
        val DEFAULT = D1
        fun byName(s: String?): ChartRange =
            entries.firstOrNull { it.name.equals(s, true) } ?: DEFAULT
    }
}

/** One candle close, with the moment it belongs to. */
data class ChartPoint(val t: Long, val close: Double)

/**
 * A fetched price series for one symbol over one range.
 *
 * [baseline] is what the change is measured FROM and what the dotted line is drawn at: the
 * previous regular close for [ChartRange.D1] and [ChartRange.OVERNIGHT], and simply the
 * first point for every longer range, where "the previous close" is not a meaningful
 * reference for a five-year line.
 *
 * [truncated] records that the provider returned fewer points than the range implies - a
 * stock that listed eighteen months ago has no five-year chart, and the UI says so rather
 * than drawing eighteen months and calling it five years.
 */
data class ChartSeries(
    val symbol: String,
    val range: ChartRange,
    val points: List<ChartPoint> = emptyList(),
    val baseline: Double = 0.0,
    val currency: String = "USD",
    val fetched: Long = 0L,
    val truncated: Boolean = false
) {
    val isEmpty: Boolean get() = points.size < 2
    val first: Double get() = points.firstOrNull()?.close ?: 0.0
    val last: Double get() = points.lastOrNull()?.close ?: 0.0

    /** What the line is measured from. Falls back to the first point when there is no close. */
    val from: Double get() = if (baseline > 0.0) baseline else first

    val change: Double get() = if (isEmpty || from <= 0.0) 0.0 else last - from
    val changePct: Double get() = if (isEmpty || from <= 0.0) 0.0 else (last - from) / from * 100.0

    val low: Double get() = points.minOfOrNull { it.close } ?: 0.0
    val high: Double get() = points.maxOfOrNull { it.close } ?: 0.0

    /** Oldest and newest timestamps actually present, in ms. 0 when there is nothing. */
    val startMs: Long get() = (points.firstOrNull()?.t ?: 0L) * 1000L
    val endMs: Long get() = (points.lastOrNull()?.t ?: 0L) * 1000L

    fun stale(now: Long = System.currentTimeMillis()): Boolean =
        fetched <= 0L || now - fetched > range.ttlMs
}

/**
 * The on-disk codec for [ChartSeries].
 *
 * Points are stored as two parallel arrays rather than an array of objects: a 400-point
 * intraday series is roughly 6 KB that way against 20 KB as `[{"t":..,"c":..}, ...]`, and
 * every one of those bytes is written and re-parsed on a cold start.
 *
 * PARSING IS TOTAL. A row that cannot be read comes back as an empty series, never as an
 * exception - a corrupt cache row must degrade to "fetch it again", not to a crash on the
 * screen that reads it.
 */
object ChartJson {

    fun encode(s: ChartSeries): String {
        val t = JSONArray()
        val c = JSONArray()
        s.points.forEach { t.put(it.t); c.put(it.close) }
        return JSONObject().apply {
            put("v", 1)
            put("symbol", s.symbol)
            put("range", s.range.name)
            put("t", t)
            put("c", c)
            put("baseline", s.baseline)
            put("currency", s.currency)
            put("fetched", s.fetched)
            put("truncated", s.truncated)
        }.toString()
    }

    fun decode(json: String): ChartSeries? = runCatching {
        val o = JSONObject(json)
        val t = o.optJSONArray("t") ?: return@runCatching null
        val c = o.optJSONArray("c") ?: return@runCatching null
        val n = minOf(t.length(), c.length())
        val pts = ArrayList<ChartPoint>(n)
        for (i in 0 until n) {
            val v = c.optDouble(i, Double.NaN)
            // Same rule as everywhere else in this app: a missing candle is skipped, never
            // read as a zero. A single fabricated 0.0 collapses the whole y-axis.
            if (v.isNaN() || v <= 0.0) continue
            pts.add(ChartPoint(t.optLong(i, 0L), v))
        }
        ChartSeries(
            symbol = o.optString("symbol").uppercase(),
            range = ChartRange.byName(o.optString("range")),
            points = pts,
            baseline = o.optDouble("baseline", 0.0).let { if (it.isFinite()) it else 0.0 },
            currency = o.optString("currency", "USD").ifBlank { "USD" },
            fetched = o.optLong("fetched", 0L),
            truncated = o.optBoolean("truncated", false)
        )
    }.getOrNull()
}
