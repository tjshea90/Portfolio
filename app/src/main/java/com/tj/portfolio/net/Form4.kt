package com.tj.portfolio.net

import com.tj.portfolio.data.InsiderFiling
import com.tj.portfolio.data.InsiderTrade

/**
 * Reads an SEC Form 4 document into something a human can act on.
 *
 * WHY THIS EXISTS AT ALL. Until now the Insider feed was built from EDGAR's *atom listing*,
 * not from the filings. TJ's screenshot shows exactly what that is worth:
 *
 *     4  - Statement of changes in beneficial ownership of securities
 *
 * That is the whole headline, it is the same string for every Form 4 ever filed, and it says
 * nothing about who traded, which way, or how much. EDGAR used to put the reporting person's
 * name in that title; it does not any more (verified live, 5 Sept 2026), which is why the old
 * `cleanTitle` had nothing left to clean. The information the reader wants - *the CFO bought
 * 40,000 shares* - only exists inside the filing's own XML, so this file goes and reads it.
 *
 * WHY THE PARSER IS HAND-ROLLED AND NOT `android.util.Xml`. Two reasons, both practical.
 * Form 4 is a machine-generated schema with no namespaces, no attributes worth reading and no
 * mixed content, so a pull parser buys nothing here. And being pure Kotlin means the whole of
 * this file runs in a plain JVM unit test against filings captured from the live SEC service -
 * `Form4Test` does exactly that - instead of needing Robolectric to stand up a parser factory.
 *
 * WHAT THE READER ACTUALLY NEEDS TO KNOW, and where it lives in the document:
 *
 *   who      `<rptOwnerName>` plus `<reportingOwnerRelationship>` / `<officerTitle>`
 *   what     `<transactionCode>` - a single letter, and the whole point of the exercise
 *   how much `<transactionShares>`, `<transactionPricePerShare>`
 *   planned? `<aff10b5One>`, and any footnote that names Rule 10b5-1
 *
 * THE 10b5-1 FLAG IS THE REASON THIS ROUND HAPPENED. TJ asked for real purchases and sales,
 * "not automatic transactions scheduled before hand". Since the SEC's December 2022
 * amendments every Form 4 carries a checkbox saying whether the trade was made under a
 * pre-arranged Rule 10b5-1 plan, and it reaches the XML as `<aff10b5One>`. A sale under a plan
 * adopted months earlier tells you nothing about what the seller thinks today; an unplanned
 * open-market purchase is one of the few genuinely informative things an insider can do. The
 * app can only draw that line because this element exists, so it is read carefully: the
 * element is absent on older filings and on some agent-prepared ones, and in that case the
 * footnotes are searched for the rule by name, because filers who omit the checkbox almost
 * always still mention the plan in a footnote.
 */
object Form4 {

    // ------------------------------------------------------------------ codes

    /**
     * Form 4 transaction codes, from the General Instructions to Forms 3, 4 and 5.
     *
     * Only P and S are trades in the sense a reader means by the word: someone decided to buy
     * or sell on the open market. Everything else is machinery - compensation being granted,
     * options being converted, shares handed back to cover the tax on a vesting, a gift to a
     * trust - and lumping those in with real trades is exactly what makes an insider feed
     * useless. They are still parsed and still available behind the "Everything" filter,
     * because a reader who wants to see the machinery should be able to.
     */
    const val BUY = "BUY"
    const val SELL = "SELL"
    const val GRANT = "GRANT"
    const val EXERCISE = "EXERCISE"
    const val TAX = "TAX"
    const val GIFT = "GIFT"
    const val OTHER = "OTHER"

    fun actionFor(code: String): String = when (code.uppercase()) {
        "P" -> BUY
        "S" -> SELL
        "A" -> GRANT              // grant, award, or other acquisition from the issuer
        "M", "X", "C" -> EXERCISE // exercise / conversion of a derivative
        "F" -> TAX                // shares surrendered to pay tax or an exercise price
        "G" -> GIFT
        else -> OTHER             // D, I, J, K, L, U, W, Z ...
    }

    /** Plain English for the code, used in the "Everything" view and in the Claude prompt. */
    fun verbFor(action: String, disposed: Boolean): String = when (action) {
        BUY -> "bought"
        SELL -> "sold"
        GRANT -> "was granted"
        EXERCISE -> "exercised options for"
        TAX -> "surrendered"
        GIFT -> if (disposed) "gifted away" else "received as a gift"
        else -> if (disposed) "disposed of" else "acquired"
    }

    // ------------------------------------------------------------------ parse

    /**
     * Parse one filing. [body] may be the raw ownership XML or the whole `.txt` submission
     * with EDGAR's SGML wrapper around it - the wrapper is simply skipped.
     *
     * Returns null when the document is not a Form 4 ownership document at all. That case is
     * real and had to be handled: `browse-edgar?type=4` matches by PREFIX, so it happily
     * returns 424B5 prospectuses alongside the Form 4s. [Insider] filters those out by exact
     * form type before ever fetching them - a 424B5 body is half a megabyte and downloading
     * one on a phone to discover it is the wrong form would be inexcusable - but a parser that
     * silently invented a filing out of the wrong document would be worse, so it checks too.
     */
    fun parse(
        symbolHint: String,
        accession: String,
        url: String,
        filedAt: Long,
        body: String
    ): InsiderFiling? {
        val start = body.indexOf("<ownershipDocument")
        if (start < 0) return null
        val endTag = body.indexOf("</ownershipDocument>", start)
        val doc = if (endTag < 0) body.substring(start) else body.substring(start, endTag)

        // "4" for a Form 4, "4/A" for an amendment. Form 3 (initial statement) and Form 5
        // (annual catch-up) share the schema and would otherwise parse cleanly into rows that
        // look like trades and are not.
        val docType = valueOf(doc, "documentType").trim()
        if (docType != "4" && docType != "4/A") return null

        val symbol = valueOf(doc, "issuerTradingSymbol").trim().uppercase()
            .ifBlank { symbolHint.uppercase() }
        val person = personName(valueOf(doc, "rptOwnerName"))

        val isDirector = flag(doc, "isDirector")
        val isOfficer = flag(doc, "isOfficer")
        val isTenPct = flag(doc, "isTenPercentOwner")
        val officerTitle = valueOf(doc, "officerTitle").trim()
        val otherText = valueOf(doc, "otherText").trim()
        val role = roleFor(isDirector, isOfficer, isTenPct, officerTitle, otherText)

        // EITHER signal is enough. The checkbox is authoritative when present but is absent
        // on older filings and on some agent-prepared ones, and a filer who omits it almost
        // always still names the plan in a footnote - so the footnotes are read regardless
        // rather than only as a fallback.
        val footnotes = sectionOf(doc, "footnotes").orEmpty()
        val planned = isTrue(valueOf(doc, "aff10b5One")) || mentionsPlan(footnotes)

        val trades = ArrayList<InsiderTrade>()
        collect(doc, "nonDerivativeTransaction", false, trades)
        collect(doc, "derivativeTransaction", true, trades)
        if (trades.isEmpty()) return null

        val tradeDate = trades.mapNotNull { it.date.takeIf { d -> d > 0 } }.maxOrNull() ?: filedAt
        val sharesAfter = trades.lastOrNull { !it.derivative }?.sharesAfter ?: 0.0

        return InsiderFiling(
            symbol = symbol,
            accession = accession,
            filedAt = filedAt,
            tradeDate = tradeDate,
            person = person,
            role = role,
            planned = planned,
            amended = docType == "4/A",
            sharesAfter = sharesAfter,
            url = url,
            trades = trades
        )
    }

    /**
     * Pull every `<...Transaction>` block out of a table.
     *
     * A single Form 4 routinely reports several lines - an option exercise and the sale that
     * followed it, or one sale broken into four price bands because the broker filled it in
     * pieces. They are kept as separate trades and aggregated later by
     * [InsiderFiling.headlineTrade], which is where the weighted-average price is worked out.
     * Aggregating here instead would have thrown away the codes, and the codes are the part
     * that says whether any of it was a real trade.
     */
    private fun collect(
        doc: String,
        tag: String,
        derivative: Boolean,
        into: MutableList<InsiderTrade>
    ) {
        var from = 0
        while (true) {
            val b = blockRange(doc, tag, from) ?: return
            val t = doc.substring(b.first, b.second)
            from = b.second
            val code = valueOf(t, "transactionCode").trim().uppercase()
            if (code.isBlank()) continue
            val shares = valueOf(t, "transactionShares").toDoubleOrNull() ?: 0.0
            val price = valueOf(t, "transactionPricePerShare").toDoubleOrNull() ?: 0.0
            val ad = valueOf(t, "transactionAcquiredDisposedCode").trim().uppercase()
            into.add(
                InsiderTrade(
                    code = code,
                    action = actionFor(code),
                    security = securityName(valueOf(t, "securityTitle")),
                    disposed = ad == "D",
                    shares = shares,
                    price = price,
                    date = parseDay(valueOf(t, "transactionDate")),
                    sharesAfter = valueOf(t, "sharesOwnedFollowingTransaction")
                        .toDoubleOrNull() ?: 0.0,
                    derivative = derivative
                )
            )
        }
    }

    /**
     * What was actually traded, when it is worth naming.
     *
     * Ordinary shares are the overwhelming majority and the reader already knows what a share
     * is, so "Common Stock" is normalised away and the headline just says "shares". A
     * derivative line is different - "Director was granted 1,594 shares" is wrong when the
     * filing says Restricted Stock Units, and the distinction between units that vest later
     * and stock held today is exactly what someone reading an insider feed is trying to see.
     */
    fun securityName(raw: String): String {
        // Everything after the first comma is legal boilerplate, and it is long: Amazon files
        // its ordinary shares as "Common Stock, par value $.01 per share", which read on
        // screen as "sold 3,741 Common Stock, par value $.01 per share".
        val head = raw.trim().replace(WHITESPACE, " ").substringBefore(',').trim()
        if (head.isBlank()) return ""
        // The MATCH RANGE, not indexOf. Looking up the class letter by value finds the "a"
        // inside the word "Class" itself, so "Class A Common Stock" was left with the
        // remainder "ss a common stock" and came out unnormalised.
        val m = CLASS_PREFIX.find(head)
        val cls = m?.groupValues?.get(1)?.uppercase()
        val rest = (if (m != null) head.substring(m.range.last + 1) else head)
            .trim().lowercase()
        if (rest in PLAIN_STOCK) {
            // A share class is worth keeping - Alphabet's A and C are different securities
            // with different votes - but the words around it are not.
            return if (cls != null) "Class $cls shares" else ""
        }
        return head.take(40)
    }

    private val CLASS_PREFIX = Regex("^class\\s+([A-Za-z0-9]+)\\b", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

    private val PLAIN_STOCK = setOf(
        "", "common", "stock", "shares", "common stock", "capital stock", "common shares",
        "ordinary shares", "ordinary stock", "common share", "voting common stock"
    )

    // ------------------------------------------------------------------ role

    /**
     * The one line that answers "who is this?".
     *
     * An officer's own title is by far the most useful thing available - "CEO", "CFO",
     * "SVP, General Counsel" - so it wins when there is one. Plenty of filings tick the
     * officer box and leave the title blank, and a great many insiders are directors and
     * nothing else, so both fall back rather than producing an empty badge.
     */
    fun roleFor(
        director: Boolean,
        officer: Boolean,
        tenPercent: Boolean,
        officerTitle: String,
        otherText: String
    ): String {
        val title = tidyTitle(officerTitle)
        // "See Remarks" is a filer's placeholder, not a job. Printing it as one produced
        // "See Remarks sold 492,348 Class A shares" on a live Palantir filing.
        if (officer && title.isNotBlank() && !title.equals("see remarks", true)) return title
        if (officer) return "Officer"
        if (director) return "Director"
        if (tenPercent) return "10% owner"
        if (otherText.isNotBlank()) return tidyTitle(otherText)
        return "Insider"
    }

    /**
     * Titles arrive shouted, abbreviated and occasionally with a stray "and" hanging off the
     * end where a filer's template joined two fields. Expanded a little and case-corrected so
     * a headline reads as a sentence, but deliberately NOT rewritten - "SVP, GC and Government
     * Affairs" is that person's actual title and inventing a tidier one would be a lie.
     */
    private fun tidyTitle(raw: String): String {
        val t = raw.trim().trimEnd(',', ';', '-', ' ')
        if (t.isBlank()) return ""
        // Mixed case already - the filer wrote it as they wanted it read. Left alone.
        if (t.any { it.isLowerCase() }) return t.take(48)
        return t.split(' ').joinToString(" ") { w -> softenWord(w) }.take(48)
    }

    /**
     * Take the shout out of an ALL-CAPS title WITHOUT destroying the abbreviations in it.
     *
     * A blanket title-case turns "CEO" into "Ceo" and "EVP & CSO" into "Evp & Cso", which is
     * how the first version of this read on a live sample - a chief executive's own job title,
     * mangled, on the most interesting row in the feed. An acronym whitelist alone does not
     * save it either: filers use house abbreviations nobody has ever seen ("EVP, GBUL, SIPS"
     * is a real SoFi title), and any of them would come out as "Gbul".
     *
     * So the rule is structural rather than a list. A short all-capital word is an
     * abbreviation and stays; a long one is an ordinary word being shouted and is softened.
     * The connectives are named because they are short but not abbreviations.
     */
    private fun softenWord(word: String): String {
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty()) return word
        if (letters.lowercase() in CONNECTIVES) return word.lowercase()
        if (letters.length <= 4) return word            // CEO, EVP, GC, GBUL, SIPS
        return word[0].uppercase() + word.substring(1).lowercase()
    }

    private val CONNECTIVES = setOf("and", "the", "of", "for", "to", "in", "at", "a", "or")

    /**
     * EDGAR files a person as "LAST FIRST MIDDLE" - "COXE TENCH", "TAN LIP BU",
     * "Radakovich Lynn Vojvodich". Moving the first token to the end turns all three into the
     * name the person actually goes by.
     *
     * NOT APPLIED TO ENTITIES. A ten-percent holder is often a fund or a partnership, and
     * "Vanguard Group Inc" must not become "Group Inc Vanguard". Anything carrying a
     * corporate-form word, or a single token, is left exactly as filed - the cost of being
     * wrong is a name printed backwards on screen, so the guard is deliberately generous.
     */
    fun personName(raw: String): String {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isBlank()) return ""
        val parts = t.split(' ')
        val cased = if (t.none { it.isLowerCase() }) titleCase(t) else t
        if (parts.size < 2 || parts.size > 4) return cased
        val lower = t.lowercase()
        if (ENTITY_WORDS.any { w -> lower.split(Regex("[^a-z0-9]+")).contains(w) }) return cased
        val c = cased.split(' ')
        return (c.drop(1) + c.first()).joinToString(" ")
    }

    private val ENTITY_WORDS = setOf(
        "inc", "corp", "corporation", "co", "company", "llc", "lp", "llp", "plc", "ltd",
        "limited", "trust", "fund", "funds", "partners", "partnership", "capital", "group",
        "holdings", "holding", "management", "advisors", "advisers", "associates", "ventures",
        "sa", "nv", "ag", "gmbh", "se", "bv", "plc", "foundation", "bank", "investments",
        "securities", "asset", "family", "trustee", "estate", "gp", "lllp"
    )

    /**
     * Generational suffixes stay shouted; everything else is title-cased.
     *
     * An earlier version kept every two-letter word as filed, on the theory that they were
     * initials and suffixes. Intel's chief executive is filed as "TAN LIP BU" and came out as
     * "Lip BU Tan" - a real person's name, printed wrong, on the most interesting row in the
     * feed. Two letters is not evidence of an abbreviation.
     */
    private val NAME_SUFFIXES = setOf("II", "III", "IV", "V", "VI")

    private fun titleCase(s: String): String = s.split(' ').joinToString(" ") { w ->
        when {
            w.isEmpty() -> w
            w.uppercase() in NAME_SUFFIXES -> w.uppercase()
            else -> w[0].uppercase() + w.substring(1).lowercase()
        }
    }

    // ------------------------------------------------------------- tiny scanner

    /**
     * Range of the content between `<tag ...>` and `</tag>`, searching from [from].
     *
     * Self-closing tags return an empty range rather than running off to the end of the
     * document, which is what a naive `indexOf("</tag>")` does when the closing tag is absent:
     * `<footnoteId id="F1"/>` and `<derivativeTable/>` both appear in real filings.
     */
    private fun blockRange(src: String, tag: String, from: Int): Pair<Int, Int>? {
        var i = from
        while (true) {
            val open = src.indexOf("<$tag", i)
            if (open < 0) return null
            val close = src.indexOf('>', open)
            if (close < 0) return null
            // "<transactionShares>" must not match a search for "<transactionShare"
            val nameEnd = src[open + tag.length + 1]
            if (!(nameEnd == '>' || nameEnd == ' ' || nameEnd == '/' || nameEnd == '\n' ||
                    nameEnd == '\r' || nameEnd == '\t')
            ) { i = open + 1; continue }
            if (src[close - 1] == '/') return (close + 1) to (close + 1)  // self-closing
            val end = src.indexOf("</$tag>", close)
            return if (end < 0) null else (close + 1) to end
        }
    }

    private fun sectionOf(src: String, tag: String): String? =
        blockRange(src, tag, 0)?.let { src.substring(it.first, it.second) }

    /**
     * The text of [tag], unwrapping the `<value>` box the schema puts numbers in.
     *
     * Form 4 wraps nearly every leaf in `<value>` so a footnote can be attached to it:
     * `<transactionShares><value>500000</value></transactionShares>`. Callers only ever want
     * the number, so this returns it either way and they never have to care which shape a
     * particular element uses.
     */
    fun valueOf(src: String, tag: String): String {
        val r = blockRange(src, tag, 0) ?: return ""
        val inner = src.substring(r.first, r.second)
        val v = blockRange(inner, "value", 0)
        val text = if (v != null) inner.substring(v.first, v.second) else inner
        val out = text.trim()
        // A LEAF VALUE NEVER CONTAINS MARKUP, and this guard is not theoretical. Filers are
        // allowed to attach a footnote to an element INSTEAD of giving it a value:
        //
        //     <transactionPricePerShare><footnoteId id="F1"/></transactionPricePerShare>
        //
        // is a real shape, used when the price is explained in prose rather than stated -
        // roughly one filing in ten across a live 110-filing sample. Without this the
        // fallback branch returns the footnote element itself as the "value". A number
        // survives that by parsing to null and defaulting to zero, but a TEXT field does not:
        // an officer title or a security name would have carried raw XML onto the screen.
        if (out.startsWith("<") || out.contains("</")) return ""
        return unescape(out)
    }

    private fun flag(src: String, tag: String): Boolean = isTrue(valueOf(src, tag))

    /** Filers write these as `1`/`0` or as `true`/`false`; both shapes are in live filings. */
    private fun isTrue(s: String): Boolean {
        val t = s.trim().lowercase()
        return t == "1" || t == "true" || t == "y" || t == "yes"
    }

    /**
     * Rule 10b5-1 named in a footnote, for filings whose `<aff10b5One>` element is missing.
     * Written every way filers write it, including the plain "trading plan" phrasing that
     * predates the checkbox.
     */
    private fun mentionsPlan(footnotes: String): Boolean {
        val f = footnotes.lowercase()
        return f.contains("10b5-1") || f.contains("10b5.1") || f.contains("10b51") ||
            f.contains("rule 10b-5.1") || f.contains("trading plan")
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&amp;", "&")

    /** `yyyy-MM-dd` at UTC noon, so a timezone shift can never move it onto another day. */
    fun parseDay(s: String): Long {
        val t = s.trim()
        if (t.length < 10) return 0L
        val y = t.substring(0, 4).toIntOrNull() ?: return 0L
        val m = t.substring(5, 7).toIntOrNull() ?: return 0L
        val d = t.substring(8, 10).toIntOrNull() ?: return 0L
        return runCatching {
            val c = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
            c.clear(); c.set(y, m - 1, d, 12, 0, 0)
            c.timeInMillis
        }.getOrDefault(0L)
    }
}
