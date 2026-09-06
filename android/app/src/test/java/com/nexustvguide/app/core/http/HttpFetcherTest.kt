package com.nexustvguide.app.core.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Fixes the deliberate deviation from the Node backend (plan §4.2/§4.3): only transient
 * failures (429 and 5xx) and network errors are retried, permanent 4xx never are.
 */
class HttpFetcherTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    /** No real waiting: backoff is recorded instead of slept. */
    private fun fetcher(waited: MutableList<Long> = mutableListOf(), maxRetries: Int = 2) =
        HttpFetcher(OkHttpClient(), maxRetries, 1000, 3000, wait = { waited.add(it) }, nowMs = { 0L })

    private fun request() = Request.Builder().url(server.url("/programs")).build()

    @Test
    fun `does not retry a permanent 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val waited = mutableListOf<Long>()
        try {
            fetcher(waited).fetch(request())
            fail("expected HttpFailure")
        } catch (e: HttpFailure) {
            assertEquals(404, e.status)
            assertTrue(!e.isTransient)
        }
        assertEquals(1, server.requestCount)
        assertTrue(waited.isEmpty())
    }

    @Test
    fun `does not retry 401 or 403`() = runBlocking {
        for (code in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(code))
            try {
                fetcher().fetch(request())
                fail("expected HttpFailure for $code")
            } catch (e: HttpFailure) {
                assertEquals(code, e.status)
            }
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries 500 and succeeds with exponential backoff`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val waited = mutableListOf<Long>()

        assertEquals("""{"ok":true}""", fetcher(waited).fetch(request()))
        assertEquals(3, server.requestCount)
        // 1000, then 2000; capped at 3000.
        assertEquals(listOf(1000L, 2000L), waited)
    }

    @Test
    fun `gives up after the retry budget and reports the last status`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        try {
            fetcher().fetch(request())
            fail("expected HttpFailure")
        } catch (e: HttpFailure) {
            assertEquals(503, e.status)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `honours a numeric Retry-After within the cycle budget`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val waited = mutableListOf<Long>()

        assertEquals("ok", fetcher(waited).fetch(request()))
        assertEquals(listOf(5000L), waited)
    }

    @Test
    fun `defers the run when Retry-After exceeds the budget`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "600"))
        try {
            fetcher().fetch(request())
            fail("expected HttpFailure")
        } catch (e: HttpFailure) {
            assertEquals(429, e.status)
            assertEquals(600_000L, e.retryAfterMs)
        }
        // A long server-requested delay defers instead of holding the cycle open.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `classifies transient versus permanent failures`() {
        assertTrue(isTransientSourceFailure(HttpFailure(500)))
        assertTrue(isTransientSourceFailure(HttpFailure(429)))
        assertTrue(isTransientSourceFailure(java.io.IOException("socket closed")))
        assertTrue(!isTransientSourceFailure(HttpFailure(404)))
        assertTrue(!isTransientSourceFailure(IllegalStateException("schema")))
    }

    @Test
    fun `cancellation propagates instead of being retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        var observed: Throwable? = null

        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Cancels while the fetcher is waiting out its backoff.
                HttpFetcher(OkHttpClient(), 5, 50_000, 50_000).fetch(request())
            } catch (e: Throwable) {
                observed = e
            }
        }
        while (server.requestCount < 1) Thread.sleep(10)
        job.cancel()
        job.join()

        assertTrue("expected CancellationException but got $observed", observed is CancellationException)
    }
}
