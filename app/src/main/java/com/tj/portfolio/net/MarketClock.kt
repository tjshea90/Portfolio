package com.tj.portfolio.net

import java.util.Calendar
import java.util.TimeZone

/**
 * What the US market is doing right now, worked out from the device clock in New York time.
 *
 * WHY THIS EXISTS: the refresh loop used to run at the same speed forever. At 3am on a
 * Sunday it was still asking for every holding's price four times a minute - tens of
 * thousands of pointless requests a week for numbers that cannot change until Monday. That
 * is wasted battery and mobile data, and it is the surest way to get rate-limited by a free
 * feed that owes us nothing.
 *
 * Deliberately computed locally rather than read from a quote: the app needs to know how
 * often to ASK before it has anything to look at, and on a night when every request fails
 * there is no quote to read a market state from.
 *
 * HOLIDAYS AND EARLY CLOSES ARE COMPUTED FROM THE EXCHANGE'S OWN RULES (full-tests audit
 * 2026-09-22, D-M4/D-L9) - see [isHoliday] and [closeMinute]. This header used to say there was
 * no free calendar and hard-coded dates rot, both true and neither the point: NYSE's calendar
 * is RULES ("the fourth Thursday of November", "a Saturday holiday is observed on the Friday"),
 * which work out any year's dates with nothing to maintain. Before this, 10am on Thanksgiving
 * was OPEN - polling every holding at the user's market-hours rate all day - and the 1pm half
 * days after Thanksgiving and on Christmas Eve / July 3 ran the Day Trading clock to 16:00, so
 * a plan made at 12:45 was told it had three and a quarter hours when it had fifteen minutes.
 * One-off closures (a national day of mourning) cannot be predicted by any rule;
 * [PortfolioViewModel] still covers those from the other end - when the exchange timestamp on
 * the quotes stops advancing during what this class calls open hours, it backs off anyway.
 */
object MarketClock {

    enum class Phase {
        /** 09:30-16:00 ET on a weekday. */
        OPEN,

        /** 04:00-09:30 and 16:00-20:00 ET - pre-market and after-hours trading. */
        EXTENDED,

        /** Overnight, and all weekend. */
        CLOSED
    }

    private val ET: TimeZone = TimeZone.getTimeZone("America/New_York")

    /** Minutes since midnight, New York time. */
    private fun etMinutes(c: Calendar) = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)

    fun phase(now: Long = System.currentTimeMillis()): Phase {
        val c = Calendar.getInstance(ET)
        c.timeInMillis = now
        val dow = c.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return Phase.CLOSED
        if (isHoliday(c)) return Phase.CLOSED
        val m = etMinutes(c)
        val close = closeMinute(c)
        // After-hours runs four hours past the close: to 20:00 on a normal day, 17:00 on a half day.
        return when {
            m >= OPEN_MINUTE && m < close -> Phase.OPEN
            m >= 4 * 60 && m < OPEN_MINUTE -> Phase.EXTENDED
            m >= close && m < close + 4 * 60 -> Phase.EXTENDED
            else -> Phase.CLOSED
        }
    }

    // ------------------------------------------------------------------ the exchange calendar

    /** A full-day NYSE closure, on the ET calendar date [c] holds. Weekends are not asked. */
    internal fun isHoliday(c: Calendar): Boolean {
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val mon = dow == Calendar.MONDAY
        fun observed(month: Int, day: Int): Boolean =
            (m == month && d == day) ||
                // Saturday's holiday is taken on the Friday before, Sunday's on the Monday after.
                (m == month && d == day - 1 && dow == Calendar.FRIDAY) ||
                (m == month && d == day + 1 && mon)
        return when {
            // New Year's: a Sunday one is taken on Monday the 2nd. A SATURDAY one is not taken
            // at all - NYSE Rule 7.2 keeps 31 December open for year-end accounting.
            m == 1 && (d == 1 || (d == 2 && mon)) -> true
            m == 1 && mon && d in 15..21 -> true                 // Martin Luther King Jr. Day
            m == 2 && mon && d in 15..21 -> true                 // Washington's Birthday
            isGoodFriday(y, m, d) -> true
            m == 5 && mon && d >= 25 -> true                     // Memorial Day, last Monday
            y >= 2022 && observed(6, 19) -> true                 // Juneteenth
            observed(7, 4) -> true                               // Independence Day
            m == 9 && mon && d <= 7 -> true                      // Labor Day
            m == 11 && dow == Calendar.THURSDAY && d in 22..28 -> true   // Thanksgiving
            observed(12, 25) -> true                             // Christmas
            else -> false
        }
    }

    /**
     * The regular session's closing minute (ET) on [c]'s date: 13:00 on NYSE's three standing
     * early-close days, 16:00 otherwise. The early closes are the day after Thanksgiving, and
     * July 3 and December 24 whenever they fall Monday-Thursday - on a Friday the next day's
     * holiday is itself observed on it, and on a weekend there is no session to shorten.
     */
    internal fun closeMinute(c: Calendar): Int {
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val monToThu = dow in Calendar.MONDAY..Calendar.THURSDAY
        val early = (m == 11 && dow == Calendar.FRIDAY && d in 23..29) ||
            (m == 7 && d == 3 && monToThu) ||
            (m == 12 && d == 24 && monToThu)
        return if (early) EARLY_CLOSE_MINUTE else CLOSE_MINUTE
    }

    /**
     * The first regular-session opening bell (09:30 ET on a trading day) strictly after [t].
     * Anything that can only change while the market is OPEN is unchanged from [t] until then.
     */
    fun nextOpenAfter(t: Long): Long {
        val c = Calendar.getInstance(ET)
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 9); c.set(Calendar.MINUTE, 30)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= t) c.add(Calendar.DAY_OF_MONTH, 1)
        // Two weeks is far past the longest run of closed days the calendar can produce.
        repeat(14) {
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY && !isHoliday(c)) return c.timeInMillis
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }

    /**
     * Whole NEW YORK calendar days from [now] until [t]: 0 later today, 1 tomorrow, and so on
     * (full-tests audit 2026-09-22, U-L3). Counting 24-hour blocks instead called a report due
     * at 09:00 "today" when it was asked about at 20:00 the night before, and one two mornings
     * away "tomorrow". A moment already PAST is always at least -1, so a release earlier today
     * reads as done rather than as still to come - the rule the floor division it replaces was
     * written to guarantee.
     */
    fun daysUntil(t: Long, now: Long = System.currentTimeMillis()): Long {
        val zone = java.time.ZoneId.of("America/New_York")
        val from = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val to = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        val d = java.time.temporal.ChronoUnit.DAYS.between(from, to)
        return if (t < now) minOf(d, -1L) else d
    }

    /** Easter Sunday by the anonymous Gregorian algorithm; Good Friday is two days before. */
    private fun isGoodFriday(y: Int, m: Int, d: Int): Boolean {
        val a = y % 19; val b = y / 100; val cc = y % 100
        val dd = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - dd - g + 15) % 30
        val i = cc / 4; val k = cc % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val mm = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * mm + 114) / 31
        val day = (h + l - 7 * mm + 114) % 31 + 1
        val easter = Calendar.getInstance(ET).apply { clear(); set(y, month - 1, day) }
        easter.add(Calendar.DAY_OF_MONTH, -2)
        return easter.get(Calendar.MONTH) + 1 == m && easter.get(Calendar.DAY_OF_MONTH) == d
    }

    /**
     * How often to re-pull prices, given the user's chosen interval for market hours.
     *
     * The user's setting is honoured exactly while the market is open - that is the number
     * they chose and the only time it matters. Outside those hours it is a floor, not a
     * target: prices move slowly before the open and hardly at all overnight.
     */
    fun quoteIntervalSecs(userSecs: Int, now: Long = System.currentTimeMillis()): Int {
        if (userSecs <= 0) return 0            // the user turned auto-refresh off entirely
        return when (phase(now)) {
            Phase.OPEN -> userSecs
            Phase.EXTENDED -> maxOf(userSecs, 60)
            Phase.CLOSED -> maxOf(userSecs, 900)   // 15 minutes
        }
    }

    /** Headlines and filings ride a much slower clock than prices. */
    fun feedIntervalSecs(now: Long = System.currentTimeMillis()): Int = when (phase(now)) {
        Phase.OPEN -> 180
        Phase.EXTENDED -> 600
        Phase.CLOSED -> 1800
    }

    /** Plain-language label for the Settings screen. */
    fun label(now: Long = System.currentTimeMillis()): String = when (phase(now)) {
        Phase.OPEN -> "Market open"
        Phase.EXTENDED -> "Extended hours"
        Phase.CLOSED -> "Market closed"
    }

    /** 16:00 ET, as minutes since New York midnight - the close, and the day trade's deadline. */
    private const val CLOSE_MINUTE = 16 * 60
    private const val EARLY_CLOSE_MINUTE = 13 * 60

    /** 09:30 ET. */
    private const val OPEN_MINUTE = 9 * 60 + 30

    /**
     * HOW MANY MINUTES OF THE REGULAR SESSION ARE LEFT (Round 73). 0 when it is not open.
     *
     * ---- WHY THE DAY-TRADING PLAN NEEDS A CLOCK AT ALL
     *
     * Until now [ResearchScore.tradePlan] built the identical plan at 09:35 and at 15:55. It
     * cannot: a day trade is closed the same session, so "buy the break above $X, first
     * objective 2R higher" is a real instruction in the morning and an impossible one nine
     * minutes before the bell - there is no time left for the move the target assumes. Every
     * profitable variant in the published day-trading literature this feature is built from
     * (Zarattini, Barbon & Aziz 2024, and the SPY intraday-momentum paper that followed it)
     * closes its positions at 16:00 ET; the exit is not optional and it is not negotiable, so
     * the plan has to know how much room the clock leaves.
     *
     * EXTENDED HOURS COUNT AS ZERO, not as negative and not as the next session's minutes.
     * Pre-market and after-hours trade thinly on wide spreads and none of the intraday
     * structure this feature reads - VWAP, the opening range, the session high - is defined
     * there. "No regular-session minutes remain" is the honest answer at 08:00 and at 18:00
     * alike; what the plan does with that is [ResearchScore]'s decision, not this clock's.
     */
    fun minutesLeftInSession(now: Long = System.currentTimeMillis()): Int {
        if (phase(now) != Phase.OPEN) return 0
        val c = Calendar.getInstance(ET)
        c.timeInMillis = now
        val close = closeMinute(c)
        return (close - etMinutes(c)).coerceIn(0, close - OPEN_MINUTE)
    }

    /**
     * HOW MUCH OF THE REGULAR SESSION HAS ALREADY HAPPENED, 0.0 to 1.0 (Round 73).
     *
     * ---- THE BUG THIS EXISTS TO PREVENT, WHICH A CODE REVIEW CAUGHT BEFORE SHIPPING
     *
     * `ScreenRow.volumeRatio` divides volume SO FAR TODAY by a full THREE-MONTH DAILY average.
     * Those are not the same unit. At 10:00 ET a stock running at three times its normal pace
     * has still only traded a fraction of a normal DAY, so its ratio reads something like 0.3 -
     * and a naive "relative volume must be at least 1.0" gate would have excluded it, and
     * almost everything else, for the entire morning. Pre-market it is worse: the day's volume
     * is 0, so the ratio is 0 and the gate would empty the section completely, including the
     * overnight planning path the rest of this feature deliberately supports.
     *
     * This function supplies the clock half of the correction that compares like with like.
     *
     * ---- BUT THE CLOCK IS NOT THE ANSWER ON ITS OWN, AND THIS FUNCTION IS NOT THE CONVERSION
     *
     * Real intraday volume is U-shaped, not flat - the open and the close carry far more than
     * their share of the day (Wood, McInish & Ord 1985; Harris 1986). So at 10:00 a stock at
     * genuinely normal pace has already done MORE of its day's volume than the 8% of the clock
     * that has elapsed - roughly a sixth of it. Dividing by the elapsed clock fraction alone
     * would therefore report a completely ordinary stock as running at twice its normal rate
     * all morning, and the list would be nothing but that artefact.
     *
     * So callers do NOT use this number as the expected share of the day's volume. They pass it
     * to [ResearchScore.expectedVolumeFraction], which bends it into that share, and compare
     * against the result - see [ResearchScore.pacedVolumeRatio]. What this function returns is
     * only ever "how much of the session has elapsed": a clock reading, nothing more. A second
     * code-review pass replaced an earlier linear use of it, and this paragraph with it.
     */
    fun sessionElapsedFraction(now: Long = System.currentTimeMillis()): Double {
        val c = Calendar.getInstance(ET)
        c.timeInMillis = now
        return when (phase(now)) {
            Phase.OPEN -> {
                val total = (closeMinute(c) - OPEN_MINUTE).toDouble()
                ((etMinutes(c) - OPEN_MINUTE) / total).coerceIn(0.0, 1.0)
            }
            // Before the opening bell nothing of today has traded yet, so no comparison against
            // today's participation is possible at all - which is a 0 threshold, not a 1.
            Phase.EXTENDED -> if (etMinutes(c) < OPEN_MINUTE) 0.0 else 1.0
            // Overnight and weekends: the volume figure describes a session that finished.
            Phase.CLOSED -> 1.0
        }
    }

    /**
     * THE MIDDAY LULL - 11:30-13:30 ET, when intraday continuation setups work least well.
     *
     * The U-shape in intraday volume and volatility is one of the oldest documented facts in
     * market microstructure (Wood, McInish & Ord 1985; Harris 1986), and spreads follow it
     * (McInish & Wood 1992): heaviest and widest at the open, thinnest and quietest over
     * lunch, active again into the close. Zarattini/Barbon/Aziz's own seasonality analysis
     * reports the same shape in trend continuation specifically - positive 10:00-12:00,
     * pausing over lunch, resuming into the afternoon.
     *
     * A CAUTION, NOT A BLOCK. The evidence for the pattern is strong; the evidence that
     * refusing to trade through it improves a given trader's results is not, so this only ever
     * warns - see [ResearchScore.tradePlan]. Blocking on it would be asserting more than the
     * sources support.
     */
    fun inMiddayLull(now: Long = System.currentTimeMillis()): Boolean {
        if (phase(now) != Phase.OPEN) return false
        val c = Calendar.getInstance(ET)
        c.timeInMillis = now
        val m = etMinutes(c)
        return m >= 11 * 60 + 30 && m < 13 * 60 + 30
    }

    /**
     * The trading day a timestamp falls in, e.g. "20260911" - New York time, not the device's,
     * so two phones in different time zones agree on when "today" turns over.
     *
     * Used to freeze the per-holding BUY/HOLD/SELL recommendation for the day it was computed
     * (`Recommend.build`, `PortfolioViewModel.loadRecommendation`): TJ asked that it not
     * reshuffle through the day as fundamentals happen to refresh underneath it on their own,
     * shorter TTL, so this - not a rolling one - is what gates a recompute.
     */
    fun dayKey(now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance(ET)
        c.timeInMillis = now
        return "%04d%02d%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }
}
