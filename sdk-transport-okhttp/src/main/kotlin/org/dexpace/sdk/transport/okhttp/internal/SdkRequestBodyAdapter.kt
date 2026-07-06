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
 * [contentLength] is taken straight from the SDK body (`-1` if unknown). [contentType] is the
 * caller's explicit request `Content-Type` header ([explicitContentType]) when one was set,
 * otherwise the SDK body's [org.dexpace.sdk.core.http.common.MediaType]; either way it is parsed
 * via `toString()` → `okhttp3.MediaType`, and an unparseable string returns `null`. Reporting the
 * explicit header here is what keeps it authoritative: OkHttp's `BridgeInterceptor` overwrites the
 * request's `Content-Type` with `body.contentType()` when the latter is non-null, so without this
 * the auto-stamped body media type would silently replace a caller-set vendor `Content-Type`. A
 * `null` return (no explicit header AND no body media type, or an unparseable explicit header)
 * leaves the caller's own header untouched.
 *
 * [isOneShot] mirrors the SDK body's replayability: it returns `true` for a non-replayable
 * body so OkHttp will not re-write it on a connection-failure or auth (401/407) follow-up.
 * A second [writeTo] on a single-use SDK body trips its consume-guard and throws
 * `IllegalStateException`; reporting the body as one-shot lets OkHttp fail the call cleanly
 * instead, leaving retry/replay decisions to the SDK pipeline. Replayable bodies report
 * `false`, so OkHttp may safely retransmit them.
 */
internal class SdkRequestBodyAdapter(
    private val sdkBody: SdkRequestBody,
    private val explicitContentType: String? = null,
) : RequestBody() {
    override fun contentType(): okhttp3.MediaType? =
        if (explicitContentType != null) {
            explicitContentType.toMediaTypeOrNull()
        } else {
            sdkBody.mediaType()?.toString()?.toMediaTypeOrNull()
        }

    override fun contentLength(): Long = sdkBody.contentLength()

    override fun isOneShot(): Boolean = !sdkBody.isReplayable()

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
