package com.tj.portfolio.tmp

import com.tj.portfolio.data.DayTradingLogEntry
import com.tj.portfolio.data.DayTradingOutcome
import com.tj.portfolio.net.DayTradingEval
import com.tj.portfolio.net.DayTradingEval.IntradayBar
import com.tj.portfolio.net.DayTradingGrader
import com.tj.portfolio.net.EngineTuning
import com.tj.portfolio.net.EngineTuningPrompt
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptDumpTest {
    @Test fun dump() {
        val out = System.getenv("PROMPT_DUMP") ?: return
        val rng = Random(7)
        val open = 1_789_479_000L
        val setups = listOf("Breakout", "Pullback", "VWAP reclaim")
        val lvls = listOf("the prior session's high", "the high of day", "VWAP", "the premarket high")
        val rows = (0 until 140).map { i ->
            val setup = setups[i % 3]
            val rises = setup != "Pullback"
            val entry = 20.0; val risk = 0.4
            val stop = entry - risk; val target = entry + risk * (1.0 + rng.nextDouble() * 2.5)
            val rec = open + (5 + rng.nextInt(330)) * 60L
            var px = if (rises) entry - 0.15 else entry + 0.2
            val bars = (0 until 390).map { m ->
                val t = open + m * 60L
                val o = px; px += (rng.nextGaussian() * 0.05) + if (rises) 0.002 else 0.0
                IntradayBar(t, maxOf(o, px) + rng.nextDouble() * 0.03, minOf(o, px) - rng.nextDouble() * 0.03, px, o)
            }
            val spec = DayTradingGrader.Spec(entry, stop, target, rises, rec * 1000L,
                entryDeadlineSec = open + 360 * 60L, flatSec = open + 380 * 60L)
            val g = DayTradingGrader.grade(spec, bars, Long.MAX_VALUE, 1)
            val f = JSONObject().put("setup", setup).put("lvl", lvls[i % 4]).put("px", entry - 0.1).put("rr", (target - entry) / risk)
                .put("riskAtr", 1.5 + rng.nextDouble()).put("trigAtr", if (rises) 0.4 else -0.5).put("atrPct", 1.1)
                .put("vwapAtr", rng.nextGaussian()).put("rangeUsed", rng.nextDouble()).put("chg", 4.0 + rng.nextDouble() * 6)
                .put("score", 20 + rng.nextInt(60)).put("lik", 50).put("conf", 60).put("rvol", 1 + rng.nextDouble() * 4)
                .put("mso", (rec - open) / 60).put("obb", rng.nextBoolean())
            DayTradingLogEntry(i.toLong(), "SYM$i", "20260915", rec * 1000L, setup, entry, stop, target, entry - 0.1,
                if (i % 9 == 0) "CLAUDE" else "APP", g.outcome, g.exitPrice, 1L, if (i < 80) "v0" else "v1", f.toString(),
                DayTradingGrader.VERSION, g.detail?.toJson() ?: "")
        }
        val st = EngineTuning.State()
        val text = EngineTuningPrompt.prompt(st, rows, DayTradingEval.stats(rows), open * 1000L + 86_400_000L)
        java.io.File(out).writeText(text)
    }
}
