package com.tj.portfolio.net

/**
 * Does this headline actually concern this stock?
 *
 * WHY THIS FILE EXISTS. TJ reported that tapping News on FIVE (Five Below) returned stories
 * that merely contain the English word "five". Reproduced against the live feed on
 * 4 September 2026 and it is worse than it sounds - the app asked Google News for
 *
 *     "Five Below, Inc." OR FIVE stock
 *
 * expecting the OR to bind the two alternatives. Google does not read it that way; it
 * matched documents containing "five" and "stock", which is a template simplywall.st and
 * Yahoo Finance publish many times a day:
 *
 *     Axsome Therapeutics (AXSM) Stock Looks Like A Bargain On Its 7x FIVE YEAR Run
 *     Upstart (UPST) Stock Looks Expensive Following a 90% FIVE YEAR Slump
 *     Charter (CHTR) Stock Still Looks Undervalued After Its 82% FIVE YEAR Fall
 *
 * Measured 15 of 31 headlines relevant. The query was also unstable - an identical repeat
 * two minutes later returned zero items.
 *
 * The query itself is fixed in [News.googleRss]. This file is the second half: a filter over
 * what comes back, because a search engine is a black box that can change its mind, and
 * because the same mistake existed a SECOND time in the Feed's "My stocks" filter, which
 * uppercased the whole headline before looking for a ticker and so read "Five Year Run" as
 * the FIVE holding. Both callers now share this one implementation, so the rule cannot drift
 * apart in two places again.
 *
 * THE TRAP, WRITTEN DOWN SO IT IS NOT REINTRODUCED. The tempting fix - "match the first word
 * of the company name" - brings the bug straight back, because "Five" leads both
 * "Five Below" and "Five Year Run". [AMBIGUOUS_NAME_WORDS] is what stops that: a company
 * whose distinctive word is ordinary English cannot be identified from that word alone and
 * must be named in full or carry its ticker.
 *
 * Scored 40/40 on a case set built from the real polluted headlines plus every trap found
 * while designing it - see `RelevanceTest`.
 */
object Relevance {

    /** Corporate-form words that carry no identifying information. */
    private val LEGAL_SUFFIXES = setOf(
        "inc", "corp", "corporation", "co", "company", "ltd", "limited", "plc", "llc",
        "lp", "llp", "sa", "nv", "ag", "se", "gmbh", "the", "group", "holdings", "holding",
        "international", "industries", "enterprises", "class", "common", "stock", "shares",
        "trust", "reit", "fund", "etf", "technologies", "systems", "solutions", "motor",
        "motors", "labs", "laboratories", "partners", "brands", "worldwide", "global"
    )

    /**
     * Words that are simultaneously a common company name-word AND ordinary English or
     * everyday finance vocabulary. A headline containing one of these, capitalised, is not
     * evidence of anything: "Five Year Run", "raises its price Target", "the valuation Gap",
     * "a Block trade", "at the Open".
     *
     * A holding whose leading word is on this list is matched ONLY by its full name or by
     * its ticker in upper case. That is a deliberate trade: slightly fewer headlines for
     * about a dozen companies, in exchange for the reported bug being impossible.
     *
     * Numerals lead the list because they are the case TJ actually hit.
     */
    private val AMBIGUOUS_NAME_WORDS = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "first", "second", "third", "next", "new", "old", "big", "best",
        "top", "prime", "core", "key", "target", "gap", "open", "close", "block", "square",
        "match", "range", "edge", "peak", "summit", "value", "growth", "capital", "alpha",
        "beta", "delta", "focus", "direct", "express", "advance", "general", "national",
        "american", "united", "world", "state", "states", "union", "sun", "star", "moon",
        "snow", "rain", "storm", "field", "river", "lake", "hill", "stone", "rock", "gold",
        "silver", "iron", "steel", "carbon", "energy", "power", "light", "fast", "free",
        "live", "real", "true", "good", "great", "point", "line", "wave", "bank", "index",
        "market", "trade", "deal", "share", "future", "public", "premier", "select", "unity"
    )

    /**
     * COMPILED ONCE. This was a `Regex(...)` literal inside [coreWords], so a fresh
     * `Pattern.compile` ran on every call - and [coreWords] is called from [matches], which
     * the research build invokes in a nested loop over ~500 headlines x ~450 candidate
     * symbols. That is 200,000-odd pattern compilations per build, for one pattern that never
     * changes. The same fix was applied to `News.normaliseTitle` in v6.3 and is documented
     * there; this call site was missed.
     */
    private val WORD_SPLIT = Regex("[^A-Za-z0-9]+")

    /** Splits a company name into the words that actually identify it. */
    fun coreWords(company: String): List<String> =
        company.split(WORD_SPLIT)
            .filter { it.isNotBlank() && it.lowercase() !in LEGAL_SUFFIXES }

    /** Word-boundary match that does not treat a dot as a boundary, so "MOG.A" != "A". */
    private fun containsToken(hay: String, token: String, ignoreCase: Boolean): Int {
        if (token.isEmpty()) return -1
        var from = 0
        while (from <= hay.length - token.length) {
            val i = hay.indexOf(token, from, ignoreCase)
            if (i < 0) return -1
            val before = if (i == 0) ' ' else hay[i - 1]
            val afterIdx = i + token.length
            val after = if (afterIdx >= hay.length) ' ' else hay[afterIdx]
            val okBefore = !before.isLetterOrDigit() && before != '.'
            val okAfter = !after.isLetterOrDigit() && after != '.'
            if (okBefore && okAfter) return i
            from = i + 1
        }
        return -1
    }

    /** Letters-and-digits-only lowercase form, for comparing a multi-word name as a phrase. */
    private fun squash(s: String): String {
        val sb = StringBuilder(s.length)
        var lastSpace = true
        for (c in s.lowercase()) {
            if (c.isLetterOrDigit()) { sb.append(c); lastSpace = false }
            else if (!lastSpace) { sb.append(' '); lastSpace = true }
        }
        return sb.toString().trim()
    }

    /**
     * A headline written entirely in capitals tells us nothing by containing an
     * upper-case ticker - every word in it is upper case. Short strings are exempt because
     * "FIVE, WOOF Stocks Rise" is a real headline that begins with two tickers.
     */
    private fun isShouting(text: String): Boolean {
        var letters = 0
        var upper = 0
        for (c in text) if (c.isLetter()) { letters++; if (c.isUpperCase()) upper++ }
        return letters > 12 && upper == letters
    }

    /**
     * True when [title] (plus optional [summary]) is about [symbol] / [company].
     *
     * Three independent ways to qualify, in order of strength:
     *  1. the company's full distinctive name as a phrase - "five below", "ford motor";
     *  2. its leading distinctive word, capitalised, when that word is not ordinary
     *     English - so "Nvidia earnings beat" and "Ford recalls trucks" match while
     *     "apple orchards" and "Five Year Run" do not;
     *  3. the ticker as a standalone UPPER-CASE token - "(FIVE)", "NASDAQ: FIVE", "$FIVE",
     *     or its appearance in an earnings-list headline.
     */
    fun matches(title: String, summary: String, symbol: String, company: String): Boolean =
        matches(Subject.of(symbol, company), title, summary)

    /**
     * ONE COMPANY'S IDENTITY, WORKED OUT ONCE.
     *
     * [matches] used to derive all of this on every call: split the company name with a
     * freshly compiled regex, filter the legal suffixes, join a phrase, uppercase the ticker.
     * That is fine for a per-symbol news feed. It is not fine in `Research.build`, which asks
     * "is this headline about this company?" for every one of ~500 market headlines against
     * every one of ~450 screener symbols - so all of it ran a couple of hundred thousand
     * times per research build, always with the same answer for a given symbol.
     *
     * Hoisting it out costs one small object per symbol and removes essentially all of the
     * work. The single-shot [matches] above still exists and behaves identically, for the
     * dozen call sites that genuinely have one symbol and one headline.
     */
    class Subject(
        val symbol: String,
        val phrase: String?,
        val lead: String?,
        val oneLetterMarks: List<String>
    ) {
        companion object {
            fun of(symbol: String, company: String): Subject {
                val sym = symbol.trim().uppercase()
                val core = coreWords(company)
                val phrase =
                    if (core.size >= 2) core.joinToString(" ") { it.lowercase() } else null
                val lead = core.firstOrNull()
                    ?.takeIf { it.length >= 3 && it.lowercase() !in AMBIGUOUS_NAME_WORDS }
                val marks = if (sym.length == 1)
                    listOf("($sym)", "($sym ", ": $sym", ":$sym", "\$$sym") else emptyList()
                return Subject(sym, phrase, lead, marks)
            }
        }
    }

    /**
     * The same three rules as the single-shot [matches], against a prepared [Subject].
     *
     * [squash] is still computed here rather than by the caller, because it depends on the
     * HEADLINE, not the symbol - callers that loop over symbols for one headline should hoist
     * it themselves via [squashed]; `Research.build` does exactly that.
     */
    fun matches(s: Subject, title: String, summary: String, squashedHay: String? = null): Boolean {
        val hay = (title.trim() + " " + summary.trim()).trim()
        if (hay.isEmpty()) return false
        val sym = s.symbol

        // 1. full company phrase
        if (s.phrase != null) {
            if ((squashedHay ?: squash(hay)).contains(s.phrase)) return true
        }

        // 2. leading distinctive word, capitalised, not ordinary English
        val lead = s.lead
        if (lead != null) {
            val at = containsToken(hay, lead, ignoreCase = true)
            if (at >= 0 && hay[at].isUpperCase()) return true
        }

        // 3. the ticker in upper case
        if (sym.length >= 2 && !isShouting(hay)) {
            if (containsToken(hay, sym, ignoreCase = false) >= 0) return true
        }
        // A one-letter ticker is only meaningful next to an explicit ticker marker.
        for (mark in s.oneLetterMarks) {
            if (hay.contains(mark)) return true
        }
        return false
    }

    /**
     * The squashed form of a headline, for callers that test one headline against many
     * symbols. Passing it into [matches] turns an O(headlines x symbols) rebuild of the same
     * string into one per headline.
     */
    fun squashed(title: String, summary: String): String =
        squash((title.trim() + " " + summary.trim()).trim())

    /**
     * Keep only the headlines that concern this stock - but NEVER return nothing.
     *
     * The safety valve is the important part. This filter runs against a live search engine
     * whose behaviour is not ours to control, and a stock's news tab going permanently empty
     * because a heuristic got strict is a worse failure than a little noise. If every
     * candidate is rejected, the caller gets the unfiltered list back and the reader can
     * judge for themselves.
     */
    fun <T> keepRelevant(
        items: List<T>,
        symbol: String,
        company: String,
        title: (T) -> String,
        summary: (T) -> String = { "" }
    ): List<T> {
        if (items.isEmpty()) return items
        val kept = items.filter { matches(title(it), summary(it), symbol, company) }
        return if (kept.isEmpty()) items else kept
    }

    /**
     * Which of [owned] a market-wide headline is about, or null.
     *
     * This replaces the Feed's old matcher, which did
     * `title.uppercase().split(...)` and then looked for the ticker - so EVERY word in the
     * headline was upper case by the time it was compared and "Five Year Run" matched the
     * FIVE holding. Matching is now done against the headline as written.
     *
     * [names] maps a symbol to its company name, so the same three rules as [matches] apply.
     * A headline that could be about two holdings returns null rather than guessing.
     */
    fun matchHolding(title: String, owned: Set<String>, names: Map<String, String>): String? {
        if (title.isBlank() || owned.isEmpty()) return null
        var hit: String? = null
        for (sym in owned) {
            if (matches(title, "", sym, names[sym].orEmpty())) {
                if (hit != null && hit != sym) return null   // ambiguous - claim neither
                hit = sym
            }
        }
        return hit
    }
}
