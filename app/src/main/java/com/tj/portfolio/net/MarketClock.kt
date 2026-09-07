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
}
