package com.tj.portfolio.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class HttpResult(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
    /** True when the request was skipped locally because the host is in a cooldown. */
    val throttledLocally: Boolean get() = code == CODE_COOLDOWN

    companion object {
        const val CODE_COOLDOWN = -429
    }
}

/**
 * HTTP with two things bolted on that the app cannot do without: a per-host cooldown after
 * a rate-limit response, and conditional GETs for feeds.
 *
 * WHY. Every quote is one request, and before v4.5 the app asked for all ~20 of them every
 * 15 seconds, around the clock, 16 at a time. That is about 115,000 requests a day to Yahoo,
 * most of them at 3am for prices that had not moved since the close. Yahoo's documented
 * behaviour under that kind of load is "429 errors or empty responses" - and the old code
 * treated a 429 exactly like a network blip, retried on the other host immediately, and kept
 * the same schedule, which is how a throttle turns into a block.
 *
 * Nothing here changes what a caller sees on the happy path.
 */
object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 16; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    // ---------------------------------------------------------- host cooldown

    private class HostState {
        @Volatile var until: Long = 0L      // no requests before this moment
        @Volatile var strikes: Int = 0      // consecutive rate-limited responses
        @Volatile var failures: Int = 0     // consecutive failures to connect at all
        @Volatile var level: Int = 0        // how many times the unreachable backoff has escalated
    }

    private val hosts = java.util.concurrent.ConcurrentHashMap<String, HostState>()

    /** 30s, 1m, 2m, 4m, 8m, then held at 10m. Cleared by the first success. */
    private fun backoffMs(strikes: Int): Long =
        minOf(30_000L shl (strikes - 1).coerceIn(0, 5), 600_000L)

    /**
     * How long to leave a host alone after it could not be reached AT ALL - a DNS failure, a
     * refused connection, a TLS handshake that will not complete.
     *
     * 15s, 30s, 1m, 2m, 4m, then held at 5m. Deliberately gentler and slower to start than
     * the rate-limit backoff above, because the commonest cause is the phone briefly having
     * no signal, and that must not turn into a long blackout the moment it comes back.
     *
     * ESCALATION IS PER COOLDOWN, NOT PER REQUEST, and that distinction is load-bearing. A
     * refresh fires every held symbol at once, so one offline tick produces twenty failures
     * within a second or two. Escalating on each of them would take a single momentary blip
     * straight to the five-minute ceiling. [HostState.level] therefore only advances when a
     * NEW cooldown is armed - i.e. when the previous one had already lapsed and the host was
     * tried again and failed again.
     */
    private fun unreachableBackoffMs(level: Int): Long =
        minOf(15_000L shl (level - 1).coerceIn(0, 5), 300_000L)

    private const val FAILURES_BEFORE_BACKOFF = 3

    private fun hostOf(url: String): String =
        runCatching { URL(url).host.orEmpty() }.getOrDefault("")

    /**
     * UNIT TESTS NEVER REACH THE INTERNET (full test 2026-09-24, T-1).
     *
     * `app/build.gradle.kts` sets this property on every unit-test JVM; nothing sets it on a
     * phone, so on a device this is always false and costs one string compare per request.
     * Without it, every Robolectric test that builds the ViewModel sent real quote, chart and
     * news requests from whatever machine ran the suite - the container's proxy, a GitHub
     * runner - which made results depend on the market and the network (2026-09-23's
     * WatchSinceAddedTest flake) while the tests themselves assume "no network in a unit test".
     * Loopback (all of 127.0.0.0/8) stays open: NetLogicTest drives the real failure and
     * cooldown paths against 127.0.0.1..5 port 1, which never leaves the machine.
     */
    private val offlineForTests: Boolean = System.getProperty("portfolio.test.offline") == "true"

    private fun blockedForTests(host: String): Boolean =
        offlineForTests && !host.startsWith("127.") && host != "localhost"

    /**
     * Disconnects [connRef]'s connection the moment the calling coroutine is CANCELLED - not
     * when it completes, which a coroutine blocked in `read()` cannot do (L-1/N-1). The
     * handler runs on whichever thread calls `cancel()`; `disconnect()` only closes the socket.
     * The caller disposes the handle in its `finally`.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun cancelWatch(
        connRef: java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>
    ): kotlinx.coroutines.DisposableHandle? =
        kotlin.coroutines.coroutineContext[Job]?.invokeOnCompletion(
            onCancelling = true, invokeImmediately = true
        ) { cause -> if (cause != null) runCatching { connRef.get()?.disconnect() } }

    // ------------------------------------------------------------ rate meter

    /**
     * How many requests this app has actually sent to each host in the last hour.
     *
     * WHY MEASURE RATHER THAN REASON. Everything else in this file is an argument that the
     * app is a polite client. This is the only part that can be checked. The request budget
     * was worked out on paper more than once in this project's history and the paper answer
     * was wrong both times, because the number that matters is the product of the polling
     * interval, the market phase, the symbol count AND how long the app is actually open -
     * and the last of those is not knowable from the source.
     *
     * Surfaced in Settings, so if a provider ever does start refusing, the first question
     * ("how hard are we actually hitting them?") has an answer on the device instead of an
     * estimate. It is also how the effect of the visibility-scoped polling was confirmed.
     *
     * Implementation is one long per bucket, cheap enough to run on every request: 60 buckets
     * of one minute in a ring, so the whole thing is 60 longs per host and never grows.
     */
    private class RateMeter {
        val counts = LongArray(60)
        val stamps = LongArray(60)      // which minute each bucket is holding
    }

    private val meters = java.util.concurrent.ConcurrentHashMap<String, RateMeter>()

    @Synchronized
    private fun noteRequest(host: String) {
        if (host.isEmpty()) return
        val minute = System.currentTimeMillis() / 60_000L
        val m = meters.getOrPut(host) { RateMeter() }
        val i = (minute % 60).toInt()
        // A bucket holding a different minute is stale by a whole hour - reset, do not add.
        if (m.stamps[i] != minute) { m.stamps[i] = minute; m.counts[i] = 0 }
        m.counts[i]++
    }

    /** Requests sent in the last 60 minutes, by host, busiest first. */
    @Synchronized
    fun requestsLastHour(): List<Pair<String, Long>> {
        val minute = System.currentTimeMillis() / 60_000L
        return meters.entries.map { (host, m) ->
            var total = 0L
            for (i in 0 until 60) if (minute - m.stamps[i] < 60L) total += m.counts[i]
            host to total
        }.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    /** Total across every host in the last hour. */
    fun totalLastHour(): Long = requestsLastHour().sumOf { it.second }

    @Synchronized
    fun resetMeters() = meters.clear()

    /** How long the given host is being left alone for, in ms. 0 when it is free. */
    fun cooldownRemaining(url: String): Long {
        val st = hosts[hostOf(url)] ?: return 0L
        return (st.until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    // These run from up to five coroutines at once, and `x += 1` on a @Volatile is not
    // atomic, so the counters are updated under a lock. Cheap: this is a handful of field
    // writes per request, against a network call.
    /**
     * @param retryAfterMs what the server's own `Retry-After` header asked for, or 0.
     *
     * HONOURING Retry-After IS THE POINT OF THIS WHOLE FILE.
     *
     * It was being ignored. A 429 got the app's own 30-second backoff whatever the server
     * said - so a host answering `Retry-After: 3600` ("come back in an hour") was retried
     * 120 times inside the window it asked to be left alone. That is not a throttle any more;
     * from the provider's side it is a client refusing an explicit instruction, and it is the
     * behaviour that turns a temporary rate-limit into a permanent block. It is also the one
     * signal a provider can see that distinguishes a well-behaved client from an abusive one.
     *
     * The server's number always wins when it is larger than ours. It is clamped to six hours
     * so a header saying "next week" cannot silently disable the app forever - at that point
     * the user's own pull-to-refresh, which clears every cooldown, is the way back.
     */
    @Synchronized
    private fun noteRateLimited(host: String, retryAfterMs: Long = 0L) {
        if (host.isEmpty()) return
        val st = hosts.getOrPut(host) { HostState() }
        val now = System.currentTimeMillis()
        // ---- ONE STEP PER COOLDOWN, NOT PER RESPONSE (Round 66 audit, H4).
        //
        // THE BUG THIS FIXES. `strikes` was incremented on every 429, and up to
        // [MAX_PER_HOST] requests are in flight at once - `Research.build` runs four screener
        // calls under a Semaphore(4), and the feed pulls several RSS sources together. When
        // the host answered 429 they all landed within milliseconds, `strikes` jumped from 0
        // to 4, and the FIRST rate-limit event armed `backoffMs(4)` = four minutes instead of
        // the documented thirty seconds. The next lapse sent the same four out together and
        // reached the ten-minute ceiling, so the ladder's middle rungs were unreachable in
        // practice and every Yahoo-dependent screen was frozen for minutes over what may have
        // been a one-second throttle.
        //
        // `noteUnreachable` right below already does it this way and its comment calls the
        // distinction load-bearing. This is the same rule, finally applied to both.
        val (strikes, until) = nextRateLimit(now, st.until, st.strikes, retryAfterMs)
        st.strikes = strikes
        st.until = until
    }

    /**
     * The rate-limit ladder as a pure function, so the rule can be tested (Round 66 audit, H4).
     *
     * Returns the new strike count and the new cooldown deadline. Split out because the bug it
     * fixes is invisible from the outside - it needs concurrent 429s to reproduce - and a rule
     * this load-bearing should not only be checkable through a socket.
     */
    internal fun nextRateLimit(
        now: Long,
        until: Long,
        strikes: Int,
        retryAfterMs: Long
    ): Pair<Int, Long> {
        val next = if (now >= until) strikes + 1 else strikes
        val ours = backoffMs(next)
        val theirs = retryAfterMs.coerceIn(0L, 6 * 3_600_000L)
        // NEVER SHORTEN AN EXISTING COOLDOWN. A late 429 from a request that was already in
        // flight must not be able to pull the deadline back towards now.
        return next to maxOf(until, now + maxOf(ours, theirs))
    }

    /**
     * `Retry-After` in either form the spec allows: delta-seconds, or an HTTP date.
     * Returns 0 when the header is absent or unparseable - never a guess.
     */
    internal fun parseRetryAfter(raw: String?, now: Long = System.currentTimeMillis()): Long {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return 0L
        v.toLongOrNull()?.let { return if (it <= 0) 0L else it * 1000L }
        // HTTP-date. SimpleDateFormat is not thread-safe, so it is built per call - this
        // runs at most once per rate-limited response, which is rare by construction.
        return runCatching {
            val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("GMT")
            (f.parse(v)?.time ?: 0L).let { if (it > now) it - now else 0L }
        }.getOrDefault(0L)
    }

    /**
     * A host that cannot be connected to at all is worth backing off from too.
     *
     * WHAT THIS IS ACTUALLY FOR. The existing cooldown only watches for 429/503/403, so a
     * failure with no status code - no signal, DNS not resolving, a TLS handshake that cannot
     * complete - never armed it. **While the phone has no connectivity the app therefore
     * retried every host on its normal schedule**: for a twenty-symbol portfolio that is
     * twenty doomed connection attempts every fifteen seconds, indefinitely, each one a DNS
     * lookup and a TCP connect going nowhere. That is the real cost this removes.
     *
     * It was found by way of a permanently dead host rather than an offline phone:
     * `api.tradestie.com`, the app's primary r/wallstreetbets source, has served an EXPIRED
     * Let's Encrypt certificate since 3 January 2026 (verified against the live host).
     * Android validates certificates with no way to opt out, so it fails every time on any
     * network. To be accurate about the benefit there: that source is only polled every
     * fifteen minutes anyway, so the saving on it alone is small - `Social.merge` already
     * falls back to ApeWisdom and nothing is broken. The offline case is where this pays.
     *
     * Three consecutive failures is the threshold on purpose: one is a blip, and a phone
     * moving between wifi and cell produces those constantly.
     */
    @Synchronized
    private fun noteUnreachable(host: String) {
        if (host.isEmpty()) return
        val st = hosts.getOrPut(host) { HostState() }
        st.failures += 1
        val now = System.currentTimeMillis()
        // Only arm a new cooldown once the previous one has lapsed - see the note on
        // unreachableBackoffMs about a whole refresh failing at once.
        if (st.failures >= FAILURES_BEFORE_BACKOFF && now >= st.until) {
            st.level += 1
            st.until = now + unreachableBackoffMs(st.level)
        }
    }

    @Synchronized
    private fun noteSuccess(host: String) {
        val st = hosts[host] ?: return
        if (st.strikes != 0 || st.until != 0L || st.failures != 0 || st.level != 0) {
            st.strikes = 0; st.until = 0L; st.failures = 0; st.level = 0
        }
    }

    /**
     * Forget every cooldown. Called when the USER asks for a refresh.
     *
     * The backoffs above exist to stop the app hammering a host on its own schedule; they
     * must never make a deliberate pull-to-refresh do nothing. Coming back into signal and
     * pulling down is precisely when the user wants the app to try again immediately, and
     * without this they would be told to wait out a timer they cannot see.
     */
    @Synchronized
    fun clearCooldowns() {
        hosts.values.forEach { it.strikes = 0; it.failures = 0; it.level = 0; it.until = 0L }
    }

    // ------------------------------------------------------- conditional GET

    private class Cached(val etag: String?, val lastModified: String?, val body: String)

    /**
     * Last successful body per URL, with its validators. RSS feeds are polled far more often
     * than they change, so a conditional GET usually comes back 304 with no body at all -
     * the feed refresh stops re-downloading the same few hundred KB every few minutes.
     * Bounded so a long session cannot grow it without limit.
     */
    /**
     * THE MEMORY CACHE IS NOW ONLY THE FAST PATH IN FRONT OF A DISK CACHE (Round 56).
     *
     * It used to be the whole cache, and it was sized wrong for the job: 48 entries against a
     * working set of 24 symbols x up to 3 per-symbol feeds plus 7 market-wide ones = 79 URLs.
     * `accessOrder = true` meant the least recently used entry was evicted on nearly every
     * pass, so most per-symbol feeds never got to send a validator and came back in full every
     * time. `onLowMemory` then dropped whatever had survived, and a process death dropped all
     * of it - so a cold start re-downloaded every feed.
     *
     * With [disk] wired up, an eviction here costs one SQLite read rather than one download,
     * so this is now what it should always have been: a small hot cache, backed by something
     * that actually remembers.
     */
    private const val CACHE_MAX_ENTRIES = 48

    /**
     * Where validators and bodies live between runs.
     *
     * An interface rather than a direct `Db` reference on purpose: `net/` knows nothing about
     * `data/` or about Android, which is what lets every one of these classes be unit-tested
     * on a plain JVM. `MainActivity` plugs the database in at startup; until it does, or in a
     * test, the cache is simply memory-only and everything still works.
     */
    interface DiskCache {
        fun load(url: String): Triple<String, String, String>?   // etag, lastModified, body
        fun save(url: String, etag: String, lastModified: String, body: String)
        /** A 304 means the entry is still in use - keep retention measuring last USE. */
        fun touch(url: String)
        /** Drop a URL whose stored validators are no longer trustworthy. See [get]. */
        fun forget(url: String)
    }

    @Volatile private var disk: DiskCache? = null

    fun attachDiskCache(cache: DiskCache?) { disk = cache }

    /**
     * Two different limits, because the heap and the disk have nothing in common.
     *
     * A body over [CACHE_MAX_BODY_MEM] is still written to disk and still gets its validators
     * sent - it just is not held in the heap, where a Kotlin `String` costs two bytes a
     * character in a process that also holds a Compose UI and a WebView. The disk limit is
     * far higher because the payloads that most need caching are the big ones: Yahoo's
     * analyst-history module is ~195 KB and changes a handful of times a YEAR.
     */
    private const val CACHE_MAX_BODY_MEM = 120_000
    private const val CACHE_MAX_BODY_DISK = 800_000

    /**
     * THE CEILING WAS BEING COUNTED WRONG, AND IT MATTERS ON A PHONE.
     *
     * The comment here used to claim the worst case was "well under 6MB of heap": 48 entries
     * times a 120,000 cap, read as bytes. A Kotlin `String` is UTF-16, so a 120,000-CHARACTER
     * body is 240,000 bytes of `char[]` - the real ceiling was 11.5MB, nearly double what was
     * written down, in a process whose whole heap on a mid-range device may be 192MB and which
     * also holds a Compose UI, a WebView and a bitmap being resized for the screenshot import.
     *
     * Rather than reason about the ceiling, the cache is now bounded by what it is actually
     * holding. Entries are evicted oldest-first until the total is back under the budget, so
     * the bound holds whatever mix of feed sizes turns up.
     */
    private const val CACHE_MAX_TOTAL_CHARS = 1_500_000   // ~3MB of UTF-16

    /** Total characters currently held. Guarded by the same lock as the map. */
    private var cacheChars: Long = 0L

    private val conditional = LinkedHashMap<String, Cached>(32, 0.75f, true)

    /**
     * Memory first, then disk. A disk hit is promoted into memory so a feed polled every few
     * minutes is read from SQLite once per session rather than on every pass.
     */
    private fun cachedFor(url: String): Cached? {
        synchronized(conditional) { conditional[url] }?.let { return it }
        val row = runCatching { disk?.load(url) }.getOrNull() ?: return null
        val (etag, lm, body) = row
        if (etag.isBlank() && lm.isBlank()) return null
        val c = Cached(etag.ifBlank { null }, lm.ifBlank { null }, body)
        // PROMOTED ONLY IF IT WOULD HAVE BEEN ALLOWED IN THE HEAP IN THE FIRST PLACE.
        // Without this check the promotion path is a hole straight through the memory limit:
        // a 400,000-character body that `putCached` deliberately kept disk-only gets pulled
        // back into the map on the next launch, and `evictLocked` then throws out the small
        // RSS entries to make room for it - the heap ends up holding exactly what the split
        // limits exist to keep out, at the cost of what they exist to protect.
        if (body.length <= CACHE_MAX_BODY_MEM) {
            // Memory only - it came FROM disk, writing it back would be a no-op write per read.
            synchronized(conditional) {
                conditional.put(url, c)?.let { cacheChars -= it.body.length }
                cacheChars += c.body.length
                evictLocked()
            }
        }
        return c
    }

    /** Forget one URL, in both layers. See the note in [get] about validators going stale. */
    private fun dropCached(url: String) {
        synchronized(conditional) {
            conditional.remove(url)?.let { cacheChars -= it.body.length }
            if (conditional.isEmpty()) cacheChars = 0L
        }
        runCatching { disk?.forget(url) }
    }

    private fun putCached(url: String, c: Cached) {
        if (c.body.length <= CACHE_MAX_BODY_MEM) {
            synchronized(conditional) {
                conditional.put(url, c)?.let { cacheChars -= it.body.length }
                cacheChars += c.body.length
                evictLocked()
            }
        }
        // Write-through. Fire-and-forget is not an option here - it must land before the
        // process is killed to be worth anything - but this whole function already runs on
        // the IO dispatcher inside [get], so a synchronous SQLite write is on the right
        // thread and costs a fraction of the request that just completed.
        runCatching { disk?.save(url, c.etag.orEmpty(), c.lastModified.orEmpty(), c.body) }
    }

    /** Evict oldest-first on BOTH bounds. Caller must hold the `conditional` lock. */
    private fun evictLocked() {
        val it = conditional.entries.iterator()
        while (it.hasNext() &&
            (conditional.size > CACHE_MAX_ENTRIES || cacheChars > CACHE_MAX_TOTAL_CHARS)
        ) {
            val e = it.next()
            cacheChars -= e.value.body.length
            it.remove()
        }
        if (conditional.isEmpty()) cacheChars = 0L
    }

    /** Drops every stored validator and body. Used when the user forces a refresh. */
    fun clearConditionalCache() {
        synchronized(conditional) { conditional.clear(); cacheChars = 0L }
    }

    /** What the conditional cache is holding, for the Settings diagnostics card. */
    fun cacheStats(): Pair<Int, Long> = synchronized(conditional) {
        conditional.size to cacheChars * 2   // UTF-16: two bytes a character
    }

    /**
     * Give the heap back when Android says it needs it.
     *
     * Since Round 56 this costs nothing at all: the validators and bodies are on disk, so
     * dropping the heap copy means the next request reads one row out of SQLite instead of
     * re-downloading a feed. Before the disk cache existed this was a real trade - free heap
     * against a full re-download - and it was one worth making, because holding a cache while
     * the system is trimming is how a backgrounded process gets chosen for death.
     */
    fun onLowMemory() {
        clearConditionalCache()
    }

    // ------------------------------------------------- per-host concurrency

    /**
     * At most this many requests in flight to any ONE host, across the whole app.
     *
     * WHY IT HAD TO MOVE IN HERE. Every gate in the app was a `Semaphore` owned by its call
     * site - one in `refresh()`, one in `refreshFeed()`, one in `preloadNewsForAdvice()`, one
     * in `Research.build` and one in `enrichAnalyst`. Several budgets
     * that knew nothing about each other, and most of them pointed at the same two Yahoo
     * hosts. A Research build overlapping a feed pass and a quote tick could legitimately
     * have twenty-five sockets open, nearly all of them to Yahoo - which is precisely the
     * traffic shape that gets a free endpoint to start answering 403.
     *
     * A limit at the HOST is the only one that can be right, because the host is what does
     * the throttling. The call-site gates still exist and still bound how much work the app
     * queues up; this bounds what any provider actually sees.
     *
     * Four is deliberately not two: the app's own latency matters, and Yahoo serves a
     * chart request in well under a second. It is the sustained RATE the cooldown machinery
     * watches; this is about not arriving all at once.
     */
    private const val MAX_PER_HOST = 4

    private val hostGates = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Semaphore>()

    private fun gateFor(host: String): kotlinx.coroutines.sync.Semaphore =
        hostGates.getOrPut(host) { kotlinx.coroutines.sync.Semaphore(MAX_PER_HOST) }

    // ------------------------------------------------------------------ GET

    /**
     * @param conditionalKey when true, remember this URL's ETag/Last-Modified and send them
     *        on the next call, turning an unchanged feed into a bodyless 304.
     * @param cacheAs the identity to file the cache entry under, when it must differ from the
     *        URL. Needed exactly once, and for a good reason: Yahoo's `quoteSummary` carries a
     *        `crumb` query parameter that is re-minted every twelve hours, so the URL for the
     *        SAME resource changes twice a day. Keyed by URL, every entry would be orphaned on
     *        the next mint and the 195 KB analyst payload would never once answer 304.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15000,
        conditionalKey: Boolean = false,
        cacheAs: String? = null
    ): HttpResult = withContext(Dispatchers.IO) {
        val cacheKey = cacheAs ?: url
        val host = hostOf(url)
        if (blockedForTests(host)) return@withContext HttpResult(-1, "offline (unit test)")
        val st = hosts[host]
        if (st != null && System.currentTimeMillis() < st.until) {
            // Deliberately NOT a network call. Callers treat a non-ok result as "no data
            // this time" and keep whatever is on screen, which is the right behaviour while
            // a host is telling us to slow down.
            return@withContext HttpResult(HttpResult.CODE_COOLDOWN, "rate limited, backing off")
        }

        // Counted here, after the cooldown gate: a request the app declined to send is not
        // traffic the provider ever saw, and including it would overstate the load.
        noteRequest(host)

        val prior = if (conditionalKey) cachedFor(cacheKey) else null
        // AN ATOMIC HOLDER, NOT A CAPTURED `var`. The cancellation handler below runs on
        // whichever thread called `cancel()` - the main thread, from `setForeground(false)` -
        // while `conn` is assigned on the IO thread. Kotlin boxes a captured `var` into a
        // `Ref.ObjectRef` whose field is NOT volatile, so with no happens-before edge between
        // the two the canceller may legally read null and skip the disconnect entirely,
        // silently turning this whole mechanism back into the behaviour it replaced.
        val connRef = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        var conn: HttpURLConnection? = null
        // Deferred out of the permit below: writing up to 800KB into SQLite while holding one
        // of only four slots for this host would make a slow disk look like a slow server.
        var toStore: Cached? = null
        var toDrop = false
        val result =
        // The permit is taken AFTER the cooldown check and the cache read, so a request that
        // is never going to be sent does not queue behind four that are.
        gateFor(host).withPermit {
        // AND CHECKED AGAIN ONCE THE PERMIT IS HELD (full test 2026-09-23, N-3). A request that
        // was already queued when one of the four in flight got a 429 would otherwise still be
        // sent into the throttle - five or ten of them at once, which is how a throttle turns
        // into a block.
        hosts[host]?.let { h ->
            if (System.currentTimeMillis() < h.until) {
                return@withPermit HttpResult(HttpResult.CODE_COOLDOWN, "rate limited, backing off")
            }
        }
        // CANCELLATION HAS TO REACH THE SOCKET, or it is not cancellation.
        //
        // `HttpURLConnection` blocks in `read()`, and coroutine cancellation is cooperative -
        // there is no suspension point inside a blocking read to observe it. So before this
        // existed, cancelling the feed pass when the user left the app stopped the requests
        // that were still QUEUED (a `Semaphore.acquire` does suspend) but let every request
        // already in flight run to completion or to its 15-second timeout, downloading data
        // nothing would ever draw. `disconnect()` is the one thing that unblocks that read;
        // calling it on an already-finished connection is a documented no-op, so the handler
        // is safe on every path.
        //
        // ON CANCELLING, NOT ON COMPLETION (full test 2026-09-24, L-1/N-1). This used the public
        // `invokeOnCompletion { }`, whose handler runs when the job reaches its FINAL state - and
        // a job blocked in `read()` cannot reach it until the read returns by itself (whole body
        // downloaded, or the read timeout). So the disconnect only ever landed on a connection
        // that was already finished, and the whole mechanism described above did nothing.
        // `onCancelling = true` runs the handler at the moment of `cancel()`, which is the only
        // moment it is useful. HttpCancelTest pins it with a loopback server that stalls mid-body.
        val cancelWatch = cancelWatch(connRef)
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).also { connRef.set(it) }.apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                prior?.etag?.let { setRequestProperty("If-None-Match", it) }
                prior?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            // A cancel that landed between registering the watch and publishing `connRef` found
            // nothing to disconnect - so look once more before the first blocking call.
            coroutineContext.ensureActive()
            val code = conn.responseCode

            if (code == 304 && prior != null) {
                noteSuccess(host)
                // Retention is measured by last USE, not last change - otherwise a feed that
                // is stable enough to answer 304 for a month, which is exactly the kind worth
                // caching, ages out of the table and has to be downloaded in full again.
                runCatching { disk?.touch(cacheKey) }
                return@withContext HttpResult(200, prior.body)
            }
            if (code == 429 || code == 503 || code == 403) {
                // 403 is in here on purpose: Yahoo and Google both answer a client they have
                // decided to throttle with a 403 rather than a 429, and hammering through it
                // is what escalates to a real block.
                noteRateLimited(host, parseRetryAfter(conn.getHeaderField("Retry-After")))
                return@withContext HttpResult(code, "")
            }

            val declared = conn.contentLengthLong
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val read = stream?.let { read(it, conn.contentEncoding, declared) }
            val body = read?.text ?: ""
            if (code in 200..299) {
                noteSuccess(host)
                if (conditionalKey) {
                    val etag = conn.getHeaderField("ETag")
                    val lm = conn.getHeaderField("Last-Modified")
                    // NEVER CACHE A BODY THAT MAY BE TRUNCATED, and cancellation is exactly
                    // how one gets truncated now that cancelling disconnects the socket.
                    //
                    // `read` loops until EOF and has no length check, so a socket closed
                    // mid-body USUALLY throws - but a plain, non-gzip stream can simply
                    // report EOF instead. Control then reaches here with a 2xx, a complete
                    // ETag, and half a document. Stored, that pair is permanent: every later
                    // request sends the ETag, the origin correctly answers 304, and the half
                    // document is returned as a fresh 200 - with `touch` renewing the
                    // retention on each one, so it never even ages out.
                    //
                    // Two independent guards, because either alone leaves a hole: the job
                    // must still be active, and a declared Content-Length must match what
                    // was actually read.
                    val looksComplete = read?.complete == true &&
                        coroutineContext[Job]?.isActive != false
                    val storable = looksComplete && body.length <= CACHE_MAX_BODY_DISK &&
                        (!etag.isNullOrBlank() || !lm.isNullOrBlank())
                    if (storable) {
                        toStore = Cached(etag, lm, body)
                    } else if (prior != null && looksComplete) {
                        // A STALE ENTRY MUST BE DROPPED, NOT LEFT BEHIND. Both branches here
                        // used to do nothing, and the consequence was a cache that could
                        // serve last month's body indefinitely: if the origin later stopped
                        // sending validators, or the body grew past the disk limit, the old
                        // entry survived with its old ETag. The next request sent that ETag,
                        // the origin answered 304 - correctly, against a version we no longer
                        // wanted to keep - and the 304 branch above returned the ancient body
                        // as a fresh 200. Worse, every such 304 called `touch`, so retention
                        // renewed the staleness rather than ageing it out.
                        toDrop = true
                    }
                }
            }
            HttpResult(code, body)
        } catch (e: Exception) {
            // A CANCELLED REQUEST IS NOT AN UNREACHABLE HOST.
            //
            // Cancelling now closes the socket on purpose, which surfaces here as an ordinary
            // `SocketException` - and `noteUnreachable` counts three of those into a cooldown
            // that escalates to five minutes. Backgrounding during a feed pass cancels a
            // dozen requests to the same host at once, so without this the app would arm a
            // long backoff against a perfectly healthy provider every time the user switched
            // away, then show "last known prices" for minutes on the way back in.
            if (coroutineContext[Job]?.isActive == false) throw e
            noteUnreachable(host)
            HttpResult(-1, e.message ?: "network error")
        } finally {
            // Disposed first: leaving it registered would keep this closure - and the
            // connection it captures - reachable from the ViewModel's Job for as long as
            // that Job lives, which on a busy feed pass is thousands of dead handlers.
            cancelWatch?.dispose()
            conn?.disconnect()
        }
        }
        // Outside the permit: the slot is already free by the time the disk is touched.
        toStore?.let { putCached(cacheKey, it) }
        if (toDrop) dropCached(cacheKey)
        result
    }

    /**
     * Runs the SAME cooldown/gate/rate-meter machinery [get] does, which this used to skip
     * entirely (a Round 66 audit finding): a host already in cooldown from `get()` traffic was
     * hit anyway, and a 429/503/403 or connection failure here armed no cooldown at all,
     * invisible to the rate meter that Settings' diagnostics screen reads. Today's one caller
     * (`Claude.kt`, the user's own keyed API, one request per explicit button tap) makes the
     * gap low-volume in practice, but the protection should not silently not apply to POSTs.
     */
    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 180000
    ): HttpResult = withContext(Dispatchers.IO) {
        val host = hostOf(url)
        if (blockedForTests(host)) return@withContext HttpResult(-1, "offline (unit test)")
        val st = hosts[host]
        if (st != null && System.currentTimeMillis() < st.until) {
            return@withContext HttpResult(HttpResult.CODE_COOLDOWN, "rate limited, backing off")
        }
        noteRequest(host)
        gateFor(host).withPermit {
            // Same reasoning as get()'s connRef: an AtomicReference, not a captured `var`,
            // so the cancellation handler (running on whichever thread called cancel()) is
            // guaranteed to see the connection the IO thread assigned.
            val connRef = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
            var conn: HttpURLConnection? = null
            val cancelWatch = cancelWatch(connRef)   // on CANCELLING - see get()
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).also { connRef.set(it) }.apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = timeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", UA)
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                coroutineContext.ensureActive()   // same window as get()
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code == 429 || code == 503 || code == 403) {
                    noteRateLimited(host, parseRetryAfter(conn.getHeaderField("Retry-After")))
                    return@withContext HttpResult(code, "")
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                // postJson results are never cached, so completeness is not consulted here.
                val body = stream?.let { read(it, conn.contentEncoding, conn.contentLengthLong).text }
                    ?: ""
                if (code in 200..299) noteSuccess(host)
                HttpResult(code, body)
            } catch (e: Exception) {
                if (coroutineContext[Job]?.isActive == false) throw e
                noteUnreachable(host)
                HttpResult(-1, e.message ?: "network error")
            } finally {
                cancelWatch?.dispose()
                conn?.disconnect()
            }
        }
    }

    /**
     * A response body is read with a hard ceiling. Without one, a redirect to an HTML error
     * page or a feed that suddenly serves a huge payload is read entirely into a String on
     * a phone - and every one of these runs concurrently with up to five others.
     */
    private const val MAX_BODY_CHARS = 4_000_000

    /**
     * A body, and whether the app is confident it is the WHOLE body.
     *
     * The distinction only started to matter once cancellation began closing sockets on
     * purpose: a stream cut mid-body usually throws, but a plain (non-compressed) one can
     * simply report EOF, which is indistinguishable from a complete short document unless
     * something counts the bytes. Nothing may be CACHED unless `complete` is true.
     */
    private class Body(val text: String, val complete: Boolean)

    private fun read(
        input: java.io.InputStream,
        encoding: String?,
        declaredBytes: Long
    ): Body {
        val counting = CountingStream(input)
        val s = when {
            encoding.equals("gzip", true) -> GZIPInputStream(counting)
            encoding.equals("deflate", true) ->
                java.util.zip.InflaterInputStream(counting, java.util.zip.Inflater(true))
            else -> counting
        }
        BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { r ->
            val sb = StringBuilder()
            val buf = CharArray(8192)
            var truncatedByCap = false
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
                if (sb.length >= MAX_BODY_CHARS) { truncatedByCap = true; break }
            }
            // `Content-Length` counts the bytes ON THE WIRE, which is what [CountingStream]
            // measures - before any gzip/deflate expansion and before UTF-8 decoding. So the
            // comparison is exact whether or not the response was compressed. When the header
            // is absent (a chunked response) there is nothing to check against, and the app
            // falls back to "it did not throw".
            val complete = !truncatedByCap &&
                (declaredBytes < 0 || counting.count >= declaredBytes)
            return Body(sb.toString(), complete)
        }
    }

    /** Counts the bytes actually pulled off the socket, so a short read can be detected. */
    private class CountingStream(private val inner: java.io.InputStream) : java.io.InputStream() {
        @Volatile var count: Long = 0L
        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) count++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) count += n
            return n
        }
        override fun available(): Int = inner.available()
        override fun close() = inner.close()
    }
}
