package org.dexpace.sdk.core.http.response.exception

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpExceptionFactoryTest {
    @BeforeTest
    fun installProvider() {
        // Required because some tests read body bytes through `Io.provider.source(...)`.
        Io.installProvider(OkioIoProvider)
    }

    // ---- 1. Factory dispatch: status → expected subclass --------------------------------
    //
    // Implemented as one @Test that walks the full canonical table so we don't need to add a
    // junit-jupiter-params dependency just for this. Loop-driven test failures still pinpoint
    // the offending entry through the failure message.

    @Test
    fun `each canonical status maps to its dedicated subclass`() {
        for ((status, expectedClass, expectedRetryable) in canonicalStatusCases) {
            val ex = HttpExceptionFactory.fromResponse(response(status))
            assertEquals(
                expectedClass,
                ex.javaClass,
                "factory must dispatch status ${status.code} (${status.name}) to ${expectedClass.simpleName}",
            )
            assertEquals(expectedRetryable, ex.retryable, "${status.code} retryable flag mismatch")
            assertEquals(status, ex.status)
        }
    }

    // ---- 2. Unknown statuses → 4xx / 5xx fallback ---------------------------------------

    @Test
    fun `unknown 4xx status maps to ClientErrorException`() {
        // 418 (I'M_A_TEAPOT) is a real Status entry but has no dedicated subclass — it
        // should fall through to the 4xx generic. Same for the auth-handshake-style 407.
        val teapot = HttpExceptionFactory.fromResponse(response(Status.IM_A_TEAPOT))
        assertTrue(teapot is ClientErrorException, "418 should be ClientErrorException")
        assertEquals(false, teapot.retryable)

        val proxyAuth = HttpExceptionFactory.fromResponse(response(Status.PROXY_AUTHENTICATION_REQUIRED))
        assertTrue(proxyAuth is ClientErrorException, "407 should be ClientErrorException")
        assertEquals(false, proxyAuth.retryable)
    }

    @Test
    fun `unknown 5xx status maps to ServerErrorException`() {
        val notImpl = HttpExceptionFactory.fromResponse(response(Status.NOT_IMPLEMENTED))
        assertTrue(notImpl is ServerErrorException, "501 should be ServerErrorException")
        assertEquals(true, notImpl.retryable)

        val versionBad = HttpExceptionFactory.fromResponse(response(Status.HTTP_VERSION_NOT_SUPPORTED))
        assertTrue(versionBad is ServerErrorException, "505 should be ServerErrorException")
        assertEquals(true, versionBad.retryable)
    }

    // ---- 3. Non-error statuses must throw -----------------------------------------------

    @Test
    fun `1xx 2xx 3xx statuses throw IllegalArgumentException`() {
        val nonErrorStatuses =
            listOf(
                Status.CONTINUE,
                Status.OK,
                Status.CREATED,
                Status.MOVED_PERMANENTLY,
                Status.NOT_MODIFIED,
            )
        for (status in nonErrorStatuses) {
            assertFailsWith<IllegalArgumentException>("status ${status.code} should reject from factory") {
                HttpExceptionFactory.fromResponse(response(status))
            }
        }
    }

    // ---- 4. Retryable flag per subclass -------------------------------------------------

    @Test
    fun `retryable flag matches the canonical retryable status set`() {
        // 4xx subclasses except 429 — not retryable.
        assertEquals(false, BadRequestException(response(Status.BAD_REQUEST)).retryable)
        assertEquals(false, UnauthorizedException(response(Status.UNAUTHORIZED)).retryable)
        assertEquals(false, ForbiddenException(response(Status.FORBIDDEN)).retryable)
        assertEquals(false, NotFoundException(response(Status.NOT_FOUND)).retryable)
        assertEquals(false, MethodNotAllowedException(response(Status.METHOD_NOT_ALLOWED)).retryable)
        assertEquals(false, ConflictException(response(Status.CONFLICT)).retryable)
        assertEquals(false, GoneException(response(Status.GONE)).retryable)
        assertEquals(false, PayloadTooLargeException(response(Status.PAYLOAD_TOO_LARGE)).retryable)
        assertEquals(false, UnsupportedMediaTypeException(response(Status.UNSUPPORTED_MEDIA_TYPE)).retryable)
        assertEquals(false, UnprocessableEntityException(response(Status.UNPROCESSABLE_ENTITY)).retryable)
        assertEquals(false, ClientErrorException(response(Status.IM_A_TEAPOT)).retryable)

        // 429 + 5xx — retryable.
        assertEquals(true, TooManyRequestsException(response(Status.TOO_MANY_REQUESTS)).retryable)
        assertEquals(true, InternalServerErrorException(response(Status.INTERNAL_SERVER_ERROR)).retryable)
        assertEquals(true, BadGatewayException(response(Status.BAD_GATEWAY)).retryable)
        assertEquals(true, ServiceUnavailableException(response(Status.SERVICE_UNAVAILABLE)).retryable)
        assertEquals(true, GatewayTimeoutException(response(Status.GATEWAY_TIMEOUT)).retryable)
        assertEquals(true, ServerErrorException(response(Status.NOT_IMPLEMENTED)).retryable)
    }

    @Test
    fun `NetworkException is always retryable`() {
        val ex = NetworkException("connect refused")
        assertEquals(true, ex.retryable)
    }

    // ---- 5. bodySnapshot() behavior -----------------------------------------------------

    @Test
    fun `bodySnapshot returns null when body is null`() {
        val ex = HttpExceptionFactory.fromResponse(response(Status.BAD_REQUEST, body = null))
        assertNull(ex.bodySnapshot(), "snapshot must be null when body is null")
    }

    @Test
    fun `bodySnapshot returns up to maxBytes without consuming the body`() {
        val payload = "{\"error\":\"rate-limited\"}"
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val readCount = AtomicInteger(0)
        val body = countingBody(payloadBytes, readCount)
        val ex = HttpExceptionFactory.fromResponse(response(Status.TOO_MANY_REQUESTS, body = body))

        val snapshot = ex.bodySnapshot()
        assertNotNull(snapshot)
        assertContentEquals(payloadBytes, snapshot)

        // The primary source must still hold every byte — the snapshot used peek().
        val primary = body.source().readByteArray()
        assertContentEquals(payloadBytes, primary, "body source must still be intact after bodySnapshot()")
    }

    @Test
    fun `bodySnapshot caps at maxBytes when body is longer`() {
        val payload = ByteArray(8192) { it.toByte() }
        val body = countingBody(payload, AtomicInteger(0))
        val ex = HttpExceptionFactory.fromResponse(response(Status.INTERNAL_SERVER_ERROR, body = body))

        val capped = ex.bodySnapshot(maxBytes = 32)
        assertNotNull(capped)
        assertEquals(32, capped.size)
        assertContentEquals(payload.copyOfRange(0, 32), capped)

        // The primary source must still hold every byte — the snapshot used peek().
        val primary = body.source().readByteArray()
        assertEquals(payload.size, primary.size)
        assertContentEquals(payload, primary)
    }

    @Test
    fun `bodySnapshot returns empty array for zero cap`() {
        val body = countingBody("hello".toByteArray(), AtomicInteger(0))
        val ex = HttpExceptionFactory.fromResponse(response(Status.BAD_REQUEST, body = body))
        val empty = ex.bodySnapshot(maxBytes = 0)
        assertNotNull(empty)
        assertEquals(0, empty.size)
    }

    @Test
    fun `bodySnapshot rejects negative maxBytes`() {
        val body = countingBody("x".toByteArray(), AtomicInteger(0))
        val ex = HttpExceptionFactory.fromResponse(response(Status.BAD_REQUEST, body = body))
        assertFailsWith<IllegalArgumentException> { ex.bodySnapshot(maxBytes = -1) }
    }

    // ---- 6. Header / status passthrough -------------------------------------------------

    @Test
    fun `subclass carries headers and status from the response`() {
        val headers =
            Headers.Builder()
                .add("Retry-After", "5")
                .add("X-Request-Id", "abc")
                .build()
        val ex = HttpExceptionFactory.fromResponse(response(Status.TOO_MANY_REQUESTS, headers = headers))
        assertTrue(ex is TooManyRequestsException)
        assertEquals(Status.TOO_MANY_REQUESTS, ex.status)
        assertEquals("5", ex.headers.get("Retry-After"))
        assertEquals("abc", ex.headers.get("x-request-id"))
    }

    @Test
    fun `default message contains status code and name`() {
        val ex = HttpExceptionFactory.fromResponse(response(Status.NOT_FOUND))
        val msg = assertNotNull(ex.message)
        assertTrue(msg.contains("404"), "message must contain status code; was: $msg")
        assertTrue(msg.contains("NOT_FOUND"), "message must contain status name; was: $msg")
    }

    @Test
    fun `explicit message overrides default`() {
        val ex = NotFoundException(response(Status.NOT_FOUND), message = "User 42 was not found")
        assertEquals("User 42 was not found", ex.message)
    }

    @Test
    fun `cause is propagated`() {
        val root = IllegalStateException("root")
        val ex = ServiceUnavailableException(response(Status.SERVICE_UNAVAILABLE), cause = root)
        assertEquals(root, ex.cause)
    }

    @Test
    fun `subclass is catchable as HttpException base and as RuntimeException`() {
        // Reflection check — Kotlin's `is` smart-cast would mark the second check
        // "always true" after the first succeeds, so use Class.isInstance for both lines.
        val thrown: Throwable = NotFoundException(response(Status.NOT_FOUND))
        assertTrue(HttpException::class.java.isInstance(thrown), "NotFoundException must extend HttpException")
        assertTrue(RuntimeException::class.java.isInstance(thrown), "HttpException must extend RuntimeException")
    }

    @Test
    fun `NetworkException is catchable as IOException but NOT as HttpException`() {
        // Same reasoning as above — use Class.isInstance to avoid smart-cast narrowing
        // between the assertions.
        val net: Throwable = NetworkException("connect failed")
        assertTrue(NetworkException::class.java.isInstance(net))
        assertTrue(java.io.IOException::class.java.isInstance(net), "NetworkException must extend IOException")
        // NetworkException must not extend HttpException — it is a sibling type.
        assertTrue(!HttpException::class.java.isInstance(net), "NetworkException must not extend HttpException")
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun request(): Request =
        Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/")
            .build()

    private fun response(
        status: Status,
        headers: Headers = Headers.Builder().build(),
        body: ResponseBody? = null,
    ): Response {
        val builder =
            Response.builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .status(status)
                .headers(headers)
        if (body != null) builder.body(body)
        return builder.build()
    }

    /**
     * Returns a [ResponseBody] whose `source()` returns an idempotent, cached
     * [BufferedSource] wrapping [bytes]. `peek()` on that source must NOT advance the
     * primary cursor — this is exactly what the snapshot path relies on. [readCount] is
     * incremented every time `source()` is called and is currently used only as an
     * observability hook for future tests.
     */
    private fun countingBody(
        bytes: ByteArray,
        readCount: AtomicInteger,
    ): ResponseBody {
        return object : ResponseBody() {
            // Cache the source so that the primary read after a snapshot reads from the
            // same instance — otherwise the test would inadvertently produce two unrelated
            // sources and the "intact after snapshot" assertion would be trivially true.
            private var cachedSource: BufferedSource? = null

            override fun mediaType(): MediaType? = MediaType.parse("application/json")

            override fun contentLength(): Long = bytes.size.toLong()

            override fun source(): BufferedSource {
                readCount.incrementAndGet()
                return cachedSource ?: Io.provider.source(bytes).also { cachedSource = it }
            }

            override fun close() {
                cachedSource?.close()
            }
        }
    }

    private data class DispatchCase(
        val status: Status,
        val expectedClass: Class<out HttpException>,
        val expectedRetryable: Boolean,
    )

    private val canonicalStatusCases: List<DispatchCase> =
        listOf(
            DispatchCase(Status.BAD_REQUEST, BadRequestException::class.java, false),
            DispatchCase(Status.UNAUTHORIZED, UnauthorizedException::class.java, false),
            DispatchCase(Status.FORBIDDEN, ForbiddenException::class.java, false),
            DispatchCase(Status.NOT_FOUND, NotFoundException::class.java, false),
            DispatchCase(Status.METHOD_NOT_ALLOWED, MethodNotAllowedException::class.java, false),
            DispatchCase(Status.CONFLICT, ConflictException::class.java, false),
            DispatchCase(Status.GONE, GoneException::class.java, false),
            DispatchCase(Status.PAYLOAD_TOO_LARGE, PayloadTooLargeException::class.java, false),
            DispatchCase(Status.UNSUPPORTED_MEDIA_TYPE, UnsupportedMediaTypeException::class.java, false),
            DispatchCase(Status.UNPROCESSABLE_ENTITY, UnprocessableEntityException::class.java, false),
            DispatchCase(Status.TOO_MANY_REQUESTS, TooManyRequestsException::class.java, true),
            DispatchCase(Status.INTERNAL_SERVER_ERROR, InternalServerErrorException::class.java, true),
            DispatchCase(Status.BAD_GATEWAY, BadGatewayException::class.java, true),
            DispatchCase(Status.SERVICE_UNAVAILABLE, ServiceUnavailableException::class.java, true),
            DispatchCase(Status.GATEWAY_TIMEOUT, GatewayTimeoutException::class.java, true),
        )
}
