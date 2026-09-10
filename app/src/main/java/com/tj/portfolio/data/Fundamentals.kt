package com.tj.portfolio.data

/**
 * FUNDAMENTALS: the numbers other stock apps show on a stock's page, plus the professional
 * analyst ratings, plus everything the beginner explainer needs to talk about them.
 *
 * DESIGN NOTE - WHY A MAP AND NOT FIFTY FIELDS.
 *
 * Three providers feed this (Yahoo quoteSummary, Nasdaq's public JSON, a Finviz page parse)
 * and none of them carries every number for every stock: an ADR has no PEG, a company that
 * pays no dividend has no payout ratio, a stock Yahoo has thrown a 429 for has nothing at
 * all until Nasdaq answers. With fifty nullable fields, merging three partial answers means
 * fifty `?:` expressions that have to be kept in step with fifty parsers.
 *
 * So a value is a `key -> Double` entry that is either PRESENT or ABSENT, [merge] is one
 * loop, and the display layer asks the catalogue below what a key means. A key with no value
 * is not a zero - it prints "not reported", because a fabricated zero on a P/E ratio is a
 * wrong number that looks like a real one, and this project has been bitten by exactly that
 * shape of bug before (see the previous-close note in net/MarketData.kt).
 *
 * The catalogue is the single source of truth for the key set. [MetricCatalog.byKey] is what
 * the UI iterates, the parsers write into it, and ui/Explain.kt explains - a unit test
 * asserts all three agree, so a metric can never be fetched and then silently not shown, or
 * shown with no explanation behind its "i" button.
 */

/** How a raw number should be printed, and what it means arithmetically. */
enum class MetricUnit {
    /** Big dollars: 4.67T, 128.93B. */
    MONEY,
    /** A share price or per-share figure: $8.51. */
    PRICE,
    /** A plain multiple: 36.68. */
    RATIO,
    /** Already a percentage: 78.45 means 78.45%. */
    PERCENT,
    /** A fraction of one: 0.2762 means 27.62%. */
    FRACTION,
    /** A count of things, usually shares: 14.59B. */
    COUNT,
    /** Epoch milliseconds. */
    DATE,
    /** Free text, kept in [Fundamentals.texts] rather than [Fundamentals.values]. */
    TEXT
}

/** Display sections, in the order the Stats tab lists them. */
object MetricGroup {
    const val PROFILE = "Company"
    const val VALUATION = "Valuation"
    const val EARNINGS = "Earnings and cash"
    const val DIVIDEND = "Dividends"
    const val GROWTH = "Growth"
    const val PROFITABILITY = "Profitability"
    const val HEALTH = "Balance sheet"
    const val TRADING = "Price and risk"
    const val OWNERSHIP = "Ownership and short interest"

    /** Display order. Anything not listed sorts last. */
    val ORDER = listOf(
        PROFILE, VALUATION, EARNINGS, GROWTH, PROFITABILITY,
        DIVIDEND, HEALTH, TRADING, OWNERSHIP
    )
}

data class MetricDef(
    val key: String,
    val label: String,
    val group: String,
    val unit: MetricUnit,
    /**
     * True when a bigger number is generally the healthier one, false when smaller is,
     * null when it depends entirely on context (a P/E, a beta, a payout ratio).
     * Only used to tint the value; the real judgement lives in ui/Explain.kt.
     */
    val higherBetter: Boolean? = null
)

object MetricCatalog {

    val ALL: List<MetricDef> = listOf(
        // ---- Company ------------------------------------------------------
        MetricDef("employees", "Employees", MetricGroup.PROFILE, MetricUnit.COUNT),

        // ---- Valuation ----------------------------------------------------
        MetricDef("marketCap", "Market cap", MetricGroup.VALUATION, MetricUnit.MONEY),
        MetricDef("enterpriseValue", "Enterprise value", MetricGroup.VALUATION, MetricUnit.MONEY),
        MetricDef("peTrailing", "P/E ratio (trailing)", MetricGroup.VALUATION, MetricUnit.RATIO),
        MetricDef("peForward", "Forward P/E", MetricGroup.VALUATION, MetricUnit.RATIO),
        MetricDef("pegRatio", "PEG ratio", MetricGroup.VALUATION, MetricUnit.RATIO),
        MetricDef("priceToSales", "Price / sales (P/S)", MetricGroup.VALUATION, MetricUnit.RATIO),
        MetricDef("priceToBook", "Price / book (P/B)", MetricGroup.VALUATION, MetricUnit.RATIO),
        MetricDef("evToEbitda", "EV / EBITDA", MetricGroup.VALUATION, MetricUnit.RATIO),
        MetricDef("evToRevenue", "EV / revenue", MetricGroup.VALUATION, MetricUnit.RATIO),
        MetricDef("bookValue", "Book value per share", MetricGroup.VALUATION, MetricUnit.PRICE),

        // ---- Earnings and cash --------------------------------------------
        MetricDef("epsTrailing", "Earnings per share (last 12m)", MetricGroup.EARNINGS, MetricUnit.PRICE, true),
        MetricDef("epsForward", "Earnings per share (next 12m est.)", MetricGroup.EARNINGS, MetricUnit.PRICE, true),
        MetricDef("revenue", "Revenue (last 12m)", MetricGroup.EARNINGS, MetricUnit.MONEY, true),
        MetricDef("netIncome", "Net income (last 12m)", MetricGroup.EARNINGS, MetricUnit.MONEY, true),
        MetricDef("ebitda", "EBITDA", MetricGroup.EARNINGS, MetricUnit.MONEY, true),
        MetricDef("grossProfit", "Gross profit", MetricGroup.EARNINGS, MetricUnit.MONEY, true),
        MetricDef("operatingCashflow", "Operating cash flow", MetricGroup.EARNINGS, MetricUnit.MONEY, true),
        MetricDef("freeCashflow", "Free cash flow", MetricGroup.EARNINGS, MetricUnit.MONEY, true),
        MetricDef("revenuePerShare", "Revenue per share", MetricGroup.EARNINGS, MetricUnit.PRICE, true),

        // ---- Growth --------------------------------------------------------
        MetricDef("revenueGrowth", "Revenue growth (year over year)", MetricGroup.GROWTH, MetricUnit.FRACTION, true),
        MetricDef("earningsGrowth", "Earnings growth (year over year)", MetricGroup.GROWTH, MetricUnit.FRACTION, true),
        MetricDef("earningsQuarterlyGrowth", "Quarterly earnings growth", MetricGroup.GROWTH, MetricUnit.FRACTION, true),
        MetricDef("change52Week", "Price change over 1 year", MetricGroup.GROWTH, MetricUnit.FRACTION, true),
        MetricDef("sp500Change52Week", "S&P 500 change over 1 year", MetricGroup.GROWTH, MetricUnit.FRACTION),

        // ---- Profitability --------------------------------------------------
        MetricDef("grossMargins", "Gross margin", MetricGroup.PROFITABILITY, MetricUnit.FRACTION, true),
        MetricDef("operatingMargins", "Operating margin", MetricGroup.PROFITABILITY, MetricUnit.FRACTION, true),
        MetricDef("profitMargins", "Net profit margin", MetricGroup.PROFITABILITY, MetricUnit.FRACTION, true),
        MetricDef("ebitdaMargins", "EBITDA margin", MetricGroup.PROFITABILITY, MetricUnit.FRACTION, true),
        MetricDef("returnOnEquity", "Return on equity (ROE)", MetricGroup.PROFITABILITY, MetricUnit.FRACTION, true),
        MetricDef("returnOnAssets", "Return on assets (ROA)", MetricGroup.PROFITABILITY, MetricUnit.FRACTION, true),

        // ---- Dividends -------------------------------------------------------
        MetricDef("dividendRate", "Annual dividend per share", MetricGroup.DIVIDEND, MetricUnit.PRICE),
        MetricDef("dividendYield", "Dividend yield", MetricGroup.DIVIDEND, MetricUnit.FRACTION),
        MetricDef("trailingDividendRate", "Dividends paid (last 12m)", MetricGroup.DIVIDEND, MetricUnit.PRICE),
        MetricDef("trailingDividendYield", "Trailing dividend yield", MetricGroup.DIVIDEND, MetricUnit.FRACTION),
        MetricDef("payoutRatio", "Payout ratio", MetricGroup.DIVIDEND, MetricUnit.FRACTION),
        MetricDef("fiveYearAvgDividendYield", "5-year average yield", MetricGroup.DIVIDEND, MetricUnit.PERCENT),
        MetricDef("exDividendDate", "Ex-dividend date", MetricGroup.DIVIDEND, MetricUnit.DATE),
        MetricDef("dividendDate", "Next dividend payment", MetricGroup.DIVIDEND, MetricUnit.DATE),

        // ---- Balance sheet ----------------------------------------------------
        MetricDef("totalCash", "Cash and short-term investments", MetricGroup.HEALTH, MetricUnit.MONEY, true),
        MetricDef("totalCashPerShare", "Cash per share", MetricGroup.HEALTH, MetricUnit.PRICE, true),
        MetricDef("totalDebt", "Total debt", MetricGroup.HEALTH, MetricUnit.MONEY, false),
        MetricDef("debtToEquity", "Debt / equity", MetricGroup.HEALTH, MetricUnit.PERCENT, false),
        MetricDef("currentRatio", "Current ratio", MetricGroup.HEALTH, MetricUnit.RATIO, true),
        MetricDef("quickRatio", "Quick ratio", MetricGroup.HEALTH, MetricUnit.RATIO, true),

        // ---- Price and risk ----------------------------------------------------
        MetricDef("beta", "Beta (how far it swings)", MetricGroup.TRADING, MetricUnit.RATIO),
        MetricDef("fiftyTwoWeekHigh", "52-week high", MetricGroup.TRADING, MetricUnit.PRICE),
        MetricDef("fiftyTwoWeekLow", "52-week low", MetricGroup.TRADING, MetricUnit.PRICE),
        MetricDef("fiftyDayAverage", "50-day average price", MetricGroup.TRADING, MetricUnit.PRICE),
        MetricDef("twoHundredDayAverage", "200-day average price", MetricGroup.TRADING, MetricUnit.PRICE),
        MetricDef("volume", "Volume today", MetricGroup.TRADING, MetricUnit.COUNT),
        MetricDef("averageVolume", "Average volume (3 months)", MetricGroup.TRADING, MetricUnit.COUNT),
        MetricDef("averageVolume10days", "Average volume (10 days)", MetricGroup.TRADING, MetricUnit.COUNT),

        // ---- Ownership and short interest ----------------------------------------
        MetricDef("sharesOutstanding", "Shares outstanding", MetricGroup.OWNERSHIP, MetricUnit.COUNT),
        MetricDef("floatShares", "Public float", MetricGroup.OWNERSHIP, MetricUnit.COUNT),
        MetricDef("heldPercentInsiders", "Held by insiders", MetricGroup.OWNERSHIP, MetricUnit.FRACTION),
        MetricDef("heldPercentInstitutions", "Held by institutions", MetricGroup.OWNERSHIP, MetricUnit.FRACTION),
        MetricDef("shortPercentOfFloat", "Short interest (% of float)", MetricGroup.OWNERSHIP, MetricUnit.FRACTION, false),
        MetricDef("sharesShort", "Shares sold short", MetricGroup.OWNERSHIP, MetricUnit.COUNT, false),
        MetricDef("shortRatio", "Days to cover", MetricGroup.OWNERSHIP, MetricUnit.RATIO)
    )

    val byKey: Map<String, MetricDef> = ALL.associateBy { it.key }

    /** Every key in [ALL], for the coverage test. */
    val keys: Set<String> = byKey.keys

    /** The catalogue's own entries for one group, in declaration order. */
    fun group(name: String): List<MetricDef> = ALL.filter { it.group == name }
}

/**
 * One professional analyst action on a stock: who, when, what rating, what price target.
 *
 * Yahoo's `upgradeDowngradeHistory` is the only free feed found that carries the PRICE
 * TARGET alongside the firm and the grade - Nasdaq gives a consensus and a list of broker
 * names with no per-broker detail, and Finnhub's per-analyst endpoint is a paid tier. The
 * Finviz parse is the fallback that keeps this populated when Yahoo is rate-limiting.
 */
data class AnalystRating(
    val firm: String,
    /** When the action was published, in epoch millis. */
    val date: Long,
    /** UPGRADE / DOWNGRADE / INIT / REITERATE / RESUME / UNKNOWN - see [Companion]. */
    val action: String = UNKNOWN,
    /** The rating the analyst now has on the stock, as the firm words it. */
    val toGrade: String = "",
    /** What it was before, when the feed says. */
    val fromGrade: String = "",
    /** The analyst's price target, 0 when not supplied. */
    val target: Double = 0.0,
    /** The previous target, so "raised from" can be shown. */
    val priorTarget: Double = 0.0,
    /** Raises / Lowers / Maintains / Announces, when the feed says. */
    val targetAction: String = "",
    /** Named individual, when the source has one (Finviz and Nasdaq do not). */
    val analyst: String = "",
    val source: String = ""
) {
    /**
     * Stable identity, used BOTH to de-duplicate across sources and as the LazyColumn key.
     * They have to be the same expression - a list handed the same key twice throws, and
     * this project has already lost an app to exactly that on the insider-filings list.
     *
     * The date is truncated to the day because Yahoo timestamps to the second while Finviz
     * only dates to the day, so the same action from the two sources must collapse to one
     * row rather than appearing twice.
     */
    val id: String
        get() = firm.lowercase().trim() + "|" + (date / 86_400_000L) + "|" +
            toGrade.lowercase().trim()

    /** BUY / HOLD / SELL, worked out from the firm's own wording. */
    val bucket: String get() = bucketOf(toGrade)

    companion object {
        const val UPGRADE = "UPGRADE"
        const val DOWNGRADE = "DOWNGRADE"
        const val INIT = "INIT"
        const val REITERATE = "REITERATE"
        const val RESUME = "RESUME"
        const val UNKNOWN = "UNKNOWN"

        const val BUY = "BUY"
        const val HOLD = "HOLD"
        const val SELL = "SELL"
        const val NONE = "NONE"

        /**
         * Wall Street has no shared vocabulary: "Outperform", "Overweight", "Add",
         * "Accumulate", "Market Outperform" and "Positive" all mean buy, and "Equal-Weight",
         * "Sector Perform", "In-Line", "Peer Perform" and "Market Perform" all mean hold.
         * Matched on substrings, longest-meaning-first, because "Market Underperform"
         * contains "perform" and must not fall through to HOLD.
         */
        fun bucketOf(grade: String): String {
            val g = grade.lowercase()
            if (g.isBlank()) return NONE
            // SELL first: "underperform" and "underweight" both contain shorter buy/hold words.
            if (g.contains("underperform") || g.contains("underweight") ||
                g.contains("sell") || g.contains("reduce") || g.contains("negative")
            ) return SELL
            if (g.contains("outperform") || g.contains("overweight") || g.contains("buy") ||
                g.contains("accumulate") || g.contains("add") || g.contains("positive") ||
                g.contains("conviction") || g.contains("strong")
            ) return BUY
            if (g.contains("hold") || g.contains("neutral") || g.contains("equal") ||
                g.contains("in-line") || g.contains("in line") || g.contains("sector perform") ||
                g.contains("peer perform") || g.contains("market perform") ||
                g.contains("perform") || g.contains("mixed")
            ) return HOLD
            return NONE
        }

        /** Yahoo's terse `action` codes, and the words other feeds use. */
        fun actionOf(raw: String): String = when (raw.lowercase().trim()) {
            "up", "upgrade", "upgraded" -> UPGRADE
            "down", "downgrade", "downgraded" -> DOWNGRADE
            "init", "initiated", "initiates", "initiate", "initiated coverage" -> INIT
            "reit", "main", "reiterated", "reiterates", "maintains", "maintained" -> REITERATE
            "resume", "resumed", "resumes" -> RESUME
            else -> UNKNOWN
        }

        /** How the action reads in the list. */
        fun actionLabel(action: String): String = when (action) {
            UPGRADE -> "Upgrade"
            DOWNGRADE -> "Downgrade"
            INIT -> "Started coverage"
            REITERATE -> "Reiterated"
            RESUME -> "Resumed coverage"
            else -> "Rating"
        }
    }
}

/**
 * The market's aggregate view: how many analysts say buy/hold/sell, and where they think
 * the price is going. [mean] is Yahoo's 1-5 scale where 1 is strong buy and 5 strong sell.
 */
data class Consensus(
    val mean: Double = 0.0,
    val key: String = "",
    val analysts: Int = 0,
    val targetMean: Double = 0.0,
    val targetHigh: Double = 0.0,
    val targetLow: Double = 0.0,
    val targetMedian: Double = 0.0,
    val strongBuy: Int = 0,
    val buy: Int = 0,
    val hold: Int = 0,
    val sell: Int = 0,
    val strongSell: Int = 0
) {
    val votes: Int get() = strongBuy + buy + hold + sell + strongSell
    val hasVotes: Boolean get() = votes > 0
    val hasTarget: Boolean get() = targetMean > 0.0

    /** Plain-English summary of [mean], which is the figure most apps print as a word. */
    val meanLabel: String
        get() = when {
            mean <= 0.0 -> ""
            mean < 1.5 -> "Strong buy"
            mean < 2.5 -> "Buy"
            mean < 3.5 -> "Hold"
            mean < 4.5 -> "Sell"
            else -> "Strong sell"
        }

    /** How far the average target sits above (or below) a live price, as a percentage. */
    fun upsidePct(price: Double): Double? =
        if (targetMean > 0 && price > 0) (targetMean - price) / price * 100.0 else null
}

/** One month's snapshot of the buy/hold/sell counts, so the drift is visible. */
data class RatingTrend(
    /** Yahoo's periods: "0m" is this month, "-1m" last month, and so on. */
    val period: String,
    val strongBuy: Int = 0,
    val buy: Int = 0,
    val hold: Int = 0,
    val sell: Int = 0,
    val strongSell: Int = 0
) {
    val total: Int get() = strongBuy + buy + hold + sell + strongSell

    val label: String
        get() = when (period) {
            "0m" -> "Now"
            "-1m" -> "1 month ago"
            "-2m" -> "2 months ago"
            "-3m" -> "3 months ago"
            else -> period
        }
}

/** What analysts expect the company to report, for one period. */
data class EarningsEstimate(
    /** "0q" this quarter, "+1q" next quarter, "0y" this year, "+1y" next year. */
    val period: String,
    val endDate: String = "",
    val epsAvg: Double = 0.0,
    val epsLow: Double = 0.0,
    val epsHigh: Double = 0.0,
    val epsYearAgo: Double = 0.0,
    val epsGrowth: Double = 0.0,
    val analysts: Int = 0,
    val revenueAvg: Double = 0.0,
    val revenueGrowth: Double = 0.0
) {
    val label: String
        get() = when (period) {
            "0q" -> "Current quarter"
            "+1q" -> "Next quarter"
            "0y" -> "Current year"
            "+1y" -> "Next year"
            "-1q" -> "Last quarter"
            else -> period
        }
}

/** One quarter already reported: what was expected against what actually landed. */
data class EarningsResult(
    val quarter: String,
    val date: Long = 0L,
    val epsEstimate: Double = 0.0,
    val epsActual: Double = 0.0,
    val surprisePct: Double = 0.0
) {
    val beat: Boolean get() = epsActual > epsEstimate
}

/**
 * Everything gathered for one symbol, from however many providers answered.
 *
 * [fetched] is when the newest piece landed; the UI prints it so a number that is a day old
 * says so rather than pretending to be live. [sources] names every provider that contributed,
 * because when two disagree the user is entitled to know which one they are reading.
 */
data class Fundamentals(
    val symbol: String,
    val values: Map<String, Double> = emptyMap(),
    val texts: Map<String, String> = emptyMap(),
    val consensus: Consensus? = null,
    val ratings: List<AnalystRating> = emptyList(),
    val trend: List<RatingTrend> = emptyList(),
    val estimates: List<EarningsEstimate> = emptyList(),
    val history: List<EarningsResult> = emptyList(),
    /** Next scheduled earnings report, epoch millis, 0 when unknown. */
    val earningsDate: Long = 0L,
    val profile: String = "",
    val sources: List<String> = emptyList(),
    val fetched: Long = 0L
) {
    fun value(key: String): Double? = values[key]
    fun text(key: String): String = texts[key].orEmpty()

    val isEmpty: Boolean
        get() = values.isEmpty() && texts.isEmpty() && ratings.isEmpty() &&
            consensus == null && estimates.isEmpty()

    /** Keys this object actually has a number for, restricted to one catalogue group. */
    fun presentIn(group: String): List<MetricDef> =
        MetricCatalog.group(group).filter { values.containsKey(it.key) }

    companion object {
        /**
         * Merge a lower-priority answer UNDERNEATH this one.
         *
         * "Underneath" is the whole point: [base] is whichever provider is trusted more, and
         * `fill` only supplies keys base is missing. A provider that answered with a partial
         * payload therefore cannot overwrite a good number with a worse one, and the order
         * of the fallback chain in net/Fundamentals.kt is the only thing that decides
         * precedence - not the order the responses happen to arrive in.
         */
        fun merge(base: Fundamentals, fill: Fundamentals): Fundamentals {
            val values = HashMap(fill.values)
            values.putAll(base.values)          // base wins on any shared key
            val texts = HashMap(fill.texts)
            texts.putAll(base.texts.filterValues { it.isNotBlank() })
            val ratings = (base.ratings + fill.ratings)
                .distinctBy { it.id }
                .sortedByDescending { it.date }
            return Fundamentals(
                symbol = base.symbol.ifBlank { fill.symbol },
                values = values,
                texts = texts,
                consensus = base.consensus ?: fill.consensus,
                ratings = ratings,
                trend = base.trend.ifEmpty { fill.trend },
                estimates = base.estimates.ifEmpty { fill.estimates },
                history = base.history.ifEmpty { fill.history },
                earningsDate = if (base.earningsDate > 0) base.earningsDate else fill.earningsDate,
                profile = base.profile.ifBlank { fill.profile },
                sources = (base.sources + fill.sources).distinct(),
                fetched = maxOf(base.fetched, fill.fetched)
            )
        }
    }
}
