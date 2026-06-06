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

/** Install the Okio IoProvider so test response bodies are readable. */
internal fun installIoProvider() {
    Io.installProvider(OkioIoProvider)
}
