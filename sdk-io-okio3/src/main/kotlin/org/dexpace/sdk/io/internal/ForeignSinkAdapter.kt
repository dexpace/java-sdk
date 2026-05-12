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

    private val timeout = okio.Timeout.NONE
    private var cachedBuffer: okio.Buffer? = null
    private var cachedWrapper: OkioBuffer? = null

    override fun write(source: okio.Buffer, byteCount: Long) {
        // Cache the OkioBuffer wrapper keyed by reference identity of the okio.Buffer Okio
        // passes us. Okio reuses the same source for a buffered producer's lifetime, so this
        // amortizes wrapper allocation to once per producer.
        val wrapper = cachedWrapper.takeIf { source === cachedBuffer }
            ?: OkioBuffer(source).also {
                cachedBuffer = source
                cachedWrapper = it
            }
        delegate.write(wrapper, byteCount)
    }

    override fun flush() {
        delegate.flush()
    }

    override fun timeout(): okio.Timeout = timeout

    override fun close() {
        delegate.close()
    }
}
