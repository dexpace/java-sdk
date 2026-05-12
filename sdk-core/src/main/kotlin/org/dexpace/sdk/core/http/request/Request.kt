package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.generics.Builder
import org.dexpace.sdk.core.http.common.Headers
import java.net.MalformedURLException
import java.net.URL

/**
 * Immutable HTTP request the SDK hands to a transport.
 *
 * Constructed exclusively through [RequestBuilder] (the private constructor is enforced by
 * `@ConsistentCopyVisibility`, so even `data class` `copy` is gated through the builder).
 * Use [newBuilder] for non-destructive mutation — it returns a builder pre-filled with this
 * request's fields.
 *
 * ## Thread-safety
 *
 * Instances are immutable and safe to share across threads. The [body], when present, may
 * carry single-use stream state — see [RequestBody] for that contract.
 *
 * @property method HTTP method on the wire.
 * @property url Fully-resolved target URL.
 * @property headers Request headers; may be empty but never `null`.
 * @property body Request body, or `null` for methods without a payload (typical for GET/HEAD).
 */
@ConsistentCopyVisibility
data class Request private constructor(
    val method: Method,
    val url: URL,
    val headers: Headers,
    val body: RequestBody?
) {
    /**
     * Returns a new [RequestBuilder] initialized with this request's data.
     *
     * @return A new builder.
     */
    fun newBuilder(): RequestBuilder = RequestBuilder(this)

    /**
     * Mutable builder for [Request]. Implements the generic [Builder] contract so it can be
     * driven by builder-folding pipeline steps.
     */
    class RequestBuilder : Builder<Request> {
        private var method: Method? = null
        private var url: URL? = null
        private var headersBuilder: Headers.Builder = Headers.Builder()
        private var body: RequestBody? = null

        /**
         * Creates a new builder.
         */
        constructor()

        /**
         * Creates a builder initialized with the data from [request].
         *
         * @param request The request to copy data from.
         */
        constructor(request: Request) {
            this.method = request.method
            this.url = request.url
            this.headersBuilder = request.headers.newBuilder()
            this.body = request.body
        }

        /**
         * Sets the HTTP method.
         *
         * @param method HTTP method, e.g., GET, POST.
         * @return This builder.
         */
        fun method(method: Method) = apply {
            this.method = method
        }

        /**
         * Sets the request body.
         *
         * @param body The request body.
         * @return This builder.
         */
        fun body(body: RequestBody) = apply {
            this.body = body
        }

        /**
         * Sets the URL.
         *
         * @param url The URL as a string.
         * @return This builder.
         * @throws MalformedURLException If [url] is invalid.
         */
        @Throws(MalformedURLException::class)
        fun url(url: String) = apply {
            val parsedUrl = URL(url)
            this.url = parsedUrl
        }

        /**
         * Sets the URL.
         *
         * @param url The URL as an [URL] object.
         * @return This builder.
         */
        fun url(url: URL) = apply {
            this.url = url
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
         * Adds a header with the specified name and values.
         *
         * @param name The header name.
         * @param values The header values list.
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
         * Sets a complete Headers instance, replacing all other headers
         *
         * @param headers The [Headers] instance
         * @return This builder.
         */
        fun headers(headers: Headers) = apply {
            this.headersBuilder = headers.newBuilder()
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
         * Builds the [Request].
         *
         * @return The built request.
         * @throws IllegalStateException If the request is invalid.
         */
        override fun build(): Request {
            val method = this.method ?: throw IllegalStateException("Method is required.")
            val url = this.url ?: throw IllegalStateException("URL is required.")

            return Request(
                method = method,
                url = url,
                headers = headersBuilder.build(),
                body = body
            )
        }
    }

    companion object {
        /**
         * Returns a fresh empty [RequestBuilder]. Java-friendly entry point matching the
         * `Request.builder()` idiom.
         */
        @JvmStatic
        fun builder(): RequestBuilder = RequestBuilder()
    }
}
