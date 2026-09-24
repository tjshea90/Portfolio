package com.tj.portfolio

import com.tj.portfolio.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Full test 2026-09-24, L-1/N-1: CANCELLING A REQUEST REACHES THE SOCKET AT CANCEL TIME.
 *
 * BRIEF.md's locked rule is "cancelling a request: disconnect the socket, not just drop the
 * queued read". The handler meant to do it was registered with the public
 * `invokeOnCompletion`, which runs only once the job has FINISHED - and a job blocked in
 * `read()` cannot finish until the read returns on its own. So leaving the app mid-download
 * kept every in-flight body downloading to its end or its timeout, and the disconnect only
 * ever landed on a connection that was already done.
 *
 * What is pinned here is the hook's TIMING, with a blocking sleep standing in for the read.
 * The disconnect itself cannot be exercised on this JVM: a desktop JDK's
 * `HttpURLConnection.disconnect()` waits for an in-progress read instead of interrupting it
 * (measured: 5.3 s for a 6 s stall), whereas Android's OkHttp-backed one closes the socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpCancelTest {

    @Before fun clean() { Http.clearCooldowns() }

    @Test fun `the cancel hook runs inside cancel(), not when the blocking call returns`() = runBlocking {
        val firedAt = AtomicLong(0L)
        val job = launch(Dispatchers.IO) {
            // Http.get's own shape: a withContext(IO) job whose body blocks without suspending.
            withContext(Dispatchers.IO) {
                val h = Http.onCancelling { firedAt.set(System.nanoTime()) }
                try { Thread.sleep(3_000) } catch (_: InterruptedException) {} finally { h?.dispose() }
            }
        }
        delay(300)
        val cancelledAt = System.nanoTime()
        job.cancel()
        assertTrue("the hook had not run when cancel() returned - it waits for the blocked call",
            firedAt.get() != 0L)
        assertTrue("the hook ran ${(firedAt.get() - cancelledAt) / 1_000_000}ms after cancel()",
            firedAt.get() - cancelledAt < 200_000_000L)
        job.join()
    }

    @Test fun `the cancel hook does not run when the work completes normally`() = runBlocking {
        var fired = false
        withContext(Dispatchers.IO) {
            val h = Http.onCancelling { fired = true }
            h?.dispose()
        }
        assertEquals(false, fired)
    }

    /** Serves one request: full headers, 16 bytes of a 1 MB body, then silence for [stallMs]. */
    private fun stallingServer(stallMs: Long): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val input = s.getInputStream().bufferedReader()
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                    s.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                            "Content-Length: 1000000\r\n\r\n").toByteArray())
                        write(ByteArray(16) { 'a'.code.toByte() })
                        flush()
                    }
                    Thread.sleep(stallMs)
                }
            }
        }
        return server
    }

    /**
     * The real request path end to end, mid-body. Also the ensureActive() re-check added with
     * the fix: a cancelled request must surface as cancellation, never as "host unreachable" -
     * three of those would arm a backoff against a healthy provider on every app switch.
     */
    @Test fun `cancelled mid-body requests do not count as the host being unreachable`() {
        repeat(3) {
            val server = stallingServer(stallMs = 1_200)
            try {
                val url = "http://127.0.0.1:${server.localPort}/stall"
                runBlocking {
                    val job = launch(Dispatchers.Default) { Http.get(url, timeoutMs = 10_000) }
                    delay(400)
                    job.cancelAndJoin()
                }
            } finally { server.close() }
        }
        assertEquals("a deliberate cancel must not arm a backoff",
            0L, Http.cooldownRemaining("http://127.0.0.1:1/any"))
    }
}
