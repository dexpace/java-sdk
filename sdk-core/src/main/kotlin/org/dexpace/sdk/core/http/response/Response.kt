package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.generics.Builder
import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Request
import java.io.Closeable
import java.io.IOException

/**
 * Immutable HTTP response produced by a transport.
 *
 * Constructed exclusively through [ResponseBuilder]; the private constructor combined with
 * `@ConsistentCopyVisibility` gates the data-class `copy` too. Use [newBuilder] for
 * non-destructive mutation.
 *
 * Implements [Closeable] so callers can release the response body in a `use {}` /
 * try-with-resources block. [close] is idempotent and forwards to the body.
 *
 * ## Thread-safety
 *
 * The header/metadata surface is immutable and safe to share. The [body], when present,
 * carries single-use stream state — see [ResponseBody] for that contract.
 *
 * @property request Request that produced this response.
 * @property protocol Wire protocol negotiated for the exchange.
 * @property status Parsed HTTP status.
 * @property message Reason phrase from the status line, or `null` if absent.
 * @property headers Response headers; may be empty but never `null`.
 * @property body Response body, or `null` for status codes without a payload (e.g. 204).
 */
@ConsistentCopyVisibility
data class Response private constructor(
    val request: Request,
    val protocol: Protocol,
    val status: Status,
    val message: String?,
    val headers: Headers,
    val body: ResponseBody?
) : Closeable {
    /**
     * Returns a new [ResponseBuilder] initialized with this response's data.
     *
     * @return A new builder.
     */
    fun newBuilder(): ResponseBuilder = ResponseBuilder(this)

    /**
     * Closes the response body and releases any transport resources. Idempotent — the
     * underlying body is expected to no-op on a second close. After this call the body
     * cannot be read.
     *
     * @throws IOException If the body's close fails.
     */
    @Throws(IOException::class)
    override fun close() {
        body?.close()
    }

    /**
     * Mutable builder for [Response]. Implements the generic [Builder] contract so it can be
     * driven by builder-folding pipeline steps.
     */
    class ResponseBuilder : Builder<Response> {
        private var request: Request? = null
        private var protocol: Protocol? = null
        private var status: Status? = null
        private var message: String? = null
        private var headersBuilder: Headers.Builder = Headers.Builder()
        private var body: ResponseBody? = null

        /**
         * Creates an empty builder.
         */
        constructor()

        /**
         * Creates a builder initialized with the data from [response].
         *
         * @param response The response to copy data from.
         */
        constructor(response: Response) {
            this.request = response.request
            this.protocol = response.protocol
            this.status = response.status
            this.message = response.message
            this.headersBuilder = response.headers.newBuilder()
            this.body = response.body
        }

        /**
         * Sets the request that initiated this response.
         *
         * @param request The originating request.
         * @return This builder.
         */
        fun request(request: Request) = apply {
            this.request = request
        }

        /**
         * Sets the protocol used for the response.
         *
         * @param protocol The protocol (e.g., HTTP/1.1).
         * @return This builder.
         */
        fun protocol(protocol: Protocol) = apply {
            this.protocol = protocol
        }

        /**
         * Sets the HTTP status code.
         *
         * @param status The HTTP status code.
         * @return This builder.
         */
        fun status(status: Status) = apply {
            this.status = status
        }

        /**
         * Sets the HTTP reason phrase.
         *
         * @param message The reason phrase.
         * @return This builder.
         */
        fun message(message: String) = apply {
            this.message = message
        }

        /**
         * Adds a header with the specified name and value.
         *
         * @param name The header name.
         * @param value The header value.
         * @return This builder.
         */
        fun addHeader(name: String, value: String) = apply {
            headersBuilder.add(name, value)
        }

        /**
         * Adds a header with the specified name and values list.
         *
         * @param name The header name.
         * @param values The header value.
         * @return This builder.
         */
        fun addHeader(name: String, values: List<String>) = apply {
            headersBuilder.add(name, values)
        }

        /**
         * Sets a header with the specified name and value, replacing any existing values.
         *
         * @param name The header name.
         * @param value The header value.
         * @return This builder.
         */
        fun setHeader(name: String, value: String) = apply {
            headersBuilder.set(name, value)
        }

        /**
         * Sets a header with the specified name and values list, replacing any existing values.
         *
         * @param name The header name.
         * @param values The header values list.
         * @return This builder.
         */
        fun setHeader(name: String, values: List<String>) = apply {
            headersBuilder.set(name, values)
        }

        /**
         * Removes all headers with the specified name.
         *
         * @param name The header name.
         * @return This builder.
         */
        fun removeHeader(name: String) = apply {
            headersBuilder.remove(name)
        }

        /**
         * Sets the response headers.
         *
         * @param headers The response headers.
         * @return This builder.
         */
        fun headers(headers: Headers) = apply {
            headersBuilder = headers.newBuilder()
        }

        /**
         * Sets the response body.
         *
         * @param body The response body, or null if none.
         * @return This builder.
         */
        fun body(body: ResponseBody?) = apply {
            this.body = body
        }

        /**
         * Builds the [Response].
         *
         * @return The built response.
         * @throws IllegalStateException in case required fields are missing.
         */
        override fun build(): Response {
            val request = this.request ?: throw IllegalStateException("request is required")
            val protocol = this.protocol ?: throw IllegalStateException("protocol is required")
            val code = this.status ?: throw IllegalStateException("status is required")

            return Response(
                request = request,
                protocol = protocol,
                status = code,
                message = message,
                headers = headersBuilder.build(),
                body = body
            )
        }
    }

    companion object {
        /**
         * Returns a fresh empty [ResponseBuilder]. Java-friendly entry point matching the
         * `Response.builder()` idiom.
         */
        @JvmStatic
        fun builder() = ResponseBuilder()
    }
}
