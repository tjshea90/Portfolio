package com.tj.portfolio.net

import com.tj.portfolio.data.Trending
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject

/**
 * What r/wallstreetbets is talking about right now.
 *
 * Reddit's own unauthenticated JSON endpoints were blocked in 2026 and all API access now
 * requires approval, so this reads two free aggregators that do the scraping and counting:
 *
 *   1. Tradestie   - refreshes every 15 minutes, publishes a 20 req/min limit, no key,
 *                    and includes a sentiment score per ticker.
 *   2. ApeWisdom   - refreshes about every 30 minutes, no key, and carries 24h-ago rank
 *                    and mention counts so momentum needs no local state.
 *
 * Neither offers any guarantee. Tradestie is tried first for freshness; ApeWisdom fills in
 * the momentum fields and stands in if Tradestie is down.
 */
object Social {

    suspend fun trending(limit: Int = 25): List<Trending> = kotlinx.coroutines.coroutineScope {
        // These two were awaited one after the other, so the feed paid for BOTH round trips
        // end to end. They are independent - fetch them at the same time.
        val pJob = async { runCatching { tradestie() }.getOrDefault(emptyList()) }
        val sJob = async { runCatching { apeWisdom() }.getOrDefault(emptyList()) }
        val primary = pJob.await()
        val secondary = sJob.await()
        merge(primary, secondary, limit)
    }

    private fun merge(
        primary: List<Trending>,
        secondary: List<Trending>,
        limit: Int
    ): List<Trending> {

        if (primary.isEmpty() && secondary.isEmpty()) return emptyList()
        if (primary.isEmpty()) return secondary.take(limit)

        // merge: Tradestie ranking + sentiment, enriched with ApeWisdom's momentum
        val byApe = secondary.associateBy { it.symbol }
        val merged = primary.map { t ->
            val a = byApe[t.symbol]
            if (a == null) t
            else t.copy(
                mentions = if (t.mentions > 0) t.mentions else a.mentions,
                mentions24hAgo = a.mentions24hAgo,
                rank24hAgo = a.rank24hAgo,
                // carry ApeWisdom's current rank alongside its 24h-ago rank, so the
                // momentum figure compares two numbers from the same source
                apeRank = a.rank,
                upvotes = a.upvotes
            )
        }
        // anything hot on ApeWisdom that Tradestie missed
        val extra = secondary.filter { s -> merged.none { it.symbol == s.symbol } }
        return (merged + extra).take(limit)
    }

    /** https://tradestie.com/apps/reddit/api/ - free, no key, 20 requests/minute. */
    private suspend fun tradestie(): List<Trending> {
        val r = Http.get(
            "https://api.tradestie.com/v1/apps/reddit",
            timeoutMs = 15000, conditionalKey = true
        )
        if (!r.ok) return emptyList()
        val arr = JSONArray(r.body)
        val out = ArrayList<Trending>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.optString("ticker").uppercase()
            if (sym.isBlank()) continue
            out.add(
                Trending(
                    symbol = sym,
                    rank = i + 1,
                    comments = o.optInt("no_of_comments", 0),
                    sentiment = o.optString("sentiment"),
                    sentimentScore = o.optDouble("sentiment_score", 0.0),
                    // the AGGREGATOR, not the subreddit - the UI has to be able to say which
                    // of the two answered, because only this one carries sentiment
                    source = "Tradestie"
                )
            )
        }
        return out
    }

    /** https://apewisdom.io/api/ - free, no key, includes 24h-ago comparison fields. */
    private suspend fun apeWisdom(): List<Trending> {
        val r = Http.get(
            "https://apewisdom.io/api/v1.0/filter/wallstreetbets/page/1",
            timeoutMs = 15000, conditionalKey = true
        )
        if (!r.ok) return emptyList()
        val arr = JSONObject(r.body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Trending>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.optString("ticker").uppercase()
            if (sym.isBlank()) continue
            out.add(
                Trending(
                    symbol = sym,
                    rank = o.optInt("rank", i + 1),
                    apeRank = o.optInt("rank", i + 1),
                    mentions = o.optInt("mentions", 0),
                    mentions24hAgo = o.optInt("mentions_24h_ago", 0),
                    rank24hAgo = o.optInt("rank_24h_ago", 0),
                    upvotes = o.optInt("upvotes", 0),
                    source = "ApeWisdom"
                )
            )
        }
        return out
    }
}
