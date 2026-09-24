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
        // A SOURCE THAT FAILED IS LEFT ALONE FOR HOURS, NOT MINUTES (full test 2026-09-23, N-9).
        // Tradestie's certificate had expired (re-verified 2026-09-23), and `Http`'s unreachable
        // backoff tops out at five minutes - below this pass's own 15-minute cadence - so every
        // pass paid a DNS lookup, a connect and a TLS handshake that could not succeed. Kept, not
        // removed: it is the only source with sentiment, and it comes back on its own if fixed.
        val now = System.currentTimeMillis()
        val pJob = async {
            if (now < tradestieDeadUntil) emptyList()
            else try {
                tradestie().also { if (it == null) tradestieDeadUntil = now + SOURCE_DEAD_MS }.orEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e      // leaving the app is not the source failing (diff review, R-6)
            } catch (e: Exception) {
                emptyList()  // a parse surprise: try again next pass
            }
        }
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
                // Tradestie's own Trending never sets `mentions` (it only carries `comments`,
                // above) - this always takes ApeWisdom's figure. Written as the unconditional
                // assignment it actually is, rather than a live-looking `if` that can't go
                // the other way; see [comments] just above for Tradestie's own count.
                mentions = a.mentions,
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

    @Volatile private var tradestieDeadUntil = 0L
    private const val SOURCE_DEAD_MS = 6L * 3_600_000L

    /**
     * https://tradestie.com/apps/reddit/api/ - free, no key, 20 requests/minute. Null when the
     * source FAILED (as opposed to answering with nothing) - see the dead-source note above.
     */
    private suspend fun tradestie(): List<Trending>? {
        val r = Http.get(
            "https://api.tradestie.com/v1/apps/reddit",
            timeoutMs = 15000, conditionalKey = true
        )
        // A local cooldown refusal is not the source failing - do not mark it dead for it.
        if (r.throttledLocally) return emptyList()
        // ONLY A FAILURE THE SOURCE ITSELF GAVE marks it dead (diff review 2026-09-23, R-6): an
        // HTTP error, or a TLS/certificate refusal (Tradestie's expired certificate). Being
        // offline is the phone, not the source, and must not switch it off for six hours.
        if (!r.ok) {
            val b = r.body.lowercase()
            // `r.tls` by exception type (N-6): Android words an expired certificate "Chain
            // validation failed", which none of the substrings below ever matched.
            val sourceRefused = r.code >= 400 || r.tls ||
                b.contains("certificate") || b.contains("ssl") || b.contains("handshake")
            return if (sourceRefused) null else emptyList()
        }
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
