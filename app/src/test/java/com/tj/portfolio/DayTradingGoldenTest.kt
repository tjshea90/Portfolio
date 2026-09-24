package com.tj.portfolio

import com.tj.portfolio.data.ScreenRow
import com.tj.portfolio.net.DayTradingTechnicals.DayTechnicals
import com.tj.portfolio.net.ResearchScore
import com.tj.portfolio.net.Screener
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * THE ORIGINAL DAY-TRADING ENGINE, PINNED (2026-09-24c, B1).
 *
 * Tj's rule 3 is "revert the app back to its original day trading section engine" - which is only
 * a real promise if the engine's defaults, once every constant became a tunable parameter, still
 * produce EXACTLY what the engine produced before that refactor. This file is the proof: the
 * fixture `daytrading_golden_v1.txt` was generated from the pre-refactor code (commit afb40a4d,
 * v7.41) over a deterministic spread of inputs - live and closed sessions, every setup, missing
 * readings, late-day clocks - and every run compares the current engine (at its defaults)
 * against it line by line: the plan, the decline reason, the scores, the confidence checklist,
 * the technicals bonus and the beginner summary.
 *
 * Deterministic (fixed seed), no clock: the exit sentence's "flat by" time is normalised because
 * it follows the real calendar's half days.
 *
 * To regenerate (ONLY if the ORIGINAL engine itself is deliberately changed - never to make a
 * tuning refactor pass): delete the fixture and run with -Dgolden.write=true.
 */
class DayTradingGoldenTest {

    private val fixture = "src/test/resources/daytrading_golden_v1.txt"

    private fun r2(v: Double) = if (v == 0.0) "0" else "%.6f".format(java.util.Locale.US, v)

    private fun tech(rng: Random): DayTechnicals {
        val price = listOf(2.4, 8.0, 23.5, 61.0, 140.0, 410.0)[rng.nextInt(6)] * (0.9 + rng.nextDouble() * 0.2)
        val atrD = price * (0.02 + rng.nextDouble() * 0.06)
        val atrI = if (rng.nextInt(5) == 0) 0.0 else atrD * (0.05 + rng.nextDouble() * 0.12)
        fun maybe(v: Double) = if (rng.nextInt(6) == 0) 0.0 else v
        val live = rng.nextInt(4) != 0
        val prevClose = price * (0.94 + rng.nextDouble() * 0.08)
        val prevHigh = maxOf(prevClose, price * (0.97 + rng.nextDouble() * 0.08))
        val prevLow = minOf(prevClose, price * (0.90 + rng.nextDouble() * 0.06))
        val sLow = price * (0.95 + rng.nextDouble() * 0.04)
        val sHigh = price * (1.0 + rng.nextDouble() * 0.05)
        val orComplete = rng.nextBoolean()
        return DayTechnicals(
            atr14 = maybe(atrD),
            vwap = if (live) maybe(price * (0.97 + rng.nextDouble() * 0.06)) else 0.0,
            openingRangeHigh = maybe(price * (0.99 + rng.nextDouble() * 0.04)),
            openingRangeLow = maybe(price * (0.95 + rng.nextDouble() * 0.04)),
            openingRangeComplete = orComplete,
            or5High = maybe(price * (0.98 + rng.nextDouble() * 0.05)),
            or5Low = maybe(price * (0.94 + rng.nextDouble() * 0.04)),
            openingBarBullish = rng.nextBoolean(),
            atrIntraday = atrI,
            adr = maybe(atrD * (0.8 + rng.nextDouble() * 0.6)),
            prevHigh = maybe(prevHigh),
            prevLow = maybe(prevLow),
            prevClose = maybe(prevClose),
            premarketHigh = maybe(price * (0.98 + rng.nextDouble() * 0.06)),
            sessionHigh = if (rng.nextInt(5) == 0) 0.0 else sHigh,
            sessionLow = if (rng.nextInt(5) == 0) 0.0 else sLow,
            sessionLive = live,
            intradayFetched = true,
            sessionDay = if (live) "20260924" else "",
            lastPrice = price
        )
    }

    private fun lines(): List<String> {
        val rng = Random(20260924L)
        val out = ArrayList<String>()
        repeat(600) { i ->
            val t = tech(rng)
            val price = t.lastPrice * (0.97 + rng.nextDouble() * 0.06)
            val minutesLeft = listOf(0, 390, 200, 61, 45, 29, 12, 1)[rng.nextInt(8)]
            val lull = rng.nextInt(4) == 0
            val earn = rng.nextInt(5) == 0
            val (plan, reason) = ResearchScore.planInternal(price, t, minutesLeft, lull, earn)
            val flat = Regex("""be flat by \d{1,2}:\d{2} ET""")
            out.add(
                "P$i|" + (plan?.let {
                    listOf(r2(it.entry), r2(it.stop), r2(it.target), it.setup, it.trigger, it.note,
                        flat.replace(it.exit, "be flat by HH:MM ET"), it.tooLateToStart).joinToString("|")
                } ?: "null|$reason")
            )
            plan?.let {
                val s = ResearchScore.beginnerSummary("SYM", price * (0.98 + rng.nextDouble() * 0.04),
                    it.entry, it.stop, it.target, it.tooLateToStart, it.setup, price)
                out.add("B$i|" + (s?.let { b -> "${b.headline}|${b.explanation}|${b.skip}" } ?: "null"))
            }
            out.add("T$i|" + ResearchScore.technicalConfirmationBonus(price, t) + "|" +
                ResearchScore.withTechnicals(ResearchScore.Scored(40, listOf("base"), 50), t, price)
                    .let { s -> "${s.score}|${s.reasons.joinToString(";")}|${s.confidence}" })
            out.add("L$i|${ResearchScore.tooLateToStart(minutesLeft)}|" +
                ResearchScore.exitPlan(if (plan != null) plan.target else 10.0, minutesLeft, t.sessionLive, 16 * 60))
        }
        val lists = listOf(Screener.Lists.MOST_SHORTED, Screener.Lists.DAY_GAINERS, Screener.Lists.MOST_ACTIVE)
        repeat(300) { i ->
            val price = 2.0 + rng.nextDouble() * 300.0
            val row = ScreenRow(
                symbol = "S$i",
                price = price,
                changePct = -5.0 + rng.nextDouble() * 25.0,
                volume = rng.nextDouble() * 9e6,
                avgVolume3M = if (rng.nextInt(8) == 0) 0.0 else 1e6 + rng.nextDouble() * 3e6,
                fiftyDayAvg = if (rng.nextInt(6) == 0) 0.0 else price * (0.8 + rng.nextDouble() * 0.4),
                fiftyTwoWeekHigh = price * (1.0 + rng.nextDouble() * 0.6),
                fiftyTwoWeekLow = price * (0.3 + rng.nextDouble() * 0.6),
                lists = lists.filter { rng.nextInt(3) == 0 }.toSet()
            )
            val trend = if (rng.nextBoolean()) ResearchScore.TrendInput(
                symbol = row.symbol, mentions = rng.nextInt(80), newsCount = rng.nextInt(6)
            ) else null
            val frac = listOf(1.0, 0.0, 0.1, 0.5, 0.9)[rng.nextInt(5)]
            val word = listOf("today", "this session", "in the last session")[rng.nextInt(3)]
            val sc = ResearchScore.dayTrading(row, trend, 80, 6, rng.nextInt(5) == 0, word, frac)
            val conf = ResearchScore.dayTradingConfidence(row, null, frac)
            out.add("S$i|${sc.score}|${sc.confidence}|${sc.reasons.joinToString(";")}|$conf|" +
                ResearchScore.blendedScore(sc.score, conf))
        }
        return out
    }

    @Test fun defaultEngineReproducesTheOriginalExactly() {
        val now = lines()
        val f = File(fixture)
        if (!f.exists() && System.getProperty("golden.write") == "true") {
            f.parentFile.mkdirs()
            f.writeText(now.joinToString("\n") + "\n")
        }
        val want = f.readLines().filter { it.isNotEmpty() }
        assertEquals("line count", want.size, now.size)
        for (i in want.indices) assertEquals("golden line ${i + 1}", want[i], now[i])
    }
}
