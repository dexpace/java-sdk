package org.dexpace.sdk.core.io

import java.io.Closeable
import java.io.Flushable

/**
 * A receiver of bytes.
 *
 * Unlike [java.io.OutputStream], which receives one byte at a time from caller-supplied arrays,
 * a [Sink] receives bulk data from a [Buffer]. This enables zero-copy segment transfers between
 * buffers.
 *
 * Implementations should consume exactly [byteCount] bytes from the head of [source].
 *
 * @see BufferedSink for a richer interface with typed write operations.
 * @see Buffer which implements both [Source] and [Sink].
 */
interface Sink : Closeable, Flushable {

    /**
     * Removes [byteCount] bytes from [source] and writes them to this sink.
     */
    fun write(source: Buffer, byteCount: Long)

    /**
     * Pushes all buffered bytes to their final destination.
     */
    override fun flush()

    /**
     * Pushes all buffered bytes to their final destination and releases resources.
     */
    override fun close()
}

/**
 * A [Sink] that delegates all operations to [delegate].
 *
 * Extend this to intercept or transform writes without reimplementing the full interface.
 */
open class ForwardingSink(
    protected val delegate: Sink
) : Sink {

    /** Forwards the write to [delegate]. */
    override fun write(source: Buffer, byteCount: Long) = delegate.write(source, byteCount)

    /** Flushes the [delegate]. */
    override fun flush() = delegate.flush()

    /** Closes the [delegate]. */
    override fun close() = delegate.close()

    /** Returns a string identifying this forwarding sink and its delegate. */
    override fun toString(): String = "${javaClass.simpleName}($delegate)"
}

/**
 * Returns a [Sink] that discards all bytes written to it.
 */
fun blackholeSink(): Sink = object : Sink {
    /** Discards [byteCount] bytes from [source] by skipping them. */
    override fun write(source: Buffer, byteCount: Long) {
        source.skip(byteCount)
    }

    /** No-op — there is nothing to flush. */
    override fun flush() {}

    /** No-op — there are no resources to release. */
    override fun close() {}

    /** Returns a string identifying this as a blackhole sink. */
    override fun toString(): String = "blackhole()"
}
