/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Source

/**
 * Adapts a foreign primitive [Source] (one whose `read` writes into our [org.dexpace.sdk.core.io.Buffer])
 * to an [okio.Source] so it can be fed into Okio's buffering machinery.
 *
 * Okio calls `read(sink: okio.Buffer, byteCount: Long)` with the same `sink` reference for
 * the lifetime of a buffered consumer. The wrapper around that buffer is cached so the adapter
 * doesn't allocate a fresh [OkioBuffer] on every chunk read.
 *
 * ## Thread-safety
 *
 * Not safe for concurrent use — Okio buffered sources are single-threaded, and the cached
 * wrapper field is read/written without synchronization.
 */
internal class ForeignSourceAdapter(private val delegate: Source) : okio.Source {
    private val wrappers = OkioBufferWrapperCache()

    override fun read(
        sink: okio.Buffer,
        byteCount: Long,
    ): Long = delegate.read(wrappers.wrap(sink), byteCount)

    override fun timeout(): okio.Timeout = okio.Timeout.NONE

    override fun close() {
        delegate.close()
    }
}
