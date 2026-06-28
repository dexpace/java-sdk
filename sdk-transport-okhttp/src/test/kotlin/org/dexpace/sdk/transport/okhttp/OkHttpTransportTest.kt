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
import org.dexpace.sdk.core.auth.BasicChallengeHandler
import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.FileRequestBody
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.util.ProxyOptions
import org.dexpace.sdk.io.OkioIoProvider
import org.dexpace.sdk.transport.okhttp.internal.SdkRequestBodyAdapter
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun executeGetReturnsBody() {
        server.enqueue(MockResponse.Builder().code(200).body("hello").build())
        val request = simpleGet("/sync")
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
            assertEquals("hello", response.body?.source()?.readUtf8())
        }
    }

    @Test
    fun executePostSendsBody() {
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

    // -------- body-less methods that OkHttp requires a body for --------

    @Test
    fun executeBodylessPostSendsEmptyBody() {
        // The SDK treats a body as optional for every method, so a body-less POST is a valid,
        // publicly-constructible request. OkHttp's Request.Builder.method, however, rejects a
        // null body for POST/PUT/PATCH. The adapter must substitute a zero-length body so the
        // request dispatches with an empty payload instead of throwing IllegalArgumentException.
        server.enqueue(MockResponse.Builder().code(200).build())
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/bodyless-post").toUrl())
                .build()
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
        }
        val recorded = server.takeRequest()
        assertEquals("", recorded.body?.utf8() ?: "")
        assertEquals("0", recorded.headers["Content-Length"], "empty body must report zero length")
        assertEquals("/bodyless-post", recorded.url.encodedPath)
    }

    @Test
    fun executeBodylessPutSendsEmptyBody() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val request =
            Request.builder()
                .method(Method.PUT)
                .url(server.url("/bodyless-put").toUrl())
                .build()
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
        }
        val recorded = server.takeRequest()
        assertEquals("", recorded.body?.utf8() ?: "")
        assertEquals("0", recorded.headers["Content-Length"], "empty body must report zero length")
    }

    @Test
    fun executeBodylessPatchSendsEmptyBody() {
        server.enqueue(MockResponse.Builder().code(201).build())
        val request =
            Request.builder()
                .method(Method.PATCH)
                .url(server.url("/bodyless-patch").toUrl())
                .build()
        transport.execute(request).use { response ->
            assertEquals(201, response.status.code)
        }
        val recorded = server.takeRequest()
        assertEquals("", recorded.body?.utf8() ?: "")
        assertEquals("0", recorded.headers["Content-Length"], "empty body must report zero length")
    }

    // -------- body on a body-forbidden method (GET/HEAD/TRACE/CONNECT) --------

    @Test
    fun bodyOnForbiddenMethodIsRejectedBeforeDispatch() {
        // OkHttp's Request.Builder.method throws IllegalArgumentException("method GET must not
        // have a request body.") for a GET carrying a body. That unchecked exception would escape
        // execute's @Throws(IOException) contract. The SDK rejects the body at request
        // construction (Request.RequestBuilder.build), so such a request is never built and never
        // reaches the transport.
        for (method in listOf(Method.GET, Method.HEAD, Method.TRACE, Method.CONNECT)) {
            assertFailsWith<IllegalArgumentException>("expected rejection for $method") {
                Request.builder()
                    .method(method)
                    .url(server.url("/forbidden-body").toUrl())
                    .body(RequestBody.create("x", null))
                    .build()
            }
        }
    }

    @Test
    fun bodylessGetDispatchesWithNoBody() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val request =
            Request.builder()
                .method(Method.GET)
                .url(server.url("/bodyless-get").toUrl())
                .build()
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
        }
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("", recorded.body?.utf8() ?: "")
        assertEquals("/bodyless-get", recorded.url.encodedPath)
    }

    // -------- async golden paths --------

    @Test
    fun executeAsyncReturnsBody() {
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
    fun executeAsyncPostSendsBody() {
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

    // -------- async adaptation failures --------

    @Test
    fun executeAsyncDeliversAdaptationFailureThroughFuture() {
        // A non-http(s) URL scheme makes request adaptation throw synchronously inside
        // executeAsync: java.net.URL accepts an ftp:// URL, but OkHttp's Request.Builder.url
        // rejects any scheme other than http/https with IllegalArgumentException. The contract is
        // that executeAsync completes exceptionally on error, so the failure must arrive through
        // the returned future — a future-composing caller's .exceptionally/.handle would never
        // observe a synchronous throw.
        val request =
            Request.builder()
                .method(Method.GET)
                .url(URL("ftp://example.test/async-adapt-fail"))
                .build()
        // Must return a future rather than throwing on the caller's thread.
        val future = transport.executeAsync(request)
        // Completion is synchronous, not merely eventual: the future is already completed
        // exceptionally on return, before anything is awaited.
        assertTrue(
            future.isCompletedExceptionally,
            "adaptation failure must complete the future exceptionally synchronously on return",
        )
        val ex = assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
        assertTrue(
            ex.cause is IllegalArgumentException,
            "adaptation failure must surface as the future's cause, was: ${ex.cause?.let { it::class }}",
        )
        // Assert the message so an unrelated IllegalArgumentException cannot satisfy the test.
        // OkHttp's Request.Builder.url rejects a non-http(s) scheme with "Expected URL scheme
        // 'http' or 'https' but was '<scheme>'".
        assertTrue(
            ex.cause?.message?.contains("scheme") == true,
            "expected OkHttp's URL-scheme rejection message, was: ${ex.cause?.message}",
        )
    }

    // -------- headers round-trip --------

    @Test
    fun headersRoundTrip() {
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
    fun restrictedHeadersAreDropped() {
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

    @Test
    fun headerRejectedByOkHttpStricterRuleIsDroppedNotThrown() {
        // The SDK model layer permits non-ASCII header names/values (it rejects only control
        // characters), but OkHttp restricts both to printable ASCII and throws an unchecked
        // IllegalArgumentException otherwise. The adapter must drop such a header — mirroring the
        // JDK transport — so the exception never escapes execute()'s @Throws(IOException) contract.
        // The offending byte is built with toChar() to keep it unambiguous in source.
        val oUmlaut = 246.toChar() // 'o' with diaeresis (U+00F6): valid UTF-8, not printable ASCII
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val request =
            Request.builder()
                .method(Method.GET)
                .url(server.url("/non-ascii").toUrl())
                .addHeader("X-Uni" + oUmlaut + "code", "plain") // non-ASCII name
                .addHeader("X-Plain", "v" + oUmlaut + "lue") // non-ASCII value
                .addHeader("X-Pass-Through", "kept")
                .build()
        // execute must NOT throw; the rejected headers are simply absent on the wire.
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
        }
        val recorded = server.takeRequest()
        assertNull(recorded.headers["X-Uni" + oUmlaut + "code"], "non-ASCII name must be dropped")
        assertNull(recorded.headers["X-Plain"], "header carrying a non-ASCII value must be dropped")
        assertEquals("kept", recorded.headers["X-Pass-Through"])
    }

    @Test
    fun headerRejectedByOkHttpStricterRuleIsDroppedNotThrownAsync() {
        // The adapter runs on the async path too. A header OkHttp rejects must be dropped so the
        // future completes normally (not exceptionally) and the request still dispatches.
        val oUmlaut = 246.toChar() // 'o' with diaeresis (U+00F6)
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val request =
            Request.builder()
                .method(Method.GET)
                .url(server.url("/non-ascii-async").toUrl())
                .addHeader("X-Uni" + oUmlaut + "code", "plain")
                .addHeader("X-Pass-Through", "kept")
                .build()
        val response = transport.executeAsync(request).get(5, TimeUnit.SECONDS)
        response.use {
            assertEquals(200, it.status.code)
        }
        val recorded = server.takeRequest()
        assertNull(recorded.headers["X-Uni" + oUmlaut + "code"], "non-ASCII name must be dropped")
        assertEquals("kept", recorded.headers["X-Pass-Through"])
    }

    // -------- request body streaming --------

    @Test
    fun requestBodyStreamingMatrix() {
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
    fun responseBodyStreamingMatrix() {
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
    fun connectTimeoutFires() {
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
    fun readTimeoutFires() {
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
    fun syncCancellationPropagates() {
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
    fun asyncCancellationPropagates() {
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

    @Test
    fun asyncResponseThatLosesTheRaceIsClosed() {
        // The consumer can complete/cancel the returned future in the window *between* OkHttp
        // finishing the exchange and Callback.onResponse adapting it. In that race
        // `future.complete(adapted)` returns false and nothing else will ever close `adapted` — a
        // live connection — so the transport must close it. We reproduce the race deterministically
        // without tripping OkHttp's own cancel handling (which, once the call is canceled, routes a
        // completed exchange to onFailure rather than onResponse): a BYO interceptor parks the
        // already-received response on the dispatcher thread while the test completes the future
        // with a decoy value. When the interceptor is released, onResponse runs, observes a
        // future it cannot complete, and must close the adapted response.
        val responseReady = CountDownLatch(1)
        val releaseInterceptor = CountDownLatch(1)
        val racingClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .retryOnConnectionFailure(false)
                .addInterceptor(
                    Interceptor { chain ->
                        val response = chain.proceed(chain.request())
                        // The exchange is complete; signal the test, then block on the dispatcher
                        // thread so the future is already settled by the time onResponse adapts.
                        responseReady.countDown()
                        releaseInterceptor.await(2, TimeUnit.SECONDS)
                        response
                    },
                )
                .build()
        val racingTransport = OkHttpTransport.create(racingClient)

        server.enqueue(MockResponse.Builder().code(200).body("dropped").build())
        val future = racingTransport.executeAsync(simpleGet("/race"))

        assertTrue(responseReady.await(2, TimeUnit.SECONDS), "interceptor should have received the response")
        // Settle the future from the test thread so the in-flight onResponse will lose the
        // `future.complete(adapted)` race. Completing (rather than cancelling) avoids triggering
        // `call.cancel()`, which would otherwise re-route the completed exchange to onFailure.
        val decoy =
            Request.builder()
                .method(Method.GET)
                .url(server.url("/decoy").toUrl())
                .build()
                .let { req ->
                    Response.builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .status(Status.OK)
                        .build()
                }
        assertTrue(future.complete(decoy), "test must win the race so onResponse is the loser")
        // Release the parked interceptor; onResponse now adapts and must close the response it
        // cannot publish. get() returns the decoy immediately and does not wait for onResponse.
        releaseInterceptor.countDown()
        future.get(2, TimeUnit.SECONDS).close()

        // onResponse runs on the dispatcher thread; wait until its closeQuietly has returned the
        // connection to the pool (it becomes idle) before issuing the follow-up. A leaked response
        // would never produce an idle connection here.
        assertTrue(
            awaitCondition { racingClient.connectionPool.idleConnectionCount() >= 1 },
            "the dropped response's connection must be released back to the pool",
        )

        // Proof the connection was released cleanly: the follow-up reuses the same physical
        // connection rather than opening a fresh one.
        server.enqueue(MockResponse.Builder().code(200).body("reused").build())
        val firstIndex = server.takeRequest().connectionIndex
        val followUp = racingTransport.executeAsync(simpleGet("/after-race")).get(5, TimeUnit.SECONDS)
        followUp.use { assertEquals(200, it.status.code) }
        val secondIndex = server.takeRequest().connectionIndex
        assertEquals(
            firstIndex,
            secondIndex,
            "the dropped response's connection must be released so the follow-up reuses it",
        )
    }

    // -------- proxy: unsupported challenge handler --------

    @Test
    fun proxyChallengeHandlerIsAcceptedAndIgnored() {
        // ProxyOptions.challengeHandler is not honoured by the OkHttp transport. Building a
        // transport with one set must be accepted (it logs a loud WARNING and falls back to Basic
        // auth from username/password) rather than throwing. We point the proxy at the
        // MockWebServer so the built transport still routes a normal request through it, proving
        // the proxy wiring survived the challenge-handler branch in applyProxy.
        val proxyOptions =
            ProxyOptions(
                type = ProxyOptions.Type.HTTP,
                address = server.socketAddress,
                username = "proxy-user",
                password = "proxy-pass",
                challengeHandler = BasicChallengeHandler("ignored-user", "ignored-pass"),
            )
        val proxiedTransport =
            OkHttpTransport.builder()
                .proxy(proxyOptions)
                .build()

        server.enqueue(MockResponse.Builder().code(200).body("via-proxy").build())
        // An HTTP forward proxy receives the request directly; MockWebServer records it.
        val request =
            Request.builder()
                .method(Method.GET)
                .url(URL("http://downstream.example/resource"))
                .build()
        proxiedTransport.execute(request).use { response ->
            assertEquals(200, response.status.code, "request routed through the configured proxy must succeed")
            assertEquals("via-proxy", response.body?.source()?.readUtf8())
        }
        val recorded = server.takeRequest()
        // An HTTP forward proxy receives the absolute-form request target; its presence proves the
        // request was routed through the configured proxy (so the proxy wiring survived the
        // challenge-handler branch).
        assertEquals(
            "http://downstream.example/resource",
            recorded.target,
            "request must have been forwarded via the proxy in absolute form",
        )
    }

    // -------- redirect behaviour --------

    @Test
    fun followRedirectsDefaultFalse() {
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
    fun byoFactoryRespectsClient() {
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
    fun protocolMappingHttp11() {
        // MockWebServer defaults to HTTP/1.1 over plain text — verify the protocol is mapped.
        server.enqueue(MockResponse.Builder().code(200).body("h1").build())
        transport.execute(simpleGet("/proto")).use { response ->
            assertEquals(Protocol.HTTP_1_1, response.protocol)
        }
    }

    // -------- non-replayable body: single write, no OkHttp re-send --------

    @Test
    fun nonReplayableBodyIsSentOnce() {
        // A BufferedSource-backed body is single-use: a second writeTo trips its consume
        // guard. Because SdkRequestBodyAdapter now reports isOneShot()==true and the
        // SDK-managed client disables retryOnConnectionFailure, OkHttp writes the body
        // exactly once and the POST succeeds. An interceptor counts request-body writes to
        // prove there is exactly one.
        val writeCount = AtomicInteger(0)
        val countingClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .retryOnConnectionFailure(false)
                .addInterceptor(
                    Interceptor { chain ->
                        if (chain.request().body != null) {
                            writeCount.incrementAndGet()
                        }
                        chain.proceed(chain.request())
                    },
                )
                .build()
        val countingTransport = OkHttpTransport.create(countingClient)

        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val payload = "single-use-body-bytes"
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/one-shot").toUrl())
                .body(nonReplayableBody(payload))
                .build()

        countingTransport.execute(request).use { response ->
            assertEquals(200, response.status.code)
        }

        val recorded = server.takeRequest()
        assertEquals(payload, recorded.body?.utf8())
        assertEquals(1, writeCount.get(), "OkHttp must write the body exactly once")
    }

    @Test
    fun isOneShotReflectsReplayability() {
        // The adapter's isOneShot() drives OkHttp's re-send decision: a non-replayable SDK
        // body must report true (no re-send), a replayable body must report false.
        val nonReplayable = SdkRequestBodyAdapter(nonReplayableBody("x"))
        assertTrue(nonReplayable.isOneShot(), "non-replayable body must be one-shot")

        val replayable = SdkRequestBodyAdapter(RequestBody.create("x", CommonMediaTypes.TEXT_PLAIN))
        assertFalse(replayable.isOneShot(), "replayable body must not be one-shot")
    }

    // -------- vendor status codes are surfaced, not rejected --------

    @Test
    fun vendorStatusCodeIsSurfacedWithoutLeak() {
        // Cloudflare 520 is outside the canonical enum. The total Status.fromCode maps it to
        // a Status carrying the raw code; the response (and its body) must be surfaced and
        // closeable. Closing it returns the connection so a follow-up request reuses the pool
        // rather than leaking the socket.
        server.enqueue(MockResponse.Builder().code(520).body("origin down").build())
        transport.execute(simpleGet("/vendor-status")).use { response ->
            assertEquals(520, response.status.code, "vendor code must be surfaced verbatim")
            assertEquals("origin down", response.body?.source()?.readUtf8())
        }
        // A second call succeeds, proving the first connection was released cleanly.
        server.enqueue(MockResponse.Builder().code(200).body("recovered").build())
        transport.execute(simpleGet("/after-vendor-status")).use { response ->
            assertEquals(200, response.status.code)
            assertEquals("recovered", response.body?.source()?.readUtf8())
        }
    }

    // -------- FileRequestBody zero-copy fast path --------

    @Test
    fun fileRequestBodyIsSentInFull(
        @TempDir tmp: Path,
    ) {
        val bytes = randomBytes(64 * 1024, seed = 99L)
        val file = tmp.resolve("upload.bin")
        Files.write(file, bytes)

        server.enqueue(MockResponse.Builder().code(200).build())
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/file-upload").toUrl())
                .body(FileRequestBody(file, CommonMediaTypes.APPLICATION_OCTET_STREAM))
                .build()
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
        }
        val recorded = server.takeRequest()
        val received = recorded.body?.toByteArray() ?: ByteArray(0)
        assertEquals(md5(bytes), md5(received), "uploaded file bytes must round-trip")
        assertEquals(
            "application/octet-stream",
            recorded.headers["Content-Type"]?.substringBefore(';'),
            "Content-Type from FileRequestBody must reach the wire",
        )
        assertEquals(bytes.size.toString(), recorded.headers["Content-Length"], "length must match count")
    }

    @Test
    fun fileRequestBodyHonoursPositionAndCount(
        @TempDir tmp: Path,
    ) {
        val bytes = randomBytes(10_000, seed = 7L)
        val file = tmp.resolve("slice.bin")
        Files.write(file, bytes)

        val position = 1_000L
        val count = 4_096L
        server.enqueue(MockResponse.Builder().code(200).build())
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/file-slice").toUrl())
                .body(
                    FileRequestBody(
                        file,
                        CommonMediaTypes.APPLICATION_OCTET_STREAM,
                        position,
                        count,
                    ),
                )
                .build()
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
        }
        val recorded = server.takeRequest()
        val received = recorded.body?.toByteArray() ?: ByteArray(0)
        val expected = bytes.copyOfRange(position.toInt(), (position + count).toInt())
        assertContentEquals(expected, received, "only the requested byte range must be uploaded")
    }

    // -------- helpers --------

    private fun simpleGet(path: String): Request =
        Request.builder()
            .method(Method.GET)
            .url(server.url(path).toUrl())
            .build()

    /**
     * Builds a single-use (non-replayable) body backed by a [BufferedSource]. A second
     * `writeTo` trips its consume guard, so any OkHttp re-send would throw — exactly the
     * failure [org.dexpace.sdk.transport.okhttp.internal.SdkRequestBodyAdapter.isOneShot]
     * exists to prevent.
     */
    private fun nonReplayableBody(content: String): RequestBody {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return RequestBody.create(
            Io.provider.source(bytes),
            CommonMediaTypes.TEXT_PLAIN,
            bytes.size.toLong(),
        )
    }

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

    /**
     * Polls [condition] until it is `true` or [timeoutMillis] elapses. Returns whether the
     * condition was met. Used to wait deterministically for asynchronous dispatcher-thread work
     * (e.g. a connection being returned to the pool) without a fixed sleep.
     */
    private fun awaitCondition(
        timeoutMillis: Long = 2_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(10)
        }
        return condition()
    }
}
