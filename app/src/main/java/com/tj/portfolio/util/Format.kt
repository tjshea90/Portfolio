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
     * FOUR DECIMALS, for a price CHANGE on a sub-dollar stock only (Round 66).
     *
     * `changeFor` and `changeMoney` both claimed in their own KDoc that "sub-dollar stocks
     * still get four decimals, where they matter", and `changeMoney`'s worked example was
     * "-$0.0135" - but both took the three-decimal branch, so that exact move printed as
     * "-$0.014". Two comments describing a precision the code did not have, on the one class
     * of holding where the missing digit is the whole move: a stock at $0.42 trades in
     * hundredths of a cent, and rounding its change to a tenth of a cent can round a real
     * move to zero.
     *
     * Deliberately NOT used by [price]: a sub-dollar PRICE at three decimals is right, and
     * that formatter's own comment already matches its code.
     */
    private val money4Tl = dec("#,##0.0000")
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
    private val money4: java.text.DecimalFormat get() = money4Tl.get()!!
    private val priceFmt: java.text.DecimalFormat get() = priceTl.get()!!
    private val qty: java.text.DecimalFormat get() = qtyTl.get()!!

    /**
     * A value that ROUNDS to zero at the precision shown IS zero (full test 2026-09-24, U-7):
     * float noise in a realised P/L (-1e-15) printed "-$0.00" in red, and `pctSigned(-0.0)`
     * printed "+-0.00%". Also returns +0.0 for -0.0.
     */
    internal fun snapZero(v: Double, eps: Double = 0.005): Double = if (abs(v) < eps) 0.0 else v

    /** Sign outside the dollar, like [price] (U-7: this read "$-1,234.56"). */
    fun usd(v: Double): String {
        val s = snapZero(v)
        return (if (s < 0) "-$" else "$") + money.format(abs(s))
    }

    fun usdSigned(v: Double): String {
        val s = snapZero(v)
        return (if (s >= 0) "+$" else "-$") + money.format(abs(s))
    }

    /**
     * Sub-dollar stocks get more precision, matching how brokers print them.
     *
     * THE SIGN GOES OUTSIDE THE DOLLAR (Round 66 audit, DET-5). This used to format the signed
     * value and prepend "$", which reads "$-0.420" for a loss-making company's EPS - the only
     * place in the app that shape appears. Every other money formatter here writes "-$0.42"
     * ([usdSigned], and the MONEY branch of `formatMetric`), and the Stats card puts the two
     * side by side: "Net income (last 12m) -$18.40M" directly above "Earnings per share
     * $-0.420". Prices, targets and fills are never negative, so this only ever showed up on
     * the earnings figures - and it showed up on every one of them.
     */
    fun price(v: Double): String =
        (if (v < 0) "-$" else "$") + priceBare(abs(v))

    fun priceBare(v: Double): String =
        if (v != 0.0 && abs(v) < 1.0) money3.format(v) else priceFmt.format(v)

    fun pct(v: Double): String = String.format(Locale.US, "%.2f%%", snapZero(v))

    fun pctSigned(v: Double): String {
        val s = snapZero(v)
        return (if (s >= 0) "+" else "") + String.format(Locale.US, "%.2f%%", s)
    }

    fun changeSigned(v: Double): String {
        val s = snapZero(v, 0.0005)          // priceBare shows up to three decimals
        return (if (s >= 0) "+" else "-") + priceBare(abs(s))
    }

    /**
     * A price change sized to the stock, not to the change. A 13-cent move on an $89 ETF
     * should read "+0.13", not "+0.1300" - the extra digits pushed the percentage off the
     * edge of the row. Sub-dollar stocks still get four decimals, where they matter.
     */
    fun changeFor(price: Double, v: Double): String {
        val sub = price > 0 && price < 1.0
        val s = snapZero(v, if (sub) 0.00005 else 0.0005)
        return (if (s >= 0) "+" else "-") + (if (sub) money4 else priceFmt).format(abs(s))
    }

    /**
     * Same digits as [changeFor], written as money: "+$1.24", "-$0.0135". Used where the
     * change sits next to an actual price, so a bare "+1.24" would be ambiguous.
     */
    fun changeMoney(price: Double, v: Double): String {
        val sub = price > 0 && price < 1.0
        val s = snapZero(v, if (sub) 0.00005 else 0.0005)
        return (if (s >= 0) "+$" else "-$") + (if (sub) money4 else priceFmt).format(abs(s))
    }

    fun shares(v: Double): String = qty.format(v)

    /**
     * THE VALUE ITSELF, AS TEXT, WITH NOTHING ROUNDED AWAY (Round 66).
     *
     * ---- WHAT THIS IS FOR, AND THE BUG IT FIXES
     *
     * Every other formatter here is for READING. This one is for a text field the user can
     * edit and save back, where a display rounding becomes a permanent change to the ledger.
     *
     * The transaction editor used to seed its Price box with [priceBare], which gives two or
     * three decimals. Prices in this app routinely carry four: [Txn.unitPriceFromTotal]
     * derives them from a net total, so a 1,000-share buy for $1,559.50 is stored at 1.5595.
     * The editor showed "1.560", and `Txn.cashEffect` recomputes the cash from quantity x
     * price whenever both are present - so opening that transaction and pressing Save with
     * NOTHING CHANGED rewrote it as $1,560.00. Fifty cents of drift in the cash balance, the
     * cost basis and every figure derived from them, with no edit made and nothing on screen
     * saying so.
     *
     * ---- WHY `BigDecimal.valueOf` AND NOT `toBigDecimal()`
     *
     * `Double.toBigDecimal()` takes the EXACT binary value, so 230.115 comes back as
     * 230.11500000000000909494701772928237915039062500 - unreadable, and worse in a text box
     * than the rounding it replaced. `BigDecimal.valueOf(d)` goes through `Double.toString`,
     * which produces the shortest decimal that reads back as the same double: "230.115".
     * `stripTrailingZeros` then removes the "5.0" tail, and `toPlainString` keeps a very
     * small or very large number out of scientific notation, which no parser here accepts.
     *
     * ---- AND WHY IT IS THEN ROUNDED TO 12 SIGNIFICANT DIGITS (Round 66 audit, PUI-1)
     *
     * "The shortest decimal that reads back as the same double" is the right rule only when
     * the double is one somebody typed. Half the values seeded into these boxes are the
     * result of a DIVISION - `avgCost` is `costBasis / shares`, `unitPriceFromTotal` is a net
     * over a quantity - and a quotient is very often a double that no short decimal names.
     * 3,000 shares bought for $1,270.65 average `1270.65 / 3000.0`, whose nearest double is
     * not 0.42355 but 0.42355000000000004, so the shortest faithful decimal is exactly that:
     * seventeen digits in an edit box, over a price of forty-two cents.
     *
     * A double carries about 15-17 significant digits and the noise from an arithmetic like
     * that lands in the last one or two, so rounding to 12 removes it and touches nothing
     * else: no price, share count or quantity this app can hold needs more, and 12 digits is
     * a relative error of 1e-12, which on a $100,000 position is a ten-millionth of a cent.
     * The old behaviour never wrote a wrong number - it just showed an unreadable one, which
     * in a box the user is invited to edit is its own kind of wrong.
     *
     * `MathContext` counts SIGNIFICANT digits, not decimal places, which is what makes it
     * safe across magnitudes: `setScale(12)` would leave 12345.678900000001 untouched (its
     * noise is at the twelfth decimal) while mangling a genuinely small value.
     */
    fun exact(v: Double): String {
        if (v == 0.0) return "0"
        if (!v.isFinite()) return ""
        return java.math.BigDecimal.valueOf(v)
            .round(java.math.MathContext(12))
            .stripTrailingZeros()
            .toPlainString()
    }

    fun compact(v: Double): String {
        val a = abs(v)
        // THE UNIT IS PICKED AFTER ROUNDING (U-7): 999,995 is "1.00M", not "1000.00K".
        fun reaches(scale: Double) = a >= scale * (1 - 5e-6)
        return when {
            reaches(1e12) -> String.format(Locale.US, "%.2fT", v / 1e12)
            reaches(1e9) -> String.format(Locale.US, "%.2fB", v / 1e9)
            reaches(1e6) -> String.format(Locale.US, "%.2fM", v / 1e6)
            reaches(1e3) -> String.format(Locale.US, "%.2fK", v / 1e3)
            else -> money.format(snapZero(v))
        }
    }

    /**
     * A dollar amount at the scale funds are quoted at: "$4.21B", "$860.00M", "$412.00".
     *
     * [compact] with a dollar sign, split out rather than written at each call site because
     * the ETF card prints five of these and one of them missing its sign reads as a share
     * count. Round 63.
     */
    fun compactMoney(v: Double): String = (if (v < 0) "-$" else "$") + compact(abs(v))

    /** One decimal place. For quantities where two is false precision - a fund's age. */
    fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)

    private val dayTl = date("MMM d, yyyy")
    private val shortTl = date("MMM d")
    private val timeTl = date("h:mm a")
    private val isoTl = date("yyyy-MM-dd")
    private val yearTl = date("yyyy")

    private val dayFmt: SimpleDateFormat get() = dayTl.get()!!
    private val shortFmt: SimpleDateFormat get() = shortTl.get()!!
    private val timeFmt: SimpleDateFormat get() = timeTl.get()!!
    private val isoFmt: SimpleDateFormat get() = isoTl.get()!!
    private val yearFmt: SimpleDateFormat get() = yearTl.get()!!

    fun day(ms: Long): String = dayFmt.format(Date(ms))
    /** "Sep 3" - used to name the trading session a day figure refers to. */
    fun shortDay(ms: Long): String = shortFmt.format(Date(ms))
    fun iso(ms: Long): String = isoFmt.format(Date(ms))
    /** "2026" - used to decide whether a chart axis label needs its year spelled out. */
    fun year(ms: Long): String = yearFmt.format(Date(ms))
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

    /**
     * Parse yyyy-MM-dd (or MM/dd/yyyy) into epoch millis at local NOON - not midnight: a
     * midday stamp keeps the date the same in any nearby time zone, and the ledger's
     * same-day replay order relies on it (A-Q3, doc corrected 2026-09-24).
     */
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
                // A `yyyy` pattern takes "26" LITERALLY - year 26 AD - so "09/15/26" matched
                // "MM/dd/yyyy" and never reached "MM/dd/yy" (full-tests audit 2026-09-22,
                // A-L7). A trade dated in antiquity is a mis-parse, not a date: try the next
                // pattern, where the two-digit form resolves to this century.
                if (c.get(Calendar.YEAR) < 1900) continue
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
