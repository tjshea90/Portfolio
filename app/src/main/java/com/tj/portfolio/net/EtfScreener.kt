package com.tj.portfolio.net

import com.tj.portfolio.data.EtfRow
import com.tj.portfolio.util.finiteDouble
import com.tj.portfolio.util.text
import org.json.JSONObject

/**
 * THE ETF UNIVERSE - Yahoo's own fund screens, keyless, ten requests for ~850 funds.
 *
 * [Screener] does this for stocks and deliberately DROPS anything that is not `EQUITY`,
 * because the stock scorers read company fundamentals. Funds need the opposite filter and a
 * different set of fields, so this is its own object rather than a flag on that one - trying
 * to serve both from one parser is how a fund ends up being scored on its forward P/E.
 *
 * ---- WHICH LISTS, AND WHY
 *
 * Three, fetched by [Research.buildEtfs]: `top_etfs_us` six pages deep, `bond_etfs` three,
 * `commodity_etfs` one. That is about 850 distinct funds for ten requests.
 *
 * `top_performing_etfs` is NOT among them, and [Lists.TOP_PERFORMING_UNUSED] records the
 * measurement that settled it: it returns the same 523 symbols as `top_etfs_us` in a
 * different order, so taking both was six extra requests per pass, four times a day, for rows
 * the app already had. (An earlier version of this header argued the opposite - that their
 * first hundred rows overlapped by seven and both were therefore worth taking. That was
 * measured on one page rather than on the whole list, and it was wrong.)
 *
 * ---- WHY PAGINATION IS WORTH IT
 *
 * Yahoo caps `count` at 100 and honours `start`, so paging is the only way to see past the
 * first hundred funds of a 523-fund list - and every row arrives with its expense ratio, its
 * net assets, its three- and five-year annualised NAV returns, its YTD, its yield, its
 * liquidity and its moving averages already attached. The per-fund alternative -
 * `quoteSummary` with the cookie-and-crumb handshake, one call each - would be eight hundred
 * and fifty requests for the same answer, which the app's provider-safety rule forbids
 * outright.
 *
 * A page that does not answer costs nothing but its own rows: the caller scores whatever
 * arrived. That matters because this is still Yahoo and still 429s in bursts.
 */
object EtfScreener {

    object Lists {
        /** Yahoo's whole US ETF list - 523 funds in September 2026, six pages deep. */
        const val TOP_ETFS = "top_etfs_us"

        /**
         * Fixed income, which [TOP_ETFS] barely covers - 377 funds, and the reason the list
         * is not all equity.
         */
        const val BOND = "bond_etfs"

        /** Small (about 30) and entirely absent from the other two. */
        const val COMMODITY = "commodity_etfs"

        /**
         * NOT FETCHED, and recorded here so nobody adds it back.
         *
         * Measured live in September 2026: `top_performing_etfs` returns exactly the same 523
         * symbols as [TOP_ETFS], in a different order. Taking both was six extra requests per
         * pass for rows the app already had - four times a day, indefinitely, for nothing.
         * If this ever needs revisiting, the test is one page of each and a set intersection.
         */
        const val TOP_PERFORMING_UNUSED = "top_performing_etfs"
    }

    fun label(id: String): String = when (id) {
        Lists.TOP_ETFS -> "Yahoo's US ETF screen"
        Lists.BOND -> "Yahoo's bond-ETF screen"
        Lists.COMMODITY -> "Yahoo's commodity-ETF screen"
        else -> id.replace('_', ' ')
    }

    /** Yahoo silently truncates anything above this and the caller never notices. */
    private const val MAX_COUNT = 100

    /**
     * One page of one list.
     *
     * [start] is Yahoo's own offset. A page beyond the end of a list returns no quotes rather
     * than an error, which is the natural stopping condition for [fetchAll].
     */
    /**
     * One page.
     *
     * ---- NULL AND EMPTY ARE DIFFERENT ANSWERS (Round 66 audit, ETF-2)
     *
     * `emptyList()` means "Yahoo answered, and there is nothing here" - the end of the list.
     * `null` means "no host would answer": both cooling, both refusing, or the body
     * unparseable. They used to be the same value, and [fetchAll] stops on an empty page, so
     * a single transient 429 in the middle of a list truncated the fund universe and looked
     * exactly like reaching the end of it.
     */
    suspend fun fetch(listId: String, count: Int = MAX_COUNT, start: Int = 0): List<EtfRow>? {
        val n = count.coerceIn(1, MAX_COUNT)
        val from = start.coerceAtLeast(0)
        for (host in listOf("query1", "query2")) {
            val r = Http.get(
                "https://$host.finance.yahoo.com/v1/finance/screener/predefined/saved" +
                    "?scrIds=" + MarketData.enc(listId) + "&count=$n&start=$from",
                timeoutMs = 20000, conditionalKey = true
            )
            // ---- A COOLING HOST IS SKIPPED, NOT A REASON TO GIVE UP (Round 66 audit, H2).
            //
            // THE BUG THIS FIXES, and `ChartFeed.series` already carries the same note: `Http`
            // arms cooldowns PER HOST, so query1 being left alone says nothing at all about
            // query2. Abandoning the whole call on the first host's cooldown meant ONE 429
            // anywhere in the app - a quote batch, a chart, a quoteSummary, they all share
            // query1 - emptied this list, and with it the tab it feeds, for the length of that
            // cooldown while a perfectly healthy query2 sat unused. The comment that used to
            // be here argued "the throttle is on us, not it", which is true and beside the
            // point: it is on us AND THAT HOST.
            //
            // Skipping still honours the throttle - nothing is sent to a cooling host - and
            // when every host is cooling the loop falls through to the same empty result it
            // used to return immediately.
            if (r.throttledLocally) continue
            if (!r.ok) continue
            val parsed = runCatching { parse(listId, r.body) }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
            // AN EMPTY PAGE THAT PARSED IS AN ANSWER, NOT A FAILURE - it is how a list says
            // "that is the end of me", and `fetchAll` relies on exactly that to stop. Falling
            // through to the other Yahoo host here asked the identical question twice, so
            // every list's terminal page cost two requests rather than one.
            if (isWellFormed(r.body)) return emptyList()
        }
        // Nothing answered. NOT the same as an empty list - see the KDoc.
        return null
    }

    /** True when the body is a screener response we understood, however few rows it carried. */
    internal fun isWellFormed(body: String): Boolean = runCatching {
        JSONObject(body).optJSONObject("finance")?.optJSONArray("result")
            ?.optJSONObject(0)?.has("quotes") == true
    }.getOrDefault(false)

    /**
     * [pages] pages of one list, IN SEQUENCE.
     *
     * Sequential rather than parallel on purpose: these are three requests to the same host
     * within a second of each other, which is precisely the burst shape that earns a 429, and
     * page two is worthless without page one anyway. The whole ETF pass is six requests - it
     * does not need to be fast, it needs to arrive.
     *
     * Stops early on an empty page, so a list shorter than expected costs one wasted request
     * rather than all of them.
     */
    data class Fetched(
        val rows: List<EtfRow>,
        /**
         * True when every page asked for was either delivered or reached the end of the list.
         *
         * False means a page went unanswered and the universe is SHORT by an unknown amount -
         * which the caller has to be able to say out loud, because the alternative is a
         * ranking built from a fraction of the funds while the screen still claims to have
         * read all of them (Round 66 audit, ETF-2).
         */
        val complete: Boolean,
        /** How many pages actually arrived, for the warning text. */
        val pagesRead: Int,
        val pagesAsked: Int
    )

    suspend fun fetchAll(listId: String, pages: Int = 3): Fetched =
        fetchAllWith(pages) { start -> fetch(listId, MAX_COUNT, start) }

    /**
     * [fetchAll]'s loop, with the page fetcher handed in.
     *
     * Split out for one reason: the decision this loop makes - stop and call it complete, or
     * stop and say the universe is short - is the whole of ETF-2, and it was untestable while
     * it could only be reached through two live Yahoo hosts. `page` returns null for "nobody
     * answered" and an empty list for "that is the end of the list", exactly as [fetch] does.
     */
    internal suspend fun fetchAllWith(
        pages: Int,
        page: suspend (start: Int) -> List<EtfRow>?
    ): Fetched {
        val want = pages.coerceAtLeast(1)
        val out = ArrayList<EtfRow>(want * MAX_COUNT)
        var read = 0
        var unanswered = false
        for (p in 0 until want) {
            val got = page(p * MAX_COUNT)
            // ---- A PAGE NOBODY ANSWERED IS NOT THE END OF THE LIST (Round 66 audit, ETF-2).
            //
            // THE BUG THIS FIXES. `fetch` returned `emptyList()` for four different reasons -
            // past the end, both hosts cooling, both refusing, body unparseable - so this loop
            // could not tell them apart and treated all four as "that was the last page".
            // `Http` arms cooldowns per host and every feed in the app shares query1, so a
            // chart or a quote batch tripping a 429 anywhere was enough: page 2 of the US ETF
            // screen came back empty, the loop broke, and the universe was 200 funds instead
            // of 523. `buildEtfs` only warned when a list returned NOTHING, so 200 rows passed
            // silently and the screen went on saying it had ranked about 850 funds.
            //
            // Ironic detail worth recording: the Round 66 change that made a cooling host
            // `continue` instead of abandoning the call - a strict improvement on its own -
            // made THIS failure more likely, because it turned "give up loudly" into "return
            // empty quietly".
            if (got == null) { unanswered = true; break }
            read++
            // ---- STOP ONLY ON AN EMPTY PAGE (Round 66 audit, E1).
            //
            // THE BUG THIS FIXES. This used to also stop on `page.size < MAX_COUNT`, which
            // looks like the obvious "that was the last page" test and is not: `page` is what
            // [parse] returned AFTER dropping every quote that is not typed ETF - and parse's
            // own comment says these screens "return the occasional non-fund". So ONE equity
            // row anywhere in the first hundred made the count 99, broke the loop, and cut the
            // universe from 523 funds to 100. Silently: the caller only warns when a list
            // returns nothing at all, so the screen went on saying it had ranked about 850
            // funds while the top of the list was whatever happened to be on Yahoo's first
            // page. On the one list TJ is about to spend money from.
            //
            // The empty-page stop that `fetch` already documents is the correct one and needs
            // no extra information. `pages` is bounded at six, so the worst case is one wasted
            // request per list - which is the cheaper mistake by a wide margin.
            if (got.isEmpty()) break
            out.addAll(got)
        }
        // Reaching the end of a SHORT list is complete - the list really is that long. Only
        // a page nobody answered leaves the universe short by an unknown amount.
        return Fetched(out, complete = !unanswered, pagesRead = read, pagesAsked = want)
    }

    /** Split out so it can be tested against a saved response with no network. */
    internal fun parse(listId: String, body: String): List<EtfRow> {
        val res = JSONObject(body).optJSONObject("finance")?.optJSONArray("result")
            ?: return emptyList()
        val first = res.optJSONObject(0) ?: return emptyList()
        val quotes = first.optJSONArray("quotes") ?: return emptyList()
        val out = ArrayList<EtfRow>(quotes.length())
        for (i in 0 until quotes.length()) {
            val q = quotes.optJSONObject(i) ?: continue
            // BOTH LISTS RETURN THE OCCASIONAL NON-FUND. `top_performing_etfs` in particular
            // carries a few rows typed EQUITY, and scoring a share on an expense ratio it does
            // not have would put a fund-shaped hole in the middle of the list.
            if (q.text("quoteType") != "ETF") continue
            val sym = q.text("symbol").uppercase()
            if (sym.isBlank() || sym.contains('^')) continue
            out.add(
                EtfRow(
                    symbol = sym,
                    name = q.text("longName").ifBlank {
                        q.text("displayName").ifBlank { q.text("shortName") }
                    },
                    price = d(q, "regularMarketPrice"),
                    changePct = d(q, "regularMarketChangePercent"),
                    // PERCENT, NOT A FRACTION: 0.03 is three basis points. Verified live in
                    // September 2026 against `top_etfs_us` - SPY came back with
                    // `netExpenseRatio: 0.0945`, `yieldTTM: 0.98` and `dividendYield: 0.98`,
                    // and SPY's yield is 0.98%, not 98%.
                    //
                    // DO NOT "FIX" THIS BY MULTIPLYING BY 100 to match [Screener]. That file
                    // multiplies a DIFFERENT FIELD - `trailingAnnualDividendYield` on an
                    // EQUITY row - which really is a fraction. Two field names, two units,
                    // and reading one rule onto the other is a 100x error on a card.
                    // ---- -1.0, NOT 0.0, WHEN THE FIELD IS ABSENT (Round 66 audit, ETF-6).
                    //
                    // Every other number here can use 0.0 as its "not published" sentinel
                    // because zero is not a value any of them can really take. A fee can:
                    // BKLC and BKAG charge 0.00%. Sharing the sentinel made the cheapest
                    // funds on the market indistinguishable from funds with no fee data, and
                    // [EtfScore.best] scored them accordingly. -1.0 is impossible for a fee,
                    // so it can mean "absent" without stealing a real value.
                    expenseRatio = q.optDouble("netExpenseRatio", -1.0)
                        .let { if (it.isNaN() || it.isInfinite()) -1.0 else it },
                    netAssets = d(q, "netAssets"),
                    yieldPct = d(q, "yieldTTM").takeIf { it != 0.0 } ?: d(q, "dividendYield"),
                    ytdReturnPct = d(q, "ytdReturn"),
                    threeMonthPct = d(q, "trailingThreeMonthReturns").takeIf { it != 0.0 }
                        ?: d(q, "trailingThreeMonthNavReturns"),
                    // The 52-week CHANGE is a price return over exactly one year, which is
                    // the closest thing this feed carries to a one-year total return.
                    oneYearPct = d(q, "fiftyTwoWeekChangePercent"),
                    threeYearAnnualPct = d(q, "annualReturnNavY3"),
                    fiveYearAnnualPct = d(q, "annualReturnNavY5"),
                    avgVolume3M = d(q, "averageDailyVolume3Month").takeIf { it > 0 }
                        ?: d(q, "averageDailyVolume10Day"),
                    fiftyDayAvg = d(q, "fiftyDayAverage"),
                    twoHundredDayAvg = d(q, "twoHundredDayAverage"),
                    fiftyTwoWeekHigh = d(q, "fiftyTwoWeekHigh"),
                    fiftyTwoWeekLow = d(q, "fiftyTwoWeekLow"),
                    // Already in ms in this feed, unlike `earningsTimestamp` on the stock
                    // screener which arrives in seconds. Checked, not assumed.
                    inceptionMs = q.optLong("firstTradeDateMilliseconds", 0L),
                    exchange = q.text("fullExchangeName"),
                    lists = setOf(listId)
                )
            )
        }
        return out
    }

    /** See [com.tj.portfolio.util.finiteDouble] - the shared NaN/Infinite-guarded optDouble. */
    private fun d(o: JSONObject, key: String): Double = o.finiteDouble(key)
}
