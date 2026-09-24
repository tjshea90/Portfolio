package com.tj.portfolio.data

import com.tj.portfolio.net.Form4
import org.json.JSONArray
import org.json.JSONObject

/**
 * One line of a Form 4's transaction table.
 *
 * A filing usually has several. The two shapes that matter:
 *
 *   - one sale filled by the broker in price bands, reported as four S lines a few cents
 *     apart. These are ONE decision and must be shown as one, at the weighted average price.
 *   - an option exercise (M) immediately followed by a sale of the resulting shares (S).
 *     Two different events in one filing, and the S is the one a reader cares about.
 *
 * [InsiderFiling.headlineTrade] is what resolves both, which is why the lines are kept apart
 * here rather than summed on the way in.
 */
data class InsiderTrade(
    /** The raw Form 4 code - P, S, A, M, F, G and the rest. */
    val code: String,
    /** [Form4.BUY], [Form4.SELL], and so on. */
    val action: String,
    /**
     * What was traded, when it is not ordinary stock - "Restricted Stock Units", "Employee
     * Stock Option". Blank for common shares, which is the usual case. See
     * [Form4.securityName].
     */
    val security: String = "",
    /** True for a disposal (the filing's A/D column reads D). */
    val disposed: Boolean,
    val shares: Double,
    val price: Double,
    val date: Long,
    val sharesAfter: Double,
    /**
     * True when the line came from the derivative table - options, RSUs, warrants. Kept
     * separate because "sold 20,000 shares" and "sold 20,000 options" are different facts,
     * and because a derivative line's share count is a count of *underlying* shares that
     * would double-count against the ordinary table if the two were added together.
     */
    val derivative: Boolean
) {
    val value: Double get() = shares * price
}

/**
 * One SEC Form 4, reduced to the facts worth reading.
 *
 * IDENTITY is the accession number: unique across the whole of EDGAR, immutable once filed,
 * and therefore usable both as the LazyColumn key and as the cache key that stops the app
 * re-downloading a filing it has already read. The old feed keyed insider rows by
 * kind|symbol|date|title, which collided whenever two insiders filed on the same day - a
 * duplicate key throws in a keyed LazyColumn, and that is a crash the app has had before.
 */
data class InsiderFiling(
    val symbol: String,
    val accession: String,
    /** When EDGAR accepted the filing. */
    val filedAt: Long,
    /** When the trade itself happened - up to two business days before [filedAt], by law. */
    val tradeDate: Long,
    val person: String,
    /** "CEO", "Director", "10% owner", "SVP, General Counsel"... */
    val role: String,
    /** The Rule 10b5-1 box was ticked, or a footnote names a pre-arranged plan. */
    val planned: Boolean,
    /** A 4/A amendment - a correction to something already filed. */
    val amended: Boolean,
    val sharesAfter: Double,
    val url: String,
    val trades: List<InsiderTrade>
) {

    /**
     * The line that decides what this filing gets to say.
     *
     * ORDER OF PREFERENCE, and the reasoning behind it. A real open-market trade beats
     * everything - if an executive both received a grant and sold shares, the sale is the news.
     * Ordinary shares beat derivatives, because a reader thinks in shares. Beyond that the
     * largest line wins, which is the only sensible tie-break when a filing reports several
     * unrelated movements.
     *
     * Same-code lines are then MERGED, at the share-weighted average price. Without that a
     * single sale filled across four price bands appeared as four separate rows, each looking
     * like its own decision, which overstates insider activity by a factor of four on exactly
     * the large trades most worth noticing.
     */
    val headlineTrade: InsiderTrade? by lazy {
        if (trades.isEmpty()) return@lazy null
        val best = trades.maxByOrNull { t ->
            var score = 0.0
            if (t.action == Form4.BUY || t.action == Form4.SELL) score += 1e15
            if (!t.derivative) score += 1e13
            score + t.shares.coerceAtMost(1e12)
        } ?: return@lazy null
        val same = trades.filter {
            it.code == best.code && it.disposed == best.disposed && it.derivative == best.derivative
        }
        if (same.size == 1) return@lazy best
        val shares = same.sumOf { it.shares }
        // AVERAGED OVER THE LINES THAT STATE A PRICE (full test 2026-09-24, S-12). A price given
        // only as a footnote parses to 0; counting that line's shares in the divisor turned
        // 1,000 at $50 + 1,000 footnoted into "2,000 shares at $25.00 - $50K". Every line's
        // shares still count toward the total, at the priced lines' average.
        val priced = same.filter { it.price > 0.0 }
        val pricedShares = priced.sumOf { it.shares }
        best.copy(
            shares = shares,
            price = if (pricedShares > 0) priced.sumOf { it.shares * it.price } / pricedShares
            else best.price,
            date = same.maxOf { it.date },
            // The LAST line, not the latest-dated one. A Form 4's table is in chronological
            // order and several lines routinely share a date - the NVDA fixture has four on
            // 2 Sept - so picking by date returns whichever of them came first and reports a
            // holding that is hundreds of thousands of shares too high. Document order is
            // the only thing that separates them.
            sharesAfter = same.last().sharesAfter
        )
    }

    val action: String get() = headlineTrade?.action ?: Form4.OTHER
    val shares: Double get() = headlineTrade?.shares ?: 0.0
    val price: Double get() = headlineTrade?.price ?: 0.0
    val value: Double get() = headlineTrade?.value ?: 0.0

    /** A purchase or a sale on the open market - what "insider trading" means to a reader. */
    val isTrade: Boolean get() = action == Form4.BUY || action == Form4.SELL

    /**
     * A trade the insider chose to make when they made it.
     *
     * This is the filter TJ asked for in so many words: purchases and sales, "not automatic
     * transactions scheduled before hand". A sale executing under a plan adopted six months
     * ago carries no opinion about today, so it is excluded here and badged in the UI rather
     * than hidden outright.
     */
    val isDiscretionary: Boolean get() = isTrade && !planned

    /**
     * True when the shares sold came straight out of an option exercise in the same filing.
     *
     * Worth saying on screen. An exercise-and-sell is a compensation event that happens to
     * end in a sale, and reading it as "the CFO is bailing out" is the single most common way
     * to misread a Form 4.
     */
    val exerciseAndSell: Boolean get() =
        action == Form4.SELL && trades.any { it.action == Form4.EXERCISE }

    /**
     * The headline, in the words TJ asked for: "CEO bought shares", "Director sold shares",
     * with the size attached because a 300-share purchase and a 300,000-share purchase are
     * not the same story.
     */
    fun headline(): String {
        val t = headlineTrade ?: return "$role filed a Form 4"
        val verb = Form4.verbFor(t.action, t.disposed)
        val what = if (t.security.isNotBlank())
            "${qty(t.shares)} ${plural(t.security, t.shares)}"
        else "${qty(t.shares)} shares"
        val sb = StringBuilder("$role $verb $what")
        if (t.value > 0) sb.append(" — ").append(money(t.value))
        return sb.toString()
    }

    /**
     * The same filing as a generic feed row, so real trades can appear in the All and
     * My-stocks lists alongside the headlines.
     *
     * The uid is the accession number, which is unique across the whole of EDGAR - so
     * `FeedItem.id` is unique too, and two insiders filing on the same day can no longer
     * collide into one LazyColumn key. That collision used to crash the app.
     */
    fun asFeedItem(owned: Boolean): FeedItem = FeedItem(
        kind = FeedItem.INSIDER,
        symbol = symbol,
        title = headline(),
        detail = subtitle(),
        url = url,
        source = if (planned) "SEC Form 4 · 10b5-1 plan" else "SEC Form 4",
        published = filedAt,
        owned = owned,
        uid = accession
    )

    /** The second line: who, at what price, and what they have left. */
    fun subtitle(): String {
        val parts = ArrayList<String>(4)
        if (person.isNotBlank()) parts.add(person)
        headlineTrade?.let { if (it.price > 0) parts.add("at " + priceOf(it.price)) }
        if (sharesAfter > 0) parts.add("${qty(sharesAfter)} shares held after")
        return parts.joinToString(" · ")
    }

    companion object {
        /**
         * Formatting lives here rather than in `Fmt` on purpose: these run inside pure JVM
         * unit tests, and `Fmt`'s `DecimalFormat` instances are locale-sensitive
         * ThreadLocals shared with the whole app. A share count printed in the feed must not
         * depend on which screen formatted a number last.
         */
        fun qty(v: Double): String =
            if (v >= 1_000_000) String.format(java.util.Locale.US, "%,.2fM", v / 1e6)
            else String.format(java.util.Locale.US, "%,.0f", v)

        /**
         * The K threshold is $10,000, not $1,000. Rounding to whole thousands turns a $1,234
         * trade into "$1K", which throws away most of what the reader wanted to know at
         * exactly the size where the exact figure still fits on the line.
         */
        fun money(v: Double): String = when {
            v >= 1e9 -> String.format(java.util.Locale.US, "$%.2fB", v / 1e9)
            v >= 1e6 -> String.format(java.util.Locale.US, "$%.2fM", v / 1e6)
            v >= 1e4 -> String.format(java.util.Locale.US, "$%,.0fK", v / 1e3)
            else -> String.format(java.util.Locale.US, "$%,.0f", v)
        }

        fun priceOf(v: Double): String = String.format(java.util.Locale.US, "$%,.2f", v)

        /**
         * "7,690 Restricted Stock Unit" is what the filing literally says and it reads as a
         * typo. Filers are inconsistent about number here - Apple files "Restricted Stock
         * Unit", Ford files "Ford Stock Units" - so the count decides, not the document.
         */
        fun plural(name: String, count: Double): String = when {
            count == 1.0 -> name
            name.endsWith("s", ignoreCase = true) -> name
            else -> name + "s"
        }

        // ------------------------------------------------------------------ JSON
        //
        // Persisted as ONE settings document, exactly like Keys.RESEARCH_CACHE and for the
        // same reasons: it is derived data, it is replaced wholesale, nothing ever queries
        // inside it, and a new table would have meant an onUpgrade migration - the one place
        // in this app where a mistake destroys the user's own transaction history. Losing
        // this cache costs one refresh.

        fun toJson(list: List<InsiderFiling>): String {
            val arr = JSONArray()
            for (f in list) {
                val t = JSONArray()
                for (x in f.trades) {
                    t.put(
                        JSONObject()
                            .put("c", x.code).put("a", x.action).put("d", x.disposed)
                            .put("s", x.shares).put("p", x.price).put("t", x.date)
                            .put("h", x.sharesAfter).put("v", x.derivative)
                            .put("n", x.security)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("sym", f.symbol).put("acc", f.accession)
                        .put("filed", f.filedAt).put("traded", f.tradeDate)
                        .put("who", f.person).put("role", f.role)
                        .put("plan", f.planned).put("amd", f.amended)
                        .put("after", f.sharesAfter).put("url", f.url)
                        .put("tr", t)
                )
            }
            return arr.toString()
        }

        fun fromJson(s: String): List<InsiderFiling> = runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val ta = o.optJSONArray("tr") ?: JSONArray()
                val trades = (0 until ta.length()).mapNotNull { j ->
                    val x = ta.optJSONObject(j) ?: return@mapNotNull null
                    InsiderTrade(
                        code = x.optString("c"),
                        action = x.optString("a"),
                        security = x.optString("n"),
                        disposed = x.optBoolean("d"),
                        shares = x.optDouble("s", 0.0),
                        price = x.optDouble("p", 0.0),
                        date = x.optLong("t"),
                        sharesAfter = x.optDouble("h", 0.0),
                        derivative = x.optBoolean("v")
                    )
                }
                val acc = o.optString("acc")
                if (acc.isBlank() || trades.isEmpty()) return@mapNotNull null
                InsiderFiling(
                    symbol = o.optString("sym"),
                    accession = acc,
                    filedAt = o.optLong("filed"),
                    tradeDate = o.optLong("traded"),
                    person = o.optString("who"),
                    role = o.optString("role"),
                    planned = o.optBoolean("plan"),
                    amended = o.optBoolean("amd"),
                    sharesAfter = o.optDouble("after", 0.0),
                    url = o.optString("url"),
                    trades = trades
                )
            }
        }.getOrDefault(emptyList())
    }
}
