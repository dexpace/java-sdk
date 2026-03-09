@file:JvmName("Streams")

package org.dexpace.sdk.core.io

import java.io.InputStream
import java.io.OutputStream

/**
 * Wraps this [Source] with an internal buffer for efficient, typed reads.
 */
fun Source.buffered(): BufferedSource = RealBufferedSource(this)

/**
 * Wraps this [Sink] with an internal buffer for efficient, typed writes.
 */
fun Sink.buffered(): BufferedSink = RealBufferedSink(this)

/**
 * Returns a [Source] that reads from this [InputStream].
 *
 * The returned source reads in chunks of up to [Segment.SIZE] bytes.
 */
fun InputStream.asSource(): Source = InputStreamSource(this)

/**
 * Returns a [Sink] that writes to this [OutputStream].
 */
fun OutputStream.asSink(): Sink = OutputStreamSink(this)

/**
 * Returns a [BufferedSource] that reads from this [InputStream].
 *
 * Convenience for `inputStream.asSource().buffered()`.
 */
fun InputStream.asBufferedSource(): BufferedSource = asSource().buffered()

/**
 * Returns a [BufferedSink] that writes to this [OutputStream].
 *
 * Convenience for `outputStream.asSink().buffered()`.
 */
fun OutputStream.asBufferedSink(): BufferedSink = asSink().buffered()

// ========================================================================
// Internal bridge implementations
// ========================================================================

/**
 * A [Source] that reads from a [java.io.InputStream] into a [Buffer]'s segments.
 *
 * Reads up to [Segment.SIZE] bytes at a time directly into the buffer's tail segment,
 * avoiding intermediate array copies. Recycles unused segments on EOF.
 */
private class InputStreamSource(
    /** The underlying input stream to read from. */
    private val input: InputStream
) : Source {

    /** Reads up to [byteCount] bytes from [input] into [sink]'s tail segment. */
    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L

        val tail = sink.writableSegment(1)
        val maxToCopy = minOf(byteCount, (Segment.SIZE - tail.limit).toLong()).toInt()
        val bytesRead = input.read(tail.data, tail.limit, maxToCopy)
        if (bytesRead == -1) {
            if (tail.pos == tail.limit) {
                // Clean up unused segment.
                sink.head = tail.pop()
                SegmentPool.recycle(tail)
            }
            return -1L
        }
        tail.limit += bytesRead
        sink.size += bytesRead
        return bytesRead.toLong()
    }

    /** Closes the underlying [input] stream. */
    override fun close() {
        input.close()
    }

    /** Returns a string identifying this source and its underlying input stream. */
    override fun toString(): String = "source($input)"
}

/**
 * A [Sink] that writes from a [Buffer]'s segments to a [java.io.OutputStream].
 *
 * Consumes segments from the buffer's head, writing their bytes to the output stream
 * and recycling empty segments.
 */
private class OutputStreamSink(
    /** The underlying output stream to write to. */
    private val out: OutputStream
) : Sink {

    /** Consumes [byteCount] bytes from [source]'s head segments and writes them to [out]. */
    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val head = source.head ?: throw IllegalArgumentException("source is empty")
            val toCopy = minOf(remaining, head.count.toLong()).toInt()
            out.write(head.data, head.pos, toCopy)
            head.pos += toCopy
            remaining -= toCopy
            source.size -= toCopy
            if (head.pos == head.limit) {
                source.head = head.pop()
                SegmentPool.recycle(head)
            }
        }
    }

    /** Flushes the underlying [out] stream. */
    override fun flush() {
        out.flush()
    }

    /** Closes the underlying [out] stream. */
    override fun close() {
        out.close()
    }

    /** Returns a string identifying this sink and its underlying output stream. */
    override fun toString(): String = "sink($out)"
}
