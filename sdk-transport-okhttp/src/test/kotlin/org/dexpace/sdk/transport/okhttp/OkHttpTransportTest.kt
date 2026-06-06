/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.okhttp

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.io.IOException
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MockWebServer-driven matrix exercising [OkHttpTransport] over the sync [HttpClient] and
 * async [org.dexpace.sdk.core.client.AsyncHttpClient] paths.
 *
 * `Io.installProvider(OkioIoProvider)` is invoked once in `@BeforeTest` — install is
 * idempotent for the same provider so repeated invocations across tests are a no-op.
 *
 * The 5 MB streaming case uses pre-allocated byte arrays; the assertion is MD5 round-trip
 * to keep test memory bounded.
 */
class OkHttpTransportTest {
    @StartStop
    private val server = MockWebServer()

    private lateinit var transport: OkHttpTransport

    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
        transport = OkHttpTransport.builder().build()
    }

    // -------- sync golden paths --------

    @Test
    fun `executeGetReturnsBody`() {
        server.enqueue(MockResponse.Builder().code(200).body("hello").build())
        val request = simpleGet("/sync")
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
            assertEquals("hello", response.body?.source()?.readUtf8())
        }
    }

    @Test
    fun `executePostSendsBody`() {
        server.enqueue(MockResponse.Builder().code(201).build())
        val payload = "request-body-bytes"
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/echo").toUrl())
                .body(RequestBody.create(payload, CommonMediaTypes.TEXT_PLAIN))
                .build()
        transport.execute(request).use { response ->
            assertEquals(201, response.status.code)
        }
        val recorded = server.takeRequest()
        assertEquals(payload, recorded.body?.utf8())
        assertEquals("/echo", recorded.url.encodedPath)
    }

    // -------- async golden paths --------

    @Test
    fun `executeAsyncReturnsBody`() {
        server.enqueue(MockResponse.Builder().code(200).body("async-hello").build())
        val request = simpleGet("/async-get")
        val future = transport.executeAsync(request)
        val response = future.get(5, TimeUnit.SECONDS)
        response.use {
            assertEquals(200, it.status.code)
            assertEquals("async-hello", it.body?.source()?.readUtf8())
        }
    }

    @Test
    fun `executeAsyncPostSendsBody`() {
        server.enqueue(MockResponse.Builder().code(202).build())
        val payload = "async-request-bytes"
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/async-echo").toUrl())
                .body(RequestBody.create(payload, CommonMediaTypes.TEXT_PLAIN))
                .build()
        val response = transport.executeAsync(request).get(5, TimeUnit.SECONDS)
        response.use {
            assertEquals(202, it.status.code)
        }
        val recorded = server.takeRequest()
        assertEquals(payload, recorded.body?.utf8())
    }

    // -------- headers round-trip --------

    @Test
    fun `headersRoundTrip`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("X-Single", "one")
                .addHeader("Set-Cookie", "a=1")
                .addHeader("Set-Cookie", "b=2")
                .build(),
        )
        val request =
            Request.builder()
                .method(Method.GET)
                .url(server.url("/headers").toUrl())
                .addHeader("Authorization", "Bearer token-123")
                .addHeader("Accept", "application/json")
                .addHeader("X-Custom", "alpha")
                .addHeader("X-Custom", "beta")
                .build()
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
            assertEquals("one", response.headers.get("X-Single"))
            val cookies = response.headers.values("Set-Cookie")
            assertTrue(cookies.containsAll(listOf("a=1", "b=2")), "expected cookies in $cookies")
        }
        val recorded = server.takeRequest()
        assertEquals("Bearer token-123", recorded.headers["Authorization"])
        assertEquals("application/json", recorded.headers["Accept"])
        val xCustomValues = recorded.headers.values("X-Custom")
        assertTrue(xCustomValues.containsAll(listOf("alpha", "beta")), "expected both custom values in $xCustomValues")
    }

    // -------- restricted headers --------

    @Test
    fun `restrictedHeadersAreDropped`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/restricted").toUrl())
                .body(RequestBody.create("payload", CommonMediaTypes.TEXT_PLAIN))
                .addHeader("Content-Length", "999")
                .addHeader("Host", "bogus.example")
                .addHeader("Transfer-Encoding", "chunked")
                .addHeader("X-Pass-Through", "kept")
                .build()
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
        }
        val recorded = server.takeRequest()
        // OkHttp recomputes Content-Length from the actual body bytes.
        assertEquals("7", recorded.headers["Content-Length"], "Content-Length should match payload")
        // Host should be the MockWebServer address, not the bogus value supplied.
        val host = recorded.headers["Host"]
        assertNotNull(host)
        assertTrue(!host.contains("bogus.example"), "Host should be recomputed from URL, was $host")
        // Pass-through header still reaches the server.
        assertEquals("kept", recorded.headers["X-Pass-Through"])
    }

    // -------- request body streaming --------

    @Test
    fun `requestBodyStreamingMatrix`() {
        val sizes = listOf(1, 1024, 100 * 1024, 5 * 1024 * 1024)
        for (size in sizes) {
            val bytes = randomBytes(size, seed = size.toLong())
            server.enqueue(MockResponse.Builder().code(200).build())
            val request =
                Request.builder()
                    .method(Method.POST)
                    .url(server.url("/stream-up-$size").toUrl())
                    .body(RequestBody.create(bytes, CommonMediaTypes.APPLICATION_OCTET_STREAM))
                    .build()
            transport.execute(request).use { it.body?.source()?.readByteArray() }
            val recorded = server.takeRequest()
            val received = recorded.body?.toByteArray() ?: ByteArray(0)
            assertEquals(md5(bytes), md5(received), "round-trip mismatch at size=$size")
        }
    }

    // -------- response body streaming --------

    @Test
    fun `responseBodyStreamingMatrix`() {
        val sizes = listOf(1, 1024, 100 * 1024, 5 * 1024 * 1024)
        for (size in sizes) {
            val bytes = randomBytes(size, seed = -size.toLong())
            val okioBuffer = Buffer().write(bytes)
            server.enqueue(MockResponse.Builder().code(200).body(okioBuffer).build())
            val request = simpleGet("/stream-down-$size")
            transport.execute(request).use { response ->
                val read = response.body?.source()?.readByteArray()
                assertContentEquals(bytes, read, "round-trip mismatch at size=$size")
            }
        }
    }

    // -------- timeouts --------

    @Test
    fun `connectTimeoutFires`() {
        // Acquire a free port and immediately close the socket so connects fail fast (no
        // SYN-ACK). connectTimeout should still bound the wait.
        val deadPort = freePort()
        val shortTimeoutTransport =
            OkHttpTransport.builder()
                .connectTimeout(Duration.ofMillis(500))
                .build()
        val request =
            Request.builder()
                .method(Method.GET)
                .url(URL("http://127.0.0.1:$deadPort/"))
                .build()
        val ex =
            assertFails {
                shortTimeoutTransport.execute(request).close()
            }
        assertTrue(ex is IOException, "expected IOException, got ${ex::class}")
    }

    @Test
    fun `readTimeoutFires`() {
        // Headers delay is comfortably longer than the read timeout so the timeout fires
        // first, but short enough that MockWebServer's pending-response state has cleared
        // by the time `@StartStop` shuts it down at the end of the test. A 10-second delay
        // (and an interrupt from the test framework during shutdown) is what surfaces the
        // `Failed to close extension context` error we saw in earlier runs.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("late")
                .headersDelay(800, TimeUnit.MILLISECONDS)
                .build(),
        )
        val shortReadTransport =
            OkHttpTransport.builder()
                .readTimeout(Duration.ofMillis(200))
                .build()
        val ex =
            assertFails {
                shortReadTransport.execute(simpleGet("/slow")).close()
            }
        assertTrue(ex is IOException, "expected IOException, got ${ex::class}")
        // The transport's `execute` preserves the interrupt status when surfacing an
        // `InterruptedIOException`. OkHttp's SocketTimeoutException path on JDK 25 leaves
        // the thread's interrupt flag set; clear it before sleeping so MockWebServer's
        // pending dispatch has time to drain without an InterruptedException.
        Thread.interrupted()
        Thread.sleep(900)
    }

    // -------- cancellation --------

    @Test
    fun `syncCancellationPropagates`() {
        // Verifies the documented contract: when `execute` surfaces an
        // `InterruptedIOException`, the calling thread's interrupt status is preserved.
        // OkHttp 5.x's default transport uses plain `java.net.Socket` rather than
        // `SocketChannel`, so `Thread.interrupt()` does NOT in general abort a pending
        // socket read — we therefore inject an `InterruptedIOException` via a custom
        // OkHttp interceptor (the same hook that real callers would use to wire
        // cancellation into their own stack) and verify that our wrapper re-asserts
        // `Thread.currentThread().interrupt()` before rethrowing.
        val interceptedTransport =
            OkHttpTransport.create(
                OkHttpClient.Builder()
                    .addInterceptor(
                        Interceptor {
                            throw java.io.InterruptedIOException("simulated interrupt")
                        },
                    )
                    .build(),
            )
        val errorRef = AtomicReference<Throwable?>()
        val interruptedAfter = AtomicBoolean(false)
        val worker =
            Thread {
                try {
                    interceptedTransport.execute(simpleGet("/never-served"))
                } catch (t: Throwable) {
                    errorRef.set(t)
                    interruptedAfter.set(Thread.currentThread().isInterrupted)
                }
            }
        worker.start()
        worker.join(3000)
        assertTrue(!worker.isAlive, "worker should exit when interceptor throws InterruptedIOException")
        val captured = errorRef.get()
        assertNotNull(captured, "expected InterruptedIOException to surface")
        assertTrue(
            captured is java.io.InterruptedIOException,
            "expected InterruptedIOException, got ${captured::class}",
        )
        assertTrue(interruptedAfter.get(), "Thread.currentThread().interrupt() should have been re-asserted")
    }

    @Test
    fun `asyncCancellationPropagates`() {
        // Headers delay is bounded so MockWebServer's shutdown doesn't observe a still-
        // pending response after the test. The test only needs the call to be in-flight
        // long enough for future.cancel() to propagate to Call.cancel().
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("eventual")
                .headersDelay(800, TimeUnit.MILLISECONDS)
                .build(),
        )
        val future = transport.executeAsync(simpleGet("/cancel-me"))
        // give OkHttp a moment to schedule the call onto its dispatcher
        Thread.sleep(100)
        val cancelled = future.cancel(true)
        assertTrue(cancelled, "future.cancel should report success")
        val ex =
            assertFails {
                future.get(2, TimeUnit.SECONDS)
            }
        // CompletableFuture.get on a cancelled future throws CancellationException.
        assertTrue(
            ex is CancellationException || ex is CompletionException,
            "expected cancellation surface, got ${ex::class}",
        )
        // Drain MockWebServer's pending dispatch so shutdown doesn't observe a stale task.
        // Clear the interrupt flag (CancellationException paths may leave it set on JDK 25).
        Thread.interrupted()
        Thread.sleep(900)
    }

    // -------- redirect behaviour --------

    @Test
    fun `followRedirectsDefaultFalse`() {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/after-redirect")
                .build(),
        )
        transport.execute(simpleGet("/before-redirect")).use { response ->
            assertEquals(302, response.status.code, "transport must surface the 302, not follow it")
            assertEquals("/after-redirect", response.headers.get("Location"))
        }
    }

    // -------- BYO factory --------

    @Test
    fun `byoFactoryRespectsClient`() {
        server.enqueue(MockResponse.Builder().code(200).body("byo").build())
        val interceptorRan = AtomicBoolean(false)
        val customClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .addInterceptor(
                    Interceptor { chain ->
                        interceptorRan.set(true)
                        chain.proceed(chain.request())
                    },
                )
                .build()
        val byoTransport = OkHttpTransport.create(customClient)
        byoTransport.execute(simpleGet("/byo")).use { response ->
            assertEquals(200, response.status.code)
        }
        assertTrue(interceptorRan.get(), "custom interceptor should have been invoked")
    }

    // -------- protocol mapping --------

    @Test
    fun `protocolMappingHttp11`() {
        // MockWebServer defaults to HTTP/1.1 over plain text — verify the protocol is mapped.
        server.enqueue(MockResponse.Builder().code(200).body("h1").build())
        transport.execute(simpleGet("/proto")).use { response ->
            assertEquals(Protocol.HTTP_1_1, response.protocol)
        }
    }

    // -------- helpers --------

    private fun simpleGet(path: String): Request =
        Request.builder()
            .method(Method.GET)
            .url(server.url(path).toUrl())
            .build()

    private fun randomBytes(
        size: Int,
        seed: Long,
    ): ByteArray {
        val rand = java.util.Random(seed)
        val bytes = ByteArray(size)
        rand.nextBytes(bytes)
        return bytes
    }

    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun freePort(): Int {
        ServerSocket(0).use { socket -> return socket.localPort }
    }
}
