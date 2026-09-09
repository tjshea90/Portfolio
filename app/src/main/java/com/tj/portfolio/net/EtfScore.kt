package com.tj.portfolio.net

import com.tj.portfolio.data.EtfRow
import com.tj.portfolio.util.Fmt
import kotlin.math.ln
import kotlin.math.max

/**
 * HOW THE APP RANKS A FUND (Round 63) - pure arithmetic, no network, no clock, no model.
 *
 * The same contract as [ResearchScore], and for the same three reasons: it is testable, it
 * shows its work in the user's own language, and a missing input scores zero for its
 * component rather than being guessed at.
 *
 * ---- WHY THESE FACTORS AND NOT OTHERS
 *
 * A fund is not a company, so none of the equity scorers apply. What every serious "how to
 * choose an ETF" checklist agrees on - State Street's and Schwab's both, and they are written
 * by people who sell the things - is cost, size, liquidity, tracking and long-run return. So:
 *
 *   * **Long-run return, weighted toward the long run (0-34).** Five-year and three-year
 *     ANNUALISED NAV returns carry more than YTD, and YTD carries more than three months.
 *     This is the single most important guard in the file: rank on a trailing year and the
 *     list fills with whatever sector happened to run, which is the classic way retail money
 *     arrives at the top.
 *   * **Cost (0-20).** The expense ratio is the only number in investing known in advance.
 *     Three basis points against sixty is more than half a percent a year, compounding, and
 *     it is subtracted from the return above whether the fund goes up or down.
 *   * **Size (0-16).** A fund under $50m can be closed by its issuer, which forces a sale at
 *     a time the holder did not choose and in a tax year they did not pick. Scored on a log
 *     scale because the difference between $50m and $500m matters and the difference between
 *     $50bn and $500bn does not.
 *   * **Liquidity (0-12).** Dollar volume, not share volume: the spread is paid out of
 *     dollars. Also log-scaled.
 *   * **Record length (0-8).** A five-year figure needs five years to exist. A fund launched
 *     eighteen months ago may be excellent and cannot yet have shown it.
 *   * **Trend (0-10).** Above its own 50- and 200-day averages. Small on purpose - it is the
 *     one momentum term here and it must not be able to outvote cost and returns.
 *
 * DELIBERATELY NOT SCORED: yield. A high distribution yield is not a better fund, it is a
 * different one - a bond fund and a growth fund are not competing on that axis - and adding
 * points for it would quietly rank the list by asset class. It is REPORTED on the card,
 * because it is the first thing a person wants to know, and it earns nothing.
 *
 * LEVERAGE AND INVERSE FUNDS ARE EXCLUDED, not penalised - see [isLeveragedOrInverse]. A 3x
 * fund's five-year annualised return is arithmetically enormous and says nothing about
 * whether holding it was a good idea; leaving them in would put them at the top of the list
 * every single time, which is the opposite of useful.
 */
object EtfScore {

    /** Linear ramp: 0 at [lo], [maxPoints] at [hi], clamped both ends. */
    internal fun ramp(v: Double, lo: Double, hi: Double, maxPoints: Double): Double {
        if (v.isNaN() || !v.isFinite() || hi == lo) return 0.0
        return ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) * maxPoints
    }

    /** Log-scaled ramp, for quantities spanning orders of magnitude. */
    internal fun logRamp(v: Double, lo: Double, hi: Double, maxPoints: Double): Double {
        if (v <= 0 || hi <= lo) return 0.0
        return ramp(ln(v), ln(lo), ln(hi), maxPoints)
    }

    /**
     * A leveraged or inverse fund, recognised from its NAME.
     *
     * From the name rather than from a field, because no keyless feed publishes a leverage
     * flag - but every one of these funds is required to say so on the tin, and they all do:
     * "Direxion Daily Semiconductor Bull 3X Shares", "ProShares UltraShort QQQ", "T-Rex 2X
     * Long NVIDIA". Matching on the printed name is not a heuristic here so much as reading
     * the label.
     *
     * ERRS TOWARD EXCLUDING. A plain fund wrongly excluded costs one row on a list of forty;
     * a 3x fund wrongly included takes the top of that list with a number that is not
     * comparable to anything else on it.
     */
    internal fun isLeveragedOrInverse(name: String, symbol: String): Boolean {
        val n = " " + name.lowercase()
            .replace('-', ' ').replace('/', ' ').replace(",", " ") + " "
        val s = symbol.uppercase()

        // ---- 1. AN EXPLICIT MULTIPLE. Unambiguous wherever it appears, and it catches the
        // great majority of these funds on its own: "Bull 3X Shares", "UltraPro", "2x Short",
        // "T-Rex 2X Long", "-1x".
        val multiples = listOf(
            " 2x ", " 3x ", " 4x ", " 1.5x ", " 1x ", " -1x ", " -2x ", " -3x ",
            "2x shares", "3x shares", " ultrapro ", " leveraged ", " double ", " triple "
        )
        if (multiples.any { n.contains(it) }) return true
        if (s.endsWith("3X") || s.endsWith("2X")) return true

        // ---- 2. WORDS THAT ONLY EVER MEAN INVERSE.
        if (n.contains(" inverse ") || n.contains(" bear ")) return true
        if (n.contains(" daily ") && n.contains(" bull ")) return true

        // ---- 3. "SHORT", WHICH IS THE HARD ONE.
        //
        // "ProShares Short QQQ" is an inverse fund. "iShares Short Treasury Bond ETF" is a
        // perfectly ordinary bond fund, and so are "PIMCO Enhanced Short Maturity",
        // "Vanguard Short-Term Bond" and "iShares Ultra Short-Term Bond" - which contains
        // BOTH of the words this function used to exclude on. Getting this wrong is not
        // cosmetic: short-duration bond funds are among the most widely held ETFs there are,
        // and dropping every one of them would leave the list unable to contain the safe half
        // of a portfolio.
        //
        // The distinguisher is what the word is short OF. An inverse fund is short an INDEX
        // or a STOCK ("Short QQQ", "Short 20+ Year Treasury"); a bond fund is short in
        // DURATION, and the word that follows says so. So the test is the next word, and
        // anything not in that list is treated as inverse - the safe direction, since a fund
        // wrongly excluded costs one row and a 3x fund wrongly included takes the top of the
        // list.
        val duration = setOf(
            "term", "terms", "duration", "maturity", "maturities",
            "treasury", "treasuries", "bond", "bonds", "government", "govt",
            "corporate", "credit", "municipal", "muni", "income", "aggregate",
            "tips", "securities", "debt", "yield"
        )
        val words = n.trim().split(Regex("\\s+"))
        var sawShort = false
        var everyShortIsDuration = true
        for (i in words.indices) {
            val w = words[i]
            if (w != "short" && w != "ultrashort") continue
            sawShort = true
            val next = words.getOrNull(i + 1)
            if (next == null || next !in duration) everyShortIsDuration = false
        }
        if (sawShort) return !everyShortIsDuration

        // ---- 4. "ULTRA" ON ITS OWN is ProShares' 2x prefix ("Ultra QQQ", "Ultra S&P500").
        // Reached only when the short-duration test above did not already clear the name.
        return n.contains(" ultra ")
    }

    /**
     * SCORE ONE FUND, 0-100, with the reasons behind it.
     *
     * [confidence] is the share of the six factors this row actually carried. The caller uses
     * it to drop rows scored mostly on absent data - a fund that published nothing but a
     * price would otherwise sit at a low score with no way to tell "genuinely poor" from
     * "nothing known".
     */
    fun best(r: EtfRow): ResearchScore.Scored {
        val why = ArrayList<String>()
        var s = 0.0
        var have = 0
        var want = 0

        // --- long-run return, weighted toward the long run (0-34)
        want++
        val hasLong = r.fiveYearAnnualPct != 0.0 || r.threeYearAnnualPct != 0.0
        if (hasLong || r.ytdReturnPct != 0.0 || r.oneYearPct != 0.0) {
            have++
            // 5Y annualised: 0 points at 0%/yr, full at 20%/yr. 20 is roughly double the
            // long-run return of the US market, so a fund only tops this out by having
            // genuinely doubled it over five years.
            var earned = 0.0
            var possible = 0.0
            if (r.fiveYearAnnualPct != 0.0) {
                earned += ramp(r.fiveYearAnnualPct, 0.0, 20.0, 14.0); possible += 14.0
            }
            if (r.threeYearAnnualPct != 0.0) {
                earned += ramp(r.threeYearAnnualPct, 0.0, 20.0, 10.0); possible += 10.0
            }
            if (r.oneYearPct != 0.0) { earned += ramp(r.oneYearPct, 0.0, 30.0, 6.0); possible += 6.0 }
            if (r.ytdReturnPct != 0.0) { earned += ramp(r.ytdReturnPct, 0.0, 25.0, 4.0); possible += 4.0 }

            // ---- YOUTH IS PENALISED ONCE, NOT TWICE (Round 66).
            //
            // THE BUG THIS FIXES. The four terms were simply added, so a fund with no
            // five-year figure lost those 14 points outright - and then lost points AGAIN on
            // the record-length factor below, which exists precisely to say "too young to
            // have shown it". A four-year-old fund that had beaten the market every one of
            // those four years could not place, and the reason lines gave no hint why.
            //
            // Scored on the record it HAS, so long as that record is at least three years.
            // The three-year floor is the whole guard against the classic failure of ranking
            // funds on trailing return: a fund eighteen months old with one hot year would
            // otherwise normalise its way to full marks on the strength of that year, which
            // is exactly how retail money arrives at the top of a sector. Below the floor the
            // terms are added raw, so such a fund keeps its points and still cannot reach the
            // top of a list that is weighted toward the long run.
            s += if (possible >= 20.0) earned * 34.0 / possible else earned

            // ONE LINE, NOT FOUR. The card shows up to six reasons and four separate return
            // lines would crowd out cost and size, which are the ones a person cannot look up
            // as easily.
            val parts = ArrayList<String>(4)
            if (r.fiveYearAnnualPct != 0.0) parts.add("${Fmt.pct(r.fiveYearAnnualPct)}/yr over 5y")
            if (r.threeYearAnnualPct != 0.0) parts.add("${Fmt.pct(r.threeYearAnnualPct)}/yr over 3y")
            if (r.oneYearPct != 0.0) parts.add("${Fmt.pct(r.oneYearPct)} over 1y")
            if (r.ytdReturnPct != 0.0) parts.add("${Fmt.pct(r.ytdReturnPct)} YTD")
            if (parts.isNotEmpty()) why.add("Returned " + parts.joinToString(", "))
        }

        // --- cost (0-20)
        want++
        if (r.expenseRatio > 0.0) {
            have++
            // Full marks at or below 5bp, nothing at or above 75bp. Both ends are real: 3bp
            // is what the cheapest broad index funds charge and 75bp is where thematic funds
            // start; between them the ramp is linear because the drag is.
            s += ramp(-r.expenseRatio, -0.75, -0.05, 20.0)
            val hundred = 10_000.0 * r.expenseRatio / 100.0
            why.add(
                "Costs ${Fmt.pct(r.expenseRatio)} a year - " +
                    "${Fmt.usd(hundred)} a year on every \$10,000 held"
            )
        }

        // --- size (0-16)
        want++
        if (r.netAssets > 0.0) {
            have++
            s += logRamp(r.netAssets, 5e7, 5e10, 16.0)
            why.add("${Fmt.compactMoney(r.netAssets)} under management")
            if (r.netAssets < 1e8) why.add(
                "Small fund - under \$100m is where issuers close funds, which forces a sale " +
                    "in a tax year you did not choose"
            )
        }

        // --- liquidity (0-12)
        want++
        if (r.dollarVolume > 0.0) {
            have++
            s += logRamp(r.dollarVolume, 1e5, 1e8, 12.0)
            if (r.dollarVolume < 1e6) why.add(
                "Thinly traded - about ${Fmt.compactMoney(r.dollarVolume)} changes hands on " +
                    "an average day, so the spread is a real cost"
            )
        }

        // --- how long it has existed (0-8)
        want++
        val age = r.ageYears
        if (age >= 0) {
            have++
            s += ramp(age, 1.0, 5.0, 8.0)
            if (age < 3.0) why.add(
                "Only ${Fmt.oneDp(age)} years old - too young for a three-year record to mean much"
            )
        }

        // --- trend (0-10)
        want++
        if (r.price > 0 && r.fiftyDayAvg > 0 && r.twoHundredDayAvg > 0) {
            have++
            val above50 = r.price > r.fiftyDayAvg
            val above200 = r.price > r.twoHundredDayAvg
            if (above50) s += 5.0
            if (above200) s += 5.0
            if (above50 && above200) why.add("Trading above both its 50- and 200-day averages")
            else if (!above50 && !above200) why.add("Below both its 50- and 200-day averages")
        }

        if (r.offHighPct >= 0 && r.offHighPct > 15.0) {
            why.add("${Fmt.pct(r.offHighPct)} below its own 52-week high")
        }
        if (r.yieldPct > 0.5) {
            // Reported, never scored - see the note at the top of this file.
            why.add("Pays ${Fmt.pct(r.yieldPct)} in distributions")
        }
        if (r.lists.isNotEmpty()) {
            why.add("Found in " + r.lists.joinToString(", ") { EtfScreener.label(it) })
        }

        return ResearchScore.Scored(
            score = s.coerceIn(0.0, 100.0).toInt(),
            reasons = why,
            confidence = if (want <= 0) 0 else (have * 100 / max(1, want)).coerceIn(0, 100)
        )
    }
}
