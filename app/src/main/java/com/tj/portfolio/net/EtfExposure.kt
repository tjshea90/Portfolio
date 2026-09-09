package com.tj.portfolio.net

/**
 * WHAT A FUND IS ACTUALLY EXPOSED TO, read off its printed name (Round 66).
 *
 * ---- WHY THIS EXISTS
 *
 * TJ: *"I'm going to buy some of the top ETFs, so that section should be very accurate...
 * This section should tell me healthy etfs that perform well and are strong buys, listed from
 * best at the top."*
 *
 * A list ranked purely by score does not answer that. VOO, IVV and SPLG are the same index,
 * the same holdings and within a basis point of each other on cost, so they score within a
 * point or two and arrive as three consecutive rows. Ten rows can easily be five decisions
 * wearing ten tickers, and the funds that would have been the other five never appear.
 *
 * Both of the serious, disinterested guides say the same thing about this. Schwab's own
 * "How to evaluate ETFs" puts EXPOSURE first and warns that even funds in the same category
 * can hold quite different things; Saxo's guide says to compare "similar funds" with identical
 * exposure, structure and strategy rather than ranking every broad index fund against every
 * other. So: group by exposure, rank within the group, and show the winner with its siblings
 * named beside it. The ranking is unchanged - this only decides which rows get a place.
 *
 * ---- WHY THE NAME, AND WHY IT ERRS TOWARD *NOT* GROUPING
 *
 * No keyless feed publishes a fund's index or category. The name is what there is, and for
 * index funds it is reliable because issuers put the index in it: "Vanguard S&P 500 ETF",
 * "iShares Core S&P 500 ETF", "SPDR Portfolio S&P 500 ETF".
 *
 * The failure that matters is OVER-grouping: merging two genuinely different funds hides a
 * real choice from someone about to spend money, and does it silently. Under-grouping merely
 * leaves the list as it is today. So this recognises a fixed set of EXPLICIT index and asset
 * names and returns null - meaning "keep it, it stands alone" - for everything else. A fund
 * is only ever grouped when its name says plainly what it tracks.
 */
object EtfExposure {

    /**
     * One exposure group, or null when the name does not clearly say.
     *
     * The order of the checks matters: the more specific pattern has to win, or "S&P 500
     * Growth" would be grouped with plain "S&P 500" and a growth tilt would vanish into a
     * broad index fund.
     */
    fun keyOf(name: String): String? {
        if (name.isBlank()) return null
        // '+' with the rest of the punctuation: "iShares 20+ Year Treasury" has to reduce to
        // "20 year" or the maturity band below never matches it, which is how TLT ended up
        // ungrouped on the first attempt at this.
        val n = " " + name.lowercase()
            .replace('-', ' ').replace('&', ' ').replace('/', ' ').replace('+', ' ')
            .replace(",", " ").replace(".", " ")
            .replace(Regex("\\s+"), " ") + " "

        // A TILT IS NOT THE INDEX. Growth, value, dividend, equal-weight, hedged, ESG and
        // buffered versions of an index are different products with different holdings, and
        // grouping them with the plain fund would hide exactly the choice being made.
        val tilted = listOf(
            " growth ", " value ", " dividend ", " equal weight ", " equally weighted ",
            " hedged ", " esg ", " buffer ", " covered call ", " enhanced ", " momentum ",
            " quality ", " low volatility ", " minimum volatility ", " min vol ",
            " screened ", " sri ", " catholic ", " sustainable "
        )
        val hasTilt = tilted.any { n.contains(it) }

        fun group(key: String) = if (hasTilt) null else key

        // ---- REGION IS TESTED BEFORE SIZE (Round 66 audit, E2).
        //
        // THE BUG THIS FIXES, and it was in the first version of this file. The US size
        // ladder ran first, so " small cap " matched before " eafe " was ever reached and
        // "iShares MSCI EAFE Small-Cap ETF" was handed the key "US small cap" - merging a
        // developed-markets fund with a US one and dropping whichever scored lower off the
        // page, under a card claiming "same exposure". "Vanguard FTSE All-World ex-US
        // Small-Cap" went the same way. That is precisely the over-grouping this file's own
        // note calls the failure that matters: it hides a real choice, silently, from someone
        // about to spend money.
        val region = when {
            n.contains(" emerging markets ") || n.contains(" emerging market ") ->
                "Emerging markets"
            n.contains(" eafe ") || n.contains(" developed markets ") ||
                n.contains(" developed world ") -> "Developed ex-US"
            n.contains(" total international ") || n.contains(" total world ") ||
                n.contains(" all world ") || n.contains(" acwi ") ||
                n.contains(" ex us ") || n.contains(" ex u s ") -> "Global equity"
            else -> null
        }
        if (region != null) {
            // A regional fund's SIZE band still separates it: EAFE small-cap and EAFE large
            // are not one decision either.
            val size = when {
                n.contains(" small cap ") || n.contains(" smallcap ") -> " small cap"
                n.contains(" mid cap ") || n.contains(" midcap ") -> " mid cap"
                else -> ""
            }
            return group(region + size)
        }

        return when {
            // ---- US large cap, the crowded end of the list
            n.contains(" s p 500 ") || n.contains(" sp 500 ") -> group("US large cap - S&P 500")
            n.contains(" nasdaq 100 ") || n.contains(" qqq ") -> group("US large cap - Nasdaq-100")
            // The three real spellings, verified against the actual fund names: Vanguard's
            // "Total Stock Market", iShares' "Core S&P Total U.S. Stock Market" (which the
            // dot-stripping above turns into "total u s stock market") and Schwab's
            // "U.S. Broad Market".
            n.contains(" total stock market ") || n.contains(" total u s stock ") ||
                n.contains(" total us stock ") || n.contains(" total market ") ||
                n.contains(" broad market ") ->
                group("US total market")
            n.contains(" dow jones industrial ") -> group("US large cap - Dow 30")
            n.contains(" russell 1000 ") -> group("US large cap - Russell 1000")
            n.contains(" mega cap ") -> group("US mega cap")

            // ---- the rest of the US size ladder
            n.contains(" russell 2000 ") || n.contains(" small cap ") || n.contains(" smallcap ") ->
                group("US small cap")
            n.contains(" mid cap ") || n.contains(" midcap ") || n.contains(" s p 400 ") ->
                group("US mid cap")

            // ---- fixed income, by what it holds AND how long it holds it
            //
            // MATURITY IS PART OF THE DECISION (Round 66 audit, E2). SGOV holds 0-3 month
            // bills and TLT holds 20+ year bonds; both are "Treasuries" and they are not
            // remotely the same instrument - one is a cash substitute and the other is a
            // duration bet. A fund whose name does not state a band is left ungrouped rather
            // than guessed at, which is this file's standing rule.
            n.contains(" tips ") || n.contains(" inflation protected ") ->
                maturityKey(n, "Bonds - inflation protected")
            n.contains(" treasury ") || n.contains(" treasuries ") ->
                maturityKey(n, "Bonds - Treasuries")
            n.contains(" municipal ") || n.contains(" muni ") -> group("Bonds - municipal")
            n.contains(" high yield ") || n.contains(" junk ") -> group("Bonds - high yield")
            n.contains(" corporate bond ") || n.contains(" investment grade ") ->
                maturityKey(n, "Bonds - corporate")
            n.contains(" aggregate bond ") || n.contains(" total bond ") ->
                group("Bonds - US aggregate")

            // ---- commodities and cash
            //
            // BULLION IS NOT A MINER (Round 66 audit, E2). "SPDR Gold Shares" holds metal;
            // "VanEck Gold Miners" holds equities that dig it up, with operating leverage,
            // labour costs and country risk the metal does not have. They move together often
            // enough to look alike and are not one decision.
            n.contains(" miners ") || n.contains(" mining ") -> null
            n.contains(" gold ") -> group("Gold")
            n.contains(" silver ") -> group("Silver")
            n.contains(" bitcoin ") -> group("Bitcoin")
            n.contains(" ethereum ") || n.contains(" ether ") -> group("Ethereum")
            n.contains(" money market ") || n.contains(" ultra short ") ->
                group("Cash and ultra-short")

            else -> null
        }.let { if (hasTilt) null else it }
    }

    /**
     * A bond key with its maturity band, or null when the name does not state one.
     *
     * Returning null - "leave this fund alone" - is deliberate for an unstated band. A broad
     * Treasury fund and a 20-year one really are different decisions, and the cost of failing
     * to group two funds is one extra row; the cost of wrongly grouping them is a decision
     * removed from the page with a card that says they were the same.
     */
    private fun maturityKey(n: String, base: String): String? {
        val band = when {
            n.contains(" 0 3 month ") || n.contains(" 1 3 month ") ||
                n.contains(" ultra short ") || n.contains(" 0 1 year ") -> "0-3 month"
            n.contains(" 1 3 year ") || n.contains(" short term ") ||
                n.contains(" short duration ") -> "1-3 year"
            n.contains(" 3 7 year ") || n.contains(" 5 10 year ") ||
                n.contains(" intermediate ") -> "intermediate"
            n.contains(" 7 10 year ") -> "7-10 year"
            n.contains(" 10 20 year ") -> "10-20 year"
            n.contains(" 20 year ") || n.contains(" 25 year ") ||
                n.contains(" long term ") || n.contains(" extended duration ") -> "20+ year"
            else -> return null
        }
        return "$base, $band"
    }

    /**
     * Keep the best fund in each exposure group, in the order the list already had.
     *
     * [rank] is the caller's own ordering, so the winner of a group is whichever of its
     * members the score already put highest - this does not re-rank anything, it only decides
     * which rows get a place on the page. Funds with no recognised group are all kept.
     *
     * Returns each survivor with the tickers it beat, so the card can name them: someone
     * choosing between three S&P 500 funds is better served by "VOO - also IVV, SPLG" than by
     * three rows they have to notice are the same thing.
     */
    fun <T> dedupe(rows: List<T>, name: (T) -> String, symbol: (T) -> String): List<Pair<T, List<String>>> {
        val out = ArrayList<Pair<T, List<String>>>(rows.size)
        val indexOfGroup = HashMap<String, Int>()
        for (r in rows) {
            val key = keyOf(name(r))
            if (key == null) { out.add(r to emptyList()); continue }
            val at = indexOfGroup[key]
            if (at == null) {
                indexOfGroup[key] = out.size
                out.add(r to emptyList())
            } else {
                val (winner, also) = out[at]
                // Bounded: naming three alternatives is useful, naming eleven is a wall.
                if (also.size < 3) out[at] = winner to (also + symbol(r))
            }
        }
        return out
    }
}
