package com.tj.portfolio.data

object TxnType {
    const val BUY = "BUY"
    const val SELL = "SELL"
    const val DEPOSIT = "DEPOSIT"
    const val WITHDRAWAL = "WITHDRAWAL"
    const val DIVIDEND = "DIVIDEND"
    const val INTEREST = "INTEREST"
    const val FEE = "FEE"
    val ALL = listOf(BUY, SELL, DEPOSIT, WITHDRAWAL, DIVIDEND, INTEREST, FEE)
    val CASH_ONLY = setOf(DEPOSIT, WITHDRAWAL, INTEREST, FEE)
}

/**
 * A single ledger entry. [amount] is the signed cash effect on buying power:
 * negative for BUY / WITHDRAWAL / FEE, positive for SELL / DEPOSIT / DIVIDEND.
 */
data class Txn(
    val id: Long = 0L,
    val type: String,
    val symbol: String? = null,
    val quantity: Double = 0.0,
    val price: Double = 0.0,
    val amount: Double = 0.0,
    val fees: Double = 0.0,
    val date: Long = 0L,
    val note: String? = null,
    val source: String = "MANUAL"
) {
    companion object {
        /**
         * Per-share price for a trade that gives a TOTAL but no price per share.
         *
         * The total on a statement - and the total a user types in from one - is the NET
         * cash that moved, which already has the fee inside it. [cashEffect] then treats
         * `quantity * price` as the GROSS and subtracts the fee again, so dividing the net
         * total by the share count charges the fee twice.
         *
         * Worked through with the real numbers: 100 shares of a sub-$2 stock bought for a
         * net debit of $155.95 with Ally's $5.95 low-priced commission on it. Deriving
         * 155.95/100 recorded a cash effect of -$161.90 - six dollars that never left the
         * account - and a cost basis $5.95 too high. On the sell side the same shape
         * understated the proceeds by the fee. Removing the fee first gives $1.50/share,
         * a cash effect of exactly the -$155.95 the user entered, and the true basis.
         *
         * This is the same convention `Ledger.unitPrice` uses when it recovers a price from
         * a stored row, so a trade entered by total and one recovered from the ledger agree.
         * On an ordinary Ally trade the fee is zero and this is byte-identical to the old
         * `amount / quantity`.
         */
        fun unitPriceFromTotal(type: String, quantity: Double, total: Double, fees: Double): Double {
            if (quantity < 1e-9) return 0.0
            val net = kotlin.math.abs(total)
            val gross = when (type) {
                TxnType.BUY -> net - fees
                TxnType.SELL -> net + fees
                else -> net
            }
            return (gross / quantity).coerceAtLeast(0.0)
        }

        /** Derive the signed cash effect from the trade fields. */
        fun cashEffect(type: String, quantity: Double, price: Double, amount: Double, fees: Double): Double {
            val gross = quantity * price
            return when (type) {
                TxnType.BUY -> -(if (gross > 0) gross else kotlin.math.abs(amount)) - fees
                TxnType.SELL -> (if (gross > 0) gross else kotlin.math.abs(amount)) - fees
                TxnType.DEPOSIT, TxnType.DIVIDEND, TxnType.INTEREST -> kotlin.math.abs(amount)
                TxnType.WITHDRAWAL, TxnType.FEE -> -kotlin.math.abs(amount)
                else -> amount
            }
        }
    }
}

/** Cached market data for one symbol. */
data class Quote(
    val symbol: String,
    val name: String = "",
    val price: Double = 0.0,
    val prevClose: Double = 0.0,
    val dayHigh: Double = 0.0,
    val dayLow: Double = 0.0,
    val extPrice: Double? = null,
    val extLabel: String? = null,
    val marketState: String = "",
    val spark: List<Double> = emptyList(),
    val currency: String = "USD",
    /** Exchange timestamp of the last regular-session print, when the feed supplies it. */
    val quoteTime: Long = 0L,
    val updated: Long = 0L,
    val stale: Boolean = false
) {
    val dayChange: Double get() = if (prevClose > 0) price - prevClose else 0.0
    val dayChangePct: Double get() = if (prevClose > 0) (price - prevClose) / prevClose * 100.0 else 0.0
    val extChange: Double get() = if (extPrice != null && price > 0) extPrice - price else 0.0
    val extChangePct: Double get() = if (extPrice != null && price > 0) (extPrice - price) / price * 100.0 else 0.0
}

data class NewsItem(
    val symbol: String,
    val title: String,
    val url: String,
    val source: String,
    val published: Long,
    val summary: String = ""
)

data class StockRating(
    val symbol: String,
    val rating: Int,
    val action: String,
    val reasoning: String,
    val target: String = ""
)

data class Advice(
    val summary: String = "",
    /** Set when the request failed; the UI shows this instead of treating it as analysis. */
    val error: String? = null,
    val actions: List<String> = emptyList(),
    val stocks: List<StockRating> = emptyList(),
    val risks: String = "",
    val generated: Long = 0L,
    val raw: String = ""
)

data class Override(
    val symbol: String,
    val avgCost: Double? = null,
    val shares: Double? = null
)

/** One row in the live Feed tab: a headline, an insider filing, or a filing summary. */
data class FeedItem(
    val kind: String,
    val symbol: String,
    val title: String,
    val detail: String = "",
    val url: String = "",
    val source: String = "",
    val published: Long = 0L,
    /** True when the symbol is one the user actually holds. */
    val owned: Boolean = false,
    /**
     * A publisher-assigned unique identifier when the source supplies one - for SEC filings
     * this is the EDGAR accession number, which is unique across the whole system.
     *
     * WHY IT EXISTS: the fallback identity below is built from kind + symbol + timestamp +
     * title, and EDGAR's atom feed dates a filing to the DAY, not the second. Two Form 4s
     * filed by the same insider on the same day therefore produced a byte-identical id, and
     * a LazyColumn handed two rows with the same key throws - which is exactly what killed
     * the app a few seconds after opening a stock's news (the filings land after the
     * headlines because EDGAR is the slower request). An accession number cannot collide.
     */
    val uid: String = ""
) {
    /**
     * Stable identity, used BOTH for de-duplication and as the LazyColumn key. They must
     * be the same expression: if dedupe kept two rows that the key considered identical,
     * Compose would throw "key was already used".
     */
    val id: String get() =
        if (uid.isNotBlank()) "$kind|$uid"
        else "$kind|$symbol|$published|${title.lowercase().take(80)}"

    companion object {
        const val NEWS = "NEWS"
        const val INSIDER = "INSIDER"
        /** Market-wide headline, not tied to a symbol the user follows. */
        const val MARKET = "MARKET"
    }
}

/** A ticker currently being talked about on r/wallstreetbets. */
data class Trending(
    val symbol: String,
    val rank: Int = 0,
    val mentions: Int = 0,
    val comments: Int = 0,
    val mentions24hAgo: Int = 0,
    val rank24hAgo: Int = 0,
    val upvotes: Int = 0,
    /**
     * ApeWisdom's own CURRENT rank. [rank24hAgo] is ApeWisdom's too, so the momentum figure
     * has to be computed against this and not against [rank], which on a merged row is
     * Tradestie's ordering. Comparing the two invented movement that never happened - a
     * ticker sitting at #3 on Tradestie and #15 on both ApeWisdom lists read as "up 12".
     */
    val apeRank: Int = 0,
    val sentiment: String = "",
    val sentimentScore: Double = 0.0,
    val source: String = ""
) {
    /** Positive when a ticker is climbing the board (rank 40 -> 3 is a big move). */
    val rankDelta: Int get() {
        val nowRank = if (apeRank > 0) apeRank else rank
        return if (rank24hAgo > 0 && nowRank > 0) rank24hAgo - nowRank else 0
    }

    val mentionDelta: Int get() = if (mentions24hAgo > 0) mentions - mentions24hAgo else 0

    val activity: Int get() = if (mentions > 0) mentions else comments
}

/**
 * WHETHER A PROFIT-AND-LOSS FIGURE LEADS WITH DOLLARS OR WITH A PERCENTAGE.
 *
 * Round 61. Every P/L on screen already showed BOTH numbers - a bold dollar figure with the
 * percentage under or beside it - so this does not add or remove information. It swaps which
 * of the two is the big one, which is the thing other stock apps let you tap, and which of
 * the two you want depends entirely on the question you are asking: "how much did I make
 * today" is a dollar question, "which of these is actually performing" is a percentage one.
 *
 * A DISPLAY PREFERENCE, NOT DERIVED DATA. It is stored in settings, it survives a restart,
 * and it is carried in the JSON backup for the same reason the cost method and the sort order
 * are: restoring onto a new phone should give back the app the user had set up, not the
 * defaults.
 */
enum class PlMode {
    /** The dollar figure leads. What the app has always done, and the default. */
    DOLLAR,

    /** The percentage leads. */
    PERCENT;

    val flipped: PlMode get() = if (this == DOLLAR) PERCENT else DOLLAR

    companion object {
        /** Total: an unreadable stored value falls back to the default rather than throwing. */
        fun byName(s: String?): PlMode =
            entries.firstOrNull { it.name.equals(s, true) } ?: DOLLAR
    }
}
