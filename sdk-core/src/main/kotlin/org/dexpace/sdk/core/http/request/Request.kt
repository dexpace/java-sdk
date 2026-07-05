/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.generics.Builder
import org.dexpace.sdk.core.generics.checkRequired
import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.common.MediaType
import java.net.MalformedURLException
import java.net.URI
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
 * ## Equality
 *
 * [url] participates in equality by its external form ([URL.toExternalForm]) — a pure string
 * comparison that performs **no** network I/O. `java.net.URL.equals`/`hashCode` resolve the host
 * via DNS (blocking, and wrong for virtual hosts that share an address), so this type compares
 * URLs textually instead. The remaining fields ([method], [headers], [body]) compare by value.
 *
 * @property method HTTP method on the wire.
 * @property url Fully-resolved target URL.
 * @property headers Request headers; may be empty but never `null`.
 * @property body Request body, or `null` for methods without a payload. A non-`null` body is
 *   only valid on a method that permits one ([Method.permitsRequestBody]); building a `GET`,
 *   `HEAD`, `TRACE`, or `CONNECT` request with a body throws `IllegalArgumentException`.
 */
@ConsistentCopyVisibility
public data class Request private constructor(
    val method: Method,
    val url: URL,
    val headers: Headers,
    val body: RequestBody?,
) {
    /**
     * Compares by value. [url] is compared by its external form (a pure string, no DNS lookup);
     * [method], [headers], and [body] compare structurally as the generated equality would.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Request) return false
        return method == other.method &&
            url.toExternalForm() == other.url.toExternalForm() &&
            headers == other.headers &&
            body == other.body
    }

    /**
     * Hash consistent with [equals]: [url] hashes by its external form (no DNS lookup), the rest
     * by value.
     */
    override fun hashCode(): Int {
        var result = method.hashCode()
        result = HASH_MULTIPLIER * result + url.toExternalForm().hashCode()
        result = HASH_MULTIPLIER * result + headers.hashCode()
        result = HASH_MULTIPLIER * result + (body?.hashCode() ?: 0)
        return result
    }

    /**
     * Returns a new [RequestBuilder] initialized with this request's data.
     *
     * @return A new builder.
     */
    public fun newBuilder(): RequestBuilder = RequestBuilder(this)

    /**
     * Mutable builder for [Request]. Implements the generic [Builder] contract so it can be
     * driven by builder-folding pipeline steps.
     */
    public class RequestBuilder : Builder<Request> {
        private var method: Method? = null
        private var url: URL? = null
        private var headersBuilder: Headers.Builder = Headers.Builder()
        private var body: RequestBody? = null

        /**
         * Creates a new builder.
         */
        public constructor()

        /**
         * Creates a builder initialized with the data from [request].
         *
         * @param request The request to copy data from.
         */
        public constructor(request: Request) {
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
        public fun method(method: Method): RequestBuilder =
            apply {
                this.method = method
            }

        /**
         * Sets the request body, or clears it when [body] is `null`.
         *
         * Passing `null` is the way to drop a body carried over by [newBuilder] — for example
         * when downgrading a body-carrying request to `GET`, `HEAD`, `TRACE`, or `CONNECT`, whose
         * [build] rejects a non-`null` body ([Method.permitsRequestBody] is `false` for them).
         *
         * @param body The request body, or `null` to clear any previously-set body.
         * @return This builder.
         */
        public fun body(body: RequestBody?): RequestBuilder =
            apply {
                this.body = body
            }

        /**
         * Sets the URL from a string.
         *
         * @param url The URL as a string.
         * @return This builder.
         * @throws IllegalArgumentException If [url] is not a valid URL.
         */
        public fun url(url: String): RequestBuilder =
            apply {
                try {
                    this.url = URL(url)
                } catch (e: MalformedURLException) {
                    throw IllegalArgumentException("Invalid URL: $url", e)
                }
            }

        /**
         * Sets the URL from a [URI]. Converts via [URI.toURL].
         *
         * @param uri The URI to convert and set.
         * @return This builder.
         * @throws IllegalArgumentException If [uri] cannot be converted to a URL.
         */
        public fun url(uri: URI): RequestBuilder =
            apply {
                try {
                    this.url = uri.toURL()
                } catch (e: MalformedURLException) {
                    throw IllegalArgumentException("Invalid URL: $uri", e)
                }
            }

        /**
         * Sets the URL.
         *
         * @param url The URL as a [URL] object.
         * @return This builder.
         */
        public fun url(url: URL): RequestBuilder =
            apply {
                this.url = url
            }

        /**
         * Adds a header with the specified name and value.
         *
         * @param name The header name.
         * @param value The header value.
         * @return This builder.
         */
        public fun addHeader(
            name: String,
            value: String,
        ): RequestBuilder =
            apply {
                headersBuilder.add(name, value)
            }

        /**
         * Adds a header with the specified name and values.
         *
         * @param name The header name.
         * @param values The header values list.
         * @return This builder.
         */
        public fun addHeader(
            name: String,
            values: List<String>,
        ): RequestBuilder =
            apply {
                headersBuilder.add(name, values)
            }

        /**
         * Sets a header with the specified name and value, replacing any existing values.
         *
         * @param name The header name.
         * @param value The header value.
         * @return This builder.
         */
        public fun setHeader(
            name: String,
            value: String,
        ): RequestBuilder =
            apply {
                headersBuilder.set(name, value)
            }

        /**
         * Sets a header with the specified name and values list, replacing any existing values.
         *
         * @param name The header name.
         * @param values The header values list.
         * @return This builder.
         */
        public fun setHeader(
            name: String,
            values: List<String>,
        ): RequestBuilder =
            apply {
                headersBuilder.set(name, values)
            }

        /**
         * Adds a header using a typed [HttpHeaderName] and a single value.
         *
         * @param name The typed header name.
         * @param value The header value.
         * @return This builder.
         */
        public fun addHeader(
            name: HttpHeaderName,
            value: String,
        ): RequestBuilder =
            apply {
                headersBuilder.add(name, value)
            }

        /**
         * Adds a header using a typed [HttpHeaderName] and a list of values.
         *
         * @param name The typed header name.
         * @param values The header values.
         * @return This builder.
         */
        public fun addHeader(
            name: HttpHeaderName,
            values: List<String>,
        ): RequestBuilder =
            apply {
                headersBuilder.add(name, values)
            }

        /**
         * Convenience overload: adds a header whose value is the wire form of [value].
         * Delegates to [MediaType.toString].
         *
         * @param name The typed header name (e.g. [HttpHeaderName.CONTENT_TYPE]).
         * @param value The media type whose [MediaType.toString] form is used as the header value.
         * @return This builder.
         */
        public fun addHeader(
            name: HttpHeaderName,
            value: MediaType,
        ): RequestBuilder =
            apply {
                headersBuilder.add(name, value.toString())
            }

        /**
         * Sets a header using a typed [HttpHeaderName] and a single value, replacing any existing values.
         *
         * @param name The typed header name.
         * @param value The header value.
         * @return This builder.
         */
        public fun setHeader(
            name: HttpHeaderName,
            value: String,
        ): RequestBuilder =
            apply {
                headersBuilder.set(name, value)
            }

        /**
         * Sets a header using a typed [HttpHeaderName] and a list of values, replacing any existing values.
         *
         * @param name The typed header name.
         * @param values The header values.
         * @return This builder.
         */
        public fun setHeader(
            name: HttpHeaderName,
            values: List<String>,
        ): RequestBuilder =
            apply {
                headersBuilder.set(name, values)
            }

        /**
         * Sets a complete Headers instance, replacing all other headers
         *
         * @param headers The [Headers] instance
         * @return This builder.
         */
        public fun headers(headers: Headers): RequestBuilder =
            apply {
                this.headersBuilder = headers.newBuilder()
            }

        /**
         * Removes all headers with the specified name.
         *
         * @param name The header name.
         * @return This builder.
         */
        public fun removeHeader(name: String): RequestBuilder =
            apply {
                headersBuilder.remove(name)
            }

        /**
         * Removes all headers with the specified typed name.
         *
         * @param name The typed header name.
         * @return This builder.
         */
        public fun removeHeader(name: HttpHeaderName): RequestBuilder =
            apply {
                headersBuilder.remove(name)
            }

        /**
         * Sets the method to [Method.GET] and clears any previously-set body. Equivalent to
         * `method(Method.GET).body(null)`.
         *
         * @return This builder.
         */
        public fun get(): RequestBuilder =
            apply {
                this.method = Method.GET
                this.body = null
            }

        /**
         * Sets the method to [Method.POST] and the body to [body].
         *
         * @param body The request body.
         * @return This builder.
         */
        public fun post(body: RequestBody): RequestBuilder =
            apply {
                this.method = Method.POST
                this.body = body
            }

        /**
         * Sets the method to [Method.PUT] and the body to [body].
         *
         * @param body The request body.
         * @return This builder.
         */
        public fun put(body: RequestBody): RequestBuilder =
            apply {
                this.method = Method.PUT
                this.body = body
            }

        /**
         * Sets the method to [Method.DELETE] and clears any previously-set body. Equivalent to
         * `method(Method.DELETE).body(null)`.
         *
         * DELETE-with-body remains available explicitly via `.method(Method.DELETE).body(body)`.
         *
         * @return This builder.
         */
        public fun delete(): RequestBuilder =
            apply {
                this.method = Method.DELETE
                this.body = null
            }

        /**
         * Sets the method to [Method.HEAD] and clears any previously-set body. Equivalent to
         * `method(Method.HEAD).body(null)`.
         *
         * @return This builder.
         */
        public fun head(): RequestBuilder =
            apply {
                this.method = Method.HEAD
                this.body = null
            }

        /**
         * Sets the method to [Method.PATCH] and the body to [body].
         *
         * @param body The request body.
         * @return This builder.
         */
        public fun patch(body: RequestBody): RequestBuilder =
            apply {
                this.method = Method.PATCH
                this.body = body
            }

        /**
         * Builds the [Request].
         *
         * When no method was set the method defaults to [Method.GET] — but only if no body is
         * present. A body with no method is a forgotten verb (e.g. a dropped `.post(body)` in a
         * refactor), so it is reported as a missing `method` rather than silently defaulting to
         * GET and then failing the body check below with the misleading "GET must not carry a
         * request body".
         *
         * A body set on a method that forbids one ([Method.permitsRequestBody] is `false` —
         * `GET`, `HEAD`, `TRACE`, `CONNECT`) is rejected here rather than passed to a transport:
         * the two reference transports disagree on the case (OkHttp throws, the JDK builder drops
         * the body and may consume a single-use stream for nothing), so the SDK fails fast at
         * construction with one consistent error instead. To downgrade a body-carrying request to
         * one of these methods, clear the body first with `body(null)`.
         *
         * @return The built request.
         * @throws IllegalStateException If a required field is missing — the `url`, or the `method`
         *         when a body is present.
         * @throws IllegalArgumentException If a body is set on a method that forbids one
         *         ([Method.GET], [Method.HEAD], [Method.TRACE], or [Method.CONNECT]).
         */
        override fun build(): Request {
            val resolvedMethod = method ?: if (body == null) Method.GET else checkRequired("method", method)
            val resolvedUrl = checkRequired("url", url)
            require(body == null || resolvedMethod.permitsRequestBody) {
                "$resolvedMethod must not carry a request body; remove the body or use a " +
                    "method that permits one (POST, PUT, PATCH, DELETE, or OPTIONS)."
            }
            return Request(
                method = resolvedMethod,
                url = resolvedUrl,
                headers = headersBuilder.build(),
                body = body,
            )
        }
    }

    public companion object {
        private const val HASH_MULTIPLIER = 31

        /**
         * Returns a fresh empty [RequestBuilder]. Java-friendly entry point matching the
         * `Request.builder()` idiom.
         */
        @JvmStatic
        public fun builder(): RequestBuilder = RequestBuilder()

        /**
         * Builds a GET request for [url]. Equivalent to
         * `builder().url(url).get().build()`.
         *
         * @param url The target URL string; throws [IllegalArgumentException] if invalid.
         * @return A [Request] with method [Method.GET] and no body.
         */
        @JvmStatic
        public fun get(url: String): Request = builder().url(url).get().build()

        /**
         * Builds a POST request for [url] with [body]. Equivalent to
         * `builder().url(url).post(body).build()`.
         *
         * @param url The target URL string; throws [IllegalArgumentException] if invalid.
         * @param body The request body.
         * @return A [Request] with method [Method.POST] and the given [body].
         */
        @JvmStatic
        public fun post(
            url: String,
            body: RequestBody,
        ): Request = builder().url(url).post(body).build()

        /**
         * Builds a PUT request for [url] with [body]. Equivalent to
         * `builder().url(url).put(body).build()`.
         *
         * @param url The target URL string; throws [IllegalArgumentException] if invalid.
         * @param body The request body.
         * @return A [Request] with method [Method.PUT] and the given [body].
         */
        @JvmStatic
        public fun put(
            url: String,
            body: RequestBody,
        ): Request = builder().url(url).put(body).build()

        /**
         * Builds a PATCH request for [url] with [body]. Equivalent to
         * `builder().url(url).patch(body).build()`.
         *
         * @param url The target URL string; throws [IllegalArgumentException] if invalid.
         * @param body The request body.
         * @return A [Request] with method [Method.PATCH] and the given [body].
         */
        @JvmStatic
        public fun patch(
            url: String,
            body: RequestBody,
        ): Request = builder().url(url).patch(body).build()

        /**
         * Builds a DELETE request for [url]. Equivalent to
         * `builder().url(url).delete().build()`.
         *
         * @param url The target URL string; throws [IllegalArgumentException] if invalid.
         * @return A [Request] with method [Method.DELETE] and no body.
         */
        @JvmStatic
        public fun delete(url: String): Request = builder().url(url).delete().build()

        /**
         * Builds a HEAD request for [url]. Equivalent to
         * `builder().url(url).head().build()`.
         *
         * @param url The target URL string; throws [IllegalArgumentException] if invalid.
         * @return A [Request] with method [Method.HEAD] and no body.
         */
        @JvmStatic
        public fun head(url: String): Request = builder().url(url).head().build()
    }
}
