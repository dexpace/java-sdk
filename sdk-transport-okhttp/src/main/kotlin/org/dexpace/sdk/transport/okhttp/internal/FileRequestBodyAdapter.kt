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
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.dexpace.sdk.core.http.request.FileRequestBody

/**
 * Streams a [FileRequestBody] onto OkHttp's [RequestBody] with okio's zero-copy file path.
 *
 * The generic [SdkRequestBodyAdapter] would route a file body through
 * `FileChannel.transferTo` into a `Channels.newChannel(sink.outputStream())` shim, defeating
 * the zero-copy intent. This adapter instead opens the file via okio's
 * [okio.FileSystem.openReadOnly] handle and writes the requested region directly into
 * OkHttp's `BufferedSink` (`sink.write(source, count)`), honouring the body's [position]
 * and [count] so byte-range uploads work. okio keeps the transfer on its segment fast path
 * without copying through a user-space `OutputStream`.
 *
 * [FileRequestBody] is replayable (the file is the source of truth), so [isOneShot] returns
 * `false`: OkHttp may safely re-write the body on a connection-failure or auth follow-up,
 * and each write opens a fresh handle positioned at [position].
 *
 * [contentLength] is the body's [count] (the exact number of bytes that will be written).
 * [contentType] is derived from the body's [org.dexpace.sdk.core.http.common.MediaType]; an
 * unparseable value yields `null` and OkHttp emits no `Content-Type`.
 */
internal class FileRequestBodyAdapter(
    private val sdkBody: FileRequestBody,
) : RequestBody() {
    override fun contentType(): okhttp3.MediaType? = sdkBody.mediaType()?.toString()?.toMediaTypeOrNull()

    override fun contentLength(): Long = sdkBody.count

    override fun isOneShot(): Boolean = false

    override fun writeTo(sink: BufferedSink) {
        // Open a read-only handle, position a Source at the body's start offset, and stream
        // exactly `count` bytes into OkHttp's sink. The FileHandle owns the OS file
        // descriptor; closing it releases the fd. The derived Source is closed by the handle.
        FileSystem.SYSTEM.openReadOnly(sdkBody.file.toOkioPath()).use { handle ->
            handle.source(sdkBody.position).use { source ->
                sink.write(source, sdkBody.count)
            }
        }
    }
}
