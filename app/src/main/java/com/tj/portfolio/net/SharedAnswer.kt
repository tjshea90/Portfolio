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

        /**
         * One of this app's own BACKUP files (full test 2026-09-24, A-1). Its root holds a
         * `transactions` array, so it used to fall through to [CLAUDE] and be imported as an
         * answer: every row re-dated to today (backup dates are epoch numbers) and marked
         * "date estimated", splits dropped, cash rows offered as new. A backup is restored
         * from Settings, where Merge/Replace are explained - never through the answer importer.
         */
        BACKUP,

        /**
         * Only a link - most often the Claude app's "Share chat" link (2026-09-24b). The answer
         * is not in it (the page behind it is rendered in a browser, behind a sign-in), so it
         * is said plainly instead of reaching the parser as "no JSON found".
         */
        LINK,

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
        if (isBackup(text)) return Kind.BACKUP
        if (isOnlyLink(text)) return Kind.LINK
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

    /**
     * A Portfolio backup: a JSON object whose `format` is [com.tj.portfolio.data.Db.BACKUP_FORMAT].
     * The substring test keeps an ordinary answer from paying for a parse.
     */
    fun isBackup(text: String): Boolean {
        if (!text.contains(com.tj.portfolio.data.Db.BACKUP_FORMAT)) return false
        return runCatching {
            org.json.JSONObject(text.trim()).optString("format") == com.tj.portfolio.data.Db.BACKUP_FORMAT
        }.getOrDefault(false)
    }

    /** A share that is a URL and a few words at most, with no JSON anywhere in it. */
    fun isOnlyLink(text: String): Boolean {
        val t = text.trim()
        if (t.length > 600 || t.contains('{')) return false
        return LINK.containsMatchIn(t) && LINK.replace(t, "").trim().length <= 120
    }

    private val LINK = Regex("""https?://\S+""")

    /** What to say when a backup arrives where an answer was expected. */
    const val BACKUP_MESSAGE =
        "That's a Portfolio backup, not a Claude answer - nothing was imported. To restore it, " +
            "use Settings > Restore from a backup file."

    /** The plain-language reply for a share that is not something the app can import. */
    fun rejection(kind: Kind): String? = when (kind) {
        Kind.EMPTY -> "That share was empty - nothing to import."
        Kind.NOT_TEXT ->
            "That isn't a text file. Share the .md answer file Claude wrote, not an image or PDF."
        Kind.BACKUP -> BACKUP_MESSAGE
        Kind.LINK ->
            "That's a link to the chat, not Claude's answer. In the chat, tap the answer file " +
                "Claude made and share that to Portfolio - or copy Claude's whole reply and share " +
                "the text."
        Kind.PROMPT_FILE ->
            "That is the prompt file this app wrote - the question, not Claude's answer. Share " +
                "it to a Claude chat, then share the file Claude writes back here."
        else -> null
    }
}
