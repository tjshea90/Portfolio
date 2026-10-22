package com.tj.portfolio.net

import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.EarningsEstimate
import com.tj.portfolio.data.EarningsResult
import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.data.RatingTrend
import kotlin.math.abs
import kotlin.math.exp

/**
 * THE EARNINGS OUTLOOK (2026-10-22): a green / yellow / red read on an upcoming report.
 *
 * WHAT IT IS AND IS NOT. Nobody can know how a stock will react to a report - the move depends
 * on the gap between the numbers and what the market had priced in, which is not published.
 * This is a transparent, repeatable average of the signals that DO have predictive footing,
 * each shown to the user with its reason, and it says "unknown" (yellow) when there is not
 * enough to go on rather than inventing a read.
 *
 * THE SIGNALS (weights are renormalised over whichever are available):
 *
 *   30%  Track record      - beat/miss and surprise size over the last four reported quarters.
 *                            Companies that beat tend to keep beating (guidance is managed).
 *   20%  Estimate growth   - analysts' EPS and revenue expectation against a year ago.
 *   15%  Analyst stance    - consensus rating and whether the buy share is rising or falling.
 *   20%  News tone         - recency-weighted tone of the last three weeks of headlines.
 *   10%  Momentum          - price against its 50-day average; a big run-up is penalised
 *                            because good news is then already in the price.
 *    5%  Target upside     - average analyst price target against the live price.
 *
 * The result is a score from -100 to +100: at or above [GREEN_AT] green, at or below
 * [RED_AT] red, in between yellow. Pure and deterministic - same inputs, same answer.
 */
object EarningsOutlook {

    enum class Light { GREEN, YELLOW, RED }

    data class Reason(val label: String, val detail: String, val tone: Int, val impact: Double)

    data class Result(
        val score: Double,
        val light: Light,
        /** False when there was too little data: the light is then yellow by default. */
        val known: Boolean,
        /** Share of the full weight that had data behind it, 0..1. */
        val coverage: Double,
        val reasons: List<Reason>
    ) {
        companion object {
            val UNKNOWN = Result(0.0, Light.YELLOW, false, 0.0,
                listOf(Reason("Not enough data", "Too little is known about this company to lean either way.", 0, 0.0)))
        }
    }

    const val GREEN_AT = 18.0
    const val RED_AT = -18.0
    private const val MIN_COVERAGE = 0.40

    private const val W_RECORD = 0.30
    private const val W_ESTIMATES = 0.20
    private const val W_ANALYSTS = 0.15
    private const val W_NEWS = 0.20
    private const val W_MOMENTUM = 0.10
    private const val W_TARGET = 0.05

    data class Input(
        val price: Double = 0.0,
        val estimates: List<EarningsEstimate> = emptyList(),
        val history: List<EarningsResult> = emptyList(),
        val consensus: Consensus? = null,
        val trend: List<RatingTrend> = emptyList(),
        val headlines: List<NewsItem> = emptyList(),
        val fiftyDayAverage: Double? = null,
        val now: Long = System.currentTimeMillis()
    )

    private class Part(val weight: Double, val score: Double, val reason: Reason)

    fun evaluate(i: Input): Result {
        val parts = listOfNotNull(
            record(i), estimates(i), analysts(i), news(i), momentum(i), target(i)
        )
        val coverage = parts.sumOf { it.weight }
        if (parts.size < 2 || coverage < MIN_COVERAGE) {
            return Result.UNKNOWN.copy(coverage = coverage,
                reasons = parts.map { it.reason }.ifEmpty { Result.UNKNOWN.reasons })
        }
        val score = parts.sumOf { it.weight * it.score } / coverage * 100.0
        val light = when {
            score >= GREEN_AT -> Light.GREEN
            score <= RED_AT -> Light.RED
            else -> Light.YELLOW
        }
        return Result(score, light, true, coverage,
            parts.map { it.reason }.sortedByDescending { it.impact })
    }

    private fun clamp(v: Double, lo: Double = -1.0, hi: Double = 1.0) = v.coerceIn(lo, hi)
    private fun tone(s: Double) = if (s >= 0.25) 1 else if (s <= -0.25) -1 else 0
    private fun part(w: Double, s: Double, label: String, detail: String) =
        Part(w, s, Reason(label, detail, tone(s), w * abs(s)))

    // ------------------------------------------------------------ track record

    private fun record(i: Input): Part? {
        val h = i.history.filter { it.epsActual != 0.0 || it.epsEstimate != 0.0 }.take(4)
        if (h.size < 2) return null
        val beats = h.count { it.epsActual > it.epsEstimate }
        val misses = h.count { it.epsActual < it.epsEstimate }
        val avg = h.map { it.surprisePct.coerceIn(-50.0, 50.0) }.average()
        val rate = beats.toDouble() / h.size
        val s = clamp(avg / 10.0) * 0.6 + clamp((rate - 0.5) * 2.0) * 0.4
        val detail = "Beat estimates in $beats of the last ${h.size} quarters" +
            (if (misses > 0) " (missed $misses)" else "") +
            ", average surprise ${signed(avg)}%"
        return part(W_RECORD, s, "Track record", detail)
    }

    // ------------------------------------------------------------ estimates

    private fun estimates(i: Input): Part? {
        val q = i.estimates.firstOrNull { it.period == "0q" } ?: return null
        if (q.epsAvg == 0.0 && q.revenueAvg == 0.0) return null
        val epsG = when {
            q.epsYearAgo > 0.0 -> (q.epsAvg - q.epsYearAgo) / q.epsYearAgo
            q.epsAvg > q.epsYearAgo -> 0.3          // from a loss/zero base: a percentage means nothing
            q.epsAvg < q.epsYearAgo -> -0.3
            else -> 0.0
        }
        val epsScore = clamp(epsG / 0.25)
        val revScore = if (q.revenueGrowth != 0.0) clamp(q.revenueGrowth / 0.15) else null
        val s = if (revScore != null) epsScore * 0.65 + revScore * 0.35 else epsScore
        val detail = "Analysts expect EPS ${"%.2f".format(q.epsAvg)} (${signed(epsG * 100)}% vs a year ago)" +
            (if (q.revenueGrowth != 0.0) ", revenue ${signed(q.revenueGrowth * 100)}%" else "")
        return part(W_ESTIMATES, s, "Expected growth", detail)
    }

    // ------------------------------------------------------------ analysts

    private fun bullShare(t: RatingTrend): Double? =
        if (t.total > 0) (t.strongBuy + t.buy).toDouble() / t.total else null

    private fun analysts(i: Input): Part? {
        val c = i.consensus
        val mean = c?.mean?.takeIf { it > 0.0 }
        val now = i.trend.firstOrNull { it.period == "0m" }?.let(::bullShare)
        val then = i.trend.firstOrNull { it.period == "-3m" }?.let(::bullShare)
        val drift = if (now != null && then != null) now - then else null
        if (mean == null && drift == null) return null
        val stance = mean?.let { clamp((3.0 - it) / 2.0) }
        val driftScore = drift?.let { clamp(it / 0.10) }
        val s = when {
            stance != null && driftScore != null -> stance * 0.7 + driftScore * 0.3
            stance != null -> stance
            else -> driftScore!!
        }
        val parts = ArrayList<String>()
        if (c != null && mean != null) parts.add("${c.meanLabel.lowercase()} consensus (${c.analysts.takeIf { it > 0 } ?: c.votes} analysts)")
        if (drift != null) parts.add("buy share ${if (drift >= 0) "up" else "down"} ${"%.0f".format(abs(drift) * 100)} pts in 3 months")
        return part(W_ANALYSTS, s, "Analyst stance", parts.joinToString(", ").replaceFirstChar { it.uppercase() })
    }

    // ------------------------------------------------------------ target

    private fun target(i: Input): Part? {
        val up = i.consensus?.upsidePct(i.price) ?: return null
        val s = clamp(up / 20.0)
        return part(W_TARGET, s, "Price target",
            "Average target is ${signed(up)}% ${if (up >= 0) "above" else "below"} the current price")
    }

    // ------------------------------------------------------------ momentum

    private fun momentum(i: Input): Part? {
        val ma = i.fiftyDayAverage?.takeIf { it > 0.0 } ?: return null
        if (i.price <= 0.0) return null
        val v = i.price / ma - 1.0
        val s = if (v <= 0.15) clamp(v / 0.12) else clamp(1.0 - (v - 0.15) / 0.12, -0.5, 1.0)
        val detail = when {
            v > 0.25 -> "Up ${"%.0f".format(v * 100)}% over its 50-day average - a lot of good news is already priced in"
            else -> "${signed(v * 100)}% against its 50-day average"
        }
        return part(W_MOMENTUM, s, "Momentum", detail)
    }

    // ------------------------------------------------------------ news

    private fun news(i: Input): Part? {
        val recent = i.headlines.filter { it.published > 0 && i.now - it.published <= 21L * DAY }
        if (recent.size < 2) return null
        var weighted = 0.0
        var totalW = 0.0
        var pos = 0
        var neg = 0
        for (h in recent) {
            val age = (i.now - h.published).coerceAtLeast(0L) / DAY.toDouble()
            val w = exp(-age / 7.0)
            val t = headlineTone(h.title)
            if (t > 0.0) pos++ else if (t < 0.0) neg++
            weighted += w * t
            totalW += w
        }
        // Shrunk toward neutral: three headlines should not swing the light as hard as thirty.
        val s = clamp(weighted / (totalW + 1.5))
        return part(W_NEWS, s, "News tone",
            "${recent.size} recent headlines: $pos positive, $neg negative, ${recent.size - pos - neg} neutral")
    }

    private const val DAY = 86_400_000L

    /** Tone of one headline, -1..1: summed word weights, soft-clipped. 0 when nothing matches. */
    fun headlineTone(title: String): Double {
        val t = " " + title.lowercase().replace(Regex("[^a-z0-9%$ ]"), " ").replace(Regex("\\s+"), " ") + " "
        var sum = 0.0
        for ((phrase, w) in LEXICON) if (t.contains(phrase)) sum += w
        // Negation of a positive ("fails to beat", "no growth") flips rather than adds.
        if (sum > 0 && NEGATORS.any { t.contains(it) }) sum = -sum * 0.6
        return clamp(sum / 2.0)
    }

    private val NEGATORS = listOf(" fails to ", " failed to ", " no growth", " not beat", " unable to ", " falls short")

    private val LEXICON: List<Pair<String, Double>> = listOf(
        // ---- positive
        " beats " to 1.6, " beat estimates" to 1.6, " tops " to 1.4, " topped " to 1.4, " surpass" to 1.3,
        " raises guidance" to 2.0, " raised guidance" to 2.0, " raises outlook" to 2.0, " raises forecast" to 2.0,
        " boosts outlook" to 1.8, " lifts outlook" to 1.8, " record revenue" to 1.8, " record profit" to 1.8,
        " record earnings" to 1.8, " record sales" to 1.6, " record high" to 1.0, " all time high" to 1.0,
        " upgrade" to 1.6, " upgraded" to 1.6, " price target raised" to 1.4, " raises price target" to 1.4,
        " raises target" to 1.4, " outperform" to 1.0, " overweight" to 0.8, " strong buy" to 1.0,
        " surges" to 1.3, " soars" to 1.5, " jumps" to 1.1, " rallies" to 1.0, " climbs" to 0.7, " gains" to 0.5,
        " rises" to 0.5, " growth" to 0.5, " strong demand" to 1.2, " strong results" to 1.4, " strong earnings" to 1.4,
        " profit jumps" to 1.5, " buyback" to 0.7, " dividend increase" to 0.9, " raises dividend" to 0.9,
        " approval" to 0.8, " approved" to 0.8, " wins contract" to 1.0, " new contract" to 0.7, " partnership" to 0.4,
        " expands" to 0.4, " breakthrough" to 1.0, " accelerat" to 0.6, " bullish" to 0.9, " optimis" to 0.7,
        // ---- negative
        " misses " to -1.6, " missed " to -1.6, " miss estimates" to -1.6, " falls short" to -1.3,
        " cuts guidance" to -2.0, " cut guidance" to -2.0, " lowers guidance" to -2.0, " lowers outlook" to -2.0,
        " cuts outlook" to -2.0, " cuts forecast" to -2.0, " slashes" to -1.5, " warns" to -1.4, " warning" to -1.0,
        " profit warning" to -2.0, " downgrade" to -1.6, " downgraded" to -1.6, " price target cut" to -1.4,
        " cuts price target" to -1.4, " lowers price target" to -1.4, " lowers target" to -1.3, " underperform" to -1.0,
        " underweight" to -0.8, " plunges" to -1.6, " plummets" to -1.6, " tumbles" to -1.4, " sinks" to -1.3,
        " slumps" to -1.3, " drops" to -0.7, " falls" to -0.6, " slides" to -0.8, " declines" to -0.6,
        " weak demand" to -1.2, " weak results" to -1.4, " disappointing" to -1.3, " layoffs" to -1.0, " lays off" to -1.0,
        " job cuts" to -1.0, " recall" to -1.2, " lawsuit" to -1.0, " sued" to -0.9, " investigation" to -1.0,
        " probe" to -1.0, " subpoena" to -1.2, " fraud" to -1.8, " bankruptcy" to -2.0, " delist" to -1.8,
        " default" to -1.4, " loss widens" to -1.5, " net loss" to -0.8, " bearish" to -0.9, " concern" to -0.5,
        " fears" to -0.7, " tariff" to -0.5, " delay" to -0.7, " halts" to -1.0, " suspend" to -1.0, " rejected" to -1.0
    )

    private fun signed(v: Double) = (if (v >= 0) "+" else "") + "%.1f".format(v)
}
