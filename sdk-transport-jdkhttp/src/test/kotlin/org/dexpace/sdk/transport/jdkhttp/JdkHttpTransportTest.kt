/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okio.Buffer
import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.util.ProxyOptions
import org.dexpace.sdk.io.OkioIoProvider
import org.dexpace.sdk.transport.jdkhttp.internal.BodyPublishers
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MockWebServer-driven matrix exercising [JdkHttpTransport] over the sync [HttpClient] and
 * async [org.dexpace.sdk.core.client.AsyncHttpClient] paths.
 *
 * `Io.installProvider(OkioIoProvider)` is invoked once in `@BeforeTest` — install is
 * idempotent for the same provider so repeated invocations across tests are a no-op.
 *
 * Streaming body coverage exercises both branches of the `BodyPublishers` threshold:
 *  - 1 KB / 32 KB → eager (`BodyPublishers.ofByteArray`)
 *  - 100 KB / 5 MB → piped (`BodyPublishers.ofInputStream`)
 *
 * The 5 MB streaming case uses pre-allocated byte arrays; the assertion is MD5 round-trip
 * to keep test memory bounded.
 *
 * HTTP/2 over TLS coverage is deferred — MockWebServer's HTTPS setup adds noise, and the
 * JDK client's HTTP/2 path is well-trodden in the JDK's own test suite. The plaintext
 * HTTP/1.1 path covers the SDK adapter logic we own.
 */
class JdkHttpTransportTest {
    @StartStop
    private val server = MockWebServer()

    private lateinit var transport: JdkHttpTransport

    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
        // HTTP/1.1 default — MockWebServer's plaintext server speaks HTTP/1.1 and the JDK
        // falls back to it when ALPN negotiation is unavailable on plaintext sockets.
        transport =
            JdkHttpTransport.builder()
                .httpVersion(JdkHttpTransport.HttpVersion.HTTP_1_1)
                .build()
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

    // -------- body on a body-forbidden method (GET/HEAD/TRACE/CONNECT) --------

    @Test
    fun `bodyOnForbiddenMethodIsRejectedBeforeDispatch`() {
        // The JDK builder ignores a body on GET/HEAD/TRACE, silently dropping it — and the eager
        // BodyPublishers path would already have drained a small consume-once body into a byte
        // array that is then discarded. The SDK rejects the body at request construction
        // (Request.RequestBuilder.build), so such a request is never built and never reaches the
        // transport, and no body is consumed for nothing.
        for (method in listOf(Method.GET, Method.HEAD, Method.TRACE, Method.CONNECT)) {
            assertFailsWith<IllegalArgumentException>("expected rejection for $method") {
                Request.builder()
                    .method(method)
                    .url(server.url("/forbidden-body").toUrl())
                    .body(RequestBody.create("x", CommonMediaTypes.TEXT_PLAIN))
                    .build()
            }
        }
    }

    @Test
    fun `bodylessGetDispatchesWithNoBody`() {
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

    // -------- async adaptation failures --------

    @Test
    fun `executeAsyncDeliversAdaptationFailureThroughFuture`() {
        // A CONNECT request makes request adaptation throw synchronously inside executeAsync
        // (the JDK client reserves CONNECT for internal tunnelling). The contract is that
        // executeAsync completes exceptionally on error, so the failure must arrive through the
        // returned future — a future-composing caller's .exceptionally/.handle would never
        // observe a synchronous throw.
        val request =
            Request.builder()
                .method(Method.CONNECT)
                .url(server.url("/async-adapt-fail").toUrl())
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
        // RequestAdapter rejects CONNECT with "java.net.http.HttpClient does not support
        // user-issued CONNECT requests."
        assertTrue(
            ex.cause?.message?.contains("does not support user-issued CONNECT requests") == true,
            "expected the JDK CONNECT-rejection message, was: ${ex.cause?.message}",
        )
    }

    @Test
    fun `executeAsyncRoutesSynchronousDispatchThrowThroughFuture`() {
        // `sendAsync`'s contract does not promise that every failure is delivered through the
        // returned future — a client may throw synchronously on the caller's thread. The dispatch
        // path runs after a successful adaptation, so this injects a client whose `sendAsync`
        // throws to exercise the post-adapt guard deterministically on every JDK. (The stock JDK
        // client instead returns an already-failed future for, e.g., a closed client; the bridge
        // propagates that and it never reaches the guard, so a real closed client cannot prove
        // this path. The test double does.) The failure must arrive through the returned future,
        // never escape executeAsync on the caller's thread.
        val boom = IllegalStateException("synchronous dispatch failure")
        val throwingTransport = JdkHttpTransport.create(SyncThrowingDispatchClient(boom))

        // Must return a future rather than throwing on the caller's thread.
        val future = throwingTransport.executeAsync(simpleGet("/async-dispatch-throw"))
        // Completion is synchronous, not merely eventual: the future is already completed
        // exceptionally on return, before anything is awaited.
        assertTrue(
            future.isCompletedExceptionally,
            "a synchronous dispatch throw must complete the future exceptionally synchronously on return",
        )
        val ex = assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
        assertEquals(
            boom,
            ex.cause,
            "the dispatch throw must surface verbatim as the future's cause, was: ${ex.cause}",
        )
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
        // A representative slice of the restricted headers — Content-Length, Host,
        // Transfer-Encoding, Connection. The JDK client throws IllegalArgumentException at
        // `HttpRequest.Builder.header(...)` if any of these are set; verifying that this
        // transport's adaptation step builds a valid request (i.e. no exception leaks out) is
        // the primary assertion. The server-side checks confirm the values were not used.
        // (Expect/Upgrade are covered separately in `expectHeaderDoesNotCrashTheRequest`.)
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/restricted").toUrl())
                .body(RequestBody.create("payload", CommonMediaTypes.TEXT_PLAIN))
                .addHeader("Content-Length", "999")
                .addHeader("Host", "bogus.example")
                .addHeader("Transfer-Encoding", "chunked")
                .addHeader("Connection", "close")
                .addHeader("X-Pass-Through", "kept")
                .build()
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
        }
        val recorded = server.takeRequest()
        // JDK recomputes Content-Length from the actual body bytes.
        assertEquals("7", recorded.headers["Content-Length"], "Content-Length should match payload")
        // Host should be the MockWebServer address, not the bogus value supplied.
        val host = recorded.headers["Host"]
        assertNotNull(host)
        assertTrue(!host.contains("bogus.example"), "Host should be recomputed from URL, was $host")
        // Pass-through header still reaches the server.
        assertEquals("kept", recorded.headers["X-Pass-Through"])
    }

    @Test
    fun `headerRejectedByJdkIsDroppedNotThrown`() {
        // The SDK model layer permits a non-ASCII header name (it rejects only control characters),
        // but the JDK's field-name grammar rejects a non-token byte. The adapter must drop it rather
        // than let the unchecked IllegalArgumentException escape execute()'s @Throws(IOException)
        // contract — the same drop-and-log path the OkHttp adapter now mirrors. Built with toChar()
        // so the offending byte is unambiguous in source.
        val oUmlaut = 246.toChar() // 'o' with diaeresis (U+00F6): not an RFC 7230 token char
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val request =
            Request.builder()
                .method(Method.GET)
                .url(server.url("/non-token-name").toUrl())
                .addHeader("X-Uni" + oUmlaut + "code", "plain")
                .addHeader("X-Pass-Through", "kept")
                .build()
        // execute must NOT throw; the rejected header is simply absent on the wire.
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code)
        }
        val recorded = server.takeRequest()
        assertNull(recorded.headers["X-Uni" + oUmlaut + "code"], "non-token name must be dropped")
        assertEquals("kept", recorded.headers["X-Pass-Through"])
    }

    @Test
    fun `headerRejectedByJdkIsDroppedNotThrownAsync`() {
        // The adapter runs on the async path too. A header the JDK rejects must be dropped so the
        // future completes normally (not exceptionally) and the request still dispatches —
        // mirroring the sync drop-not-throw path and the OkHttp transport's async coverage.
        val oUmlaut = 246.toChar() // 'o' with diaeresis (U+00F6): not an RFC 7230 token char
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val request =
            Request.builder()
                .method(Method.GET)
                .url(server.url("/non-token-name-async").toUrl())
                .addHeader("X-Uni" + oUmlaut + "code", "plain")
                .addHeader("X-Pass-Through", "kept")
                .build()
        val response = transport.executeAsync(request).get(5, TimeUnit.SECONDS)
        response.use {
            assertEquals(200, it.status.code)
        }
        val recorded = server.takeRequest()
        assertNull(recorded.headers["X-Uni" + oUmlaut + "code"], "non-token name must be dropped")
        assertEquals("kept", recorded.headers["X-Pass-Through"])
    }

    @Test
    fun `expectHeaderDoesNotCrashTheRequest`() {
        // `Expect` is in the JDK's disallowed-header set — setting it directly on
        // HttpRequest.Builder throws IllegalArgumentException. The adapter must pre-drop it
        // (RestrictedHeaders) so a user-set `Expect: 100-continue` never reaches the builder
        // and the request completes normally.
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/expect").toUrl())
                .body(RequestBody.create("payload", CommonMediaTypes.TEXT_PLAIN))
                .addHeader("Expect", "100-continue")
                .addHeader("Upgrade", "h2c")
                .addHeader("X-Pass-Through", "kept")
                .build()
        transport.execute(request).use { response ->
            assertEquals(200, response.status.code, "Expect/Upgrade must be dropped, not crash the request")
            assertEquals("ok", response.body?.source()?.readUtf8())
        }
        val recorded = server.takeRequest()
        // The dropped headers must not appear on the wire; the pass-through header must.
        assertTrue(recorded.headers["Expect"] == null, "Expect must have been dropped before dispatch")
        assertTrue(recorded.headers["Upgrade"] == null, "Upgrade must have been dropped before dispatch")
        assertEquals("kept", recorded.headers["X-Pass-Through"])
    }

    @Test
    fun `vendorStatusCode520IsSurfacedAndNotLeaked`() {
        // Cloudflare 520 is outside the named-status enum. `Status.fromCode` is total, so the
        // adapter surfaces it as a plain Status(520) instead of throwing before the body is
        // wrapped — the response (and its body) must round-trip and close cleanly.
        server.enqueue(MockResponse.Builder().code(520).body("origin-error").build())
        val request = simpleGet("/vendor-520")
        transport.execute(request).use { response ->
            assertEquals(520, response.status.code, "vendor status 520 must surface, not fail the response")
            assertEquals("origin-error", response.body?.source()?.readUtf8())
        }
    }

    // -------- request body streaming --------

    @Test
    fun `requestBodyStreamingEager`() {
        // Eager path: bodies ≤ 64 KiB are buffered via Io.provider.buffer() and shipped as
        // BodyPublishers.ofByteArray. The 1 KB / 32 KB choices straddle the threshold from
        // below.
        val sizes = listOf(1024, 32 * 1024)
        for (size in sizes) {
            val bytes = randomBytes(size, seed = size.toLong())
            server.enqueue(MockResponse.Builder().code(200).build())
            val request =
                Request.builder()
                    .method(Method.POST)
                    .url(server.url("/stream-up-eager-$size").toUrl())
                    .body(RequestBody.create(bytes, CommonMediaTypes.APPLICATION_OCTET_STREAM))
                    .build()
            transport.execute(request).use { it.body?.source()?.readByteArray() }
            val recorded = server.takeRequest()
            val received = recorded.body?.toByteArray() ?: ByteArray(0)
            assertEquals(md5(bytes), md5(received), "eager-path round-trip mismatch at size=$size")
        }
    }

    @Test
    fun `requestBodyStreamingPiped`() {
        // Streaming path: bodies > 64 KiB use BodyPublishers.ofInputStream with a piped pair
        // and a daemon writer thread. The 100 KB / 5 MB choices straddle the threshold from
        // above; 5 MB is large enough that the producer/consumer ordering matters (single
        // chunk would not exercise the pipe buffer cycle).
        val sizes = listOf(100 * 1024, 5 * 1024 * 1024)
        for (size in sizes) {
            val bytes = randomBytes(size, seed = -size.toLong())
            server.enqueue(MockResponse.Builder().code(200).build())
            val request =
                Request.builder()
                    .method(Method.POST)
                    .url(server.url("/stream-up-piped-$size").toUrl())
                    .body(RequestBody.create(bytes, CommonMediaTypes.APPLICATION_OCTET_STREAM))
                    .build()
            transport.execute(request).use { it.body?.source()?.readByteArray() }
            val recorded = server.takeRequest()
            val received = recorded.body?.toByteArray() ?: ByteArray(0)
            assertEquals(md5(bytes), md5(received), "piped-path round-trip mismatch at size=$size")
        }
    }

    // -------- streaming publisher re-subscription --------

    @Test
    fun `streamingPublisherSurvivesResubscription`() {
        // The JDK re-acquires the BodyPublisher's InputStream once per subscription and
        // re-subscribes on internal resends (407 proxy-auth retry, HTTP/2 GOAWAY replay). The
        // supplier must therefore mint a fresh pipe + writer per subscription so a replayable
        // body produces the full bytes every time — not an exhausted/empty stream on resend.
        // A true proxy 407 flow is awkward to drive over plaintext MockWebServer, so we drive
        // the publisher directly: a > 64 KiB replayable body, drained twice.
        val bytes = randomBytes(128 * 1024, seed = 4242L)
        val body = RequestBody.create(bytes, CommonMediaTypes.APPLICATION_OCTET_STREAM)
        val publisher = BodyPublishers.adaptBody(body)

        val first = drainPublisher(publisher)
        assertContentEquals(bytes, first, "first subscription must yield the full streaming body")

        // Re-subscribe: the same publisher must mint a fresh stream and replay the full body.
        val second = drainPublisher(publisher)
        assertContentEquals(bytes, second, "re-subscription must yield the full streaming body again")
    }

    @Test
    fun `streamingBodyRoundTripsThroughTransport`() {
        // End-to-end confirmation that the per-subscription streaming path delivers a > 64 KiB
        // replayable body intact over the real transport, not just in the direct-publisher test.
        val bytes = randomBytes(256 * 1024, seed = -7L)
        server.enqueue(MockResponse.Builder().code(200).build())
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/stream-resub").toUrl())
                .body(RequestBody.create(bytes, CommonMediaTypes.APPLICATION_OCTET_STREAM))
                .build()
        transport.execute(request).use { it.body?.source()?.readByteArray() }
        val recorded = server.takeRequest()
        val received = recorded.body?.toByteArray() ?: ByteArray(0)
        assertEquals(md5(bytes), md5(received), "streaming body must round-trip intact")
    }

    @Test
    fun `nonReplayableStreamingBodyIsNotBufferedUpFront`() {
        // The core regression for #113: a non-replayable streaming body (> 64 KiB, so it takes the
        // piped path) must stream straight from its source. The old behaviour forced the body
        // replayable via toReplayable() inside adaptBody — draining the ENTIRE body into an
        // in-memory Buffer before a single byte was published. The decisive, non-flaky signal:
        // after adaptBody() returns, the body's writeTo must NOT have been invoked at all (the
        // first writeTo only happens later, on the per-subscription writer thread). Under the old
        // buffering path, writeTo had already run to completion by the time adaptBody returned.
        val total = 4L * 1024L * 1024L // 4 MiB — far above the eager threshold
        val body = CountingNonReplayableBody(total, CommonMediaTypes.APPLICATION_OCTET_STREAM)

        val publisher = BodyPublishers.adaptBody(body)
        assertEquals(
            0L,
            body.bytesWritten(),
            "non-replayable streaming body must NOT be drained up front; writeTo ran during adaptBody",
        )

        // And it still streams the full body when actually subscribed.
        val drained = drainPublisher(publisher)
        assertEquals(total, drained.size.toLong(), "the full body must stream to the subscriber")
        assertEquals(total, body.bytesWritten(), "writeTo must have produced exactly the declared bytes")
    }

    @Test
    fun `oneShotStreamingBodyRefusesResendLoudly`() {
        // A non-replayable (one-shot) streaming body streams its FIRST subscription directly from
        // the source. The JDK re-acquires the publisher's InputStream once per subscription and
        // re-subscribes on internal resends (proxy-auth retry, HTTP/2 GOAWAY). A consumed one-shot
        // body cannot replay, so the SECOND subscription must fail loudly with an IOException
        // rather than ship a truncated/empty body. We drive the publisher directly (a real 407
        // flow is awkward over plaintext MockWebServer): first drain succeeds, second errors.
        val total = 256L * 1024L
        val body = CountingNonReplayableBody(total, CommonMediaTypes.APPLICATION_OCTET_STREAM)
        val publisher = BodyPublishers.adaptBody(body)

        val first = drainPublisher(publisher)
        assertEquals(total, first.size.toLong(), "first subscription must stream the full one-shot body")

        val ex =
            assertFails {
                drainPublisher(publisher)
            }
        // drainPublisher rethrows the publisher's onError as an AssertionError wrapping the cause.
        val cause = generateSequence<Throwable>(ex) { it.cause }.firstOrNull { it is IOException }
        assertTrue(
            cause is IOException,
            "re-subscription of a one-shot body must fail with an IOException, chain was: $ex",
        )
        assertTrue(
            cause.message?.contains("supply a replayable body") == true,
            "the resend failure must explain a replayable body is required, was: ${cause.message}",
        )
    }

    @Test
    fun `streamingBodyThatFailsMidWriteFailsLoudly`() {
        // A non-replayable streaming body (> 64 KiB, piped path) whose writeTo aborts with an
        // IOException after a partial write now streams directly: the writer thread's writeTo
        // throws, the pipe closes prematurely, and the JDK reader observes an IOException. The
        // request must fail loudly (no silent truncation), surfaced through execute's
        // @Throws(IOException) contract.
        val body = PartialThenFailingBody(CommonMediaTypes.APPLICATION_OCTET_STREAM)
        val request =
            Request.builder()
                .method(Method.POST)
                .url(server.url("/unbufferable").toUrl())
                .body(body)
                .build()
        val ex =
            assertFails {
                transport.execute(request).close()
            }
        // IOException is checked; the request must fail with one rather than completing as if the
        // (truncated) body had been sent successfully.
        val ioFailure = generateSequence<Throwable>(ex) { it.cause }.any { it is IOException }
        assertTrue(
            ioFailure,
            "expected the mid-write failure to surface as an IOException, chain was: $ex",
        )
    }

    @Test
    fun `abandonedStreamingPublisherDoesNotStrandWriter`() {
        // A subscription that is acquired but never drained (connect failure, cancellation
        // before body send) would otherwise leave the writer thread blocked forever in
        // PipedOutputStream.write once the 8 KiB pipe fills. Closing the supplied InputStream —
        // which the JDK does when it abandons a subscription — must cancel/interrupt the writer.
        // We open one subscription's stream via the test seam, let the writer fill the pipe and
        // park in write(), then close the stream and assert the writer is released.
        val writerExited = CountDownLatch(1)
        val bytes = randomBytes(512 * 1024, seed = 99L)
        val body =
            LatchedReplayableBody(bytes, CommonMediaTypes.APPLICATION_OCTET_STREAM, writerExited)

        val stream = BodyPublishers.openSubscriptionStreamForTest(body)
        // Give the writer time to fill the 8 KiB pipe and park in PipedOutputStream.write.
        Thread.sleep(200)
        assertEquals(1L, writerExited.count, "writer should still be parked, not yet exited")
        // The kill switch: closing the stream must unblock/cancel the parked writer.
        stream.close()

        assertTrue(
            writerExited.await(3, TimeUnit.SECONDS),
            "closing the abandoned subscription stream must unblock/cancel the writer thread",
        )
    }

    // -------- proxy challengeHandler --------

    @Test
    fun `proxyChallengeHandlerIsAcceptedAndSurfacedAsUnsupported`() {
        // ProxyOptions.challengeHandler cannot be honoured by java.net.http (no per-407 hook).
        // The builder must accept it, log the unsupported warning, and fall back to
        // username/password negotiation rather than throwing. The slf4j-nop test binding has no
        // appender, so we assert the accepted-and-functional contract: the transport builds and
        // dispatches a request without error through the challengeHandler branch of applyProxy.
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val proxyAddress = InetSocketAddress("127.0.0.1", freePort())
        val options =
            ProxyOptions(
                type = ProxyOptions.Type.HTTP,
                address = proxyAddress,
                username = "u",
                password = "p",
                challengeHandler = NoopChallengeHandler,
                bypassAllHosts = true,
            )
        val proxiedTransport =
            JdkHttpTransport.builder()
                .httpVersion(JdkHttpTransport.HttpVersion.HTTP_1_1)
                .proxy(options)
                .build()
        // bypassAllHosts routes every host directly, so the request reaches MockWebServer
        // without touching the (dead) proxy — proving the builder accepted the
        // challengeHandler-bearing ProxyOptions and produced a working transport.
        proxiedTransport.execute(simpleGet("/proxy-challenge")).use { response ->
            assertEquals(200, response.status.code)
            assertEquals("ok", response.body?.source()?.readUtf8())
        }
    }

    // -------- response body streaming --------

    @Test
    fun `responseBodyStreamingMatrix`() {
        val sizes = listOf(1, 1024, 100 * 1024, 5 * 1024 * 1024)
        for (size in sizes) {
            val bytes = randomBytes(size, seed = size.toLong() * 31L)
            val okioBuffer = Buffer().write(bytes)
            server.enqueue(MockResponse.Builder().code(200).body(okioBuffer).build())
            val request = simpleGet("/stream-down-$size")
            transport.execute(request).use { response ->
                val read = response.body?.source()?.readByteArray()
                assertContentEquals(bytes, read, "response round-trip mismatch at size=$size")
            }
        }
    }

    // -------- timeouts --------

    @Test
    fun `connectTimeoutFires`() {
        // Acquire a free port and immediately close the socket so connects fail fast (no
        // SYN-ACK). connectTimeout should bound the wait.
        val deadPort = freePort()
        val shortTimeoutTransport =
            JdkHttpTransport.builder()
                .httpVersion(JdkHttpTransport.HttpVersion.HTTP_1_1)
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
        assertTrue(
            ex is IOException,
            "expected IOException, got ${ex::class}: ${ex.message}",
        )
    }

    @Test
    fun `responseTimeoutFires`() {
        // Headers delay is longer than the response timeout so the timeout fires first,
        // but short enough that MockWebServer's pending-response state has cleared by the
        // time `@StartStop` shuts it down at end of test.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("late")
                .headersDelay(800, TimeUnit.MILLISECONDS)
                .build(),
        )
        val shortResponseTransport =
            JdkHttpTransport.builder()
                .httpVersion(JdkHttpTransport.HttpVersion.HTTP_1_1)
                .responseTimeout(Duration.ofMillis(200))
                .build()
        val ex =
            assertFails {
                shortResponseTransport.execute(simpleGet("/slow")).close()
            }
        assertTrue(
            ex is IOException,
            "expected IOException, got ${ex::class}: ${ex.message}",
        )
        // Clear the interrupt flag if set, then drain MockWebServer's pending dispatch so
        // shutdown doesn't observe a stale task.
        Thread.interrupted()
        Thread.sleep(900)
    }

    // -------- cancellation --------

    @Test
    fun `asyncCancellationPropagates`() {
        // JDK 11+ propagates CompletableFuture.cancel(true) to the underlying exchange
        // without any extra adapter hook. We start a slow call, cancel the future, and
        // assert both that the future surfaces a cancellation and that MockWebServer's
        // shutdown observes a consistent state.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("eventual")
                .headersDelay(800, TimeUnit.MILLISECONDS)
                .build(),
        )
        val future = transport.executeAsync(simpleGet("/cancel-me"))
        // give the JDK a moment to schedule the call onto its dispatcher
        Thread.sleep(100)
        val cancelled = future.cancel(true)
        assertTrue(cancelled, "future.cancel should report success")
        val ex =
            assertFails {
                future.get(2, TimeUnit.SECONDS)
            }
        // CompletableFuture.get on a cancelled future throws CancellationException;
        // chained futures (from .thenApply) surface the cancellation as a CompletionException
        // wrapping a CancellationException.
        assertTrue(
            ex is CancellationException || ex is CompletionException,
            "expected cancellation surface, got ${ex::class}",
        )
        // Clear the interrupt flag and drain MockWebServer's pending dispatch.
        Thread.interrupted()
        Thread.sleep(900)
    }

    @Test
    fun `syncCancellationPropagates`() {
        // Verifies the documented contract: when `execute` surfaces an
        // `InterruptedIOException`, the calling thread's interrupt status is preserved.
        // Pre-interrupt the worker thread (so HttpClient.send sees the interrupt before
        // socket I/O begins) — the adapter must translate that to InterruptedIOException
        // and re-assert the interrupt flag for the caller.
        val errorRef = AtomicReference<Throwable?>()
        val interruptedAfter = AtomicBoolean(false)
        val worker =
            Thread {
                // Pre-interrupt so the JDK's send-side sees the interrupt before completing
                // any socket I/O; the adapter's catch(InterruptedException) clause is what
                // we're exercising.
                Thread.currentThread().interrupt()
                try {
                    transport.execute(simpleGet("/never-served"))
                } catch (t: Throwable) {
                    errorRef.set(t)
                    interruptedAfter.set(Thread.currentThread().isInterrupted)
                }
            }
        worker.start()
        worker.join(3000)
        assertTrue(!worker.isAlive, "worker should exit on interrupt")
        val captured = errorRef.get()
        assertNotNull(captured, "expected InterruptedIOException to surface")
        assertTrue(
            captured is java.io.InterruptedIOException,
            "expected InterruptedIOException, got ${captured::class}: ${captured.message}",
        )
        assertTrue(interruptedAfter.get(), "Thread.currentThread().interrupt() should have been re-asserted")
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
        // The custom JDK client is configured with a specific HTTP/1.1 version, NEVER redirects,
        // and a named single-thread executor. The BYO factory must use that client verbatim
        // (no clone / no override) — we verify that:
        //   1. The request succeeds (sanity).
        //   2. The 302 surfaces unmolested (i.e. the BYO client's NEVER redirect policy applies,
        //      proving the SDK didn't override the underlying client).
        //   3. The BYO executor actually serviced the request — `thenApplyAsync(_, executor())`
        //      on the BYO transport's returned future runs on the BYO executor because the
        //      transport's own `.thenApply` already chained off it.
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/elsewhere")
                .build(),
        )
        val customThreadNameRef = AtomicReference<String?>()
        val customExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "jdkhttp-test-byo").apply { isDaemon = true }
            }
        try {
            val customClient =
                HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .executor(customExecutor)
                    .build()
            val byoTransport = JdkHttpTransport.create(customClient)
            val future = byoTransport.executeAsync(simpleGet("/byo-redirect"))
            // Chain via thenApplyAsync with the SAME executor to confirm the BYO executor is
            // hot-driving completion stages (rather than the JDK's default common pool).
            val response =
                future
                    .thenApplyAsync(
                        { sdkResponse ->
                            customThreadNameRef.set(Thread.currentThread().name)
                            sdkResponse
                        },
                        customExecutor,
                    )
                    .get(5, TimeUnit.SECONDS)
            response.use {
                // (2) The BYO client's redirect policy is honoured — we get the 302 back.
                assertEquals(302, it.status.code, "BYO client's NEVER redirect policy must apply")
                assertEquals("/elsewhere", it.headers.get("Location"))
            }
            val captured = customThreadNameRef.get()
            assertNotNull(captured)
            assertTrue(
                captured.startsWith("jdkhttp-test-byo"),
                "BYO executor should service explicit thenApplyAsync; saw thread name $captured",
            )
        } finally {
            customExecutor.shutdownNow()
        }
    }

    // -------- protocol mapping --------

    @Test
    fun `httpVersionHttp1_1Plaintext`() {
        // Build a transport explicitly pinned to HTTP/1.1; over plaintext MockWebServer this
        // should surface as HTTP_1_1 in the SDK response metadata.
        server.enqueue(MockResponse.Builder().code(200).body("h1").build())
        val h1Transport =
            JdkHttpTransport.builder()
                .httpVersion(JdkHttpTransport.HttpVersion.HTTP_1_1)
                .build()
        h1Transport.execute(simpleGet("/proto")).use { response ->
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

    /**
     * Drains a [HttpRequest.BodyPublisher] (a `Flow.Publisher<ByteBuffer>`) to completion,
     * returning the concatenated bytes. Subscribes fresh each call, so a second invocation on
     * the same publisher exercises the JDK's per-subscription re-acquisition.
     */
    private fun drainPublisher(publisher: HttpRequest.BodyPublisher): ByteArray {
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        publisher.subscribe(
            object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) {
                    subscription.request(Long.MAX_VALUE)
                }

                override fun onNext(item: ByteBuffer) {
                    val chunk = ByteArray(item.remaining())
                    item.get(chunk)
                    out.write(chunk)
                }

                override fun onError(throwable: Throwable) {
                    error.set(throwable)
                    done.countDown()
                }

                override fun onComplete() {
                    done.countDown()
                }
            },
        )
        assertTrue(done.await(5, TimeUnit.SECONDS), "publisher did not complete in time")
        error.get()?.let { throw AssertionError("publisher errored", it) }
        return out.toByteArray()
    }

    /**
     * Replayable body that trips [exited] when its [writeTo] returns or aborts — used to detect
     * that an abandoned-subscription writer was actually unblocked rather than left parked.
     */
    private class LatchedReplayableBody(
        private val bytes: ByteArray,
        private val mediaType: MediaType,
        private val exited: CountDownLatch,
    ) : RequestBody() {
        override fun mediaType(): MediaType = mediaType

        override fun contentLength(): Long = bytes.size.toLong()

        override fun isReplayable(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            try {
                sink.write(bytes)
                sink.flush()
            } finally {
                exited.countDown()
            }
        }
    }

    /**
     * Non-replayable streaming body that emits [total] zero bytes in fixed chunks, tracking the
     * cumulative count written. Used to prove the streaming path does NOT drain the whole body up
     * front: [bytesWritten] must read 0 immediately after `adaptBody`, and rise only once a
     * subscription's writer thread runs. A consume-once guard mirrors the SDK's stream-backed
     * bodies so a (buggy) second drive would fail loudly rather than silently replay.
     */
    private class CountingNonReplayableBody(
        private val total: Long,
        private val mediaType: MediaType,
    ) : RequestBody() {
        private val written = java.util.concurrent.atomic.AtomicLong(0)
        private val consumed = AtomicBoolean(false)

        override fun mediaType(): MediaType = mediaType

        override fun contentLength(): Long = total

        override fun isReplayable(): Boolean = false

        fun bytesWritten(): Long = written.get()

        override fun writeTo(sink: BufferedSink) {
            check(consumed.compareAndSet(false, true)) {
                "CountingNonReplayableBody.writeTo was already called — the body is single-use"
            }
            val chunk = ByteArray(8 * 1024)
            var remaining = total
            while (remaining > 0) {
                val n = minOf(chunk.size.toLong(), remaining).toInt()
                sink.write(chunk, 0, n)
                sink.flush()
                written.addAndGet(n.toLong())
                remaining -= n
            }
        }
    }

    /**
     * Non-replayable body that writes a partial chunk and then aborts with an [IOException]. Its
     * declared length is large enough to route through the streaming (piped) publisher path, and
     * a consume-once guard mirrors the SDK's stream-backed bodies: a second [writeTo] throws
     * [IllegalStateException]. Used to verify the adapter does not re-drive an already-consumed
     * body when buffering it into a replayable copy fails mid-write.
     */
    private class PartialThenFailingBody(
        private val mediaType: MediaType,
    ) : RequestBody() {
        private val consumed = AtomicBoolean(false)

        override fun mediaType(): MediaType = mediaType

        // > 64 KiB so adaptBody routes to the streaming publisher rather than the eager path.
        override fun contentLength(): Long = 128L * 1024L

        override fun isReplayable(): Boolean = false

        override fun writeTo(sink: BufferedSink) {
            check(consumed.compareAndSet(false, true)) {
                "PartialThenFailingBody.writeTo was already called — the body is single-use"
            }
            sink.write(ByteArray(4096))
            sink.flush()
            throw IOException("simulated mid-write failure")
        }
    }

    /** Minimal [org.dexpace.sdk.core.http.auth.ChallengeHandler] stub for the proxy challenge-handler acceptance test. */
    private object NoopChallengeHandler : org.dexpace.sdk.core.http.auth.ChallengeHandler {
        override fun handleChallenges(
            method: Method,
            uri: java.net.URI,
            challenges: List<org.dexpace.sdk.core.http.auth.AuthenticateChallenge>,
            isProxy: Boolean,
        ): org.dexpace.sdk.core.http.auth.AuthorizationHeader? = null

        override fun canHandle(challenges: List<org.dexpace.sdk.core.http.auth.AuthenticateChallenge>): Boolean = false
    }

    /**
     * A minimal [HttpClient] whose async dispatch throws synchronously on the caller's thread,
     * modelling a client (or a future JDK) that does not package every `sendAsync` failure into the
     * returned future. Only [sendAsync] is exercised by [JdkHttpTransport.executeAsync]; the rest
     * are inert stubs that are never invoked on the dispatch path under test. In particular
     * [sslContext] returns `SSLContext.getDefault()` only to satisfy the abstract member — its
     * checked `NoSuchAlgorithmException` and the cost of materialising the JVM default SSL context
     * never apply here because the path under test never reads the context; likewise [send] throws
     * rather than returning a stub response, as the synchronous path is never reached.
     */
    private class SyncThrowingDispatchClient(
        private val failure: RuntimeException,
    ) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.empty()

        override fun followRedirects(): HttpClient.Redirect = HttpClient.Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> = throw UnsupportedOperationException("synchronous send is not used by this test double")

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = throw failure

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = throw failure
    }
}
