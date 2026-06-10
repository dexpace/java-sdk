/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.Io
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpResponse
import org.dexpace.sdk.core.http.request.Request as SdkRequest
import org.dexpace.sdk.core.http.response.Response as SdkResponse
import org.dexpace.sdk.core.http.response.ResponseBody as SdkResponseBody

/**
 * Adapts a JDK [HttpResponse]&lt;[InputStream]&gt; into the SDK's [SdkResponse], preserving:
 *
 *  - status (via [Status.fromCode], which is total — unknown/vendor codes such as nginx 499
 *    or Cloudflare 520 surface as an unnamed [Status] rather than throwing).
 *  - protocol (mapped through [toSdkProtocol]).
 *  - headers (each name/value pair copied from `HttpResponse.headers().map()`, multi-value
 *    semantics preserved by re-adding each value individually).
 *  - body — the response InputStream returned by `BodyHandlers.ofInputStream()` is wrapped
 *    via [Io.provider.source] so the active IoProvider supplies the `BufferedSource`.
 *    Closing the SDK response cascades into the SDK source, which closes the
 *    [InputStream], which releases the underlying connection back to the JDK client's pool.
 *
 * The body's `Content-Type` is parsed via [MediaType.parse]; an unparseable value is
 * silently downgraded to `null` (the SDK contract allows `mediaType()` to be `null`).
 *
 * The JDK response carries no reason phrase (HTTP/2 dropped it, and the JDK normalises away
 * the HTTP/1.1 form too), so the SDK `message` field stays unset. Callers wanting a reason
 * string should look up [Status.name] instead.
 *
 * If adaptation throws anywhere between acquiring the body and building the [SdkResponse], the
 * live response [InputStream] is closed before the throwable propagates so the connection is
 * never leaked — both the synchronous `execute` and asynchronous `sendAsync().thenApply`
 * paths route through this method, so both inherit the guard.
 */
internal class ResponseAdapter {
    fun adapt(
        sdkRequest: SdkRequest,
        jdkResponse: HttpResponse<InputStream>,
    ): SdkResponse {
        // Wrap body acquisition through Response construction in a try/catch that closes the
        // live response InputStream on any throw. Without this, a failure between obtaining the
        // body and building the SDK Response (e.g. Io.provider not installed, or an OOM while
        // wrapping) would orphan the InputStream and leak the underlying connection back-pressure
        // slot. The body's own InputStream is the only native resource to release here; closing
        // it returns the connection to the JDK client's pool. Mirrors the OkHttp transport's
        // close-on-throw guard for cross-transport parity. `Status.fromCode` is total and never
        // throws, but the body-wrap and builder calls still can.
        val body: InputStream = jdkResponse.body()
        return try {
            val headersBuilder = Headers.Builder()
            // HttpResponse.headers().map() returns a Map<String, List<String>> with case-insensitive
            // key lookup but case-preserved keys; the SDK Headers builder normalises names itself
            // on `add(...)`, so we can pass the keys through verbatim.
            jdkResponse.headers().map().forEach { (name, values) ->
                for (value in values) {
                    headersBuilder.add(name, value)
                }
            }
            val headers = headersBuilder.build()
            val mediaType = parseContentType(headers.get("Content-Type"))
            val contentLength = parseContentLength(headers.get("Content-Length"))
            val bufferedSource = Io.provider.source(body)
            val sdkBody: SdkResponseBody = SdkResponseBody.create(bufferedSource, mediaType, contentLength)
            SdkResponse.builder()
                .request(sdkRequest)
                .protocol(toSdkProtocol(jdkResponse.version()))
                .status(Status.fromCode(jdkResponse.statusCode()))
                .headers(headers)
                .body(sdkBody)
                .build()
        } catch (t: Throwable) {
            try {
                body.close()
            } catch (_: IOException) {
                // Suppress close failures — the original throwable is the meaningful signal.
            }
            throw t
        }
    }

    /**
     * Parses the `Content-Type` header value into an SDK [MediaType]. Returns `null` when
     * the header is absent or the value cannot be parsed — surfacing the body without a
     * typed [MediaType] is the documented SDK behaviour rather than failing the response.
     */
    private fun parseContentType(rawValue: String?): MediaType? {
        if (rawValue == null) return null
        return try {
            MediaType.parse(rawValue)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Parses the `Content-Length` header value into a [Long]. Returns `-1L` (the SDK's
     * "unknown" sentinel) when the header is absent, non-numeric, or negative — matching
     * the contract advertised by [SdkResponseBody.contentLength].
     */
    private fun parseContentLength(rawValue: String?): Long {
        if (rawValue == null) return -1L
        val parsed = rawValue.toLongOrNull() ?: return -1L
        return if (parsed >= 0) parsed else -1L
    }

    /**
     * Maps a JDK [HttpClient.Version] onto the SDK [Protocol]. The JDK client exposes two
     * versions; SPDY / QUIC / prior-knowledge HTTP/2 do not exist in the JDK 11+ client. Any
     * future JDK version that adds a new enum constant falls through to HTTP/1.1 — this is a
     * conservative default; the SDK pipeline does not consult the protocol field for any
     * blocking decision, so an HTTP/3-shaped response surfaces as HTTP/1.1 in the metadata
     * but the bytes round-trip correctly.
     */
    private fun toSdkProtocol(v: HttpClient.Version): Protocol =
        when (v) {
            HttpClient.Version.HTTP_1_1 -> Protocol.HTTP_1_1
            HttpClient.Version.HTTP_2 -> Protocol.HTTP_2
        }
}
