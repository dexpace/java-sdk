/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider

/** Convenience: build a 200 OK response from this request + body string + optional headers. */
internal fun textResponse(
    request: Request,
    body: String,
    extraHeaders: Map<String, String> = emptyMap(),
): Response {
    val headersBuilder = Headers.Builder()
    extraHeaders.forEach { (k, v) -> headersBuilder.add(k, v) }
    val builder =
        Response.builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .headers(headersBuilder.build())
    builder.body(stringResponseBody(body))
    return builder.build()
}

private fun stringResponseBody(content: String): ResponseBody {
    val bytes = content.toByteArray(Charsets.UTF_8)
    return object : ResponseBody() {
        private var cachedSource: org.dexpace.sdk.core.io.BufferedSource? = null

        override fun mediaType(): org.dexpace.sdk.core.http.common.MediaType? = null

        override fun contentLength(): Long = bytes.size.toLong()

        override fun source(): org.dexpace.sdk.core.io.BufferedSource =
            cachedSource ?: Io.provider.source(bytes).also { cachedSource = it }

        override fun close() {
            cachedSource?.close()
        }
    }
}

/**
 * Convenience: build a 200 OK response whose body may be drained exactly once. A second
 * `source()` call throws, so any double-read of the body is a hard failure rather than a
 * silently-served cache — used to prove a single-pass extractor really reads once.
 */
internal fun singleUseResponse(
    request: Request,
    body: String,
): Response =
    Response.builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .status(Status.OK)
        .headers(Headers.Builder().build())
        .body(singleUseResponseBody(body))
        .build()

private fun singleUseResponseBody(content: String): ResponseBody {
    val bytes = content.toByteArray(Charsets.UTF_8)
    return object : ResponseBody() {
        private var consumed = false

        override fun mediaType(): org.dexpace.sdk.core.http.common.MediaType? = null

        override fun contentLength(): Long = bytes.size.toLong()

        override fun source(): org.dexpace.sdk.core.io.BufferedSource {
            check(!consumed) { "Response body already consumed: source() called twice." }
            consumed = true
            return Io.provider.source(bytes)
        }

        override fun close() {
            consumed = true
        }
    }
}

/** Install the Okio IoProvider so test response bodies are readable. */
internal fun installIoProvider() {
    Io.installProvider(OkioIoProvider)
}
