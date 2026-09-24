package com.tj.portfolio

import com.tj.portfolio.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Full test 2026-09-24, L-1/N-1: CANCELLING A REQUEST REACHES THE SOCKET.
 *
 * BRIEF.md's locked rule is "cancelling a request: disconnect the socket, not just drop the
 * queued read". The handler that was meant to do it was registered with the public
 * `invokeOnCompletion`, which runs only once the job has FINISHED - and a job blocked in
 * `read()` cannot finish until the read returns on its own. So leaving the app mid-download
 * kept every in-flight body downloading to its end or its timeout.
 *
 * A loopback server (allowed by the T-1 offline gate) sends the headers and a sliver of a
 * large body, then stalls. Cancelling the caller must return promptly, not when the server
 * finally gives up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpCancelTest {

    @Before fun clean() { Http.clearCooldowns() }

    /** Serves one request: full headers, 16 bytes of a 1 MB body, then silence for [stallMs]. */
    private fun stallingServer(stallMs: Long, post: Boolean = false): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val input = s.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true))
                            contentLength = line.substringAfter(':').trim().toInt()
                    }
                    if (post) repeat(contentLength) { input.read() }
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

    private fun cancelTakesMs(block: suspend () -> Unit): Long = runBlocking {
        val job = launch(Dispatchers.Default) { block() }
        delay(700)   // well into the stalled body read
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        (System.nanoTime() - t0) / 1_000_000
    }

    @Test fun `cancelling a GET blocked mid-body returns promptly`() {
        val server = stallingServer(stallMs = 8_000)
        try {
            val url = "http://127.0.0.1:${server.localPort}/stall"
            val ms = cancelTakesMs { Http.get(url, timeoutMs = 10_000) }
            assertTrue("cancel took ${ms}ms - the socket was never disconnected", ms < 2_000)
        } finally { server.close() }
    }

    @Test fun `cancelling a POST blocked mid-body returns promptly`() {
        val server = stallingServer(stallMs = 8_000, post = true)
        try {
            val url = "http://127.0.0.1:${server.localPort}/stall"
            val ms = cancelTakesMs { Http.postJson(url, "{}", timeoutMs = 10_000) }
            assertTrue("cancel took ${ms}ms - the socket was never disconnected", ms < 2_000)
        } finally { server.close() }
    }

    @Test fun `a cancelled request does not count as the host being unreachable`() {
        val server = stallingServer(stallMs = 8_000)
        try {
            val url = "http://127.0.0.1:${server.localPort}/stall"
            repeat(1) { cancelTakesMs { Http.get(url, timeoutMs = 10_000) } }
            assertTrue("a deliberate cancel must not arm a backoff", Http.cooldownRemaining(url) == 0L)
        } finally { server.close() }
    }
}
