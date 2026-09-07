package com.tj.portfolio.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * THREAD SAFETY: DecimalFormat and SimpleDateFormat are both mutable and explicitly not
 * thread-safe. These helpers are called from composition (main thread) AND from IO threads
 * (backup naming, the Claude portfolio JSON, news parsing), so sharing one instance can
 * yield garbled output or throw. Every formatter is therefore held in a ThreadLocal.
 */
object Fmt {
    private fun dec(pattern: String) = object : ThreadLocal<java.text.DecimalFormat>() {
        override fun initialValue() = java.text.DecimalFormat(pattern)
    }

    private fun date(pattern: String) = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat(pattern, Locale.US)
    }

    private val moneyTl = dec("#,##0.00")
    private val money3Tl = dec("#,##0.000")
    /**
     * Two decimals minimum, three when the value actually has a half-cent in it.
     *
     * Prices really do carry half-cents - a live reconciliation against Ally showed ONDS at
     * 7.565 and NVDA at 230.115 on the broker's own screen, while this app rounded both to
     * two places. On 45 shares of ONDS that is a 22c gap between two market values sitting
     * side by side, with nothing to explain it. The arithmetic was never affected (positions
     * are computed from the full double); only the number being eyeballed was.
     *
     * "#,##0.00#" is what makes it non-annoying: a whole-cent price still prints as $7.56,
     * NOT $7.560, so nothing gains a pointless trailing zero.
     */
    private val priceTl = dec("#,##0.00#")
    private val qtyTl = dec("#,##0.####")

    private val money: java.text.DecimalFormat get() = moneyTl.get()!!
    private val money3: java.text.DecimalFormat get() = money3Tl.get()!!
    private val priceFmt: java.text.DecimalFormat get() = priceTl.get()!!
    private val qty: java.text.DecimalFormat get() = qtyTl.get()!!

    fun usd(v: Double): String = "$" + money.format(v)

    fun usdSigned(v: Double): String = (if (v >= 0) "+$" else "-$") + money.format(abs(v))

    /** Sub-dollar stocks get more precision, matching how brokers print them. */
    fun price(v: Double): String =
        if (v != 0.0 && abs(v) < 1.0) "$" + money3.format(v) else "$" + priceFmt.format(v)

    fun priceBare(v: Double): String =
        if (v != 0.0 && abs(v) < 1.0) money3.format(v) else priceFmt.format(v)

    fun pct(v: Double): String = String.format(Locale.US, "%.2f%%", v)

    fun pctSigned(v: Double): String =
        (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f%%", v)

    fun changeSigned(v: Double): String =
        (if (v >= 0) "+" else "-") + priceBare(abs(v))

    /**
     * A price change sized to the stock, not to the change. A 13-cent move on an $89 ETF
     * should read "+0.13", not "+0.1300" - the extra digits pushed the percentage off the
     * edge of the row. Sub-dollar stocks still get four decimals, where they matter.
     */
    fun changeFor(price: Double, v: Double): String =
        (if (v >= 0) "+" else "-") +
            (if (price > 0 && price < 1.0) money3 else priceFmt).format(abs(v))

    /**
     * Same digits as [changeFor], written as money: "+$1.24", "-$0.0135". Used where the
     * change sits next to an actual price, so a bare "+1.24" would be ambiguous.
     */
    fun changeMoney(price: Double, v: Double): String =
        (if (v >= 0) "+$" else "-$") +
            (if (price > 0 && price < 1.0) money3 else priceFmt).format(abs(v))

    fun shares(v: Double): String = qty.format(v)

    fun compact(v: Double): String {
        val a = abs(v)
        return when {
            a >= 1e12 -> String.format(Locale.US, "%.2fT", v / 1e12)
            a >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
            a >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
            a >= 1e3 -> String.format(Locale.US, "%.2fK", v / 1e3)
            else -> money.format(v)
        }
    }

    private val dayTl = date("MMM d, yyyy")
    private val shortTl = date("MMM d")
    private val timeTl = date("h:mm a")
    private val isoTl = date("yyyy-MM-dd")

    private val dayFmt: SimpleDateFormat get() = dayTl.get()!!
    private val shortFmt: SimpleDateFormat get() = shortTl.get()!!
    private val timeFmt: SimpleDateFormat get() = timeTl.get()!!
    private val isoFmt: SimpleDateFormat get() = isoTl.get()!!

    fun day(ms: Long): String = dayFmt.format(Date(ms))
    /** "Sep 3" - used to name the trading session a day figure refers to. */
    fun shortDay(ms: Long): String = shortFmt.format(Date(ms))
    fun iso(ms: Long): String = isoFmt.format(Date(ms))
    fun clock(ms: Long): String = timeFmt.format(Date(ms))

    fun relative(ms: Long): String {
        val diff = System.currentTimeMillis() - ms
        return when {
            diff < 5_000 -> "just now"
            diff < 60_000 -> "${diff / 1000}s ago"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000}d ago"
            else -> shortFmt.format(Date(ms))
        }
    }

    /** Parse yyyy-MM-dd (or MM/dd/yyyy) into epoch millis at local midnight. */
    fun parseDate(s: String): Long? {
        val t = s.trim()
        val patterns = listOf("yyyy-MM-dd", "MM/dd/yyyy", "M/d/yyyy", "MM/dd/yy", "MMM d, yyyy", "MMM d yyyy")
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.isLenient = false
                val d = f.parse(t) ?: continue
                val c = Calendar.getInstance()
                c.time = d
                c.set(Calendar.HOUR_OF_DAY, 12)
                c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                return c.timeInMillis
            } catch (e: Exception) { /* try next */ }
        }
        return null
    }

    fun todayMs(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * Publication date from a feed. Every shape the app's own sources actually emit, checked
     * against the live feeds rather than assumed.
     *
     * THREE THINGS HERE ARE LOAD-BEARING. All three were real defects, and the symptom of
     * each is a headline with a plausible-looking but wrong timestamp - which is worse than
     * no timestamp, because `relative()` prints it and the feed sorts on it.
     *
     * 1. A TIMEZONE-LESS STAMP IS UTC, NOT THE PHONE'S CLOCK. Investing.com publishes
     *    `<pubDate>2026-09-04 18:38:16</pubDate>` in UTC with no marker at all. Parsed in the
     *    device's zone that is 18:38 EASTERN - four hours in the FUTURE. `Fmt.relative` sees
     *    a negative age and answers "just now" for every one of those headlines for four
     *    hours, and `sortedByDescending { published }` floats the whole feed to the top of
     *    the Live feed's All tab above genuinely fresher stories. Verified against the live
     *    feed: its newest item was 7 minutes old in UTC and 4h07m in the future in local time.
     *
     * 2. FRACTIONAL SECONDS NEED THEIR OWN PATTERNS. `2026-09-04T18:30:45.000Z` matches none
     *    of the second-precision ISO patterns, so it used to fall through to the bare
     *    `yyyy-MM-dd` one and land on midnight - the time thrown away and the date shifted by
     *    the UTC offset. EDGAR's atom feed does not send milliseconds today, but it is one
     *    publisher change away, and this is precisely what would flatten the filing ordering
     *    the `<updated>`/`<filing-date>` split was written to preserve.
     *
     * 3. A PATTERN MUST CONSUME THE WHOLE STRING. SimpleDateFormat.parse stops at the first
     *    character it cannot use and reports success on what it read, so `yyyy-MM-dd` happily
     *    "parses" a full timestamp and silently discards the time - which is how (2) turned
     *    into a wrong answer instead of no answer. Non-lenient parsing plus a ParsePosition
     *    check means a pattern either matches all of it or is skipped.
     */
    private val RSS_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        // fractional seconds first: a pattern without them cannot match a stamp that has them
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",   // ISO with a +00:00 style offset
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )

    /** Patterns that name no zone. Feeds that omit one publish in UTC - see note 1 above. */
    private fun assumesUtc(p: String) =
        p.endsWith("'Z'") || p == "yyyy-MM-dd HH:mm:ss" || p == "yyyy-MM-dd"

    fun parseRss(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0L
        for (p in RSS_PATTERNS) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.isLenient = false
                if (assumesUtc(p)) f.timeZone = TimeZone.getTimeZone("UTC")
                val pos = java.text.ParsePosition(0)
                val d = f.parse(t, pos) ?: continue
                // the pattern has to account for every character, or it is the wrong pattern
                if (pos.index != t.length) continue
                return d.time
            } catch (e: Exception) { /* try next */ }
        }
        return 0L
    }
}
