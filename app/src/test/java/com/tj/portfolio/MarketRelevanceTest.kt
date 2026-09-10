package com.tj.portfolio

import com.tj.portfolio.net.MarketRelevance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The market-news filter, against headlines pulled live on 5 September 2026 from every feed
 * the app considered using.
 *
 * THE FIRST BLOCK IS THE BUG REPORT. Those five are, verbatim, what TJ's Live feed was showing
 * him when he said the news "shows a lot of news unrelated to the stock market" - four of them
 * are visible in the screenshot he sent. If a future change to the word lists lets any of them
 * back in, this test is the thing that says so.
 *
 * The rest are the honest other half: real market headlines from the same pull, including the
 * awkward ones that sit close to the line. A filter is only as good as the stories it does not
 * throw away, and several rules in [MarketRelevance] exist solely because an early version of
 * it rejected one of these.
 */
class MarketRelevanceTest {

    private fun keptBroad(t: String) = MarketRelevance.isMarketNews(t, "", strict = true)
    private fun keptDesk(t: String) = MarketRelevance.isMarketNews(t, "", strict = false)

    // ------------------------------------------------- the reported problem

    @Test fun theHeadlinesTjComplainedAboutAreRejected() {
        val reported = listOf(
            "My wife and I are in our 70s. Should we move to California and take on a " +
                "bigger mortgage to be near our kids?",
            "How these Gen Z workers managed to buy homes in their early 20s",
            "Vanguard’s S&P 500 index fund changed how we invest — but there may be a " +
                "smarter way to get a piece of the market",
            "‘Poverty doesn’t have to be my reality’: I thought I’d have to rely on Social " +
                "Security. Then I taught myself how to invest.",
            "Fake job recruiters are getting smarter about scamming job seekers. AI is " +
                "making it even worse."
        )
        reported.forEach { assertFalse(it, keptBroad(it)) }
        // And rejected even from a finance desk: advice-column grammar is fatal at both
        // tiers, because MarketWatch publishes these under its own markets brand too.
        reported.forEach { assertFalse(it, keptDesk(it)) }
    }

    @Test fun moreOfTheSameFrontPage() {
        listOf(
            "Plastic surgery is booming with boomers. Here’s how to cash in on the " +
                "antiaging craze.",
            "Why does almost nobody want to befriend older people like me — except scammers?",
            "Medicare Advantage patients who get diagnosed with Alzheimer’s or other " +
                "serious diseases are ditching their plans",
            "Will a data center hurt your home’s value? Research says no. Sellers disagree.",
            "He Took a Military Pension Lump Sum. His Monthly Retired Pay Stayed Lower " +
                "Until Social Security’s Full Retirement Age",
            "Everyone Retires Near the Grandkids. Nobody Plans for When the Kids Move Again",
            "Scott Galloway warns chasing a raise with every job switch could backfire",
            "If You Buy Amazon With $10,000 at a 10% Discount From Its High, Here's What I " +
                "Predict It Could Be Worth in 5 Years"
        ).forEach { assertFalse(it, keptDesk(it)) }
    }

    @Test fun generalNewsThatIsNotAboutMarkets() {
        listOf(
            "American Airlines passenger duct-taped to seat after alleged racist, " +
                "homophobic midair outburst",
            "Thousands of OpenAI Agents Quietly Turned an Abandoned Wiki Into Their " +
                "Coordination Channel",
            "College Football’s Newest Battleground: Players Returning From the Pros",
            "Colleges Admit Students Who Haven’t Applied",
            "The Porsche Cayenne Turbo EV is Quiet, Composed and Terrifyingly Fast",
            "Humanoid robots could upend life as we know it — if only they had better brains",
            "Labor Day sales are here: The best deals from Amazon, Apple, Hoka and more"
        ).forEach { assertFalse(it, keptBroad(it)) }
    }

    // ------------------------------------------------------- must be kept

    @Test fun realMarketHeadlinesSurviveTheBroadFilter() {
        listOf(
            "Dow, S&P 500 and Nasdaq close lower as jobs data raise chances of Fed rate hike",
            "U.S. stocks down in final hour as jobs data raise chances of interest-rate hike",
            "Wall Street ends lower as solid jobs data fuels hawkish Fed bets",
            "Anthropic IPO launch shifts toward mid-October",
            "Bloom Energy was just named to the S&P 500. These other stocks are also joining.",
            "Micron is doubling down on AI memory chips. It could pay off big for investors.",
            "Norway's $2 trillion sovereign fund proposes deep cuts to US Treasury holdings",
            "Citi pushes Fed rate-cut forecast to June 2027 after resilient jobs market",
            "Novartis Says Closely Watched Cholesterol Study Failed to Meet Goal",
            "Are mortgage rates heading back above 7%? Here's what experts think.",
            "Federal Reserve Vice Chair Michelle Bowman on modernizing financial regulation",
            "Oil climbs on reports of Iran ballistic missile launches",
            "U.S. labor market booms, with 162,000 jobs added in August"
        ).forEach { assertTrue(it, keptBroad(it)) }
    }

    /**
     * WORD BOUNDARIES. "jobs dat[a raise] chances" contains the personal-finance phrase
     * "a raise" as a plain substring. Matching without boundaries threw away one of the most
     * market-relevant headlines of the day, twice over, and that is why everything is
     * normalised to space-delimited tokens.
     */
    @Test fun aSubstringIsNotAMatch() {
        assertTrue(keptBroad("Dow closes lower as jobs data raise chances of a Fed rate hike"))
        assertFalse(keptBroad("Chasing a raise with every job switch could backfire"))
        // "help" survived normalisation as an ordinary word and rejected this one.
        assertTrue(
            keptBroad(
                "Berkshire Hathaway Outperformed the S&P 500 Over 60 Years Under Buffett. " +
                    "Here's the strategy that helped."
            )
        )
    }

    /**
     * A finance desk's copy is market copy by construction, so it is filtered for advice
     * columns and nothing more. Demanding the word "stock" as well rejected all of these -
     * every one from Seeking Alpha's or Yahoo Finance's own market wire.
     */
    @Test fun financeDeskCopyIsNotHeldToTheVocabularyTest() {
        listOf(
            "Hon Hai, a key partner for Nvidia, records 52% growth in August amid AI boom",
            "Notable tech headlines for the week: Nvidia, Dell, Broadcom in focus",
            "Apple's 9-9 event 'most consequential iPhone launch' in decade as Ternus takes over",
            "Howmet’s SpaceX threat looks more like a buying opportunity: Bernstein",
            "U.S. may struggle to build power fast enough to meet rising demand, Barclays says",
            "Palantir Jumped 7.7% on an Expanded PwC Alliance as Globant Launched an AI Pod"
        ).forEach { assertTrue(it, keptDesk(it)) }
    }

    /**
     * A private-life TOPIC is overruled by an unmistakable market signal. Without this, a rule
     * written for retirement columns would throw away real news about a health insurer.
     */
    @Test fun aPersonalTopicIsOverruledByARealMarketSignal() {
        assertFalse(keptBroad("Medicare open enrollment starts today. Here is what changed."))
        assertTrue(keptBroad("UnitedHealth shares fall on Medicare Advantage rate cut"))
        assertTrue(keptBroad("Humana (HUM) downgraded on Medicare Advantage margin pressure"))
        assertTrue(
            keptBroad(
                "Jensen Huang’s Net Worth Nears $200 Billion After Nvidia Says It Will " +
                    "Acquire Hugging Face"
            )
        )
    }

    @Test fun anExplicitTickerIsAlwaysEnough() {
        assertTrue(keptBroad("Everything you need to know about (NVDA) this week"))
        assertTrue(keptBroad("A quiet week for NASDAQ: AAPL"))
        assertTrue(keptBroad("\$TSLA is the one to watch"))
        // ...but ordinary parenthetical punctuation is not a ticker.
        assertFalse(keptBroad("The reason (A) nobody talks about it"))
    }

    @Test fun blankInputIsRejectedRatherThanThrowing() {
        assertFalse(MarketRelevance.isMarketNews("", ""))
        assertFalse(MarketRelevance.isMarketNews("   ", "   "))
        assertFalse(MarketRelevance.isMarketNews("", "", strict = false))
    }

    /** The summary is searched too - a bare headline often carries none of the signal. */
    @Test fun theSummaryCounts() {
        assertFalse(keptBroad("Quiet Friday for the group"))
        assertTrue(
            MarketRelevance.isMarketNews(
                "Quiet Friday for the group",
                "Shares closed little changed after the earnings report.",
                strict = true
            )
        )
    }
}
