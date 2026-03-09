package org.dexpace.sdk.core.io

import java.io.Closeable

/**
 * A source of bytes.
 *
 * Unlike [java.io.InputStream], which reads one byte at a time into caller-supplied arrays,
 * a [Source] reads bulk data into a [Buffer]. This eliminates per-byte method call overhead
 * and enables zero-copy segment transfers between buffers.
 *
 * Implementations should support reading 1 to [byteCount] bytes into [sink] and return the
 * number of bytes read, or -1 if the source is exhausted.
 *
 * @see BufferedSource for a richer interface with typed read operations.
 * @see Buffer which implements both [Source] and [Sink].
 */
interface Source : Closeable {

    /**
     * Removes at least 1 and at most [byteCount] bytes from this source and appends them
     * to [sink].
     *
     * @return the number of bytes read, or -1 if this source is exhausted.
     */
    fun read(sink: Buffer, byteCount: Long): Long

    /**
     * Closes this source and releases any held resources.
     */
    override fun close()
}

/**
 * A [Source] that delegates all operations to [delegate].
 *
 * Extend this to intercept or transform reads without reimplementing the full interface.
 */
open class ForwardingSource(
    protected val delegate: Source
) : Source {

    /** Forwards the read to [delegate]. */
    override fun read(sink: Buffer, byteCount: Long): Long = delegate.read(sink, byteCount)

    /** Closes the [delegate]. */
    override fun close() = delegate.close()

    /** Returns a string identifying this forwarding source and its delegate. */
    override fun toString(): String = "${javaClass.simpleName}($delegate)"
}
