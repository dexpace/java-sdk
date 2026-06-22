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
         * Sets the URL.
         *
         * @param url The URL as a string.
         * @return This builder.
         * @throws MalformedURLException If [url] is invalid.
         */
        @Throws(MalformedURLException::class)
        public fun url(url: String): RequestBuilder =
            apply {
                this.url = URL(url)
            }

        /**
         * Sets the URL.
         *
         * @param url The URL as an [URL] object.
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
         * Builds the [Request].
         *
         * A body set on a method that forbids one ([Method.permitsRequestBody] is `false` —
         * `GET`, `HEAD`, `TRACE`, `CONNECT`) is rejected here rather than passed to a transport:
         * the two reference transports disagree on the case (OkHttp throws, the JDK builder drops
         * the body and may consume a single-use stream for nothing), so the SDK fails fast at
         * construction with one consistent error instead. To downgrade a body-carrying request to
         * one of these methods, clear the body first with `body(null)`.
         *
         * @return The built request.
         * @throws IllegalStateException If a required field is missing.
         * @throws IllegalArgumentException If a body is set on a method that forbids one
         *         ([Method.GET], [Method.HEAD], [Method.TRACE], or [Method.CONNECT]).
         */
        override fun build(): Request {
            val resolvedMethod = checkRequired("method", method)
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
    }
}
