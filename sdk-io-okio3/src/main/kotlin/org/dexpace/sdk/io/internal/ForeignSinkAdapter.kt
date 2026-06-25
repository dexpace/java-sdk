/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Sink

/**
 * Adapts a foreign primitive [Sink] (one whose `write` reads from our [org.dexpace.sdk.core.io.Buffer])
 * to an [okio.Sink] so Okio's buffering machinery can push bytes into it.
 *
 * As with [ForeignSourceAdapter], Okio reuses the same `source` reference across writes for
 * a buffered producer's lifetime; the [OkioBuffer] wrapper is cached.
 *
 * ## Thread-safety
 *
 * Not safe for concurrent use — Okio buffered sinks are single-threaded, and the cached
 * wrapper field is read/written without synchronization.
 */
internal class ForeignSinkAdapter(private val delegate: Sink) : okio.Sink {
    private val wrappers = OkioBufferWrapperCache()

    override fun write(
        source: okio.Buffer,
        byteCount: Long,
    ) {
        delegate.write(wrappers.wrap(source), byteCount)
    }

    override fun flush() {
        delegate.flush()
    }

    override fun timeout(): okio.Timeout = okio.Timeout.NONE

    override fun close() {
        delegate.close()
    }
}
