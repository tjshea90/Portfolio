package com.tj.portfolio.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Fundamentals] to JSON and back, for the on-disk cache.
 *
 * WHY JSON AND NOT COLUMNS. The alternative is a table with a column per metric, which
 * means a schema migration every time a number is added to [MetricCatalog] - and this app's
 * database rule is that migrations are additive and never destructive, so every one of those
 * is a permanent addition. Storing the object as a blob keeps the schema at one row per
 * symbol forever, and the catalogue stays the only place a metric is declared.
 *
 * It also makes the cache forward- and backward-tolerant, which matters because the user
 * sideloads builds: a row written by a newer build carrying a metric this build has never
 * heard of is read back with that key ignored, and a row written by an older build simply
 * has fewer keys. Neither case throws.
 *
 * Every read is defensive. A cache row is not user data, so if it cannot be parsed the right
 * answer is to behave as though it was never there and fetch again - never to crash on
 * launch because a half-written row survived a process kill.
 */
object FundamentalsJson {

    fun toJson(f: Fundamentals): String = JSONObject().apply {
        put("symbol", f.symbol)
        put("fetched", f.fetched)
        put("earningsDate", f.earningsDate)
        put("profile", f.profile)
        put("sources", JSONArray(f.sources))
        put("values", JSONObject().apply { f.values.forEach { (k, v) -> put(k, v) } })
        put("texts", JSONObject().apply { f.texts.forEach { (k, v) -> put(k, v) } })
        f.consensus?.let { put("consensus", consensusJson(it)) }
        put("ratings", JSONArray().apply { f.ratings.forEach { put(ratingJson(it)) } })
        put("trend", JSONArray().apply { f.trend.forEach { put(trendJson(it)) } })
        put("estimates", JSONArray().apply { f.estimates.forEach { put(estimateJson(it)) } })
        put("history", JSONArray().apply { f.history.forEach { put(resultJson(it)) } })
    }.toString()

    fun fromJson(raw: String): Fundamentals? = runCatching {
        val o = JSONObject(raw)
        val values = LinkedHashMap<String, Double>()
        o.optJSONObject("values")?.let { vo ->
            val it = vo.keys()
            while (it.hasNext()) {
                val k = it.next()
                val d = vo.optDouble(k, Double.NaN)
                if (d.isFinite()) values[k] = d
            }
        }
        val texts = LinkedHashMap<String, String>()
        o.optJSONObject("texts")?.let { to ->
            val it = to.keys()
            while (it.hasNext()) {
                val k = it.next()
                val s = to.optString(k)
                if (s.isNotBlank()) texts[k] = s
            }
        }
        Fundamentals(
            symbol = o.optString("symbol"),
            values = values,
            texts = texts,
            consensus = o.optJSONObject("consensus")?.let { consensusOf(it) },
            ratings = list(o.optJSONArray("ratings")) { ratingOf(it) },
            trend = list(o.optJSONArray("trend")) { trendOf(it) },
            estimates = list(o.optJSONArray("estimates")) { estimateOf(it) },
            history = list(o.optJSONArray("history")) { resultOf(it) },
            earningsDate = o.optLong("earningsDate", 0L),
            profile = o.optString("profile"),
            sources = strings(o.optJSONArray("sources")),
            fetched = o.optLong("fetched", 0L)
        )
    }.getOrNull()

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val s = a.optString(i)
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    private fun <T> list(a: JSONArray?, f: (JSONObject) -> T?): List<T> {
        if (a == null) return emptyList()
        val out = ArrayList<T>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            f(o)?.let { out.add(it) }
        }
        return out
    }

    // ------------------------------------------------------------- consensus

    private fun consensusJson(c: Consensus) = JSONObject().apply {
        put("mean", c.mean); put("key", c.key); put("analysts", c.analysts)
        put("targetMean", c.targetMean); put("targetHigh", c.targetHigh)
        put("targetLow", c.targetLow); put("targetMedian", c.targetMedian)
        put("strongBuy", c.strongBuy); put("buy", c.buy); put("hold", c.hold)
        put("sell", c.sell); put("strongSell", c.strongSell)
    }

    private fun consensusOf(o: JSONObject) = Consensus(
        mean = o.optDouble("mean", 0.0).orZero(),
        key = o.optString("key"),
        analysts = o.optInt("analysts", 0),
        targetMean = o.optDouble("targetMean", 0.0).orZero(),
        targetHigh = o.optDouble("targetHigh", 0.0).orZero(),
        targetLow = o.optDouble("targetLow", 0.0).orZero(),
        targetMedian = o.optDouble("targetMedian", 0.0).orZero(),
        strongBuy = o.optInt("strongBuy", 0),
        buy = o.optInt("buy", 0),
        hold = o.optInt("hold", 0),
        sell = o.optInt("sell", 0),
        strongSell = o.optInt("strongSell", 0)
    )

    // ---------------------------------------------------------------- rating

    private fun ratingJson(r: AnalystRating) = JSONObject().apply {
        put("firm", r.firm); put("date", r.date); put("action", r.action)
        put("toGrade", r.toGrade); put("fromGrade", r.fromGrade)
        put("target", r.target); put("priorTarget", r.priorTarget)
        put("targetAction", r.targetAction); put("analyst", r.analyst); put("source", r.source)
    }

    private fun ratingOf(o: JSONObject): AnalystRating? {
        val firm = o.optString("firm")
        if (firm.isBlank()) return null
        return AnalystRating(
            firm = firm,
            date = o.optLong("date", 0L),
            action = o.optString("action", AnalystRating.UNKNOWN),
            toGrade = o.optString("toGrade"),
            fromGrade = o.optString("fromGrade"),
            target = o.optDouble("target", 0.0).orZero(),
            priorTarget = o.optDouble("priorTarget", 0.0).orZero(),
            targetAction = o.optString("targetAction"),
            analyst = o.optString("analyst"),
            source = o.optString("source")
        )
    }

    // ----------------------------------------------------------------- trend

    private fun trendJson(t: RatingTrend) = JSONObject().apply {
        put("period", t.period); put("strongBuy", t.strongBuy); put("buy", t.buy)
        put("hold", t.hold); put("sell", t.sell); put("strongSell", t.strongSell)
    }

    private fun trendOf(o: JSONObject) = RatingTrend(
        period = o.optString("period"),
        strongBuy = o.optInt("strongBuy", 0),
        buy = o.optInt("buy", 0),
        hold = o.optInt("hold", 0),
        sell = o.optInt("sell", 0),
        strongSell = o.optInt("strongSell", 0)
    )

    // ------------------------------------------------------------- estimates

    private fun estimateJson(e: EarningsEstimate) = JSONObject().apply {
        put("period", e.period); put("endDate", e.endDate)
        put("epsAvg", e.epsAvg); put("epsLow", e.epsLow); put("epsHigh", e.epsHigh)
        put("epsYearAgo", e.epsYearAgo); put("epsGrowth", e.epsGrowth)
        put("analysts", e.analysts); put("revenueAvg", e.revenueAvg)
        put("revenueGrowth", e.revenueGrowth)
    }

    private fun estimateOf(o: JSONObject) = EarningsEstimate(
        period = o.optString("period"),
        endDate = o.optString("endDate"),
        epsAvg = o.optDouble("epsAvg", 0.0).orZero(),
        epsLow = o.optDouble("epsLow", 0.0).orZero(),
        epsHigh = o.optDouble("epsHigh", 0.0).orZero(),
        epsYearAgo = o.optDouble("epsYearAgo", 0.0).orZero(),
        epsGrowth = o.optDouble("epsGrowth", 0.0).orZero(),
        analysts = o.optInt("analysts", 0),
        revenueAvg = o.optDouble("revenueAvg", 0.0).orZero(),
        revenueGrowth = o.optDouble("revenueGrowth", 0.0).orZero()
    )

    // --------------------------------------------------------------- results

    private fun resultJson(r: EarningsResult) = JSONObject().apply {
        put("quarter", r.quarter); put("date", r.date)
        put("epsEstimate", r.epsEstimate); put("epsActual", r.epsActual)
        put("surprisePct", r.surprisePct)
    }

    private fun resultOf(o: JSONObject) = EarningsResult(
        quarter = o.optString("quarter"),
        date = o.optLong("date", 0L),
        epsEstimate = o.optDouble("epsEstimate", 0.0).orZero(),
        epsActual = o.optDouble("epsActual", 0.0).orZero(),
        surprisePct = o.optDouble("surprisePct", 0.0).orZero()
    )

    /** A NaN or infinity written by a bad parse must not come back out as one. */
    private fun Double.orZero(): Double = if (this.isFinite()) this else 0.0
}
