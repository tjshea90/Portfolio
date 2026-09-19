package com.tj.portfolio.ui

import com.tj.portfolio.data.Consensus
import com.tj.portfolio.data.Fundamentals
import com.tj.portfolio.data.MetricCatalog
import com.tj.portfolio.data.MetricUnit
import com.tj.portfolio.util.Fmt
import kotlin.math.abs

/**
 * THE "i" BUTTON: every number on a stock's page, explained to somebody who has never
 * bought a share.
 *
 * The brief was specific, and it is worth writing down because it shapes every entry below.
 * For each figure the app has to say, in a beginner's words:
 *   - what the number actually means;
 *   - which values are good and which are bad, with the ranges a real stock falls in;
 *   - how it tends to move the share price in the SHORT term and in the LONG term;
 *   - and then a read on THIS stock's current number, not a generic one.
 *
 * The last of those is the part that makes it useful rather than a glossary, and it is also
 * the part with teeth: a threshold written down here is a claim, so each one is a range that
 * a reasonable analyst would recognise rather than a number invented to make the code tidy.
 * Where a figure genuinely cannot be judged without knowing the industry - a P/E, a margin,
 * a debt load - the text says so instead of pretending. A utility carrying twice its equity
 * in debt is ordinary; a software company doing the same is in trouble.
 *
 * WHAT THIS IS NOT. It is education, not advice - and the wording avoids "should buy" and
 * "should sell" everywhere on purpose. The footer of every sheet says as much.
 *
 * COVERAGE IS ENFORCED. [MetricCatalog] is the single list of metrics; a unit test asserts
 * that every key in it reaches a real entry here rather than the fallback, so a metric can
 * never be added to the Stats tab with an empty "i".
 */

enum class Verdict { GOOD, BAD, NEUTRAL, MIXED, UNKNOWN }

data class Explanation(
    val title: String,
    /** What the number is, in plain words. */
    val plain: String,
    /** Which values count as high or low, with real-world ranges. */
    val scale: String,
    /** How it tends to move the price over days and weeks. */
    val shortTerm: String,
    /** How it tends to move the price over years. */
    val longTerm: String,
    /** This stock's actual number, read back. Blank when there is no value. */
    val read: String,
    val verdict: Verdict
)

object Explain {

    /** Everything a reader needs, gathered so a threshold can look at more than one number. */
    class Ctx(
        val v: Double,
        val f: Fundamentals,
        /** Live share price, or 0 when the quote has not arrived. */
        val price: Double
    ) {
        fun other(key: String): Double? = f.value(key)
    }

    private class Body(
        val plain: String,
        val scale: String,
        val shortTerm: String,
        val longTerm: String,
        val read: (Ctx) -> Pair<String, Verdict>
    )

    // ------------------------------------------------------------------ api

    fun of(key: String, f: Fundamentals, price: Double = 0.0): Explanation {
        val def = MetricCatalog.byKey[key]
        val body = body(key)
        val v = f.value(key)
        val (read, verdict) =
            if (v == null) NO_VALUE to Verdict.UNKNOWN else body.read(Ctx(v, f, price))
        return Explanation(
            title = def?.label ?: prettyKey(key),
            plain = body.plain,
            scale = body.scale,
            shortTerm = body.shortTerm,
            longTerm = body.longTerm,
            read = read,
            verdict = verdict
        )
    }

    /** True when [key] has real prose rather than the generic fallback. Used by the test. */
    fun covers(key: String): Boolean = body(key) !== FALLBACK

    private const val NO_VALUE =
        "No value is reported for this stock right now. That is usually because the " +
            "company does not have the thing being measured - a company that pays no " +
            "dividend has no payout ratio, one that is losing money has no P/E - or " +
            "because the data provider has not filed it yet. It is not a zero."

    /** Compiled once rather than per call - the same fix applied across `net/` in v6.3. */
    private val CAMEL_BOUNDARY = Regex("([a-z])([A-Z])")

    private fun prettyKey(key: String) =
        key.replace(CAMEL_BOUNDARY, "$1 $2").replaceFirstChar { it.uppercase() }

    // ------------------------------------------------------- formatting help

    /** 0.2762 -> "27.6%". */
    private fun pf(v: Double): String = String.format(java.util.Locale.US, "%.1f%%", v * 100.0)

    /** 78.445 -> "78.4%" (the value is already a percentage). */
    private fun pp(v: Double): String = String.format(java.util.Locale.US, "%.1f%%", v)

    private fun x(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    private fun money(v: Double): String =
        if (abs(v) >= 1_000_000) "$" + Fmt.compact(v) else Fmt.usd(v)

    /**
     * Whole calendar days from now to [ms], floor-rounded so a timestamp in the PAST reads
     * as negative (`-1`, `-2`, ...) rather than `0`.
     *
     * PLAIN `/` ON A LONG TRUNCATES TOWARD ZERO, NOT FLOOR. A date 17 hours in the past
     * divides a small negative numerator and lands on `0`, so every "is it still ahead of
     * us?" test written as `d >= 0` stayed true for the whole ~24 hours AFTER the date had
     * passed - telling the reader an ex-dividend cut-off they have already missed is
     * "essentially now", and that a dividend paid yesterday is still "due". `Math.floorDiv`
     * rounds toward negative infinity instead, so the boundary lands on the correct side.
     *
     * This is the same trap, and the same fix, as [com.tj.portfolio.net.Research.daysUntilEarnings]
     * - which documents it at length. Kept in step with that one deliberately.
     */
    private fun daysFromNow(ms: Double): Long =
        Math.floorDiv(ms.toLong() - System.currentTimeMillis(), 86_400_000L)

    /**
     * Pick the first band whose upper bound the value is under. Bands are given
     * low-to-high, and the last entry is the catch-all.
     */
    private fun band(v: Double, vararg bands: Triple<Double, String, Verdict>): Pair<String, Verdict> {
        for ((limit, text, verdict) in bands) if (v < limit) return text to verdict
        val last = bands.last()
        return last.second to last.third
    }

    // ==================================================================== body

    private val FALLBACK = Body(
        plain = "One of the standard figures other stock apps publish for a company.",
        scale = "Compare it against other companies in the same industry rather than " +
            "against the market as a whole.",
        shortTerm = "On its own it rarely moves a share price in a single session.",
        longTerm = "Numbers like this matter over years, as they either improve or do not.",
        read = { c -> "This stock's value is ${x(c.v)}." to Verdict.NEUTRAL }
    )

    private fun body(key: String): Body = when (key) {

        // ============================================================ VALUATION

        "marketCap" -> Body(
            plain = "What the whole company costs at today's share price: the share price " +
                "multiplied by every share that exists. It is the price tag on the entire " +
                "business, not on one share - a $5 stock is not 'cheaper' than a $500 one.",
            scale = "Roughly: over $200B is mega-cap (Apple, Microsoft), $10B-$200B large-cap, " +
                "$2B-$10B mid-cap, $300M-$2B small-cap, and below that micro-cap. Bigger " +
                "companies are generally steadier and slower; smaller ones swing much harder " +
                "in both directions.",
            shortTerm = "It moves exactly in step with the share price, so it tells you " +
                "nothing new day to day. It does decide which index funds must own the stock, " +
                "and a company crossing into a major index often gets a burst of forced buying.",
            longTerm = "Size sets the ceiling. A $4 trillion company doubling means adding " +
                "another $4 trillion of value, which is far harder than a $2B company doubling. " +
                "That is why small companies offer more upside and more ways to go to zero.",
            read = { c ->
                band(
                    c.v,
                    Triple(3e8, "At ${money(c.v)} this is a micro-cap. Expect big percentage " +
                        "swings, thin trading, and a real chance the company never becomes " +
                        "profitable.", Verdict.MIXED),
                    Triple(2e9, "At ${money(c.v)} this is a small-cap. Higher growth potential " +
                        "than a large company, and higher risk of a permanent loss.", Verdict.MIXED),
                    Triple(1e10, "At ${money(c.v)} this is a mid-cap - established enough to " +
                        "have a real business, small enough to grow quickly.", Verdict.NEUTRAL),
                    Triple(2e11, "At ${money(c.v)} this is a large-cap: an established company " +
                        "that most funds can own. Steadier, and slower to double.", Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "At ${money(c.v)} this is a mega-cap, one of the " +
                        "largest companies in the world. Very hard to move quickly in either " +
                        "direction, and owned by almost every index fund.", Verdict.NEUTRAL)
                )
            }
        )

        "enterpriseValue" -> Body(
            plain = "What it would cost to buy the entire company outright: the market cap, " +
                "plus the debt you would inherit, minus the cash sitting in its bank account " +
                "(which you would get to keep).",
            scale = "Compare it to market cap. Much HIGHER than market cap means the company " +
                "carries a lot of debt. LOWER than market cap means it is sitting on more cash " +
                "than debt, which is a strong position.",
            shortTerm = "Barely moves on its own. It matters when a takeover is rumoured, " +
                "because this is closer to the number an acquirer actually pays.",
            longTerm = "It is the honest denominator for valuation. Two companies can look " +
                "equally priced on market cap while one is debt-free and the other is " +
                "borrowed to the hilt - enterprise value is where that shows up.",
            read = { c ->
                val cap = c.other("marketCap")
                if (cap == null || cap <= 0) {
                    "Enterprise value is ${money(c.v)}." to Verdict.NEUTRAL
                } else {
                    val diff = (c.v - cap) / cap
                    when {
                        diff < -0.05 -> "Enterprise value ${money(c.v)} is BELOW the market cap " +
                            "of ${money(cap)}, so the company holds more cash than debt. That is " +
                            "a comfortable balance sheet." to Verdict.GOOD
                        diff > 0.25 -> "Enterprise value ${money(c.v)} is well above the market " +
                            "cap of ${money(cap)}, so a large amount of debt comes with the " +
                            "business. Check the debt/equity figure before reading the valuation " +
                            "ratios as cheap." to Verdict.MIXED
                        else -> "Enterprise value ${money(c.v)} is close to the market cap of " +
                            "${money(cap)}, so debt and cash roughly cancel out." to Verdict.NEUTRAL
                    }
                }
            }
        )

        "peTrailing" -> Body(
            plain = "Price to earnings: how many dollars you pay for each dollar of profit " +
                "the company actually made over the last twelve months. A P/E of 20 means you " +
                "pay $20 for every $1 of annual profit - or, put the other way, the company " +
                "would take 20 years of today's profits to earn back what you paid.",
            scale = "The long-run average for the US market is somewhere around 15-20. Under " +
                "15 is usually considered cheap, 15-25 ordinary, 25-40 expensive, and over 40 " +
                "means the market is paying for growth that has not happened yet. A company " +
                "losing money has NO P/E at all. Crucially, high or low only means something " +
                "against the same industry: banks and carmakers normally trade in the teens, " +
                "software companies in the 30s and 40s.",
            shortTerm = "A high P/E makes a stock fragile around news. When a company is priced " +
                "for perfection, a merely-good earnings report can still drop the price 10% in " +
                "a day, because the price already assumed the good news. Low-P/E stocks tend to " +
                "react less violently.",
            longTerm = "Over years, the P/E is the market's rating of the business, and ratings " +
                "drift back toward the industry norm. If earnings grow but the P/E shrinks, the " +
                "share price can go nowhere for years - this is how a great company can be a bad " +
                "investment at the wrong price. If earnings grow AND the rating holds, you get " +
                "both effects at once, which is where most large gains come from.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "A negative P/E means the company lost money over the last " +
                        "year, so there are no earnings to price. Judge it on revenue growth " +
                        "and cash instead.", Verdict.BAD),
                    Triple(10.0, "At ${x(c.v)} this is cheap on earnings. That is either a " +
                        "bargain or a warning that the market expects profits to fall - check " +
                        "whether revenue and earnings are still growing before calling it " +
                        "cheap.", Verdict.MIXED),
                    Triple(20.0, "At ${x(c.v)} this is around the historical market average. " +
                        "The price is not making a strong bet either way.", Verdict.NEUTRAL),
                    Triple(30.0, "At ${x(c.v)} this is above the market average - investors are " +
                        "paying up for expected growth. Fine if the growth arrives, painful " +
                        "if it stalls.", Verdict.NEUTRAL),
                    Triple(50.0, "At ${x(c.v)} this is expensive. The price assumes profits " +
                        "grow substantially from here; a missed quarter tends to hurt a lot " +
                        "more than it would on a cheaper stock.", Verdict.MIXED),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} this is very expensive on current " +
                        "earnings. Either profits are temporarily depressed and expected to " +
                        "recover, or the market is pricing in years of rapid growth. Both " +
                        "leave little room for disappointment.", Verdict.MIXED)
                )
            }
        )

        "peForward" -> Body(
            plain = "The same price-to-earnings idea, but divided by what analysts EXPECT the " +
                "company to earn over the next twelve months rather than what it already " +
                "earned. It is a forecast, so it is only as good as the forecast.",
            scale = "Read it next to the trailing P/E. A forward P/E clearly LOWER than the " +
                "trailing one means profits are expected to grow. HIGHER means profits are " +
                "expected to shrink. The same 15/25/40 rough bands apply.",
            shortTerm = "This is the number most professional investors quote, so it is what " +
                "the price is usually anchored to. When analysts cut their estimates, the " +
                "forward P/E silently rises and the stock often falls to bring it back.",
            longTerm = "Estimates more than a year out are frequently wrong, and are usually " +
                "too optimistic. Treat a low forward P/E built on aggressive growth forecasts " +
                "with more suspicion than a low trailing P/E built on money already earned.",
            read = { c ->
                val t = c.other("peTrailing")
                val cmp = when {
                    t == null || t <= 0 -> ""
                    c.v < t * 0.9 -> " That is below the trailing P/E of ${x(t)}, so analysts " +
                        "expect profits to GROW over the next year."
                    c.v > t * 1.1 -> " That is above the trailing P/E of ${x(t)}, so analysts " +
                        "expect profits to FALL over the next year."
                    else -> " That is close to the trailing P/E of ${x(t)}, so profits are " +
                        "expected to be roughly flat."
                }
                val base = band(
                    c.v,
                    Triple(0.0, "A negative forward P/E means analysts expect a loss next year.",
                        Verdict.BAD),
                    Triple(15.0, "At ${x(c.v)} next year's expected earnings are priced cheaply.",
                        Verdict.GOOD),
                    Triple(25.0, "At ${x(c.v)} next year's earnings are priced around the " +
                        "market average.", Verdict.NEUTRAL),
                    Triple(40.0, "At ${x(c.v)} the price is ahead of next year's expected " +
                        "earnings - growth is being paid for in advance.", Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} the price is far ahead of next " +
                        "year's expected earnings.", Verdict.MIXED)
                )
                (base.first + cmp) to base.second
            }
        )

        "pegRatio" -> Body(
            plain = "The P/E ratio divided by the company's expected growth rate. It answers " +
                "the obvious objection to P/E - 'of course the fast grower is more expensive' - " +
                "by asking whether you are paying a fair price FOR that growth.",
            scale = "The rule of thumb, from Peter Lynch, is that 1.0 is fair value: a company " +
                "growing 20% a year deserves a P/E around 20. Below 1.0 suggests growth is on " +
                "sale; above 2.0 suggests you are paying twice over for it. The weakness is " +
                "that the growth number is an estimate, so a low PEG built on a wild forecast " +
                "means nothing.",
            shortTerm = "Almost no short-term effect. Nobody trades a single session on a " +
                "PEG ratio, and it only moves when the growth forecast is revised.",
            longTerm = "It is one of the better single filters for growth investing, because " +
                "it punishes both the overpriced grower and the cheap company going nowhere. " +
                "Use it as a shortlist tool, never as a decision on its own.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "A negative PEG usually means the growth estimate is negative - " +
                        "earnings are expected to shrink. The ratio is not meaningful here.",
                        Verdict.BAD),
                    Triple(1.0, "At ${x(c.v)} the growth is priced cheaply relative to the " +
                        "P/E - the classic 'growth at a reasonable price' zone, IF the growth " +
                        "forecast holds.", Verdict.GOOD),
                    Triple(2.0, "At ${x(c.v)} the price is fair-to-full for the expected " +
                        "growth.", Verdict.NEUTRAL),
                    Triple(3.0, "At ${x(c.v)} you are paying well above the growth rate. The " +
                        "market must be expecting something the estimates do not capture.",
                        Verdict.MIXED),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} the price is a long way ahead of " +
                        "the expected growth rate.", Verdict.MIXED)
                )
            }
        )

        "priceToSales" -> Body(
            plain = "How many dollars you pay for each dollar of the company's REVENUE - what " +
                "it sold, before any costs. It is the fallback valuation for companies that " +
                "do not yet make a profit, because revenue always exists even when earnings " +
                "do not.",
            scale = "Under 1 is cheap, 1-3 is normal for most businesses, 3-10 is rich, and " +
                "over 10 is what the market pays for fast-growing software with very high " +
                "margins. It is meaningless across industries: a supermarket at 0.5 and a " +
                "software company at 12 can both be fairly priced, because one keeps 2 cents " +
                "of every sales dollar and the other keeps 30.",
            shortTerm = "Used mainly for unprofitable companies, and those are the ones whose " +
                "prices move most on sentiment. When the market turns against speculative " +
                "stocks, high price-to-sales names fall hardest and fastest.",
            longTerm = "Revenue eventually has to turn into profit. A high price-to-sales " +
                "ratio is a bet that today's sales will become tomorrow's earnings at a good " +
                "margin - a bet that is often wrong.",
            read = { c ->
                val m = c.other("profitMargins")
                val note = if (m != null) " Its net margin is ${pf(m)}, which is what those " +
                    "sales are actually worth to shareholders." else ""
                val base = band(
                    c.v,
                    Triple(1.0, "At ${x(c.v)} you are paying less than a dollar for each dollar " +
                        "of sales - cheap on this measure.", Verdict.GOOD),
                    Triple(3.0, "At ${x(c.v)} sales are priced in the normal range for most " +
                        "industries.", Verdict.NEUTRAL),
                    Triple(10.0, "At ${x(c.v)} sales are priced richly. This is usual for " +
                        "high-margin software and unusual for anything that ships physical " +
                        "goods.", Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} sales are priced very richly. The " +
                        "market is paying for a level of future profitability the company has " +
                        "not demonstrated.", Verdict.MIXED)
                )
                (base.first + note) to base.second
            }
        )

        "priceToBook" -> Body(
            plain = "The share price divided by the company's book value per share - what " +
                "would theoretically be left for shareholders if the company sold everything " +
                "it owns and paid off everything it owes.",
            scale = "Below 1 means the market values the company at less than its own " +
                "accounting net worth. Between 1 and 3 is normal. Very high numbers are " +
                "routine for modern companies whose real assets - brands, software, people - " +
                "barely appear on a balance sheet at all. It is genuinely useful for banks " +
                "and insurers and close to useless for a software company.",
            shortTerm = "Little day-to-day effect, but a bank trading below book value is a " +
                "signal the market doubts its loan book, and those stories move fast.",
            longTerm = "Buying below book value is the oldest form of value investing. It " +
                "works when the assets are real and saleable, and it is a trap when they are " +
                "obsolete factories or loans that will not be repaid.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "A negative price-to-book means the company owes more than it " +
                        "owns on paper. That is a serious warning outside of a few special " +
                        "cases like heavy buyback programmes.", Verdict.BAD),
                    Triple(1.0, "At ${x(c.v)} the market values the company at less than its " +
                        "accounting net worth. Sometimes a genuine bargain, often a sign the " +
                        "market doubts the assets are worth what the books say.", Verdict.MIXED),
                    Triple(3.0, "At ${x(c.v)} this is in the normal range.", Verdict.NEUTRAL),
                    Triple(10.0, "At ${x(c.v)} the price is well above the company's book " +
                        "value, which is typical when the real value is in brands, software " +
                        "or people rather than physical assets.", Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} book value tells you almost nothing " +
                        "about this company - the value is entirely in things accounting does " +
                        "not record. Use P/E and cash flow instead.", Verdict.NEUTRAL)
                )
            }
        )

        "evToEbitda" -> Body(
            plain = "The cost of buying the whole company (including its debt) divided by its " +
                "rough operating cash profit before interest, tax, depreciation and " +
                "amortisation. It is the measure professionals use to compare businesses with " +
                "very different debt loads and tax situations.",
            scale = "Under 10 is generally considered cheap, 10-15 fair, over 15 expensive. It " +
                "is more comparable across companies than P/E because it strips out financing " +
                "and accounting choices - which is also its weakness, since interest and " +
                "capital spending are real costs a shareholder ultimately pays.",
            shortTerm = "This is the multiple takeover bids are usually quoted at, so it " +
                "matters most when a company is rumoured to be for sale.",
            longTerm = "A durable low EV/EBITDA in a stable industry is one of the more " +
                "reliable value signals. A low one in a declining industry is just the market " +
                "correctly pricing decline.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "A negative figure means the company is not generating " +
                        "operating profit. The multiple is not meaningful.", Verdict.BAD),
                    Triple(8.0, "At ${x(c.v)} this is cheap on operating profit - the range " +
                        "where private-equity buyers start paying attention.", Verdict.GOOD),
                    Triple(12.0, "At ${x(c.v)} this is a fair, unremarkable multiple.",
                        Verdict.NEUTRAL),
                    Triple(20.0, "At ${x(c.v)} this is on the expensive side; growth is being " +
                        "paid for.", Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} this is a very high multiple of " +
                        "operating profit.", Verdict.MIXED)
                )
            }
        )

        "evToRevenue" -> Body(
            plain = "The cost of the whole company, debt included, per dollar of annual " +
                "revenue. The same idea as price-to-sales, but fairer, because it counts the " +
                "debt a buyer would have to take on.",
            scale = "Under 2 is modest, 2-6 normal-to-rich, over 10 is reserved for very " +
                "high-margin, fast-growing businesses. As with price-to-sales, only compare " +
                "within an industry.",
            shortTerm = "Little short-term effect on its own.",
            longTerm = "It caps how much profit the company could ever justify. A company " +
                "valued at 20 times revenue has to eventually keep an extraordinary share of " +
                "every sale as profit for the price to make sense.",
            read = { c ->
                band(
                    c.v,
                    Triple(2.0, "At ${x(c.v)} the whole business, debt included, costs less " +
                        "than two years of revenue - modest.", Verdict.GOOD),
                    Triple(6.0, "At ${x(c.v)} this is in the ordinary range.", Verdict.NEUTRAL),
                    Triple(12.0, "At ${x(c.v)} revenue is being valued richly.", Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} revenue is being valued very " +
                        "richly - the price assumes exceptional future margins.", Verdict.MIXED)
                )
            }
        )

        "bookValue" -> Body(
            plain = "The company's accounting net worth divided by the number of shares: " +
                "everything it owns minus everything it owes, per share. It is what the " +
                "accountants say one share is backed by.",
            scale = "There is no good or bad level in isolation - it only means something " +
                "next to the share price, which is the price-to-book ratio. Growing book " +
                "value per share year after year is the healthy pattern; shrinking book value " +
                "means the company is losing money or paying out more than it earns.",
            shortTerm = "No short-term effect at all - it changes once a quarter.",
            longTerm = "For banks, insurers and asset-heavy businesses, long-run share price " +
                "tends to track book value per share. For asset-light businesses it drifts " +
                "away from the price entirely and stops being informative.",
            read = { c ->
                val p = c.price
                if (p > 0) {
                    "Each share is backed by ${Fmt.price(c.v)} of accounting net worth, " +
                        "against a share price of ${Fmt.price(p)} - a price-to-book of " +
                        "${x(p / c.v)}." to Verdict.NEUTRAL
                } else {
                    "Each share is backed by ${Fmt.price(c.v)} of accounting net worth." to
                        Verdict.NEUTRAL
                }
            }
        )

        // ====================================================== EARNINGS / CASH

        "epsTrailing" -> Body(
            plain = "Earnings per share: the company's total profit over the last twelve " +
                "months divided by the number of shares. It is your slice of the profit, per " +
                "share you own - though you only receive it directly if the company pays a " +
                "dividend.",
            scale = "The level itself means nothing across companies (a $2 EPS on a $20 stock " +
                "is far better value than a $2 EPS on a $200 stock) - what matters is whether " +
                "it is positive, and whether it is rising. Share price divided by EPS is the " +
                "P/E ratio.",
            shortTerm = "This is the single most watched number in a quarterly earnings " +
                "report. Beating or missing the analyst estimate by a few cents routinely " +
                "moves a share price 5-10% within minutes of the release.",
            longTerm = "Over long periods share prices follow earnings per share more closely " +
                "than they follow anything else. Note the 'per share' part: a company that " +
                "grows profits while issuing lots of new shares may not grow EPS at all.",
            read = { c ->
                when {
                    c.v < 0 -> "At ${Fmt.price(c.v)} the company lost money over the last " +
                        "twelve months. That is normal for a young growth company and a " +
                        "serious problem for a mature one." to Verdict.BAD
                    c.v == 0.0 -> "Earnings came out at roughly break-even." to Verdict.MIXED
                    else -> {
                        val p = c.price
                        val pe = if (p > 0) " At ${Fmt.price(p)} a share that is a P/E of " +
                            "${x(p / c.v)}." else ""
                        ("The company earned ${Fmt.price(c.v)} of profit per share over the " +
                            "last year.$pe") to Verdict.GOOD
                    }
                }
            }
        )

        "epsForward" -> Body(
            plain = "What analysts expect the company to earn per share over the coming " +
                "twelve months. A forecast, not a fact.",
            scale = "Judge it against the trailing figure: higher means growth is expected, " +
                "lower means a decline is expected. The size of the gap is the size of the " +
                "expectation the company now has to meet.",
            shortTerm = "When analysts revise this figure up or down, the share price " +
                "typically moves the same way within days. Estimate revisions are one of the " +
                "few things with a genuinely reliable short-term price effect.",
            longTerm = "Forecasts a year out are wrong more often than not, and are biased " +
                "optimistic. Treat this as the market's current assumption rather than as " +
                "information about the company.",
            read = { c ->
                val t = c.other("epsTrailing")
                when {
                    t == null || t == 0.0 -> "Analysts expect ${Fmt.price(c.v)} of earnings " +
                        "per share over the next year." to Verdict.NEUTRAL
                    c.v > t -> "Analysts expect ${Fmt.price(c.v)} per share next year, up from " +
                        "${Fmt.price(t)} - growth of ${pf((c.v - t) / abs(t))} is already built " +
                        "into the price." to Verdict.GOOD
                    c.v < t -> "Analysts expect ${Fmt.price(c.v)} per share next year, DOWN " +
                        "from ${Fmt.price(t)}. The market is expecting profits to fall." to
                        Verdict.BAD
                    else -> "Analysts expect earnings to be flat at about ${Fmt.price(c.v)} " +
                        "per share." to Verdict.NEUTRAL
                }
            }
        )

        "revenue" -> Body(
            plain = "Total sales over the last twelve months - all the money customers paid " +
                "the company, before any costs at all. Often called the 'top line'.",
            scale = "There is no good or bad absolute level; what matters is the direction " +
                "and the speed. Growing revenue with stable margins is the healthiest pattern " +
                "a company can show.",
            shortTerm = "Revenue misses hurt more than earnings misses for young companies, " +
                "because profits can be manufactured by cutting costs while sales cannot.",
            longTerm = "Everything else is downstream of this. A company can improve margins " +
                "for a few years, but sustained profit growth ultimately requires selling more.",
            read = { c ->
                val g = c.other("revenueGrowth")
                val gs = if (g != null) " It grew ${pf(g)} against the same period last year." else ""
                ("The company sold ${money(c.v)} over the last twelve months.$gs") to Verdict.NEUTRAL
            }
        )

        "netIncome" -> Body(
            plain = "The profit left over after absolutely everything - costs, interest, tax. " +
                "The 'bottom line'. Divided by the share count, this is earnings per share.",
            scale = "Positive and growing is what you want. Negative is normal for a company " +
                "investing heavily to grow, and dangerous for one that has been around a " +
                "while. Compare it to revenue to get the profit margin.",
            shortTerm = "The headline number in every quarterly report, and one of the main " +
                "triggers for a large single-day move.",
            longTerm = "Sustained profit is what eventually funds dividends, buybacks and " +
                "reinvestment. A company that never earns a profit is ultimately financed by " +
                "issuing shares, which dilutes the owners.",
            read = { c ->
                if (c.v < 0) {
                    "The company LOST ${money(abs(c.v))} over the last twelve months." to Verdict.BAD
                } else {
                    val r = c.other("revenue")
                    val m = if (r != null && r > 0) " That is ${pf(c.v / r)} of revenue kept " +
                        "as profit." else ""
                    "The company earned ${money(c.v)} of profit over the last year.$m" to Verdict.GOOD
                }
            }
        )

        "ebitda" -> Body(
            plain = "Earnings before interest, tax, depreciation and amortisation - a rough " +
                "measure of the cash the core business throws off, before the effects of how " +
                "it is financed and how its accountants treat old equipment.",
            scale = "Useful for comparing companies with different debt and tax positions. " +
                "Treat it with care: interest and equipment replacement are real costs, and a " +
                "company that only looks profitable on EBITDA may not be profitable at all.",
            shortTerm = "Rarely a single-session driver, but it is the number lending " +
                "covenants are written against, so a fall can trigger debt problems.",
            longTerm = "Used with enterprise value to give EV/EBITDA, one of the standard " +
                "long-run valuation measures, especially in capital-intensive industries.",
            read = { c ->
                val r = c.other("revenue")
                val m = if (r != null && r > 0) " That is an EBITDA margin of ${pf(c.v / r)}." else ""
                if (c.v < 0) "EBITDA is negative at ${money(c.v)} - the core operations are " +
                    "not generating cash." to Verdict.BAD
                else "The core business generated ${money(c.v)} on this measure.$m" to Verdict.NEUTRAL
            }
        )

        "grossProfit" -> Body(
            plain = "Revenue minus the direct cost of making or delivering what was sold. It " +
                "is the money available to pay for everything else - research, marketing, " +
                "management, interest and finally profit.",
            scale = "As a share of revenue this is the gross margin. Higher means more room " +
                "for everything else, and more resilience when costs rise.",
            shortTerm = "Watched in earnings reports as a sign of pricing power. Falling " +
                "gross profit while revenue rises means the company is discounting to sell.",
            longTerm = "The size of the gross margin sets the ceiling on how profitable a " +
                "company can ever become, no matter how well it controls other costs.",
            read = { c ->
                val r = c.other("revenue")
                val m = if (r != null && r > 0) " - a gross margin of ${pf(c.v / r)}" else ""
                "Gross profit was ${money(c.v)}$m." to Verdict.NEUTRAL
            }
        )

        "operatingCashflow" -> Body(
            plain = "The actual cash that came in from running the business, before spending " +
                "on new equipment or buildings. Unlike reported profit, this is hard to " +
                "massage with accounting choices.",
            scale = "Should be positive, and for a healthy company should be similar to or " +
                "larger than net income. Profit that never turns into cash is the classic " +
                "warning sign of aggressive accounting.",
            shortTerm = "Not a headline number, so it rarely moves the price on the day - " +
                "which is exactly why it is worth checking yourself.",
            longTerm = "Cash is what pays dividends, buys back shares and funds growth " +
                "without borrowing. Over years this is a better guide to health than reported " +
                "earnings.",
            read = { c ->
                val n = c.other("netIncome")
                val cmp = when {
                    n == null || n <= 0 -> ""
                    c.v > n -> " That is more than the reported profit of ${money(n)}, which " +
                        "is the healthy direction."
                    c.v < n * 0.7 -> " That is well BELOW the reported profit of ${money(n)} - " +
                        "worth understanding why, since profit should eventually turn into cash."
                    else -> " That is broadly in line with the reported profit."
                }
                if (c.v < 0) "Operations consumed ${money(abs(c.v))} of cash." to Verdict.BAD
                else "Operations produced ${money(c.v)} of cash.$cmp" to Verdict.GOOD
            }
        )

        "freeCashflow" -> Body(
            plain = "Operating cash flow minus what the company had to spend on equipment, " +
                "buildings and other long-lived assets. It is the cash genuinely left over " +
                "for shareholders - the money that can fund dividends and buybacks without " +
                "borrowing.",
            scale = "Positive and growing is the target. Persistently negative free cash flow " +
                "means the company must keep raising money, by borrowing or by issuing shares. " +
                "Compare it to market cap for the free cash flow yield.",
            shortTerm = "Not usually a same-day mover, though a sudden swing to negative gets " +
                "noticed quickly by professional investors.",
            longTerm = "This is arguably the truest measure of what a business is worth to " +
                "its owners over time, and the number most long-term valuation methods " +
                "ultimately discount back to today.",
            read = { c ->
                val cap = c.other("marketCap")
                val y = if (cap != null && cap > 0 && c.v > 0)
                    " Against a market cap of ${money(cap)} that is a free cash flow yield of " +
                        "${pf(c.v / cap)}." else ""
                if (c.v < 0) "Free cash flow is NEGATIVE at ${money(c.v)} - after investment " +
                    "the company consumed cash and will need outside funding if that " +
                    "continues." to Verdict.BAD
                else "The company generated ${money(c.v)} of genuinely spare cash.$y" to Verdict.GOOD
            }
        )

        "revenuePerShare" -> Body(
            plain = "Total sales divided by the number of shares - your share of the " +
                "company's revenue.",
            scale = "Only meaningful over time, or against the share price (which gives " +
                "price-to-sales). Rising revenue per share means sales are growing faster " +
                "than the company is issuing new shares.",
            shortTerm = "No meaningful short-term effect - it is a once-a-quarter figure " +
                "that nobody trades on.",
            longTerm = "It is the dilution-adjusted view of growth. A company whose revenue " +
                "doubles while its share count doubles has delivered nothing to existing " +
                "shareholders, and this is the figure that reveals it.",
            read = { c ->
                val p = c.price
                val ps = if (p > 0) " At ${Fmt.price(p)} a share, that is a price-to-sales of " +
                    "${x(p / c.v)}." else ""
                "Each share represents ${Fmt.price(c.v)} of annual revenue.$ps" to Verdict.NEUTRAL
            }
        )

        // ================================================================ GROWTH

        "revenueGrowth" -> Body(
            plain = "How much bigger the company's sales were in the most recent quarter " +
                "compared with the same quarter a year earlier. Comparing to the same quarter " +
                "last year rather than to last quarter cancels out seasonal effects - " +
                "retailers always sell more in December.",
            scale = "Negative means the business is shrinking. Up to about 5% is slow and " +
                "typical of a mature company, 5-15% is solid, 15-30% is fast, and above 30% " +
                "is high growth that is difficult to sustain for long.",
            shortTerm = "One of the two or three numbers that move a stock on earnings day. " +
                "A DECELERATION - growth falling from 30% to 20% - often hurts the price even " +
                "though 20% is still good, because the market prices the trend, not the level.",
            longTerm = "Sustained revenue growth is the engine of long-term returns. It is " +
                "also the thing most likely to fade: very few companies grow above 20% for " +
                "more than a few years, and prices that assume they will are usually " +
                "disappointed.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "Revenue SHRANK ${pf(abs(c.v))} against the same quarter last " +
                        "year. A declining top line puts pressure on everything below it.",
                        Verdict.BAD),
                    Triple(0.05, "Revenue grew ${pf(c.v)} - slow, typical of a mature business " +
                        "in a settled market.", Verdict.NEUTRAL),
                    Triple(0.15, "Revenue grew ${pf(c.v)} - solid, healthy growth.", Verdict.GOOD),
                    Triple(0.30, "Revenue grew ${pf(c.v)} - fast growth. Check whether the " +
                        "price already assumes it continues.", Verdict.GOOD),
                    Triple(Double.MAX_VALUE, "Revenue grew ${pf(c.v)} - very fast. Rates like " +
                        "this rarely last more than a few years, and the share price usually " +
                        "already assumes several of them.", Verdict.MIXED)
                )
            }
        )

        "earningsGrowth" -> Body(
            plain = "How much the company's profit grew in the latest quarter against the " +
                "same quarter a year earlier.",
            scale = "Negative is a shrinking profit. Earnings growth is normally more " +
                "volatile than revenue growth, because costs do not move in step with sales - " +
                "a 10% sales rise can produce a 30% profit rise, and the reverse on the way " +
                "down.",
            shortTerm = "Directly comparable to what analysts forecast, so a gap between the " +
                "two is one of the most common causes of a large one-day move.",
            longTerm = "Earnings growth combined with a steady valuation is the main way " +
                "shares compound over decades. Growth driven by one-off items or cost cuts " +
                "rather than sales does not repeat.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "Profit FELL ${pf(abs(c.v))} against the same quarter last " +
                        "year.", Verdict.BAD),
                    Triple(0.05, "Profit grew ${pf(c.v)} - modest.", Verdict.NEUTRAL),
                    Triple(0.20, "Profit grew ${pf(c.v)} - healthy.", Verdict.GOOD),
                    Triple(0.50, "Profit grew ${pf(c.v)} - strong. Check whether it came from " +
                        "growing sales or from one-off cost cuts, since only the first " +
                        "repeats.", Verdict.GOOD),
                    Triple(Double.MAX_VALUE, "Profit grew ${pf(c.v)}. A jump this large usually " +
                        "means the year-earlier quarter was unusually weak, so treat it as a " +
                        "recovery rather than a run rate.", Verdict.MIXED)
                )
            }
        )

        "earningsQuarterlyGrowth" -> Body(
            plain = "The same year-over-year profit growth measured on the most recent " +
                "reported quarter. It is here because different data providers calculate the " +
                "headline earnings-growth figure slightly differently, and seeing both is " +
                "more honest than picking one.",
            scale = "Read exactly like earnings growth: negative is shrinking, 5-20% healthy, " +
                "very large numbers usually mean an easy comparison against a weak quarter a " +
                "year ago.",
            shortTerm = "Same effect as earnings growth - it is one of the numbers the market " +
                "reacts to on results day.",
            longTerm = "A single quarter is noise. The value is in the sequence: four " +
                "consecutive quarters of improvement is a trend, one is a data point.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "Quarterly profit fell ${pf(abs(c.v))} year over year.", Verdict.BAD),
                    Triple(0.10, "Quarterly profit grew ${pf(c.v)} year over year.", Verdict.NEUTRAL),
                    Triple(0.30, "Quarterly profit grew ${pf(c.v)} year over year - a strong " +
                        "quarter.", Verdict.GOOD),
                    Triple(Double.MAX_VALUE, "Quarterly profit grew ${pf(c.v)} year over year. " +
                        "Very large jumps usually reflect a weak comparison quarter.", Verdict.MIXED)
                )
            }
        )

        "change52Week" -> Body(
            plain = "How much the share price has moved over the last year, as a percentage. " +
                "Pure price - it does not include dividends.",
            scale = "There is no good or bad level; it is context. The useful comparison is " +
                "against the S&P 500 over the same year, which is shown next to it.",
            shortTerm = "Momentum is a real and well-documented effect: over three to twelve " +
                "months, stocks that have gone up have tended to keep going up slightly more " +
                "often than not. It is a weak edge, not a rule, and it reverses hard at " +
                "turning points.",
            longTerm = "Past price says nothing about future value on its own. A stock that " +
                "has doubled can be cheaper than it was if profits tripled, and one that has " +
                "halved can be more expensive if profits collapsed.",
            read = { c ->
                val sp = c.other("sp500Change52Week")
                val vs = if (sp != null) {
                    val d = c.v - sp
                    if (d >= 0) " That is ${pf(abs(d))} BETTER than the S&P 500's ${pf(sp)}."
                    else " That is ${pf(abs(d))} WORSE than the S&P 500's ${pf(sp)}."
                } else ""
                val base = if (c.v >= 0)
                    "The share price is up ${pf(c.v)} over the last year." to Verdict.GOOD
                else "The share price is down ${pf(abs(c.v))} over the last year." to Verdict.BAD
                (base.first + vs) to base.second
            }
        )

        "sp500Change52Week" -> Body(
            plain = "How much the S&P 500 - the index of 500 large US companies, the usual " +
                "stand-in for 'the market' - has moved over the same year. It is here purely " +
                "as the yardstick for this stock's own one-year change.",
            scale = "The long-run average is roughly 10% a year including dividends, but any " +
                "single year is routinely anywhere from -20% to +30%.",
            shortTerm = "The market as a whole explains a large share of any individual " +
                "stock's daily move - often more than the company's own news.",
            longTerm = "Beating this consistently is the whole point of picking individual " +
                "stocks, and most professional funds fail to do it. It is the honest bar to " +
                "measure yourself against.",
            read = { c ->
                if (c.v >= 0) "The S&P 500 is up ${pf(c.v)} over the last year." to Verdict.NEUTRAL
                else "The S&P 500 is down ${pf(abs(c.v))} over the last year." to Verdict.NEUTRAL
            }
        )

        // ========================================================= PROFITABILITY

        "grossMargins" -> Body(
            plain = "The share of every sales dollar left after paying the direct cost of the " +
                "product itself. A 40% gross margin means 40 cents of every dollar sold is " +
                "available for everything else.",
            scale = "Under 20% is thin and typical of retail, distribution and airlines. " +
                "20-40% is normal for manufacturing. Over 60% is characteristic of software " +
                "and pharmaceuticals. Compare only within an industry.",
            shortTerm = "A falling gross margin during a period of rising sales is one of the " +
                "clearest signals a company is buying growth with discounts, and the market " +
                "punishes it quickly when it shows up in a quarterly report.",
            longTerm = "High and STABLE gross margins are the best available evidence of " +
                "pricing power - that customers will pay up rather than switch. It is the " +
                "single most durable competitive advantage a business can have.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "The gross margin is negative - the company sells for less " +
                        "than its products cost to make.", Verdict.BAD),
                    Triple(0.20, "At ${pf(c.v)} the gross margin is thin. Normal for retail " +
                        "and distribution, dangerous if costs rise.", Verdict.MIXED),
                    Triple(0.40, "At ${pf(c.v)} the gross margin is in the ordinary range for " +
                        "a company that makes or sells physical goods.", Verdict.NEUTRAL),
                    Triple(0.60, "At ${pf(c.v)} the gross margin is strong - real pricing " +
                        "power.", Verdict.GOOD),
                    Triple(Double.MAX_VALUE, "At ${pf(c.v)} the gross margin is very high, the " +
                        "hallmark of software, brands or patents rather than manufacturing.",
                        Verdict.GOOD)
                )
            }
        )

        "operatingMargins" -> Body(
            plain = "The share of every sales dollar left after ALL the ordinary costs of " +
                "running the business - production, staff, marketing, research - but before " +
                "interest and tax. It measures how efficiently the core operation is run.",
            scale = "Under 5% is thin, 5-15% is ordinary, above 20% is strong, and above 30% " +
                "is exceptional and usually means a dominant position in its market.",
            shortTerm = "Margin direction often matters more to the market than the level. " +
                "Expanding margins on flat sales is well received; contracting margins on " +
                "growing sales is not.",
            longTerm = "This is where scale advantages appear. A company whose margins widen " +
                "as it grows has real operating leverage, and profits then compound faster " +
                "than sales.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "The operating margin is negative at ${pf(c.v)} - running the " +
                        "business costs more than it brings in.", Verdict.BAD),
                    Triple(0.05, "At ${pf(c.v)} the operating margin is thin. Small changes in " +
                        "costs or prices will swing profits a long way.", Verdict.MIXED),
                    Triple(0.15, "At ${pf(c.v)} the operating margin is in the normal range.",
                        Verdict.NEUTRAL),
                    Triple(0.30, "At ${pf(c.v)} the operating margin is strong.", Verdict.GOOD),
                    Triple(Double.MAX_VALUE, "At ${pf(c.v)} the operating margin is " +
                        "exceptional, which usually signals a dominant position in its market.",
                        Verdict.GOOD)
                )
            }
        )

        "profitMargins" -> Body(
            plain = "The share of every sales dollar that survives all the way to the bottom " +
                "line, after costs, interest and tax. A 10% net margin means the company keeps " +
                "10 cents of every dollar customers spend.",
            scale = "Under 5% is thin, 5-10% is ordinary, 10-20% is good, and over 20% is " +
                "excellent. Grocers run at 1-3% and stay in business; software companies at " +
                "20-30% are unremarkable for their industry.",
            shortTerm = "Reported every quarter and compared with the same quarter last year. " +
                "A sudden contraction is one of the more reliable triggers for a sell-off.",
            longTerm = "Combined with revenue growth, this is what earnings growth is made of. " +
                "Companies that hold a high net margin for a decade are rare and are usually " +
                "priced accordingly.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "The net margin is negative at ${pf(c.v)} - the company is " +
                        "losing money on its sales.", Verdict.BAD),
                    Triple(0.05, "At ${pf(c.v)} the company keeps very little of each sales " +
                        "dollar. Ordinary for retail and groceries, fragile elsewhere.",
                        Verdict.MIXED),
                    Triple(0.10, "At ${pf(c.v)} the net margin is ordinary.", Verdict.NEUTRAL),
                    Triple(0.20, "At ${pf(c.v)} the net margin is good.", Verdict.GOOD),
                    Triple(Double.MAX_VALUE, "At ${pf(c.v)} the net margin is excellent - the " +
                        "company keeps an unusually large share of what it sells.", Verdict.GOOD)
                )
            }
        )

        "ebitdaMargins" -> Body(
            plain = "The share of revenue left as operating cash profit before interest, tax, " +
                "depreciation and amortisation. A rough measure of how much cash the business " +
                "throws off per dollar of sales.",
            scale = "Higher is better within an industry. It flatters capital-heavy " +
                "businesses, because the cost of wearing out their equipment is exactly what " +
                "has been excluded - so a telecom operator's 40% EBITDA margin and a software " +
                "company's 40% are not the same thing.",
            shortTerm = "Not usually a headline number for retail investors, but debt " +
                "covenants and credit ratings are written against it.",
            longTerm = "Useful for comparing companies with very different debt loads, which " +
                "is why private-equity buyers use it. Never use it as a substitute for actual " +
                "profit or free cash flow.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "The EBITDA margin is negative - the core business is " +
                        "consuming cash.", Verdict.BAD),
                    Triple(0.10, "At ${pf(c.v)} the EBITDA margin is thin.", Verdict.MIXED),
                    Triple(0.25, "At ${pf(c.v)} the EBITDA margin is in the ordinary range.",
                        Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "At ${pf(c.v)} the EBITDA margin is high - the " +
                        "core operation converts a large share of sales into cash profit.",
                        Verdict.GOOD)
                )
            }
        )

        "returnOnEquity" -> Body(
            plain = "How much profit the company generates each year for every dollar of " +
                "shareholders' money invested in it. A 15% return on equity means the company " +
                "turns $100 of shareholder capital into $15 of annual profit.",
            scale = "Under 10% is weak, 10-20% is solid, and over 20% is excellent. Read it " +
                "with the debt figure: borrowing money inflates return on equity because debt " +
                "is not equity, so a very high number on a heavily indebted company is " +
                "leverage rather than skill. Companies that have bought back a lot of stock " +
                "can also show extreme figures for purely accounting reasons.",
            shortTerm = "Almost no short-term price effect - it changes once a quarter and " +
                "is rarely the number a results announcement is judged on.",
            longTerm = "This is close to the compounding rate of the business itself. A " +
                "company that can reinvest its profits at 20% for many years is the classic " +
                "long-term winner - which is precisely why the market rarely lets you buy one " +
                "cheaply.",
            read = { c ->
                val de = c.other("debtToEquity")
                val lev = if (de != null && de > 150 && c.v > 0.25)
                    " Note the high debt/equity of ${pp(de)}: much of this return is borrowed " +
                        "money at work rather than operating excellence." else ""
                val base = band(
                    c.v,
                    Triple(0.0, "Return on equity is negative at ${pf(c.v)} - the company is " +
                        "destroying shareholder capital rather than growing it.", Verdict.BAD),
                    Triple(0.10, "At ${pf(c.v)} the return on shareholders' money is weak.",
                        Verdict.MIXED),
                    Triple(0.20, "At ${pf(c.v)} the return on shareholders' money is solid.",
                        Verdict.GOOD),
                    Triple(Double.MAX_VALUE, "At ${pf(c.v)} the return on shareholders' money " +
                        "is excellent.", Verdict.GOOD)
                )
                (base.first + lev) to base.second
            }
        )

        "returnOnAssets" -> Body(
            plain = "How much profit the company makes per dollar of everything it owns - " +
                "factories, inventory, cash, the lot. Unlike return on equity it cannot be " +
                "inflated by borrowing, because borrowed money buys assets too.",
            scale = "Under 3% is weak for most industries, 3-10% is normal, and over 10% is " +
                "strong. Banks live at 1-2% by the nature of their business; asset-light " +
                "software companies can be over 20%.",
            shortTerm = "No meaningful short-term effect; it is reported once a quarter and " +
                "rarely mentioned in the headlines about a results announcement.",
            longTerm = "A cleaner measure of operating quality than return on equity, exactly " +
                "because leverage cannot flatter it. A durable gap between the two tells you " +
                "how much of the headline return is borrowed.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "Return on assets is negative at ${pf(c.v)}.", Verdict.BAD),
                    Triple(0.03, "At ${pf(c.v)} the company earns little from the assets it " +
                        "controls.", Verdict.MIXED),
                    Triple(0.10, "At ${pf(c.v)} the return on assets is in the normal range.",
                        Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "At ${pf(c.v)} the company earns a strong return " +
                        "on everything it owns - efficient use of capital.", Verdict.GOOD)
                )
            }
        )

        // ============================================================= DIVIDENDS

        "dividendRate" -> Body(
            plain = "The cash the company expects to pay you over the next year for each " +
                "share you own, usually split into four quarterly payments. It lands in your " +
                "brokerage account as cash.",
            scale = "The dollar amount means nothing without the share price - $2 on a $30 " +
                "stock is generous, $2 on a $400 stock is token. Divide by the price and you " +
                "get the dividend yield, which is the number to judge.",
            shortTerm = "On the ex-dividend date the share price typically drops by roughly " +
                "the dividend amount, because new buyers no longer receive it. That fall is " +
                "not a loss if you own the shares - you are getting the cash instead.",
            longTerm = "A dividend that RISES every year is one of the strongest signals of a " +
                "healthy, confident business, because cutting one later is deeply embarrassing " +
                "and companies avoid raising unless they are sure. Reinvested dividends have " +
                "historically supplied a large share of total stock market returns.",
            read = { c ->
                val p = c.price
                val y = if (p > 0) " At ${Fmt.price(p)} a share that is a yield of " +
                    "${pf(c.v / p)}." else ""
                if (c.v <= 0) "This company does not currently pay a dividend." to Verdict.NEUTRAL
                else "The company expects to pay ${Fmt.price(c.v)} per share over the next " +
                    "year.$y" to Verdict.GOOD
            }
        )

        "dividendYield" -> Body(
            plain = "The annual dividend as a percentage of the share price - the cash return " +
                "you get for owning the stock, before any price movement. A 3% yield on a " +
                "$100 stock means about $3 a year per share.",
            scale = "0% means no dividend, which is normal and often correct for a growth " +
                "company reinvesting everything. Under 2% is low, 2-4% is a solid income " +
                "stock, 4-6% is high, and above 7-8% is usually a warning: either the market " +
                "expects the dividend to be cut, or the share price has already fallen hard " +
                "for a reason.",
            shortTerm = "Yields move mechanically with the share price - a stock that falls " +
                "20% shows a yield 25% higher without the company doing anything. A rising " +
                "yield is often a falling price, not better news.",
            longTerm = "For income investors this is the return that arrives regardless of " +
                "what the price does. Yields also compete with bonds and savings rates: when " +
                "interest rates rise, dividend stocks often fall until their yields look " +
                "competitive again.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0001, "This stock pays no meaningful dividend. That is not bad in " +
                        "itself - growing companies usually reinvest instead.", Verdict.NEUTRAL),
                    Triple(0.02, "At ${pf(c.v)} the yield is low. This is a stock you would " +
                        "own for price growth, not income.", Verdict.NEUTRAL),
                    Triple(0.04, "At ${pf(c.v)} this is a solid income yield, comfortably " +
                        "above the market average.", Verdict.GOOD),
                    Triple(0.07, "At ${pf(c.v)} the yield is high. Check the payout ratio - a " +
                        "high yield is only good if the company can afford it.", Verdict.MIXED),
                    Triple(Double.MAX_VALUE, "At ${pf(c.v)} the yield is unusually high. Yields " +
                        "this size usually mean the market expects a dividend cut, or that the " +
                        "share price has fallen sharply for a reason worth understanding " +
                        "before buying for the income.", Verdict.BAD)
                )
            }
        )

        "trailingDividendRate" -> Body(
            plain = "The dividends the company ACTUALLY paid per share over the last twelve " +
                "months, as opposed to what it expects to pay next year.",
            scale = "Compare it with the forward rate. Forward higher than trailing means the " +
                "dividend has been raised; forward lower means it has been cut, which is one " +
                "of the more serious signals a company can send.",
            shortTerm = "Dividend cuts are announced, not gradual, and the announcement " +
                "itself usually causes a sharp fall.",
            longTerm = "A long record of paid and rising dividends is a genuine mark of " +
                "quality. Some companies advertise decades of consecutive increases precisely " +
                "because it is hard to fake.",
            read = { c ->
                val fwd = c.other("dividendRate")
                when {
                    c.v <= 0 -> "No dividends were paid over the last twelve months." to Verdict.NEUTRAL
                    fwd == null -> "The company paid ${Fmt.price(c.v)} per share over the last " +
                        "year." to Verdict.NEUTRAL
                    fwd > c.v * 1.01 -> "The company paid ${Fmt.price(c.v)} per share last " +
                        "year and expects to pay ${Fmt.price(fwd)} next year - the dividend is " +
                        "rising." to Verdict.GOOD
                    fwd < c.v * 0.99 -> "The company paid ${Fmt.price(c.v)} per share last " +
                        "year but only expects ${Fmt.price(fwd)} next year - the dividend is " +
                        "being reduced." to Verdict.BAD
                    else -> "The company paid ${Fmt.price(c.v)} per share last year and " +
                        "expects to hold it roughly flat." to Verdict.NEUTRAL
                }
            }
        )

        "trailingDividendYield" -> Body(
            plain = "The dividends actually paid over the last year, as a percentage of " +
                "today's share price.",
            scale = "Same bands as the forward yield: 2-4% solid, over 7% usually a warning. " +
                "This version is backward-looking, so it is a fact rather than a forecast.",
            shortTerm = "Rises automatically when the share price falls, which is why a " +
                "suddenly attractive yield deserves a look at why the price dropped.",
            longTerm = "Comparing this to the five-year average yield is a rough valuation " +
                "check for income stocks: a yield well above its own history often means the " +
                "stock is cheap, or that something has broken.",
            read = { c ->
                val avg = c.other("fiveYearAvgDividendYield")
                val cmp = if (avg != null && avg > 0) {
                    val a = avg / 100.0
                    when {
                        c.v > a * 1.2 -> " That is above its own five-year average of " +
                            "${pf(a)}, which often means the shares are cheap relative to " +
                            "their history - or that the market doubts the dividend."
                        c.v < a * 0.8 -> " That is below its own five-year average of " +
                            "${pf(a)}, so the shares are expensive relative to their own " +
                            "income history."
                        else -> " That is in line with its own five-year average of ${pf(a)}."
                    }
                } else ""
                if (c.v <= 0) "No dividend was paid over the last twelve months." to Verdict.NEUTRAL
                else ("The stock paid ${pf(c.v)} in dividends over the last year.$cmp") to Verdict.NEUTRAL
            }
        )

        "payoutRatio" -> Body(
            plain = "The share of the company's profit that it hands out as dividends. A 40% " +
                "payout ratio means 40 cents of every dollar earned goes to shareholders and " +
                "60 cents is kept in the business.",
            scale = "Under 30% is very safe and leaves plenty for reinvestment. 30-60% is " +
                "comfortable. 60-80% is tight for a normal company - though utilities and " +
                "real estate trusts live there by design. Over 100% means the company is " +
                "paying out more than it earns, which cannot continue indefinitely and is the " +
                "single clearest early warning of a dividend cut.",
            shortTerm = "No day-to-day effect, but it is the number analysts point at when " +
                "they predict a cut, and those predictions move prices.",
            longTerm = "A low payout ratio with a growing dividend is the best combination: " +
                "the income is safe AND there is room for it to keep rising. A high payout " +
                "ratio caps future dividend growth even if nothing goes wrong.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0001, "The company pays out essentially none of its profit as " +
                        "dividends, keeping it all for reinvestment or buybacks.", Verdict.NEUTRAL),
                    Triple(0.30, "At ${pf(c.v)} the dividend is very comfortably covered by " +
                        "profits, with plenty left over to reinvest.", Verdict.GOOD),
                    Triple(0.60, "At ${pf(c.v)} the payout is comfortable and leaves room for " +
                        "the dividend to keep growing.", Verdict.GOOD),
                    Triple(0.80, "At ${pf(c.v)} most of the profit is being paid out. The " +
                        "dividend is affordable today but has little room to grow.", Verdict.NEUTRAL),
                    Triple(1.0, "At ${pf(c.v)} nearly all of the profit is going out as " +
                        "dividends. A weak year would put the payment under real pressure.",
                        Verdict.MIXED),
                    Triple(Double.MAX_VALUE, "At ${pf(c.v)} the company is paying out MORE " +
                        "than it earns. That is funded from cash reserves or borrowing and " +
                        "cannot continue indefinitely - this is the classic warning sign " +
                        "before a dividend cut.", Verdict.BAD)
                )
            }
        )

        "fiveYearAvgDividendYield" -> Body(
            plain = "What this stock's dividend yield has averaged over the last five years. " +
                "It is a history lesson, not a forecast.",
            scale = "Useful only as a comparison with today's yield. Today's yield well above " +
                "the five-year average means the price is low relative to the income it pays; " +
                "well below means the opposite.",
            shortTerm = "No short-term effect of its own. It is a five-year average, so it " +
                "barely moves from one month to the next, let alone one session.",
            longTerm = "Income stocks tend to trade back toward their own typical yield over " +
                "time, which makes this one of the simpler long-run valuation anchors for a " +
                "dividend payer.",
            read = { c ->
                val now = c.other("dividendYield")
                val cmp = if (now != null && now > 0) {
                    val a = c.v / 100.0
                    when {
                        now > a * 1.2 -> " Today's ${pf(now)} is higher, so the shares are " +
                            "cheaper than usual on this measure."
                        now < a * 0.8 -> " Today's ${pf(now)} is lower, so the shares are " +
                            "more expensive than usual on this measure."
                        else -> " Today's ${pf(now)} is in line with it."
                    }
                } else ""
                ("Over the last five years this stock has yielded ${pp(c.v)} on average.$cmp") to
                    Verdict.NEUTRAL
            }
        )

        "exDividendDate" -> Body(
            plain = "The cut-off date for the next dividend. You must own the shares BEFORE " +
                "this date to receive the payment - buying on the day itself is too late.",
            scale = "Not a good or bad number, just a date to be aware of - though a company " +
                "that has an ex-dividend date at all is one that pays a dividend.",
            shortTerm = "On the morning of the ex-dividend date the share price normally opens " +
                "lower by roughly the dividend amount. That is mechanical and expected, not " +
                "bad news - if you own the shares you receive the cash instead.",
            longTerm = "No long-term significance. Chasing dividends by buying just before " +
                "the date and selling after does not work, precisely because of the price drop.",
            read = { c ->
                val d = daysFromNow(c.v)
                when {
                    d > 1 -> "The next cut-off is ${Fmt.day(c.v.toLong())}, in $d days. Buy " +
                        "before then to receive the next payment." to Verdict.NEUTRAL
                    d in 0..1 -> "The cut-off is ${Fmt.day(c.v.toLong())} - essentially now." to
                        Verdict.NEUTRAL
                    // -d is now reachable at 1 (floorDiv), so this has to say "1 day ago".
                    else -> ("The last cut-off was ${Fmt.day(c.v.toLong())}, ${-d} " +
                        (if (d == -1L) "day" else "days") + " ago.") to Verdict.NEUTRAL
                }
            }
        )

        "dividendDate" -> Body(
            plain = "When the cash from the next dividend actually arrives in your account. " +
                "It is usually a few weeks after the ex-dividend cut-off date.",
            scale = "Not a good or bad number - just when you get paid. It normally follows " +
                "the ex-dividend cut-off by two to four weeks.",
            shortTerm = "No price effect. The market has already accounted for the payment on " +
                "the ex-dividend date.",
            longTerm = "No long-term effect either, beyond planning when the income arrives " +
                "and whether you have it reinvested automatically.",
            read = { c ->
                val d = daysFromNow(c.v)
                when {
                    d > 1 -> "The next payment is due ${Fmt.day(c.v.toLong())}, in $d days." to
                        Verdict.NEUTRAL
                    d == 1L -> "The next payment is due ${Fmt.day(c.v.toLong())}, tomorrow." to
                        Verdict.NEUTRAL
                    d == 0L -> "The next payment is due ${Fmt.day(c.v.toLong())} - today." to
                        Verdict.NEUTRAL
                    else -> "The last payment was ${Fmt.day(c.v.toLong())}." to Verdict.NEUTRAL
                }
            }
        )

        // ========================================================= BALANCE SHEET

        "totalCash" -> Body(
            plain = "The money the company has in the bank right now, plus short-term " +
                "investments it could turn into cash quickly.",
            scale = "Judge it against the total debt and against how fast the company is " +
                "spending. A company with more cash than debt is in a strong position; one " +
                "burning cash with little left is in a weak one, whatever its other numbers " +
                "say.",
            shortTerm = "A company running low on cash may have to raise money by issuing new " +
                "shares, which dilutes existing owners and usually drops the price sharply " +
                "when announced.",
            longTerm = "Cash is optionality. It lets a company survive a downturn, buy a " +
                "rival cheaply, or buy back its own shares - all things a company without it " +
                "cannot do, and often exactly when the opportunity is best.",
            read = { c ->
                val d = c.other("totalDebt")
                when {
                    d == null -> "The company holds ${money(c.v)} in cash and short-term " +
                        "investments." to Verdict.NEUTRAL
                    c.v > d -> "The company holds ${money(c.v)} in cash against ${money(d)} of " +
                        "debt - a net cash position, which is a genuinely strong balance " +
                        "sheet." to Verdict.GOOD
                    else -> "The company holds ${money(c.v)} in cash against ${money(d)} of " +
                        "debt, so it is a net borrower." to Verdict.NEUTRAL
                }
            }
        )

        "totalCashPerShare" -> Body(
            plain = "The company's cash divided by the number of shares - how much of the " +
                "share price is backed by money already in the bank.",
            scale = "Compare it to the share price. When cash per share is a large fraction " +
                "of the price, the rest of the business is being valued cheaply. In rare " +
                "cases a company trades for less than its own cash.",
            shortTerm = "No day-to-day effect. Cash per share only changes when the company " +
                "reports, or when it spends or raises money.",
            longTerm = "It is a floor of sorts under the valuation, though only if management " +
                "uses the cash sensibly rather than on bad acquisitions.",
            read = { c ->
                val p = c.price
                if (p > 0) {
                    val share = c.v / p
                    when {
                        share > 0.5 -> "At ${Fmt.price(c.v)} per share, more than half of the " +
                            "${Fmt.price(p)} share price is backed by cash. The operating " +
                            "business itself is being valued at very little." to Verdict.GOOD
                        share > 0.15 -> "At ${Fmt.price(c.v)} per share, ${pf(share)} of the " +
                            "share price is backed by cash." to Verdict.GOOD
                        else -> "At ${Fmt.price(c.v)} per share, cash backs ${pf(share)} of " +
                            "the share price." to Verdict.NEUTRAL
                    }
                } else "The company holds ${Fmt.price(c.v)} of cash per share." to Verdict.NEUTRAL
            }
        )

        "totalDebt" -> Body(
            plain = "Everything the company has borrowed and must eventually repay - bank " +
                "loans, bonds, and lease obligations.",
            scale = "Debt is not bad in itself; it is cheaper than equity and using it well " +
                "raises returns. What matters is whether profits comfortably cover the " +
                "interest, and whether the debt is small relative to the company's equity - " +
                "see debt/equity.",
            shortTerm = "When interest rates rise, heavily indebted companies fall harder " +
                "than others, because refinancing gets more expensive and more of their " +
                "profit goes to lenders.",
            longTerm = "Debt is what turns a bad few years into bankruptcy. Two companies " +
                "can survive the same downturn very differently depending purely on how much " +
                "they owe and when it comes due.",
            read = { c ->
                val cash = c.other("totalCash")
                val de = c.other("debtToEquity")
                val net = if (cash != null) c.v - cash else null
                val base = "The company owes ${money(c.v)}."
                val extra = buildString {
                    if (net != null) {
                        if (net <= 0) append(" After its cash, it is net debt-FREE.")
                        else append(" After its ${money(cash!!)} of cash, net debt is " +
                            "${money(net)}.")
                    }
                    if (de != null) append(" Debt/equity is ${pp(de)}.")
                }
                val verdict = when {
                    net != null && net <= 0 -> Verdict.GOOD
                    de != null && de > 200 -> Verdict.BAD
                    de != null && de > 100 -> Verdict.MIXED
                    else -> Verdict.NEUTRAL
                }
                (base + extra) to verdict
            }
        )

        "debtToEquity" -> Body(
            plain = "How much the company has borrowed for every dollar of shareholders' " +
                "money in the business. Shown as a percentage: 78% means 78 cents of debt for " +
                "every dollar of equity.",
            scale = "Under 50% is conservative, 50-100% is moderate, 100-200% is leveraged, " +
                "and over 200% is heavy. Industry matters enormously: utilities, property and " +
                "banks are built on debt and are fine at levels that would be alarming for a " +
                "software company. Compare within an industry, never across.",
            shortTerm = "In a market sell-off, or when rates rise, high-debt companies fall " +
                "first and furthest. It is one of the sharpest dividing lines in a downturn.",
            longTerm = "Moderate debt raises returns on shareholders' money in good times. " +
                "Too much removes the company's ability to survive a bad patch - and the bad " +
                "patch always comes eventually.",
            read = { c ->
                band(
                    c.v,
                    Triple(50.0, "At ${pp(c.v)} the balance sheet is conservative - little " +
                        "borrowing relative to shareholders' money.", Verdict.GOOD),
                    Triple(100.0, "At ${pp(c.v)} borrowing is moderate and ordinary.",
                        Verdict.NEUTRAL),
                    Triple(200.0, "At ${pp(c.v)} the company is meaningfully leveraged. Fine " +
                        "for a utility or a property company; worth scrutiny anywhere else.",
                        Verdict.MIXED),
                    Triple(Double.MAX_VALUE, "At ${pp(c.v)} the company carries heavy debt " +
                        "relative to shareholders' money. That amplifies gains in good years " +
                        "and threatens survival in bad ones.", Verdict.BAD)
                )
            }
        )

        "currentRatio" -> Body(
            plain = "Whether the company can pay its bills over the next twelve months: " +
                "everything it can turn into cash within a year, divided by everything it " +
                "owes within a year. Above 1 means it can, on paper.",
            scale = "Below 1 is a warning that short-term obligations exceed short-term " +
                "resources. 1.5 to 3 is healthy. Much above 3 can mean cash is sitting idle " +
                "rather than being put to work - not dangerous, but not efficient either.",
            shortTerm = "A ratio dropping below 1 attracts attention from analysts and " +
                "lenders quickly, and can trigger credit downgrades.",
            longTerm = "It is a survival check rather than a quality measure. Companies do " +
                "not fail because profits fall; they fail because they run out of cash to pay " +
                "what is due.",
            read = { c ->
                band(
                    c.v,
                    Triple(1.0, "At ${x(c.v)} short-term obligations exceed short-term " +
                        "resources. Worth checking whether the company has committed credit " +
                        "lines to cover the gap.", Verdict.BAD),
                    Triple(1.5, "At ${x(c.v)} the company can just cover the next year's " +
                        "obligations. Adequate rather than comfortable.", Verdict.MIXED),
                    Triple(3.0, "At ${x(c.v)} the company comfortably covers its short-term " +
                        "obligations.", Verdict.GOOD),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} the company holds far more " +
                        "short-term assets than it needs. Safe, though it can mean cash is " +
                        "sitting idle.", Verdict.GOOD)
                )
            }
        )

        "quickRatio" -> Body(
            plain = "The same test as the current ratio but stricter: it excludes inventory, " +
                "on the grounds that unsold stock cannot reliably be turned into cash in a " +
                "hurry. Sometimes called the acid-test ratio.",
            scale = "Around 1 or above is comfortable. Below 0.7 means the company depends on " +
                "selling inventory to meet near-term bills. For a retailer with fast-moving " +
                "stock that is normal; for a manufacturer with slow inventory it is not.",
            shortTerm = "Rarely moves a price by itself, but it is what credit analysts look " +
                "at when a company's liquidity is questioned.",
            longTerm = "A persistent gap between the current ratio and the quick ratio means " +
                "a lot of the balance sheet is inventory - which is a risk if tastes, " +
                "technology or fashion move on.",
            read = { c ->
                val cr = c.other("currentRatio")
                val note = if (cr != null && cr - c.v > 0.5)
                    " The gap to the current ratio of ${x(cr)} shows a lot of the short-term " +
                        "assets are inventory." else ""
                val base = band(
                    c.v,
                    Triple(0.7, "At ${x(c.v)} the company would struggle to cover near-term " +
                        "bills without selling inventory.", Verdict.MIXED),
                    Triple(1.0, "At ${x(c.v)} liquidity is adequate but not generous.",
                        Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} the company can cover its near-term " +
                        "bills from liquid assets alone.", Verdict.GOOD)
                )
                (base.first + note) to base.second
            }
        )

        // ========================================================= PRICE AND RISK

        "beta" -> Body(
            plain = "How far this stock tends to swing compared with the market as a whole. " +
                "A beta of 1 means it typically moves in line with the market; 1.5 means it " +
                "tends to move about half again as far, up AND down; 0.5 means it moves about " +
                "half as far.",
            scale = "Under 0.8 is defensive - utilities, consumer staples, big pharma. Around " +
                "1 is market-like. Over 1.3 is aggressive - technology, small caps, anything " +
                "cyclical. A negative beta, which is rare, means it tends to move opposite to " +
                "the market.",
            shortTerm = "This is a direct prediction about volatility. On a day the market " +
                "falls 2%, a beta of 1.5 suggests roughly a 3% fall for this stock. It is a " +
                "tendency measured over past data, not a guarantee.",
            longTerm = "Beta measures volatility, not risk of permanent loss - a quiet company " +
                "slowly going bankrupt has a low beta. Its practical use is portfolio shape: " +
                "a portfolio full of high-beta names will feel far worse in a downturn than " +
                "the index does.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.0, "A negative beta of ${x(c.v)} means this stock has tended to " +
                        "move OPPOSITE to the market. That is rare and worth verifying against " +
                        "a longer period.", Verdict.NEUTRAL),
                    Triple(0.8, "At ${x(c.v)} this stock is calmer than the market - it has " +
                        "typically moved less than the index in both directions.", Verdict.GOOD),
                    Triple(1.2, "At ${x(c.v)} this stock moves roughly in line with the market.",
                        Verdict.NEUTRAL),
                    Triple(1.8, "At ${x(c.v)} this stock swings noticeably harder than the " +
                        "market. Expect bigger moves in both directions - on a 2% market fall, " +
                        "roughly ${x(c.v * 2)}% here.", Verdict.MIXED),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} this stock is far more volatile " +
                        "than the market. Position size matters much more than usual with a " +
                        "stock like this.", Verdict.MIXED)
                )
            }
        )

        "fiftyTwoWeekHigh" -> Body(
            plain = "The highest price this stock has traded at over the last year.",
            scale = "What matters is where the current price sits relative to it. Near the " +
                "high means the stock is in an uptrend; far below means it has fallen from " +
                "its peak.",
            shortTerm = "Round-number highs act as psychological levels. Stocks breaking to " +
                "new 52-week highs have historically shown mild continued momentum, while a " +
                "failure to break through often triggers profit-taking.",
            longTerm = "Being near a 52-week high says nothing about value on its own - a " +
                "great business makes new highs constantly for years. Conversely 'it's down " +
                "from its high, so it's cheap' is one of the most expensive beginner mistakes " +
                "there is.",
            read = { c ->
                val p = c.price
                if (p <= 0) "The 52-week high is ${Fmt.price(c.v)}." to Verdict.NEUTRAL
                else {
                    val off = (c.v - p) / c.v
                    when {
                        // ---- A NEW HIGH IS ITS OWN SENTENCE (Round 66 audit, EXP-3).
                        //
                        // `p` is the live quote, seconds old. `c.v` is the 52-week high out
                        // of the fundamentals cache, which has a six-hour TTL and which Yahoo
                        // itself publishes off the PREVIOUS CLOSE. So on any intraday
                        // breakout the live price is above the stored high, `off` goes
                        // negative, and this sheet read "the stock is within -4.2% of its
                        // 52-week high of $201.50" - a negative distance inside a sentence
                        // whose wording only works for a positive one. The ETF and Research
                        // models both clamp the identical expression, which is what makes
                        // this an oversight rather than a decision.
                        //
                        // Clamping to zero would hide it. A new high is the most interesting
                        // state this number ever has, so it gets said out loud.
                        off <= 0.0 -> "At ${Fmt.price(p)} the stock is trading ABOVE its last " +
                            "recorded 52-week high of ${Fmt.price(c.v)} - a new high. The " +
                            "stored figure is published off the previous close, so it catches " +
                            "up overnight." to Verdict.NEUTRAL
                        off < 0.03 -> "At ${Fmt.price(p)} the stock is within ${pf(off)} of its " +
                            "52-week high of ${Fmt.price(c.v)} - trading at the top of its " +
                            "yearly range." to Verdict.NEUTRAL
                        off < 0.20 -> "The stock is ${pf(off)} below its 52-week high of " +
                            "${Fmt.price(c.v)}." to Verdict.NEUTRAL
                        else -> "The stock is ${pf(off)} below its 52-week high of " +
                            "${Fmt.price(c.v)} - a substantial fall from the peak. Worth " +
                            "knowing WHY before treating that as a discount." to Verdict.MIXED
                    }
                }
            }
        )

        "fiftyTwoWeekLow" -> Body(
            plain = "The lowest price this stock has traded at over the last year.",
            scale = "Again, the useful figure is the distance from today's price. Sitting " +
                "just above the low means the stock is out of favour; far above means it has " +
                "recovered.",
            shortTerm = "New 52-week lows tend to attract more selling, partly because some " +
                "funds are required to review or exit positions that hit them.",
            longTerm = "Buying near the low works when the problem was temporary and fails " +
                "when the business is genuinely deteriorating. The price alone cannot tell " +
                "you which - the growth, margin and debt figures can.",
            read = { c ->
                val p = c.price
                if (p <= 0) "The 52-week low is ${Fmt.price(c.v)}." to Verdict.NEUTRAL
                else {
                    val up = (p - c.v) / c.v
                    // The mirror of the 52-week high, and the same reason - see the note
                    // there (Round 66 audit, EXP-3). Below the stored low, "only -3.1% above
                    // its 52-week low" is both wrong and the wrong shape of sentence.
                    if (up <= 0.0) "At ${Fmt.price(p)} the stock is trading BELOW its last " +
                        "recorded 52-week low of ${Fmt.price(c.v)} - a new low. The stored " +
                        "figure is published off the previous close, so it catches up " +
                        "overnight." to Verdict.MIXED
                    else if (up < 0.10) "At ${Fmt.price(p)} the stock is only ${pf(up)} above its " +
                        "52-week low of ${Fmt.price(c.v)} - near the bottom of its yearly " +
                        "range." to Verdict.MIXED
                    else "The stock is ${pf(up)} above its 52-week low of ${Fmt.price(c.v)}." to
                        Verdict.NEUTRAL
                }
            }
        )

        "fiftyDayAverage" -> Body(
            plain = "The average closing price over the last 50 trading days - roughly the " +
                "last ten weeks. It smooths out daily noise to show the recent trend.",
            scale = "Price above the 50-day average is a short-term uptrend; below it is a " +
                "short-term downtrend. That is all it says - it is a description of the past, " +
                "not a forecast.",
            shortTerm = "Enough traders watch this line that it can become self-fulfilling: " +
                "prices often bounce off it or accelerate when they cross it, simply because " +
                "many people are acting on the same signal.",
            longTerm = "No long-term predictive value. Moving averages are a trading tool, " +
                "not a valuation tool, and using them to judge a business is a category error.",
            read = { c ->
                val p = c.price
                if (p <= 0) "The 50-day average price is ${Fmt.price(c.v)}." to Verdict.NEUTRAL
                else {
                    val d = (p - c.v) / c.v
                    if (d >= 0) "At ${Fmt.price(p)} the stock is ${pf(d)} ABOVE its 50-day " +
                        "average of ${Fmt.price(c.v)} - a short-term uptrend." to Verdict.NEUTRAL
                    else "At ${Fmt.price(p)} the stock is ${pf(abs(d))} BELOW its 50-day " +
                        "average of ${Fmt.price(c.v)} - a short-term downtrend." to Verdict.NEUTRAL
                }
            }
        )

        "twoHundredDayAverage" -> Body(
            plain = "The average closing price over the last 200 trading days - about ten " +
                "months. The standard measure of a stock's longer-run trend.",
            scale = "Above the 200-day line is conventionally 'in an uptrend', below it 'in a " +
                "downtrend'. The 50-day crossing above the 200-day is the pattern traders " +
                "call a golden cross; crossing below, a death cross.",
            shortTerm = "Widely watched, so crossings generate real buying and selling from " +
                "trend-following funds regardless of what the company is doing.",
            longTerm = "Some long-term studies find modest value in avoiding stocks below " +
                "their 200-day average during major declines. It remains a description of " +
                "price history, not of the business.",
            read = { c ->
                val p = c.price
                val fifty = c.other("fiftyDayAverage")
                val cross = if (fifty != null) {
                    if (fifty > c.v) " The 50-day average is above the 200-day, the " +
                        "trend-following definition of an uptrend."
                    else " The 50-day average is below the 200-day, which trend followers read " +
                        "as a downtrend."
                } else ""
                if (p <= 0) "The 200-day average price is ${Fmt.price(c.v)}.$cross" to Verdict.NEUTRAL
                else {
                    val d = (p - c.v) / c.v
                    val s = if (d >= 0) "At ${Fmt.price(p)} the stock is ${pf(d)} above its " +
                        "200-day average of ${Fmt.price(c.v)}."
                    else "At ${Fmt.price(p)} the stock is ${pf(abs(d))} below its 200-day " +
                        "average of ${Fmt.price(c.v)}."
                    (s + cross) to Verdict.NEUTRAL
                }
            }
        )

        "volume" -> Body(
            plain = "How many shares changed hands today - one share bought and sold counts once. It measures how much attention the stock is getting right now.",
            scale = "Only meaningful against this stock's own average. Two or three times " +
                "normal volume means something happened; a quarter of normal means nobody is " +
                "paying attention.",
            shortTerm = "Volume is the confirmation on a price move. A 5% rise on triple " +
                "volume reflects real conviction; the same rise on light volume is more often " +
                "noise that fades. Low volume also means wider spreads and worse fills on " +
                "your own orders.",
            longTerm = "No long-run significance, though persistently thin volume makes a " +
                "stock harder to sell in a hurry - which matters most exactly when you want to.",
            read = { c ->
                val avg = c.other("averageVolume")
                if (avg == null || avg <= 0) "${Fmt.compact(c.v)} shares have traded today." to
                    Verdict.NEUTRAL
                else {
                    val r = c.v / avg
                    when {
                        r > 2.0 -> "${Fmt.compact(c.v)} shares traded, ${x(r)}x the average of " +
                            "${Fmt.compact(avg)}. Something is driving unusual interest today." to
                            Verdict.MIXED
                        r > 1.3 -> "${Fmt.compact(c.v)} shares traded - above the average of " +
                            "${Fmt.compact(avg)}." to Verdict.NEUTRAL
                        r < 0.5 -> "${Fmt.compact(c.v)} shares traded, well below the average " +
                            "of ${Fmt.compact(avg)} - a quiet session." to Verdict.NEUTRAL
                        else -> "${Fmt.compact(c.v)} shares traded, close to the average of " +
                            "${Fmt.compact(avg)}." to Verdict.NEUTRAL
                    }
                }
            }
        )

        "averageVolume" -> Body(
            plain = "The typical number of shares traded per day over the last three months. " +
                "It is a measure of how easy the stock is to buy and sell.",
            scale = "Millions of shares a day means you can trade freely at the quoted price. " +
                "Under a hundred thousand means a modest order can move the price against " +
                "you, and the gap between the buying and selling price gets wide.",
            shortTerm = "Thin stocks gap around unpredictably and are far more expensive to " +
                "trade than the headline price suggests.",
            longTerm = "Liquidity determines who CAN own the stock. Large funds cannot build " +
                "a position in a thinly traded company at all, which caps the buying pressure " +
                "available to it.",
            read = { c ->
                band(
                    c.v,
                    Triple(1e5, "At ${Fmt.compact(c.v)} shares a day this is thinly traded. " +
                        "Expect wide spreads, and use limit orders rather than market orders.",
                        Verdict.MIXED),
                    Triple(1e6, "At ${Fmt.compact(c.v)} shares a day liquidity is moderate.",
                        Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "At ${Fmt.compact(c.v)} shares a day this trades " +
                        "freely - orders fill close to the quoted price.", Verdict.GOOD)
                )
            }
        )

        "averageVolume10days" -> Body(
            plain = "The average daily share volume over just the last two weeks. A shorter, " +
                "more current view than the three-month average.",
            scale = "Compare the two. Ten-day volume well above the three-month average means " +
                "interest in the stock is picking up right now; well below means it is fading.",
            shortTerm = "A sustained rise in recent volume usually precedes or accompanies a " +
                "bigger price move, in either direction.",
            longTerm = "None on its own, though a stock whose volume has been drying up for months is one the market has stopped caring about.",
            read = { c ->
                val avg = c.other("averageVolume")
                if (avg == null || avg <= 0) "Recent average volume is ${Fmt.compact(c.v)} " +
                    "shares a day." to Verdict.NEUTRAL
                else {
                    val r = c.v / avg
                    when {
                        r > 1.3 -> "Recent volume of ${Fmt.compact(c.v)} a day is running " +
                            "${x(r)}x the three-month average - interest is rising." to Verdict.MIXED
                        r < 0.7 -> "Recent volume of ${Fmt.compact(c.v)} a day is below the " +
                            "three-month average - interest is fading." to Verdict.NEUTRAL
                        else -> "Recent volume of ${Fmt.compact(c.v)} a day is in line with the " +
                            "three-month average." to Verdict.NEUTRAL
                    }
                }
            }
        )

        // =========================================================== OWNERSHIP

        "sharesOutstanding" -> Body(
            plain = "How many shares of the company exist in total. Your ownership is your " +
                "share count divided by this number.",
            scale = "The absolute number is meaningless - it is set arbitrarily and changed " +
                "by splits. The DIRECTION is what matters: a falling count means the company " +
                "is buying back stock and each remaining share owns more of the business; a " +
                "rising count means dilution.",
            shortTerm = "Announcing a large share issue usually drops the price immediately, " +
                "because every existing share suddenly owns a smaller slice. Announcing a " +
                "buyback usually lifts it.",
            longTerm = "Steady buybacks quietly increase earnings per share year after year " +
                "even with flat profits. Steady dilution does the reverse, and is a common " +
                "reason a fast-growing company delivers poor returns to its owners.",
            read = { c ->
                val f = c.other("floatShares")
                val note = if (f != null && f > 0 && f < c.v * 0.8)
                    " Only ${Fmt.compact(f)} of them trade freely; the rest are held by " +
                        "insiders or locked up." else ""
                "${Fmt.compact(c.v)} shares exist.$note" to Verdict.NEUTRAL
            }
        )

        "floatShares" -> Body(
            plain = "The number of shares actually available to trade on the open market, " +
                "after removing those held by founders, executives and other locked-up " +
                "insiders.",
            scale = "A small float relative to total shares means fewer shares chase the same " +
                "demand. Under about 20 million shares is considered a small float.",
            shortTerm = "Small-float stocks move violently. The same amount of buying that " +
                "nudges a widely-held stock can move a small-float one many percent, which is " +
                "why they feature in most short squeezes.",
            longTerm = "A small float often means a founder still controls the company, which " +
                "cuts both ways: long-term thinking, but less accountability to outside " +
                "shareholders.",
            read = { c ->
                val total = c.other("sharesOutstanding")
                val pct = if (total != null && total > 0) c.v / total else null
                when {
                    pct != null && pct < 0.5 -> "Only ${Fmt.compact(c.v)} shares trade freely - " +
                        "${pf(pct)} of all shares. A tightly held stock like this can move " +
                        "sharply on modest volume." to Verdict.MIXED
                    c.v < 2e7 -> "${Fmt.compact(c.v)} shares trade freely - a small float, so " +
                        "expect larger price swings than the company's size alone would " +
                        "suggest." to Verdict.MIXED
                    else -> "${Fmt.compact(c.v)} shares trade freely on the open market." to
                        Verdict.NEUTRAL
                }
            }
        )

        "heldPercentInsiders" -> Body(
            plain = "The share of the company owned by its own executives, directors and " +
                "founders.",
            scale = "Under 1% is typical for a large, long-established company. Over 10% means " +
                "management has serious money of its own at stake. Very high figures - over " +
                "50% - mean outside shareholders cannot outvote the founder on anything.",
            shortTerm = "Individual insider buying and selling is reported to the SEC and " +
                "does move prices, particularly cluster buying by several executives at once.",
            longTerm = "Meaningful insider ownership aligns management with shareholders, and " +
                "studies generally find it is associated with better long-run performance. " +
                "The trade-off at very high levels is reduced accountability.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.01, "Insiders own ${pf(c.v)} - a small stake, normal for a large " +
                        "established company but little personal skin in the game.", Verdict.NEUTRAL),
                    Triple(0.10, "Insiders own ${pf(c.v)}.", Verdict.NEUTRAL),
                    Triple(0.50, "Insiders own ${pf(c.v)} - management has substantial money of " +
                        "its own riding on the share price.", Verdict.GOOD),
                    Triple(Double.MAX_VALUE, "Insiders own ${pf(c.v)}, a controlling stake. " +
                        "Strong alignment, but outside shareholders have little practical say.",
                        Verdict.MIXED)
                )
            }
        )

        "heldPercentInstitutions" -> Body(
            plain = "The share of the company owned by professional investors - pension " +
                "funds, mutual funds, index funds and hedge funds.",
            scale = "Under 30% is low and typical of very small or very new companies. 40-80% " +
                "is normal for an established stock. Above 90% means essentially every share " +
                "is in professional hands.",
            shortTerm = "High institutional ownership means bigger moves when sentiment " +
                "turns, because large funds enter and exit in size and often at the same time " +
                "as each other.",
            longTerm = "It is a rough quality filter: professionals have done work on the " +
                "company. It is also a crowding warning - when everyone who might buy already " +
                "owns it, the marginal buyer has to come from somewhere else.",
            read = { c ->
                band(
                    c.v,
                    Triple(0.30, "Institutions own ${pf(c.v)} - low. Either a very small " +
                        "company, a recent listing, or one professionals have avoided.",
                        Verdict.MIXED),
                    Triple(0.80, "Institutions own ${pf(c.v)}, which is the normal range for " +
                        "an established stock.", Verdict.NEUTRAL),
                    Triple(Double.MAX_VALUE, "Institutions own ${pf(c.v)} - almost entirely in " +
                        "professional hands, so most of the potential buyers already own it.",
                        Verdict.MIXED)
                )
            }
        )

        "shortPercentOfFloat" -> Body(
            plain = "The share of freely traded stock that has been sold short - borrowed and " +
                "sold by investors betting the price will FALL. They have to buy those shares " +
                "back eventually.",
            scale = "Under 2% is minimal, 2-5% is normal, 5-10% is elevated, 10-20% means a " +
                "serious bear case exists, and above 20% is heavily crowded. High short " +
                "interest is a warning that sophisticated investors expect trouble - and " +
                "simultaneously the fuel for a violent rally if they are wrong.",
            shortTerm = "This is what makes short squeezes possible. When a heavily shorted " +
                "stock rises on good news, short sellers are forced to buy to cut their " +
                "losses, which pushes it higher, which forces more buying. Moves of 50% or " +
                "more in days come from exactly this.",
            longTerm = "Short sellers are wrong often, but they do their homework, and " +
                "persistently high short interest in a company is worth taking seriously as a " +
                "reason to look harder at the accounts.",
            read = { c ->
                val days = c.other("shortRatio")
                val d = if (days != null && days > 0) " At the current pace it would take " +
                    "about ${x(days)} days of normal trading for them all to buy back." else ""
                val base = band(
                    c.v,
                    Triple(0.02, "Only ${pf(c.v)} of the tradable shares are sold short - " +
                        "minimal bearish pressure.", Verdict.GOOD),
                    Triple(0.05, "${pf(c.v)} of the tradable shares are sold short, which is " +
                        "in the normal range.", Verdict.NEUTRAL),
                    Triple(0.10, "${pf(c.v)} of the tradable shares are sold short - elevated. " +
                        "Some investors have a specific bearish case here.", Verdict.MIXED),
                    Triple(0.20, "${pf(c.v)} of the tradable shares are sold short - heavily " +
                        "shorted. Expect sharp moves in both directions.", Verdict.MIXED),
                    Triple(Double.MAX_VALUE, "${pf(c.v)} of the tradable shares are sold " +
                        "short. That is a crowded bearish bet: a real risk that professionals " +
                        "see something, and the ingredients for a violent squeeze if they are " +
                        "wrong.", Verdict.MIXED)
                )
                (base.first + d) to base.second
            }
        )

        "sharesShort" -> Body(
            plain = "The raw number of shares currently sold short - borrowed and sold by " +
                "investors betting on a fall.",
            scale = "The number alone means little; divide it by the float (which the app " +
                "does, just above) or by average daily volume to get days-to-cover.",
            shortTerm = "Short interest is reported roughly twice a month, so a large change " +
                "in it can itself be news when it lands.",
            longTerm = "Rising short interest over months, while the price holds up, means " +
                "the bearish case is gaining adherents. Falling short interest means they are " +
                "giving up - which often happens near the top.",
            read = { c ->
                val f = c.other("floatShares")
                val p = if (f != null && f > 0) " - ${pf(c.v / f)} of the tradable shares" else ""
                "${Fmt.compact(c.v)} shares are currently sold short$p." to Verdict.NEUTRAL
            }
        )

        "shortRatio" -> Body(
            plain = "Days to cover: how many normal trading days it would take for every " +
                "short seller to buy back their borrowed shares. Short interest divided by " +
                "average daily volume.",
            scale = "Under 2 days is easy to unwind. 2-5 days is moderate. Above 5 days means " +
                "short sellers are trapped in a position they cannot exit quickly, which is " +
                "the precondition for a squeeze. Above 10 is extreme.",
            shortTerm = "The single best predictor of squeeze potential. A stock with 8 days " +
                "to cover and good news can rise far more than the news alone would justify, " +
                "because the exit is too narrow for everyone.",
            longTerm = "No long-term significance. It is a market-mechanics figure, not a " +
                "statement about the business.",
            read = { c ->
                band(
                    c.v,
                    Triple(2.0, "At ${x(c.v)} days to cover, short sellers could exit easily. " +
                        "Little squeeze potential.", Verdict.NEUTRAL),
                    Triple(5.0, "At ${x(c.v)} days to cover, unwinding the short position " +
                        "would take a while but is manageable.", Verdict.NEUTRAL),
                    Triple(10.0, "At ${x(c.v)} days to cover, short sellers are in a crowded " +
                        "position with a narrow exit. Good news could force sharp buying.",
                        Verdict.MIXED),
                    Triple(Double.MAX_VALUE, "At ${x(c.v)} days to cover this is an extremely " +
                        "crowded short. Squeezes in stocks like this can be violent - in both " +
                        "directions.", Verdict.MIXED)
                )
            }
        )

        // =============================================================== COMPANY

        "employees" -> Body(
            plain = "How many people the company employs full time.",
            scale = "Useful mostly as revenue per employee: a software company might generate " +
                "over a million dollars of sales per head, a retailer a fraction of that. It " +
                "tells you what KIND of business this is.",
            shortTerm = "Large layoff announcements usually lift the share price on the day, " +
                "because the market reads them as cost discipline - whatever they mean for " +
                "the business longer term.",
            longTerm = "Headcount growing much faster than revenue is a warning that the " +
                "company is not scaling. Revenue growing much faster than headcount is the " +
                "sign of a business with real operating leverage.",
            read = { c ->
                val r = c.other("revenue")
                if (r != null && r > 0 && c.v > 0) {
                    "The company employs ${Fmt.compact(c.v)} people, which works out at " +
                        "${money(r / c.v)} of revenue per employee." to Verdict.NEUTRAL
                } else "The company employs ${Fmt.compact(c.v)} people." to Verdict.NEUTRAL
            }
        )

        else -> FALLBACK
    }

    // ================================================== analyst-side explainers

    /**
     * The Analysts tab has its own "i" buttons, because the ratings raise exactly the same
     * beginner questions the numbers do - what a price target actually is, whether an
     * upgrade means buy, and how much any of it is worth.
     */
    fun analystTopic(topic: String, f: Fundamentals, price: Double = 0.0): Explanation =
        when (topic) {
            TOPIC_CONSENSUS -> {
                val c = f.consensus
                Explanation(
                    title = "Analyst consensus",
                    plain = "Professional analysts at banks and research firms publish a " +
                        "rating on each stock they cover - some version of buy, hold or " +
                        "sell. The consensus is simply the average of all of them. It is " +
                        "an opinion poll of paid experts, not a fact about the company.",
                    scale = "Most ratings cluster on the optimistic side: across the whole " +
                        "market roughly half of all ratings are some form of buy and only a " +
                        "few percent are sell. That means a 'hold' from Wall Street is " +
                        "closer to what a normal person would call a mild negative, and an " +
                        "outright sell rating is a strong statement. Also look at HOW MANY " +
                        "analysts cover the stock: a consensus from 40 analysts is a real " +
                        "average, one from 2 is two people's opinions.",
                    shortTerm = "Individual rating changes move prices, sometimes several " +
                        "percent in a morning - an upgrade from a large bank triggers " +
                        "buying from clients who follow it. The consensus itself changes too " +
                        "slowly to trade on.",
                    longTerm = "The evidence on analyst accuracy is not flattering: as a " +
                        "group they are persistently too optimistic, they downgrade after " +
                        "falls rather than before them, and their price targets are wrong " +
                        "more often than right. Use the ratings as a summary of what the " +
                        "professional consensus currently is - which is useful in itself, " +
                        "because it tells you what is already priced in - rather than as a " +
                        "forecast.",
                    read = when {
                        c == null -> "No analyst coverage was found for this stock. That is " +
                            "normal for very small companies, and it means there is no " +
                            "professional consensus to lean on either way."
                        c.hasVotes -> "${c.votes} analysts cover this stock: " +
                            "${c.strongBuy + c.buy} say buy, ${c.hold} say hold and " +
                            "${c.sell + c.strongSell} say sell. The average works out at " +
                            "\"${c.meanLabel.lowercase()}\"."
                        else -> "The consensus rating is \"${c.meanLabel.lowercase()}\" " +
                            "from ${c.analysts} analysts."
                    },
                    verdict = when {
                        c == null -> Verdict.UNKNOWN
                        c.mean in 0.01..2.0 -> Verdict.GOOD
                        c.mean > 3.5 -> Verdict.BAD
                        else -> Verdict.NEUTRAL
                    }
                )
            }

            TOPIC_TARGET -> {
                val c = f.consensus
                val up = c?.upsidePct(price)
                Explanation(
                    title = "Price target",
                    plain = "An analyst's price target is where they think the stock will " +
                        "trade in about twelve months. The consensus target is the average " +
                        "of all of them, and the high and low show how much they disagree.",
                    scale = "A wide gap between the highest and lowest target means genuine " +
                        "uncertainty about the business - two professionals looking at the " +
                        "same company and reaching very different conclusions. A narrow " +
                        "range means agreement, which is comfortable but also means little " +
                        "surprise is priced in either way. Targets are almost always ABOVE " +
                        "the current price; that is the industry's optimistic bias, not a " +
                        "signal.",
                    shortTerm = "A target being raised or cut, especially by a major bank, " +
                        "moves the price the same day. The target itself has no mechanical " +
                        "effect - it is the change in it that gets traded.",
                    longTerm = "Studies consistently find price targets have little " +
                        "predictive power over a year, and are frequently revised to follow " +
                        "the price rather than to lead it. Treat the consensus target as a " +
                        "sentiment reading, and take the RANGE more seriously than the " +
                        "average.",
                    read = when {
                        c == null || !c.hasTarget ->
                            "No consensus price target is available for this stock."
                        up == null -> "The average target is ${Fmt.price(c.targetMean)}, in a " +
                            "range from ${Fmt.price(c.targetLow)} to ${Fmt.price(c.targetHigh)}."
                        up >= 0 -> "The average target of ${Fmt.price(c.targetMean)} is " +
                            "${pp(up)} above today's ${Fmt.price(price)}, with individual " +
                            "targets ranging from ${Fmt.price(c.targetLow)} to " +
                            "${Fmt.price(c.targetHigh)}."
                        else -> "The average target of ${Fmt.price(c.targetMean)} is " +
                            "${pp(abs(up))} BELOW today's ${Fmt.price(price)} - unusual, and " +
                            "worth noting, since targets normally sit above the price."
                    },
                    verdict = when {
                        c == null || !c.hasTarget -> Verdict.UNKNOWN
                        up == null -> Verdict.NEUTRAL
                        up > 15 -> Verdict.GOOD
                        up < 0 -> Verdict.BAD
                        else -> Verdict.NEUTRAL
                    }
                )
            }

            TOPIC_RATINGS -> Explanation(
                title = "Ratings and what they mean",
                plain = "Every firm invents its own words, and they do not mean what they " +
                    "sound like. Buy, Outperform, Overweight, Accumulate and Add all mean " +
                    "the same thing: buy. Hold, Neutral, Equal-Weight, Market Perform, " +
                    "Sector Perform and In-Line all mean hold. Sell, Underperform, " +
                    "Underweight and Reduce all mean sell. The app groups them for you.",
                scale = "The ACTION matters more than the rating. An upgrade or a downgrade " +
                    "is new information - somebody changed their mind. A reiteration is the " +
                    "same opinion restated, usually after a results announcement, and moves " +
                    "prices far less. Coverage being initiated is a new voice joining, " +
                    "which for a smaller company can bring real buying with it.",
                shortTerm = "Downgrades hit harder than upgrades help. A downgrade from a " +
                    "major bank can take several percent off a stock before lunch, partly " +
                    "because the bank's own clients act on it immediately.",
                longTerm = "The pattern across many analysts over months is worth more than " +
                    "any single call: a stock steadily accumulating downgrades usually has " +
                    "a deteriorating business behind it. Individual analysts are wrong " +
                    "often enough that no single rating deserves to change your mind.",
                read = if (f.ratings.isEmpty())
                    "No individual analyst actions were found for this stock."
                else {
                    val recent = f.ratings.take(10)
                    val buys = recent.count { it.bucket == "BUY" }
                    val sells = recent.count { it.bucket == "SELL" }
                    "Of the last ${recent.size} actions on this stock, $buys are buy-side " +
                        "ratings and $sells are sell-side. The full list below is newest " +
                        "first, with each firm's own wording and price target."
                },
                verdict = Verdict.NEUTRAL
            )

            TOPIC_ESTIMATES -> Explanation(
                title = "Earnings estimates",
                plain = "Before every quarterly report, analysts publish what they expect " +
                    "the company to earn per share and how much revenue it will book. The " +
                    "average of those forecasts becomes the number the company is judged " +
                    "against on results day.",
                scale = "The spread between the highest and lowest estimate is the useful " +
                    "part: a wide spread means nobody really knows, and the price reaction " +
                    "will be large either way. The number of analysts contributing tells " +
                    "you how much weight the average deserves.",
                shortTerm = "This is the single biggest source of large single-day moves in " +
                    "individual stocks. Beating the estimate is normal - companies guide " +
                    "analysts down so they can beat - so a small beat often does nothing, " +
                    "and a MISS is punished hard. What the company says about the NEXT " +
                    "quarter frequently matters more than the quarter just reported.",
                longTerm = "The trend in estimates matters more than any single one. " +
                    "Estimates being revised steadily upward over months is one of the more " +
                    "reliable positive signals in the market; steady downward revisions are " +
                    "the reverse.",
                read = f.estimates.firstOrNull { it.period == "0q" }?.let { e ->
                    "For the current quarter, ${e.analysts} analysts expect about " +
                        "${Fmt.price(e.epsAvg)} per share, with estimates ranging from " +
                        "${Fmt.price(e.epsLow)} to ${Fmt.price(e.epsHigh)}." +
                        (if (e.epsYearAgo != 0.0) " A year ago the company earned " +
                            "${Fmt.price(e.epsYearAgo)} in the same quarter." else "")
                } ?: "No earnings estimates are available for this stock.",
                verdict = Verdict.NEUTRAL
            )

            TOPIC_EARNINGS_DATE -> Explanation(
                title = "Next earnings report",
                plain = "The date the company is expected to publish its quarterly results. " +
                    "US companies report four times a year, usually either before the market " +
                    "opens or after it closes, never during trading hours.",
                scale = "Dates more than a few weeks out are estimates based on past " +
                    "patterns, and companies confirm them a few weeks ahead.",
                shortTerm = "This is the most predictable source of volatility a stock has. " +
                    "Single-day moves of 5-15% around results are ordinary, and option " +
                    "prices rise beforehand precisely because everyone knows it is coming. " +
                    "Holding through earnings is a deliberate choice to accept that risk.",
                longTerm = "Over years, the accumulation of quarterly results IS the story " +
                    "of the investment. Any single quarter is mostly noise.",
                read = if (f.earningsDate > 0) {
                    // floorDiv, not `/` - see daysFromNow. Plain Long division made a
                    // report released last night read as "expected ... in 0 days" for the
                    // whole following day, promising a price move that had already happened.
                    val days = Math.floorDiv(f.earningsDate - System.currentTimeMillis(), 86_400_000L)
                    if (days > 0) "The next report is expected ${Fmt.day(f.earningsDate)}, " +
                        "in $days days. Expect a larger-than-usual price move that day."
                    else if (days == 0L) "The next report is expected ${Fmt.day(f.earningsDate)} " +
                        "- today. Expect a larger-than-usual price move."
                    else "The most recent report was ${Fmt.day(f.earningsDate)}."
                } else "No earnings date is scheduled in the data right now.",
                verdict = Verdict.NEUTRAL
            )

            else -> Explanation(
                title = topic,
                plain = "", scale = "", shortTerm = "", longTerm = "",
                read = "", verdict = Verdict.UNKNOWN
            )
        }

    const val TOPIC_CONSENSUS = "consensus"
    const val TOPIC_TARGET = "target"
    const val TOPIC_RATINGS = "ratings"
    const val TOPIC_ESTIMATES = "estimates"
    const val TOPIC_EARNINGS_DATE = "earningsDate"

    /** Printed at the bottom of every explanation sheet. */
    const val DISCLAIMER =
        "This is general education about what the number means, not advice about what to " +
            "do. Any single figure can be misleading on its own, and the right comparison " +
            "is almost always against other companies in the same industry."

    /** Shown when a whole group is empty, so a blank section is never unexplained. */
    fun consensusOrNull(f: Fundamentals): Consensus? = f.consensus

    fun unitOf(key: String): MetricUnit =
        MetricCatalog.byKey[key]?.unit ?: MetricUnit.RATIO
}
