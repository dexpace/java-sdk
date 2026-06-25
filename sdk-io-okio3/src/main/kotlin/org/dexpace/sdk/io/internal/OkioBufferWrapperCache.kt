/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.io.internal

/**
 * Caches a single [OkioBuffer] wrapper keyed by the reference identity of the [okio.Buffer] it
 * adapts. Okio hands a buffered consumer (or producer) the same buffer instance for its whole
 * lifetime, so wrapping it once and reusing the wrapper amortizes allocation to once per
 * consumer instead of once per chunk.
 *
 * ## Thread-safety
 *
 * Not safe for concurrent use — the cached fields are read/written without synchronization,
 * matching the single-threaded contract of the Okio buffered source/sink that owns the cache.
 */
internal class OkioBufferWrapperCache {
    private var cachedBuffer: okio.Buffer? = null
    private var cachedWrapper: OkioBuffer? = null

    fun wrap(buffer: okio.Buffer): OkioBuffer =
        cachedWrapper.takeIf { buffer === cachedBuffer }
            ?: OkioBuffer(buffer).also {
                cachedBuffer = buffer
                cachedWrapper = it
            }
}
