package com.tj.portfolio.net

/**
 * Is this market-wide headline actually about the market?
 *
 * WHY THIS FILE EXISTS. TJ's Live feed, All tab, on 5 September 2026, top of the list:
 *
 *     My wife and I are in our 70s. Should we move to California and take on a bigger
 *         mortgage to be near our kids?
 *     How these Gen Z workers managed to buy homes in their early 20s
 *     'Poverty doesn't have to be my reality': I thought I'd have to rely on Social
 *         Security. Then I taught myself how to invest.
 *     Fake job recruiters are getting smarter about scamming job seekers.
 *
 * None of that is news about a traded company. It is a personal-finance advice column, and it
 * was arriving because the market-wide pull took MarketWatch's *top stories* feed, which is
 * MarketWatch's front page - Moneyist letters, retirement columns and lifestyle pieces
 * included. Fetched live while writing this: 9 of its 10 items were of that kind.
 *
 * TWO FIXES, AND BOTH WERE NEEDED. [News.market] now pulls from finance desks rather than
 * front pages - the feed swap is documented there, with the measurements. This file is the
 * second half, because no choice of source is clean: MarketWatch's markets bulletin still runs
 * "Will a data center hurt your home's value?", and a Google News query for the stock market
 * still returns "College Football's Newest Battleground".
 *
 * HOW IT DECIDES. Three tests, in order, over the headline and its summary:
 *
 *   1. ADVICE-COLUMN GRAMMAR ([SHAPE]) is fatal on its own. "My wife and I...", "Should we...",
 *      "Here's how to...", "How these...", "your home", "I'm 63 and...". This is a test of the
 *      sentence's *shape*, not its topic, and that is what makes it reliable: a piece written
 *      in the second person about the reader's own money is not market news no matter how many
 *      market words it contains. It is why "Vanguard's S&P 500 index fund changed how we
 *      invest" is rejected while genuine S&P 500 coverage is not.
 *
 *   2. PERSONAL-LIFE TOPICS ([TOPIC]) - Social Security, Medicare, nest eggs, grandkids, job
 *      scams - are fatal UNLESS the headline also carries a strong market signal ([STRONG]) or
 *      names a ticker. That exception matters: "UnitedHealth shares fall on Medicare Advantage
 *      rates" is real news about a real holding and must survive a rule aimed at retirement
 *      columns.
 *
 *   3. Otherwise the headline must contain SOME market vocabulary ([POS]) - but only from a
 *      broad source. See [isMarketNews]'s `strict` parameter.
 *
 * WORD BOUNDARIES ARE NOT OPTIONAL HERE, and this was found the hard way. Matching "a raise"
 * as a plain substring rejects
 *
 *     "Dow, S&P 500 and Nasdaq close lower as jobs dat[a raise] chances of Fed rate hike"
 *
 * which is about as market as a headline gets. Everything is normalised to space-separated
 * tokens and every phrase is matched with a space on each side.
 *
 * MEASURED, against 386 headlines pulled live from all eight candidate feeds on 5 Sept 2026:
 * 360 kept. Every one of the four items TJ complained about is rejected, and the rejections
 * were read one by one - see `MarketRelevanceTest`, which holds the real headlines as its
 * cases so a future change to these lists has to answer for them.
 */
object MarketRelevance {

    /**
     * Market vocabulary. Deliberately broad: this is the *weakest* test and only decides
     * headlines that survived both rejection rules, so a false negative here costs a real
     * story while a false positive costs one line of general business news.
     */
    private val POS = listOf(
        "stock", "stocks", "share", "shares", "shareholder", "equity", "equities", "earnings",
        "guidance", "revenue", "profit", "profits", "eps", "quarterly", "quarter", "upgrade",
        "upgraded", "downgrade", "downgraded", "price target", "analyst", "analysts", "ipo",
        "merger", "acquisition", "acquire", "acquires", "acquired", "buyout", "takeover",
        "buyback", "dividend", "nasdaq", "s&p 500", "s&p500", "dow", "russell", "wall street",
        "futures", "bond", "bonds", "yield", "yields", "treasury", "treasuries", "fed",
        "federal reserve", "fomc", "powell", "interest rate", "interest rates", "rate cut",
        "rate hike", "mortgage rate", "mortgage rates", "inflation", "cpi", "ppi",
        "jobs report", "payrolls", "labor market", "unemployment", "jobless", "gdp", "economy",
        "economic", "recession", "rally", "rallies", "rallied", "selloff", "sell-off",
        "bear market", "bull market", "index fund", "etf", "valuation", "market cap",
        "short seller", "hedge fund", "private equity", "stake", "investor", "investors",
        "investment", "trading", "traded", "trades", "trader", "traders", "premarket",
        "pre-market", "after-hours", "sector", "sectors", "commodities", "crude", "oil",
        "gas prices", "gold", "silver", "copper", "crypto", "bitcoin", "ethereum", "currency",
        "dollar", "forex", "bankruptcy", "chapter 11", "restructuring", "layoffs", "outlook",
        "forecast", "estimates", "market", "markets", "prices", "pricing", "growth", "sales",
        "fda", "approval", "recall", "lawsuit", "settlement", "antitrust", "regulator",
        "regulators", "ftc", "doj", "sec", "ceo", "chief executive", "chief financial",
        "contract", "deal", "partnership", "results", "trial", "study", "jumped", "jumps",
        "surged", "surges", "plunged", "plunges", "soared", "soars", "tumbled", "tumbles",
        "slumped", "slumps", "record high", "all-time high", "52-week", "spinoff", "spin-off",
        "delisted", "short interest", "options", "index", "indexes", "indices", "tariff",
        "tariffs", "supply chain", "demand", "capex", "margins", "billion", "million",
        "financial", "fund", "funds", "bank", "banks", "lender", "insurer", "chips",
        "output", "production", "exports", "imports", "subsidy", "stimulus"
    )

    /**
     * The grammar of an advice column. Fatal on its own - see rule 1 in the file header.
     *
     * Note "help!" is deliberately NOT here. It normalises to the bare word "help", which
     * matched "Berkshire Hathaway... Here's the strategy that helped" and threw away a real
     * headline. A pattern that survives normalisation as an ordinary English word is not a
     * pattern.
     */
    private val SHAPE = listOf(
        "my wife", "my husband", "my partner", "my mom", "my mother", "my dad", "my father",
        "my son", "my daughter", "my sister", "my brother", "my in-laws", "my ex-",
        "my boyfriend", "my girlfriend", "my neighbor", "my landlord", "my boss", "my friend",
        "my kids", "my children", "my grandchildren", "my fiance", "my stepson",
        "my stepdaughter", "my aunt", "my uncle", "my niece", "my nephew", "my money",
        "my income", "my savings", "my reality",
        "should i", "should we", "can i", "can we", "do i", "am i", "must i", "dear",
        "here's how to", "heres how to", "how to", "how i", "how we", "how these", "how do i",
        "what should i", "what should we", "like me", "for people like", "if you buy",
        "if you invested", "if you'd invested", "here's what i", "i predict",
        "your home", "your house", "your money", "your paycheck", "your taxes", "your 401",
        "your ira", "your retirement", "your family", "your kids", "your credit",
        "your wallet", "your savings",
        "i'm", "i am", "we're in our", "i thought i", "i taught myself", "i inherited",
        "i retired"
    )

    /**
     * Subjects that belong to somebody's private life rather than to a market. Overridable by
     * [STRONG] or a ticker - rule 2 in the file header, and the reason a real story about a
     * health insurer's Medicare business is not thrown away with the retirement columns.
     */
    private val TOPIC = listOf(
        "social security", "medicare", "medicaid", "nursing home", "assisted living",
        "estate plan", "prenup", "divorce", "inheritance", "student loan", "credit card debt",
        "food stamps", "tipping", "dating", "wedding", "vacation", "diet", "weight loss",
        "plastic surgery", "antiaging", "anti-aging", "recipe", "obituary", "horoscope",
        "job seekers", "job seeker", "scammers", "scamming", "romance", "parenting",
        "babysit", "alzheimer", "moneyist", "etiquette", "befriend",
        // Both numbers of each: the matcher is boundary-exact by design, so "grandkid" does
        // not find "Grandkids" - which let a retirement column straight through.
        "grandkid", "grandkids", "grandchild", "grandchildren", "grandparent", "grandparents",
        "retiree", "retirees", "nest egg", "401(k)", "401k", "roth ira",
        "side hustle", "a raise", "job switch", "older workers", "net worth", "billionaire",
        "personal finance", "frugal", "budgeting", "best deals", "labor day sales"
    )

    /** Unmistakably about securities. Only used to overrule [TOPIC]. */
    private val STRONG = listOf(
        "stock", "stocks", "share", "shares", "earnings", "guidance", "acquisition",
        "acquire", "acquires", "merger", "ipo", "upgraded", "downgraded", "price target",
        "dividend", "buyback", "bankruptcy", "nasdaq", "s&p 500", "dow", "fed",
        "federal reserve", "fomc", "rate hike", "rate cut", "revenue", "quarterly",
        "analyst", "analysts", "etf", "selloff", "rally"
    )

    /**
     * An explicit ticker marker: `(NVDA)`, `NASDAQ: NVDA`, `$NVDA`.
     *
     * Two or more letters inside the bare parentheses form on purpose. `(A)` and `(I)` are
     * ordinary English punctuation; a one-letter ticker is handled by [Relevance], which knows
     * which symbols the user actually follows and can afford to be looser.
     */
    private val TICKER = Regex(
        "\\((?:NASDAQ|NYSE|NYSEAMERICAN|AMEX|OTC|TSX|LON|ASX):\\s*[A-Z.\\-]{1,6}\\)" +
            "|\\([A-Z]{2,5}\\)" +
            "|\\$[A-Z]{1,5}\\b" +
            "|\\b(?:NASDAQ|NYSE):\\s?[A-Z.\\-]{1,6}\\b"
    )

    /**
     * Everything that is not a letter, a digit, `$` or `&` becomes a space.
     *
     * `$` and `&` survive because they carry meaning here - "$NVDA" and "S&P 500". Everything
     * else, apostrophes and full stops included, goes: "investors." must match "investors",
     * and "I'm" must match the pattern "i'm". Both of those were live bugs before the
     * normaliser was written this way.
     */
    private val CLEAN = Regex("[^a-z0-9$&]+")

    private fun norm(s: String): String =
        " " + CLEAN.replace(s.lowercase(), " ").trim() + " "

    /** Phrases are normalised the same way, once, so a match is a token-boundary match. */
    private fun prep(words: List<String>): List<String> =
        words.map { " " + CLEAN.replace(it.lowercase(), " ").trim() + " " }

    private val P = prep(POS)
    private val NS = prep(SHAPE)
    private val NT = prep(TOPIC)
    private val ST = prep(STRONG)

    private fun any(hay: String, needles: List<String>): Boolean = needles.any { hay.contains(it) }

    /**
     * @param strict require market vocabulary, not just the absence of an advice column.
     *
     * FALSE FOR A FINANCE DESK. Yahoo Finance's newsroom, Seeking Alpha's market currents,
     * Investing.com's news wire and a Google News query that already says "earnings" publish
     * market copy by construction: everything they carry is about a company or a market, so
     * demanding the word "stock" as well only throws away real stories - it rejected
     * "Hon Hai, a key partner for Nvidia, records 52% growth" from Seeking Alpha's own market
     * wire. Those sources are filtered for advice columns and nothing more.
     *
     * TRUE FOR A BROAD SOURCE - MarketWatch's bulletin, a general Google News query - where
     * "College Football's Newest Battleground" is a live possibility.
     *
     * Note this is the opposite call to the one [News.cascade] makes for PER-SYMBOL feeds, and
     * the difference is real rather than an inconsistency. There the question is "is this about
     * NVIDIA?", and a provider-scoped feed genuinely does carry other companies' stories. Here
     * the question is "is this about markets at all?", which a finance desk answers by existing.
     */
    fun isMarketNews(title: String, summary: String = "", strict: Boolean = true): Boolean {
        val raw = (title + " " + summary).trim()
        if (raw.isEmpty()) return false
        val hay = norm(raw)
        if (any(hay, NS)) return false
        val ticker = TICKER.containsMatchIn(raw)
        if (any(hay, NT) && !ticker && !any(hay, ST)) return false
        if (!strict) return true
        return ticker || any(hay, P)
    }
}
