/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.okhttp.internal

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.http.request.RequestBody as SdkRequestBody

/**
 * Bridges an SDK [SdkRequestBody] onto OkHttp's streaming [RequestBody].
 *
 * OkHttp drives the write by calling [writeTo] with an `okio.BufferedSink`. To keep the
 * adapter clear of okio types beyond OkHttp's own SPI, the implementation wraps that sink
 * as a [java.io.OutputStream] (`sink.outputStream()`) and feeds it back through
 * [org.dexpace.sdk.core.io.IoProvider.sink] so the SDK body writes through the active I/O
 * provider's [org.dexpace.sdk.core.io.BufferedSink] surface. Bytes never leave the
 * streaming path — the OutputStream wrapper is a thin shim around the okio sink.
 *
 * [contentLength] is taken straight from the SDK body (`-1` if unknown). [contentType] is
 * derived from the SDK body's [org.dexpace.sdk.core.http.common.MediaType] via
 * `toString()` → `okhttp3.MediaType` parse; an unparseable string returns `null` and OkHttp
 * falls back to no `Content-Type`.
 */
internal class SdkRequestBodyAdapter(
    private val sdkBody: SdkRequestBody,
) : RequestBody() {
    override fun contentType(): okhttp3.MediaType? = sdkBody.mediaType()?.toString()?.toMediaTypeOrNull()

    override fun contentLength(): Long = sdkBody.contentLength()

    override fun writeTo(sink: BufferedSink) {
        // The outer .use closes the OkHttp-supplied okio sink wrapper only — OkHttp itself
        // is responsible for the underlying network sink. The inner .use closes the SDK
        // BufferedSink we created via IoProvider, which flushes any staged bytes through.
        sink.outputStream().use { os ->
            Io.provider.sink(os).use { sdkSink ->
                sdkBody.writeTo(sdkSink)
            }
        }
    }
}
