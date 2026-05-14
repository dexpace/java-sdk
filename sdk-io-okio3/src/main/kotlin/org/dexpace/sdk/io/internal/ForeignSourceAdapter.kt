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
    private val timeout = okio.Timeout.NONE
    private var cachedBuffer: okio.Buffer? = null
    private var cachedWrapper: OkioBuffer? = null

    override fun read(
        sink: okio.Buffer,
        byteCount: Long,
    ): Long {
        // Cache the OkioBuffer wrapper keyed by reference identity of the okio.Buffer Okio
        // passes us. Okio reuses the same sink for a buffered consumer's lifetime, so this
        // amortizes wrapper allocation to once per consumer.
        val wrapper =
            cachedWrapper.takeIf { sink === cachedBuffer }
                ?: OkioBuffer(sink).also {
                    cachedBuffer = sink
                    cachedWrapper = it
                }
        return delegate.read(wrapper, byteCount)
    }

    override fun timeout(): okio.Timeout = timeout

    override fun close() {
        delegate.close()
    }
}
