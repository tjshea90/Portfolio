package com.tj.portfolio.net

import com.tj.portfolio.data.Consensus2
import com.tj.portfolio.data.EtfRow
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
 * One pass produces both stock sections. The shape of the pass is dictated by the app's
 * standing rule that no provider may be hammered (Round 50, T3/T4), so it is deliberately
 * WIDE AND SHALLOW rather than deep:
 *
 *   * 9 Yahoo screener calls return roughly 450 fully-populated quote objects. That is the
 *     entire candidate universe for Best and for Trending, for 9 requests.
 *   * 2 aggregator calls (Tradestie, ApeWisdom) give r/wallstreetbets mention counts.
 *   * 1 Yahoo trending call.
 *   * ~6 market-wide RSS feeds, already implemented in [News.market], give today's headlines
 *     once - the per-symbol news COUNT is then computed locally by matching those headlines
 *     against each candidate with [Relevance], which is the same matcher that fixed the FIVE
 *     bug. No per-symbol news request is made at all.
 *
 * That is ~18 requests for the whole tab, cached for [TTL_MS]. The expensive per-symbol work
 * - one Nasdaq analyst call per row - happens ONLY for the rows the user can actually see,
 * which is TJ's ten-at-a-time rule enforced on the wire and not just in the layout.
 *
 * ROUND 66 REMOVED THE "WORST" SECTION and, with it, a second per-row stage that cost up to
 * two Yahoo searches for every visible row. See the note where its scorer used to be in
 * [ResearchScore].
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
     * spread.
     */
    private const val MIN_MARKET_CAP = 5e7
    private const val MIN_PRICE = 1.0

    /** TJ: "include only stocks that are at least 2 dollars a share when searched." */
    private const val MIN_PRICE_DAY_TRADING = 2.0

    /** How many candidates the day-trading section keeps, deepest of the three sections. */
    private const val DAY_TRADING_BUFFER = 40

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

        // ------------------------------------------------------------------ best
        val best = tradable
            .asSequence()
            .filter { it.epsForward != 0.0 || it.forwardPe > 0 }
            .map { it to ResearchScore.best(it) }
            .filter { it.second.score > 0 && it.second.confidence >= 50 }
            .sortedByDescending { it.second.score }
            .take(BUFFER)
            .map { (row, sc) -> toRow(row, sc, headlines) }
            .toList()

        // ------------------------------------------------------------ day trading
        //
        // FROM THE SAME PASS, ZERO NEW REQUESTS. Reuses `universe` (the same nine screeners
        // above) and `trending`'s own output for the WSB/news attention signal, rather than
        // re-running the O(headlines x universe) matching `buildTrending` already paid for -
        // see [ResearchScore.dayTrading] for what this section is (and, at length, is not).
        val dayTrading = buildDayTrading(universe, trending)

        ResearchSet(
            trending = trending,
            best = best,
            dayTrading = dayTrading,
            generated = System.currentTimeMillis(),
            sources = SOURCES,
            warnings = warnings
        )
    }

    // ==================================================================== ETFs

    /**
     * HOW LONG A BUILT ETF LIST STAYS FRESH.
     *
     * Six hours, against thirty minutes for the stock lists, and the gap is the point. TJ:
     * *"It should periodically update the best etfs list, but keep the current list in cache
     * until each update so it doesn't load on every refresh."*
     *
     * Six because of what the ranking is made of. The heaviest inputs are five- and
     * three-year annualised returns and an expense ratio; none of those can move between
     * breakfast and lunch, and a fund ranking that reshuffled every half hour would be
     * presenting noise as news. Two refreshes in a trading day is more than enough to pick up
     * a real change, and a pull-to-refresh still forces one at any time.
     */
    const val ETF_TTL_MS = 6 * 60 * 60 * 1000L

    /** How deep the ETF buffer goes; the screen still reveals [ResearchSet.PAGE] at a time. */
    private const val ETF_BUFFER = 40

    /**
     * Funds below these are not opportunities, they are spreads - the same argument
     * [MIN_MARKET_CAP] makes for stocks, at fund scale. $25m is where issuers start closing
     * funds; $50k of daily turnover is where the bid-ask stops being a rounding error.
     */
    private const val MIN_FUND_ASSETS = 2.5e7
    private const val MIN_FUND_DOLLAR_VOLUME = 5e4

    /** Below this share of the six factors, a row is being ranked on absent data. */
    private const val MIN_ETF_CONFIDENCE = 60

    /**
     * BUILD THE BEST-ETFS LIST.
     *
     * Ten requests, wide and shallow, exactly like [build]:
     *
     *   * `top_etfs_us` x 6 pages - Yahoo's whole US ETF list, 523 funds;
     *   * `bond_etfs` x 3 pages - fixed income, which the list above barely covers;
     *   * `commodity_etfs` x 1 page.
     *
     * Every row arrives with its expense ratio, its net assets, its three- and five-year
     * annualised NAV returns, its YTD, its yield, its liquidity and its moving averages
     * already attached, so there is no per-fund second stage at all. The equivalent
     * per-symbol route is `quoteSummary` with the cookie-and-crumb handshake, once each -
     * nine hundred requests for the same answer.
     *
     * ---- WHAT THIS UNIVERSE IS NOT, AND WHY THE SCREEN SAYS SO
     *
     * Measured in September 2026: `top_etfs_us` and `top_performing_etfs` return the SAME 523
     * funds in different orders, so only one of them is fetched - taking both was two hundred
     * requests a day for a duplicate. And that 523 is not every US ETF: several very widely
     * held funds are simply not on Yahoo's lists. The app therefore ranks the universe it can
     * actually see, states which lists that was, and the Claude prompt names the gap and asks
     * for the funds it is missing by name. That is what TJ asked the online research to be
     * for - it fills a hole the free feeds genuinely have, rather than re-describing rows the
     * app already holds.
     */
    suspend fun buildEtfs(): ResearchSet = coroutineScope {
        val warnings = ArrayList<String>()
        val plan = listOf(
            EtfScreener.Lists.TOP_ETFS to 6,
            EtfScreener.Lists.BOND to 3,
            EtfScreener.Lists.COMMODITY to 1
        )
        // ONE LIST AT A TIME. `fetchAll` is already sequential within a list, and three fund
        // screens
        // firing their first pages simultaneously at one host is the burst shape that earns a
        // 429. This pass has a six-hour TTL - it does not need to be quick, it needs to land.
        val universe = LinkedHashMap<String, EtfRow>()
        for ((id, pages) in plan) {
            val got = runCatching { EtfScreener.fetchAll(id, pages) }
                .getOrDefault(EtfScreener.Fetched(emptyList(), complete = false, 0, pages))
            val rows = got.rows
            if (rows.isEmpty()) {
                warnings.add("${EtfScreener.label(id)} did not answer")
                continue
            }
            // ---- A LIST THAT ANSWERED PARTLY IS NOT A LIST THAT ANSWERED (Round 66 audit,
            // ETF-2).
            //
            // This used to warn only when a screen returned NOTHING. A screen that returned
            // its first two pages and then hit a cooldown returned 200 of 523 funds, which is
            // not nothing, so nothing was said - and the sources note under the list went on
            // telling TJ it had ranked about 850 funds. He is choosing what to buy from the
            // top of it; "these are the best funds" and "these are the best of the third of
            // the funds we managed to read" are different sentences and he is entitled to
            // know which one he is looking at.
            if (!got.complete) {
                warnings.add(
                    "${EtfScreener.label(id)} answered only ${got.pagesRead} of " +
                        "${got.pagesAsked} pages - Yahoo was rate-limiting. This ranking is " +
                        "over ${rows.size} of its funds; pull down in a few minutes for the rest."
                )
            }
            for (r in rows) {
                val existing = universe[r.symbol]
                universe[r.symbol] = if (existing == null) r else existing.merge(r)
            }
        }

        if (universe.isEmpty()) {
            return@coroutineScope ResearchSet(
                etfGenerated = 0L,
                etfWarnings = warnings,
                error = "Yahoo's fund screens did not answer. Pull down to try again - the " +
                    "app backs off on its own for a few minutes after a burst."
            )
        }

        val ranked = universe.values.asSequence()
            .filter { it.price > 0.0 }
            .filter { it.netAssets <= 0.0 || it.netAssets >= MIN_FUND_ASSETS }
            .filter { it.dollarVolume <= 0.0 || it.dollarVolume >= MIN_FUND_DOLLAR_VOLUME }
            // EXCLUDED, NOT PENALISED. A 3x fund's five-year annualised return is
            // arithmetically enormous and says nothing about whether holding it was wise;
            // left in, they would take the whole top of the list every time.
            .filter { !EtfScore.isLeveragedOrInverse(it.name, it.symbol) }
            .map { it to EtfScore.best(it) }
            .filter { it.second.confidence >= MIN_ETF_CONFIDENCE }
            // ---- THE TIE-BREAK MATTERS NOW THAT THE LOSER IS DELETED (Round 66 audit, ETF-4).
            //
            // THE BUG THIS FIXES. `sortedByDescending { score }` is a STABLE sort, so equal
            // scores kept the order they arrived in - which is Yahoo's screen order, held in a
            // `LinkedHashMap` keyed by fetch sequence. That was survivable while a tie only
            // put two funds on adjacent rows. Since the exposure de-duplication below, the
            // fund that loses a tie is REMOVED FROM THE LIST, so Yahoo's page order was
            // quietly deciding which S&P 500 tracker TJ gets shown.
            //
            // And ties are the normal case here, not the edge: every term that could separate
            // two trackers of one index is saturated for funds this size. The cost ramp is at
            // full marks anywhere at or below 5bp, so 2bp and 5bp score identically; `logRamp`
            // tops out at $50bn of assets and $100m of daily volume, which VOO, IVV and SPLG
            // all clear several times over. What is left is third-decimal noise in the
            // reported NAV returns, truncated to an Int.
            //
            // So the order becomes explicit: cheaper first (the one difference that is
            // certain, compounds, and is the reason to prefer one tracker over another), then
            // larger, then the ticker - which is arbitrary but STABLE, so the same inputs
            // always produce the same list rather than one that reshuffles with Yahoo's mood.
            // An unknown fee sorts last among equals: it cannot claim to be cheap.
            .sortedWith(ETF_ORDER)
            .toList()
            // ---- ONE FUND PER EXPOSURE (Round 66).
            //
            // Applied AFTER the ranking and BEFORE the buffer is cut, which is the only order
            // that works: dedupe first and the winner of each group would be chosen before it
            // was scored, cut first and a page of ten could still be five decisions wearing
            // ten tickers. VOO, IVV and SPLG are the same index, the same holdings and within
            // a basis point of each other, so they score within a point or two and arrive as
            // three consecutive rows - pushing out the five funds that would have been the
            // rest of the page. See [EtfExposure] for why grouping is read off the name and
            // why it errs toward leaving funds alone.
            .let { ranked ->
                EtfExposure.dedupe(ranked, name = { it.first.name }, symbol = { it.first.symbol })
            }
            .take(ETF_BUFFER)
            .map { (pair, also) -> toEtfRow(pair.first, pair.second, also) }

        if (ranked.isEmpty()) warnings.add("No fund carried enough published data to rank")

        ResearchSet(
            etfs = ranked,
            etfGenerated = System.currentTimeMillis(),
            etfWarnings = warnings
        )
    }

    const val ETF_SOURCES =
        "Yahoo Finance's own US ETF, bond-ETF and commodity-ETF screens - about 850 funds, " +
            "each arriving with its expense ratio, net assets, three- and five-year " +
            "annualised NAV returns, yield and average volume. Scores are computed on the " +
            "phone from those numbers, weighted toward the long run. Leveraged and inverse " +
            "funds are excluded, and where several funds track the same thing only the " +
            "best-scoring one takes a place - the rest are named on its card. Two things " +
            "these feeds do not carry are tracking difference and the actual holdings, which " +
            "is why the fund's own page is still worth opening. Yahoo's lists do not cover " +
            "every US ETF, so anything Claude adds through web research is added here too."

    private fun toEtfRow(
        r: EtfRow,
        sc: ResearchScore.Scored,
        alsoTracking: List<String> = emptyList()
    ): ResearchRow = ResearchRow(
        symbol = r.symbol,
        name = r.name,
        price = r.price,
        changePct = r.changePct,
        score = sc.score,
        // NAMED, NOT SILENTLY DROPPED. A fund removed from the page because something else
        // holds the same thing is still a fund TJ might prefer - a different issuer, a
        // different broker's commission-free list - so the row it lost to says so.
        //
        // FIRST, NOT LAST (Round 66 audit, E3). The card renders `reasons.take(6)`, and
        // `EtfScore.best` already emits six lines for any ordinary large fund: returns, cost,
        // size, trend, yield and "Found in ...". Appended, this line was the seventh and never
        // rendered - on VOO, which is the exact case the whole feature was written for. The
        // blurb and the sources note both promise the alternatives are named on the card, so
        // eight funds were disappearing from a forty-row list with nothing explaining where.
        reasons = if (alsoTracking.isEmpty()) sc.reasons
        else listOf(
            "Same exposure as " + alsoTracking.joinToString(", ") +
                " - this one scored highest of them"
        ) + sc.reasons,
        etf = com.tj.portfolio.data.EtfFacts(
            expenseRatio = r.expenseRatio,
            netAssets = r.netAssets,
            yieldPct = r.yieldPct,
            ytdReturnPct = r.ytdReturnPct,
            oneYearPct = r.oneYearPct,
            threeYearAnnualPct = r.threeYearAnnualPct,
            fiveYearAnnualPct = r.fiveYearAnnualPct,
            dollarVolume = r.dollarVolume,
            inceptionMs = r.inceptionMs
        )
    )

    /**
     * NASDAQ IS NAMED (Round 66 audit, RES-2). This note listed four feeds and left out the
     * one that supplies an outside party's OPINION rather than a measurement - the analyst
     * consensus and the price target that a Best card prints ("Strong Buy consensus - 16 buy /
     * 9 hold / 4 sell") and that thirty per cent of an enriched Best score is blended from.
     * The closing sentence then said the score was computed from the numbers just listed,
     * which for an enriched row was not true. A note headed "where these numbers come from"
     * has one job.
     */
    const val SOURCES =
        "Yahoo Finance predefined screeners and trending tickers; r/wallstreetbets mention " +
            "counts via Tradestie and ApeWisdom; headline counts from the app's own market " +
            "news feeds; analyst consensus and price targets from Nasdaq's public API, for " +
            "the rows on screen only. Scores are computed on the phone from those numbers, " +
            "with the analyst view blended in at 30% where there is one."

    // ----------------------------------------------------------------- trending

    /**
     * The order of the ETF list: score, then cost, then size, then ticker.
     *
     * Named and internal so the tie-break can be tested without a network - see [ETF_ORDER]'s
     * long note at the call site for why the tie-break is the interesting part.
     */
    internal val ETF_ORDER: Comparator<Pair<EtfRow, ResearchScore.Scored>> =
        compareByDescending<Pair<EtfRow, ResearchScore.Scored>> { it.second.score }
            .thenBy {
                if (it.first.expenseRatio >= 0.0) it.first.expenseRatio else Double.MAX_VALUE
            }
            .thenByDescending { it.first.netAssets }
            .thenBy { it.first.symbol }

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

    /**
     * See [ResearchScore.dayTrading]'s header for what this section is and, at length, is not.
     *
     * ZERO NEW REQUESTS: [universe] is the same nine-screener merge [build] already fetched,
     * and the WSB/news attention signal comes from `trending`'s OWN output rather than
     * re-running the O(headlines x universe) match [buildTrending] already paid for.
     */
    private fun buildDayTrading(
        universe: Map<String, ScreenRow>,
        trending: List<ResearchRow>
    ): List<ResearchRow> {
        val trendBy = trending.associateBy { it.symbol }
        val maxMentions = trending.maxOfOrNull { it.mentions } ?: 0
        val maxNews = trending.maxOfOrNull { it.newsCount } ?: 0

        return universe.values
            .asSequence()
            .filter {
                it.price >= MIN_PRICE_DAY_TRADING &&
                    (it.marketCap <= 0.0 || it.marketCap >= MIN_MARKET_CAP)
            }
            .map { row ->
                val tr = trendBy[row.symbol]
                val t = tr?.let {
                    ResearchScore.TrendInput(
                        symbol = row.symbol,
                        mentions = it.mentions,
                        rankDelta = it.rankDelta,
                        sentiment = it.sentiment,
                        newsCount = it.newsCount,
                        onYahooTrending = it.onYahooTrending,
                        changePct = row.changePct
                    )
                }
                val catalystSoon = row.earningsAt > 0 &&
                    (row.earningsAt - System.currentTimeMillis()) / 86_400_000L in 0..1
                row to ResearchScore.dayTrading(row, t, maxMentions, maxNews, catalystSoon)
            }
            .filter { it.second.score > 0 }
            .sortedByDescending { it.second.score }
            .take(DAY_TRADING_BUFFER)
            .map { (row, sc) -> toDayTradingRow(row, sc, trendBy[row.symbol]) }
            .toList()
    }

    private fun toDayTradingRow(
        r: ScreenRow,
        sc: ResearchScore.Scored,
        tr: ResearchRow?
    ): ResearchRow {
        // NO RISK PLAN AT BUILD TIME ANY MORE (Round 69). The screener pass knows a price and a
        // day change and nothing else - no VWAP, no opening range, no prior-session levels - and
        // the only "entry" derivable from that is the last traded price, which is precisely the
        // defect Tj reported. A plan now requires real intraday structure
        // ([ResearchScore.tradePlan]), which the live technicals pass fetches for the rows
        // actually on screen moments later. A blank plan for those few seconds is the honest
        // output; a fabricated one that reads like a real trigger is not.
        return ResearchRow(
            symbol = r.symbol,
            name = r.name,
            price = r.price,
            changePct = r.changePct,
            score = sc.score,
            reasons = sc.reasons,
            mentions = tr?.mentions ?: 0,
            newsCount = tr?.newsCount ?: 0,
            headline = tr?.headline.orEmpty(),
            headlineUrl = tr?.headlineUrl.orEmpty(),
            headlineSource = tr?.headlineSource.orEmpty(),
            onYahooTrending = tr?.onYahooTrending ?: false,
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
     * Second stage: analyst coverage. Runs for the VISIBLE rows only.
     *
     * [alreadyDone] lets the caller skip rows enriched on a previous page, so tapping
     * "Load more" costs exactly ten more lookups rather than re-doing the first ten.
     */
    suspend fun enrichAnalyst(
        rows: List<ResearchRow>,
        alreadyDone: Set<String> = emptySet()
    ): List<ResearchRow> = coroutineScope {
        val gate = Semaphore(3)
        rows.map { row ->
            async {
                if (row.symbol in alreadyDone || row.consensus != null) return@async row
                gate.withPermit {
                    val c = runCatching { consensus(row.symbol) }.getOrNull() ?: return@withPermit row
                    val base = ResearchScore.Scored(row.score, row.reasons, 100)
                    val blended = ResearchScore.withAnalyst(base, c, row.price)
                    row.copy(score = blended.score, reasons = blended.reasons, consensus = c)
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
