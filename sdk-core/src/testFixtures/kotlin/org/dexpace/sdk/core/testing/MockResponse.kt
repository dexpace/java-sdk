package org.dexpace.sdk.core.testing

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.Io
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Canned response returned by [FakeHttpClient]. Construct via [Builder].
 *
 * Holds the data needed to materialize a [Response] when the fake client receives a
 * request — [statusCode], [headers], optional [body], and an optional [delay] that the
 * fake honors with `Thread.sleep` to simulate latency. Tests that want deterministic
 * timing should pair with [FixedClock] and read elapsed time from the clock rather than
 * the wall.
 */
class MockResponse internal constructor(
    /** HTTP status code (must map to a known [Status] via [Status.fromCode]). */
    val statusCode: Int,
    /** Response headers; empty by default. */
    val headers: Headers,
    /** Response body, or null if the response has no body. */
    val body: ResponseBody?,
    /** Simulated network latency the fake honors before returning. */
    val delay: Duration,
) {
    /**
     * Converts this canned response into a real [Response] for [request]. Sets
     * `Protocol.HTTP_1_1` since the fake client does not negotiate.
     */
    fun toResponse(request: Request): Response {
        val builder =
            Response.builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .status(Status.fromCode(statusCode))
                .headers(headers)
        if (body != null) {
            builder.body(body)
        }
        return builder.build()
    }

    /**
     * Fluent builder for [MockResponse]. Defaults: 200 OK, no headers, no body, no delay.
     */
    class Builder {
        private var statusCode: Int = 200
        private var headersBuilder: Headers.Builder = Headers.Builder()
        private var body: ResponseBody? = null
        private var delay: Duration = Duration.ZERO

        /** Sets the HTTP status code. */
        fun status(code: Int): Builder = apply { this.statusCode = code }

        /** Adds a header. Multiple calls with the same name accumulate values. */
        fun header(
            name: String,
            value: String,
        ): Builder = apply { headersBuilder.add(name, value) }

        /** Sets the response body explicitly (may be null). */
        fun body(body: ResponseBody?): Builder = apply { this.body = body }

        /**
         * Convenience: encode [content] as UTF-8 and use it as the response body. Sets the
         * `Content-Type` header to [mediaType] when supplied.
         *
         * **Precondition: `Io.installProvider(...)` must have been called** before any test
         * reads this body. The body source is resolved lazily via `Io.provider`; failure to
         * install a provider causes an `IllegalStateException` on the first `body.source()`
         * call rather than at [Builder] construction time. Tests that only inspect headers or
         * status and never read the body may omit provider installation.
         */
        @JvmOverloads
        fun body(
            content: String,
            mediaType: MediaType? = null,
        ): Builder =
            apply {
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                this.body = stringBackedResponseBody(bytes, mediaType)
                if (mediaType != null) {
                    headersBuilder.set("Content-Type", mediaType.toString())
                }
            }

        /** Sets the simulated network latency. Must be non-negative. */
        fun delay(delay: Duration): Builder =
            apply {
                require(!delay.isNegative) { "delay must be non-negative (got $delay)" }
                this.delay = delay
            }

        /** Builds the [MockResponse]. */
        fun build(): MockResponse =
            MockResponse(
                statusCode = statusCode,
                headers = headersBuilder.build(),
                body = body,
                delay = delay,
            )

        private fun stringBackedResponseBody(
            bytes: ByteArray,
            mediaType: MediaType?,
        ): ResponseBody {
            // Resolve `Io.provider` lazily — at first source() call — so a Builder can be
            // constructed before the provider is installed. Tests that read the body must
            // install one beforehand; tests that only check headers / status do not.
            return object : ResponseBody() {
                // Cache the source so source() is idempotent and close() can release it
                // without forcing a fresh provider call after the body has been read.
                private var cachedSource: org.dexpace.sdk.core.io.BufferedSource? = null

                override fun mediaType(): MediaType? = mediaType

                override fun contentLength(): Long = bytes.size.toLong()

                override fun source(): org.dexpace.sdk.core.io.BufferedSource =
                    cachedSource ?: Io.provider.source(bytes).also { cachedSource = it }

                override fun close() {
                    cachedSource?.close()
                }
            }
        }
    }
}
