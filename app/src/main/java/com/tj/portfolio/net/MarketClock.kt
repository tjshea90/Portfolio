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
 * Holidays are not in here - there is no free calendar for them and hard-coding dates rots.
 * [PortfolioViewModel] covers that case from the other end: when the exchange timestamp on
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
        val m = etMinutes(c)
        return when {
            m >= 9 * 60 + 30 && m < 16 * 60 -> Phase.OPEN
            m >= 4 * 60 && m < 9 * 60 + 30 -> Phase.EXTENDED
            m >= 16 * 60 && m < 20 * 60 -> Phase.EXTENDED
            else -> Phase.CLOSED
        }
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
        return (CLOSE_MINUTE - etMinutes(c)).coerceIn(0, CLOSE_MINUTE - OPEN_MINUTE)
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
                val total = (CLOSE_MINUTE - OPEN_MINUTE).toDouble()
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
