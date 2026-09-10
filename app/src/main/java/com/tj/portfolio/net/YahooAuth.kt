package com.tj.portfolio.net

import kotlinx.coroutines.sync.withLock


/*
 * EXTRACTED FROM FundamentalsFeed IN ROUND 56.
 *
 * It was a private object inside that file, which was fine while `quoteSummary` was the only
 * endpoint that needed a crumb. Round 56 added a second: Yahoo's BATCHED quote endpoint,
 * `v7/finance/quote`, which is what lets one request replace sixteen. Both callers must share
 * ONE crumb and ONE cookie jar - two independent handshakes would double the mint traffic and,
 * worse, each would invalidate the other's crumb on a 401.
 */

/**
 * Yahoo's API session.
 *
 * The v10 endpoints stopped being anonymous: a request now needs a `A3` cookie from
 * Yahoo AND the `crumb` string that was minted against that same cookie. Either one on
 * its own gets 401 "Invalid Crumb" - verified against the live endpoint, both ways round.
 *
 * The cookie is handled by the JVM rather than by hand: installing a [java.net.CookieManager]
 * as the default [java.net.CookieHandler] makes every HttpURLConnection in the process
 * store and replay cookies automatically, which is all this needs. It is installed once,
 * and only if nothing else has claimed the slot.
 *
 * NOT PERSISTED, deliberately. The crumb is only valid against the cookie it was minted
 * with, and the cookie lives in memory, so writing the crumb to the database would
 * restore half of a pair and produce a confident 401 on first use. One two-request
 * handshake per app launch is the honest cost.
 */
internal object YahooAuth {
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var crumb: String = ""
    @Volatile private var mintedAt: Long = 0L

    /** Re-mint at most this often, so a bad run cannot turn into a handshake loop. */
    private const val MIN_INTERVAL_MS = 60_000L
    private const val TTL_MS = 12 * 3_600_000L

    private val gate = kotlinx.coroutines.sync.Mutex()

    private fun installCookieJar() {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            if (java.net.CookieHandler.getDefault() == null) {
                java.net.CookieHandler.setDefault(
                    java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)
                )
            }
        }
    }

    /**
     * A crumb is a short opaque token - "uhlQUfkGQfk". Yahoo answers the mint endpoint
     * with PLAIN TEXT, so when it is throttling, the body is the literal words
     * "Too Many Requests" and a naive read would send that as the crumb and then blame
     * the parser for the 401 that comes back. Anything with whitespace, a tag, or the
     * wrong length is not a crumb.
     */
    private fun looksLikeCrumb(s: String): Boolean {
        val t = s.trim()
        return t.length in 4..64 && t.none { it.isWhitespace() } &&
            !t.contains('<') && !t.contains('{')
    }

    suspend fun crumb(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && crumb.isNotBlank() && now - mintedAt < TTL_MS) return crumb
        return gate.withLock {
            // Another coroutine may have minted one while this was queued.
            val n = System.currentTimeMillis()
            if (!force && crumb.isNotBlank() && n - mintedAt < TTL_MS) return@withLock crumb
            // THE GUARD APPLIES EVEN WHEN THERE IS NO CRUMB YET, which is the case it was
            // written for. With `&& crumb.isNotBlank()` it was inert on a cold start: Yahoo
            // throttling `getcrumb` leaves the crumb blank, so the guard was skipped and every
            // caller re-ran the full three-request handshake - a handshake loop, which is
            // exactly what the interval exists to prevent. It matters more since Round 56,
            // because the batched quote endpoint made this a per-tick call rather than a
            // per-stock-screen one.
            if (n - mintedAt < MIN_INTERVAL_MS) return@withLock crumb
            installCookieJar()
            // Sets the A3 cookie. It answers 404 - that is not a failure, the cookie
            // rides on the response headers either way.
            Http.get("https://fc.yahoo.com/", timeoutMs = 10000)
            for (host in listOf("query1", "query2")) {
                val r = Http.get("https://$host.finance.yahoo.com/v1/test/getcrumb", timeoutMs = 10000)
                if (r.ok && looksLikeCrumb(r.body)) {
                    crumb = r.body.trim()
                    mintedAt = System.currentTimeMillis()
                    return@withLock crumb
                }
            }
            // Leave the old crumb in place rather than blanking it: a throttled mint
            // says nothing about whether the crumb we already hold still works.
            mintedAt = System.currentTimeMillis()
            crumb
        }
    }

    /**
     * Throw away the crumb, but NOT the clock (Round 66 audit, H3).
     *
     * ---- THE BUG THIS FIXES
     *
     * This used to also set `mintedAt = 0L`, which makes `n - mintedAt` an enormous number and
     * so kills the [MIN_INTERVAL_MS] guard above on every path that follows an invalidate -
     * and every 401 follows an invalidate. That guard is the ONLY thing standing between the
     * app and a handshake loop, and the note beside it says so.
     *
     * What that cost: if Yahoo answers 401 persistently - a crumb-scheme change, or the A3
     * cookie not being stored because something else claimed `CookieHandler.getDefault` - the
     * quote batch invalidates on every tick, and the next tick pays a fresh fc.yahoo.com
     * request plus up to two getcrumb requests before its own request 401s again. 401 is not
     * in `Http`'s rate-limit set, so no cooldown ever arms and nothing stops it: three
     * handshake requests every fifteen seconds, indefinitely.
     *
     * Keeping the clock is correct on both paths. A genuinely expired crumb is twelve hours
     * old, so the interval has long passed and the next call re-mints at once. A crumb minted
     * seconds ago that Yahoo has just rejected is not going to be fixed by minting another
     * one seconds later; the caller gets a blank crumb, treats the pass as inconclusive, and
     * tries again on the next tick with the interval honoured.
     */
    fun invalidate() {
        crumb = ""
    }
}
