package com.tj.portfolio.net

/**
 * WHAT A FILE SHARED INTO THE APP IS, DECIDED BY WHAT IS INSIDE IT (2026-09-23b).
 *
 * Tj's round trip: "Make prompt file" opens the share sheet -> he picks a new Claude chat ->
 * Claude writes an answer file -> he taps it, Share -> Portfolio -> the app imports it and
 * "already knows how to use the data just from the share". So nothing about the share itself
 * says which of the four prompts it answers - no button was pressed on this side - and the
 * file name cannot be trusted either (the Claude app, a download manager or Tj himself may
 * rename it, and a share of the chat TEXT has no name at all).
 *
 * The content can be trusted, because every answer already carries its own payload key and
 * the "Import answer" buttons already route on it: [DayTradingBridge.looksLikeDayTrading] and
 * [ResearchBridge.looksLikeResearch] first, everything else to [ClaudeBridge.parse], which
 * holds advice and transactions. This is the same order [com.tj.portfolio.ui.PortfolioViewModel.importClaudeFile]
 * uses, pulled out as a pure function so the routing can be tested without an Activity.
 *
 * The one thing checked BEFORE the payload keys is the prompt marker: a prompt file carries
 * the answer SCHEMA, key names included, and sharing the question back instead of the answer
 * is the mistake most likely to happen (the prompt file is the one that was just shared out).
 */
object SharedAnswer {

    enum class Kind {
        /** Nothing at all, or only whitespace. */
        EMPTY,

        /** Binary content - an image, a PDF, a zip - shared by mistake. */
        NOT_TEXT,

        /** One of this app's own prompt files: the question, not the answer. */
        PROMPT_FILE,

        /** A Day Trading answer ([DayTradingBridge]). */
        DAY_TRADING,

        /** A Trending / Best / ETFs answer ([ResearchBridge]). */
        RESEARCH,

        /** Anything else textual - advice and/or transactions, or junk [ClaudeBridge.parse] will reject. */
        CLAUDE
    }

    /**
     * The most characters a shared answer may have. A real answer is a few KB to a few hundred;
     * this is generous headroom that still stops a multi-megabyte log or a mis-shared export
     * from being parsed on the main thread by the importers.
     */
    const val MAX_CHARS = 2_000_000

    fun classify(text: String?): Kind {
        if (text == null || text.isBlank()) return Kind.EMPTY
        if (!looksLikeText(text)) return Kind.NOT_TEXT
        if (ClaudeBridge.isPromptFile(text)) return Kind.PROMPT_FILE
        if (DayTradingBridge.looksLikeDayTrading(text)) return Kind.DAY_TRADING
        if (ResearchBridge.looksLikeResearch(text)) return Kind.RESEARCH
        return Kind.CLAUDE
    }

    /**
     * Text, not bytes. A decoded binary file is full of NULs and U+FFFD replacement characters;
     * real Markdown/JSON has essentially none. Sampled from the start so a huge file costs
     * nothing extra, with a small allowance so one stray character in a real answer is not
     * enough to reject it.
     */
    fun looksLikeText(text: String): Boolean {
        val n = minOf(text.length, 4096)
        if (n == 0) return false
        var bad = 0
        for (i in 0 until n) {
            val c = text[i]
            if (c == '\u0000' || c == '�' ||
                (c < ' ' && c != '\n' && c != '\r' && c != '\t' && c != '\u000C')
            ) bad++
        }
        return bad * 100 <= n // at most 1%
    }

    /** The plain-language reply for a share that is not something the app can import. */
    fun rejection(kind: Kind): String? = when (kind) {
        Kind.EMPTY -> "That share was empty - nothing to import."
        Kind.NOT_TEXT ->
            "That isn't a text file. Share the .md answer file Claude wrote, not an image or PDF."
        Kind.PROMPT_FILE ->
            "That is the prompt file this app wrote - the question, not Claude's answer. Share " +
                "it to a Claude chat, then share the file Claude writes back here."
        else -> null
    }
}
