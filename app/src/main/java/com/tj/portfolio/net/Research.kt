package com.tj.portfolio.net

import com.tj.portfolio.data.Consensus2
import com.tj.portfolio.data.NewsItem
import com.tj.portfolio.data.ResearchRow
import com.tj.portfolio.data.ResearchSet
import com.tj.portfolio.data.ScreenRow
import com.tj.portfolio.util.Fmt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * BUILDS THE RESEARCH TAB.
 *
 * One pass produces all three sections. The shape of the pass is dictated by the app's
 * standing rule that no provider may be hammered (Round 50, T3/T4), so it is deliberately
 * WIDE AND SHALLOW rather than deep:
 *
 *   * 9 Yahoo screener calls return roughly 450 fully-populated quote objects. That is the
 *     entire candidate universe for Best and Worst, for 9 requests.
 *   * 2 aggregator calls (Tradestie, ApeWisdom) give r/wallstreetbets mention counts.
 *   * 1 Yahoo trending call.
 *   * ~6 market-wide RSS feeds, already implemented in [News.market], give today's headlines
 *     once - the per-symbol news COUNT is then computed locally by matching those headlines
 *     against each candidate with [Relevance], which is the same matcher that fixed the FIVE
 *     bug. No per-symbol news request is made at all.
 *
 * That is ~18 requests for the whole tab, cached for [TTL_MS]. The expensive per-symbol work
 * - one Nasdaq analyst call and, for the Worst list, one Yahoo fund search - happens ONLY for
 * the rows the user can actually see, which is TJ's ten-at-a-time rule enforced on the wire
 * and not just in the layout.
 */
object Research {

    /**
     * How long a built Research set stays fresh.
     *
     * 30 minutes is not arbitrary: ApeWisdom recomputes about every 30 minutes and Tradestie
     * every 15, so a shorter interval re-fetches the same numbers. Yahoo's screeners move
     * continuously, but a "best stocks" ranking that changes every two minutes would be noise
     * presented as signal.
     */
    const val TTL_MS = 30 * 60 * 1000L

    /** How deep each section's buffer goes; the screen reveals [ResearchSet.PAGE] at a time. */
    private const val BUFFER = 50

    /** Per-list depth. 50 x 9 lists is the candidate universe. */
    private const val SCREEN_DEPTH = 50

    private const val MAX_PARALLEL = 4

    /**
     * A stock too small or too thinly traded to research is not an opportunity, it is a
     * spread. Applies to BOTH lists: a $12m shell at the top of the Worst list is not news.
     */
    private const val MIN_MARKET_CAP = 5e7
    private const val MIN_PRICE = 1.0

    // ------------------------------------------------------------------- the pass

    suspend fun build(): ResearchSet = coroutineScope {
        val warnings = ArrayList<String>()
        val gate = Semaphore(MAX_PARALLEL)

        val lists = listOf(
            Screener.Lists.DAY_GAINERS,
            Screener.Lists.DAY_LOSERS,
            Screener.Lists.MOST_ACTIVE,
            Screener.Lists.MOST_SHORTED,
            Screener.Lists.UNDERVALUED_GROWTH,
            Screener.Lists.GROWTH_TECH,
            Screener.Lists.UNDERVALUED_LARGE,
            Screener.Lists.SMALL_CAP_GAINERS,
            Screener.Lists.AGGRESSIVE_SMALL
        )

        val screenJobs = lists.map { id ->
            async { id to gate.withPermit { runCatching { Screener.fetch(id, SCREEN_DEPTH) }.getOrDefault(emptyList()) } }
        }
        val socialJob = async { runCatching { Social.trending(60) }.getOrDefault(emptyList()) }
        val yahooTrendJob = async { runCatching { Screener.trendingSymbols(25) }.getOrDefault(emptyList()) }
        val newsJob = async { runCatching { News.market() }.getOrDefault(emptyList()) }

        // --- merge every screener result into one universe keyed by symbol
        val universe = LinkedHashMap<String, ScreenRow>()
        for (j in screenJobs) {
            val (id, rows) = j.await()
            if (rows.isEmpty()) warnings.add("Yahoo's ${Screener.label(id)} screen did not answer")
            for (r in rows) {
                val existing = universe[r.symbol]
                universe[r.symbol] = if (existing == null) r else existing.merge(r)
            }
        }
        val social = socialJob.await()
        val yahooTrending = yahooTrendJob.await().toSet()
        val headlines = newsJob.await()

        if (social.isEmpty()) warnings.add("No r/wallstreetbets data this pass")
        if (headlines.isEmpty()) warnings.add("Market headlines did not load this pass")
        if (universe.isEmpty()) {
            return@coroutineScope ResearchSet(
                generated = System.currentTimeMillis(),
                warnings = warnings,
                error = "None of the market screens answered. Pull down to try again - Yahoo " +
                    "rate-limits bursts, and the app backs off on its own for a few minutes."
            )
        }

        val tradable = universe.values.filter {
            it.price >= MIN_PRICE && (it.marketCap <= 0.0 || it.marketCap >= MIN_MARKET_CAP)
        }

        // ---------------------------------------------------------------- trending
        val trending = buildTrending(social, yahooTrending, headlines, universe)

        // ------------------------------------------------------------ best / worst
        val best = tradable
            .asSequence()
            .filter { it.epsForward != 0.0 || it.forwardPe > 0 }
            .map { it to ResearchScore.best(it) }
            .filter { it.second.score > 0 && it.second.confidence >= 50 }
            .sortedByDescending { it.second.score }
            .take(BUFFER)
            .map { (row, sc) -> toRow(row, sc, headlines) }
            .toList()

        val worst = tradable
            .asSequence()
            .map { it to ResearchScore.worst(it) }
            .filter { it.second.score >= 25 && it.second.confidence >= 50 }
            .sortedByDescending { it.second.score }
            .take(BUFFER)
            .map { (row, sc) -> toRow(row, sc, headlines) }
            .toList()

        ResearchSet(
            trending = trending,
            best = best,
            worst = worst,
            generated = System.currentTimeMillis(),
            sources = SOURCES,
            warnings = warnings
        )
    }

    const val SOURCES =
        "Yahoo Finance predefined screeners and trending tickers; r/wallstreetbets mention " +
            "counts via Tradestie and ApeWisdom; headline counts from the app's own market " +
            "news feeds. Scores are computed on the phone from those numbers."

    // ----------------------------------------------------------------- trending

    private fun buildTrending(
        social: List<com.tj.portfolio.data.Trending>,
        yahooTrending: Set<String>,
        headlines: List<NewsItem>,
        universe: Map<String, ScreenRow>
    ): List<ResearchRow> {
        // The candidate set is the union of the three sources, so a stock all over the news
        // with no Reddit chatter still appears - and vice versa. That union IS the "blend".
        val names = HashMap<String, String>()
        social.forEach { names[it.symbol] = universe[it.symbol]?.name.orEmpty() }
        yahooTrending.forEach { names.putIfAbsent(it, universe[it]?.name.orEmpty()) }

        // Anything the market-wide headlines are actually about, whether or not it is on a
        // social list, using the universe as the name book.
        //
        // THE HOTTEST LOOP IN THE APP, AND IT IS QUADRATIC BY NATURE (Round 57).
        //
        // `headlines` is the market-wide pull - 300-500 items across seven feeds - and
        // `universe` is up to nine screeners x SCREEN_DEPTH, about 450 distinct symbols. That
        // is 150,000-225,000 relevance tests per research build, and there is no way around
        // the shape: the question genuinely is "which of these symbols is each headline
        // about?".
        //
        // What CAN go is the work repeated inside it. Before this, every one of those calls
        // recompiled a word-splitting regex, re-derived the company's identifying words, and
        // rebuilt a squashed copy of the whole headline. Now the per-symbol half is computed
        // once per symbol and the per-headline half once per headline, leaving only the
        // comparisons. Same answers, ~200,000 regex compilations and ~200,000 string rebuilds
        // removed.
        val subjects = universe.map { (sym, row) -> sym to Relevance.Subject.of(sym, row.name) }
        val newsHits = HashMap<String, MutableList<NewsItem>>()
        for (h in headlines) {
            val squashed = Relevance.squashed(h.title, h.summary)
            for ((sym, subject) in subjects) {
                if (Relevance.matches(subject, h.title, h.summary, squashed)) {
                    newsHits.getOrPut(sym) { ArrayList() }.add(h)
                }
            }
        }
        newsHits.keys.forEach { names.putIfAbsent(it, universe[it]?.name.orEmpty()) }

        val socialBy = social.associateBy { it.symbol }
        val maxMentions = social.maxOfOrNull { it.activity } ?: 0
        val maxNews = newsHits.values.maxOfOrNull { it.size } ?: 0

        val rows = names.keys.mapNotNull { sym ->
            val t = socialBy[sym]
            val news = newsHits[sym].orEmpty()
            val q = universe[sym]
            // A ticker nothing else knows about and that carries no news is noise - most
            // often a Reddit post about a private company or a typo'd ticker.
            if (t == null && news.isEmpty() && sym !in yahooTrending) return@mapNotNull null
            val input = ResearchScore.TrendInput(
                symbol = sym,
                mentions = t?.activity ?: 0,
                mentions24hAgo = t?.mentions24hAgo ?: 0,
                rankDelta = t?.rankDelta ?: 0,
                sentiment = t?.sentiment.orEmpty(),
                sentimentScore = t?.sentimentScore ?: 0.0,
                newsCount = news.size,
                onYahooTrending = sym in yahooTrending,
                changePct = q?.changePct ?: 0.0
            )
            val sc = ResearchScore.trending(input, maxMentions, maxNews)
            val top = news.maxByOrNull { it.published }
            ResearchRow(
                symbol = sym,
                name = q?.name.orEmpty(),
                price = q?.price ?: 0.0,
                changePct = q?.changePct ?: 0.0,
                score = sc.score,
                reasons = sc.reasons,
                mentions = input.mentions,
                mentionDelta = t?.mentionDelta ?: 0,
                rankDelta = input.rankDelta,
                sentiment = input.sentiment,
                newsCount = news.size,
                headline = top?.title.orEmpty(),
                headlineUrl = top?.url.orEmpty(),
                headlineSource = top?.source.orEmpty(),
                onYahooTrending = input.onYahooTrending,
                catalyst = catalystFor(q)
            )
        }
        return rows.sortedByDescending { it.score }.take(BUFFER)
    }

    // --------------------------------------------------------------------- rows

    private fun toRow(
        r: ScreenRow,
        sc: ResearchScore.Scored,
        headlines: List<NewsItem>
    ): ResearchRow {
        // One subject for the whole scan rather than one per headline - same reasoning as the
        // loop in `build`, at a smaller scale (up to 100 rows x the headline list).
        val subject = Relevance.Subject.of(r.symbol, r.name)
        val top = headlines.firstOrNull { Relevance.matches(subject, it.title, it.summary) }
        return ResearchRow(
            symbol = r.symbol,
            name = r.name,
            price = r.price,
            changePct = r.changePct,
            score = sc.score,
            reasons = sc.reasons,
            headline = top?.title.orEmpty(),
            headlineUrl = top?.url.orEmpty(),
            headlineSource = top?.source.orEmpty(),
            catalyst = catalystFor(r)
        )
    }

    /** The nearest dated event the screener knows about - almost always the next earnings. */
    private fun catalystFor(r: ScreenRow?): String {
        if (r == null || r.earningsAt <= 0) return ""
        val days = (r.earningsAt - System.currentTimeMillis()) / 86_400_000L
        if (days < -2 || days > 120) return ""
        val est = if (r.earningsEstimated) " (estimated)" else ""
        return when {
            days < 0 -> "Reported earnings ${Fmt.shortDay(r.earningsAt)}"
            days == 0L -> "Earnings today$est"
            days == 1L -> "Earnings tomorrow$est"
            else -> "Earnings in $days days - ${Fmt.shortDay(r.earningsAt)}$est"
        }
    }

    // ------------------------------------------------------------- enrichment

    /**
     * Second stage: analyst coverage, and for the Worst list the listed way to bet against
     * the name. Runs for the VISIBLE rows only.
     *
     * [alreadyDone] lets the caller skip rows enriched on a previous page, so tapping
     * "Load more" costs exactly ten more lookups rather than re-doing the first ten.
     */
    suspend fun enrichAnalyst(
        rows: List<ResearchRow>,
        bullish: Boolean,
        alreadyDone: Set<String> = emptySet()
    ): List<ResearchRow> = coroutineScope {
        val gate = Semaphore(3)
        rows.map { row ->
            async {
                if (row.symbol in alreadyDone || row.consensus != null) return@async row
                gate.withPermit {
                    val c = runCatching { consensus(row.symbol) }.getOrNull() ?: return@withPermit row
                    val base = ResearchScore.Scored(row.score, row.reasons, 100)
                    val blended = ResearchScore.withAnalyst(base, c, row.price, bullish)
                    row.copy(score = blended.score, reasons = blended.reasons, consensus = c)
                }
            }
        }.map { it.await() }
    }

    /**
     * The inverse-ETF lookup, kept apart from the analyst pass ON PURPOSE.
     *
     * Each one costs up to two Yahoo search calls, so it runs for the ten rows the user can
     * see AFTER analyst coverage has settled the ranking - never for the wider window the
     * ranking is chosen from. Doing both in one pass would have quadrupled the request count
     * for rows that then fell off the page.
     */
    suspend fun enrichShortVehicles(
        rows: List<ResearchRow>,
        alreadyDone: Set<String> = emptySet()
    ): List<ResearchRow> = coroutineScope {
        val gate = Semaphore(2)
        rows.map { row ->
            async {
                if (row.symbol in alreadyDone) return@async row
                if (row.shortVehicle.isNotBlank() || row.shortVehicleNote.isNotBlank()) return@async row
                gate.withPermit {
                    val v = runCatching { ShortVehicle.forSymbol(row.symbol) }.getOrNull()
                    if (v != null) row.copy(shortVehicle = v.ticker, shortVehicleNote = v.name)
                    else row.copy(
                        shortVehicleNote = "No listed fund shorts this stock on its own"
                    )
                }
            }
        }.map { it.await() }
    }

    /**
     * Analyst consensus from Nasdaq's public API - buy/hold/sell counts and the average price
     * target in one call, no key.
     *
     * Yahoo's equivalent needs the cookie+crumb dance and returns a recommendation MEAN
     * rather than the counts, which cannot be shown as "16 buy / 9 hold / 4 sell". Nasdaq is
     * already a trusted provider in this app ([FundamentalsFeed] falls back to it), so this
     * adds no new host.
     */
    internal suspend fun consensus(symbol: String): Consensus2? {
        val r = Http.get(
            "https://api.nasdaq.com/api/analyst/" + MarketData.enc(symbol.uppercase()) +
                "/targetprice",
            timeoutMs = 15000, conditionalKey = true
        )
        if (!r.ok) return null
        return parseConsensus(r.body)
    }

    internal fun parseConsensus(body: String): Consensus2? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val c = o.optJSONObject("data")?.optJSONObject("consensusOverview") ?: return null
        val out = Consensus2(
            buy = c.optInt("buy", 0),
            hold = c.optInt("hold", 0),
            sell = c.optInt("sell", 0),
            target = c.optDouble("priceTarget", 0.0).let { if (it.isNaN()) 0.0 else it }
        )
        return if (out.total == 0 && out.target <= 0.0) null else out
    }
}
