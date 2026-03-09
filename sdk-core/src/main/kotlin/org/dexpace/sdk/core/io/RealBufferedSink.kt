package org.dexpace.sdk.core.io

import java.io.OutputStream
import java.nio.charset.Charset

/**
 * A [BufferedSink] that wraps a raw [Sink] and provides efficient buffered writing.
 *
 * Writes accumulate in the internal [Buffer]. Complete segments (8 KiB) are automatically
 * flushed to the underlying sink after each write via [emitCompleteSegments].
 *
 * @see Sink.buffered
 */
internal class RealBufferedSink(
    /** The downstream sink to which complete segments are flushed. */
    private val sink: Sink
) : BufferedSink {

    /** The internal buffer that accumulates writes before flushing to [sink]. */
    override val buffer: Buffer = Buffer()

    /** Whether this buffered sink has been closed. */
    private var closed = false

    /** Moves [byteCount] bytes from [source] into the buffer, then flushes complete segments. */
    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "closed" }
        buffer.write(source, byteCount)
        emitCompleteSegments()
    }

    /** Writes a single byte to the buffer and flushes complete segments. */
    override fun writeByte(b: Int): BufferedSink {
        check(!closed) { "closed" }
        buffer.writeByte(b)
        return emitCompleteSegments()
    }

    /** Writes a big-endian short (2 bytes) to the buffer and flushes complete segments. */
    override fun writeShort(s: Int): BufferedSink {
        check(!closed) { "closed" }
        buffer.writeShort(s)
        return emitCompleteSegments()
    }

    /** Writes a little-endian short (2 bytes) to the buffer and flushes complete segments. */
    override fun writeShortLe(s: Int): BufferedSink {
        check(!closed) { "closed" }
        buffer.writeShortLe(s)
        return emitCompleteSegments()
    }

    /** Writes a big-endian int (4 bytes) to the buffer and flushes complete segments. */
    override fun writeInt(i: Int): BufferedSink {
        check(!closed) { "closed" }
        buffer.writeInt(i)
        return emitCompleteSegments()
    }

    /** Writes a little-endian int (4 bytes) to the buffer and flushes complete segments. */
    override fun writeIntLe(i: Int): BufferedSink {
        check(!closed) { "closed" }
        buffer.writeIntLe(i)
        return emitCompleteSegments()
    }

    /** Writes a big-endian long (8 bytes) to the buffer and flushes complete segments. */
    override fun writeLong(v: Long): BufferedSink {
        check(!closed) { "closed" }
        buffer.writeLong(v)
        return emitCompleteSegments()
    }

    /** Writes a little-endian long (8 bytes) to the buffer and flushes complete segments. */
    override fun writeLongLe(v: Long): BufferedSink {
        check(!closed) { "closed" }
        buffer.writeLongLe(v)
        return emitCompleteSegments()
    }

    /** Writes all bytes from [source] to the buffer and flushes complete segments. */
    override fun write(source: ByteArray): BufferedSink {
        check(!closed) { "closed" }
        buffer.write(source)
        return emitCompleteSegments()
    }

    /** Writes [byteCount] bytes from [source] starting at [offset], then flushes complete segments. */
    override fun write(source: ByteArray, offset: Int, byteCount: Int): BufferedSink {
        check(!closed) { "closed" }
        buffer.write(source, offset, byteCount)
        return emitCompleteSegments()
    }

    /**
     * Reads exactly [byteCount] bytes from [source] and writes them to the buffer.
     *
     * Flushes complete segments after each chunk read from [source].
     *
     * @throws java.io.EOFException if [source] is exhausted before [byteCount] bytes are read.
     */
    override fun write(source: Source, byteCount: Long): BufferedSink {
        check(!closed) { "closed" }
        var remaining = byteCount
        while (remaining > 0L) {
            val read = source.read(buffer, remaining)
            if (read == -1L) {
                throw java.io.EOFException("source exhausted before reading $byteCount bytes")
            }
            remaining -= read
            emitCompleteSegments()
        }
        return this
    }

    /**
     * Reads all bytes from [source] and writes them to the buffer.
     *
     * Flushes complete segments after each chunk to limit buffer growth.
     *
     * @return the total number of bytes read from [source].
     */
    override fun writeAll(source: Source): Long {
        check(!closed) { "closed" }
        var totalBytesRead = 0L
        while (true) {
            val readCount = source.read(buffer, Segment.SIZE.toLong())
            if (readCount == -1L) break
            totalBytesRead += readCount
            emitCompleteSegments()
        }
        return totalBytesRead
    }

    /** Encodes [string] as UTF-8, writes it to the buffer, and flushes complete segments. */
    override fun writeUtf8(string: String): BufferedSink {
        check(!closed) { "closed" }
        buffer.writeUtf8(string)
        return emitCompleteSegments()
    }

    /** Encodes a substring of [string] (from [beginIndex] to [endIndex]) as UTF-8 and writes it. */
    override fun writeUtf8(string: String, beginIndex: Int, endIndex: Int): BufferedSink {
        check(!closed) { "closed" }
        buffer.writeUtf8(string, beginIndex, endIndex)
        return emitCompleteSegments()
    }

    /** Encodes [string] with [charset], writes it to the buffer, and flushes complete segments. */
    override fun writeString(string: String, charset: Charset): BufferedSink {
        check(!closed) { "closed" }
        buffer.writeString(string, charset)
        return emitCompleteSegments()
    }

    /**
     * Writes all buffered data to the underlying [sink] without flushing the sink itself.
     *
     * Unlike [flush], this does not propagate downstream.
     */
    override fun emit(): BufferedSink {
        check(!closed) { "closed" }
        val byteCount = buffer.size
        if (byteCount > 0L) {
            sink.write(buffer, byteCount)
        }
        return this
    }

    /**
     * Writes only complete segments to the underlying [sink], keeping any partial tail segment buffered.
     *
     * Called automatically after each write to limit buffer growth while avoiding
     * small writes to the downstream sink.
     */
    override fun emitCompleteSegments(): BufferedSink {
        check(!closed) { "closed" }
        val byteCount = buffer.completeSegmentByteCount()
        if (byteCount > 0L) {
            sink.write(buffer, byteCount)
        }
        return this
    }

    /** Writes all buffered data to the underlying [sink] and then flushes the sink. */
    override fun flush() {
        check(!closed) { "closed" }
        if (buffer.size > 0L) {
            sink.write(buffer, buffer.size)
        }
        sink.flush()
    }

    /**
     * Flushes any remaining buffered bytes to the underlying [sink], then closes it.
     *
     * If the flush throws, the sink is still closed; both exceptions are preserved
     * via [Throwable.addSuppressed].
     */
    override fun close() {
        if (!closed) {
            closed = true
            // Flush remaining bytes, then close the downstream sink.
            var thrown: Throwable? = null
            try {
                if (buffer.size > 0L) {
                    sink.write(buffer, buffer.size)
                }
            } catch (e: Throwable) {
                thrown = e
            }
            try {
                sink.close()
            } catch (e: Throwable) {
                if (thrown == null) thrown = e else thrown.addSuppressed(e)
            }
            buffer.clear()
            if (thrown != null) throw thrown
        }
    }

    /**
     * Returns an [OutputStream] that writes to this buffered sink.
     *
     * The returned stream delegates to [writeByte], [write], [flush], and [close]
     * on this sink.
     */
    override fun outputStream(): OutputStream {
        return object : OutputStream() {
            /** Writes a single byte to the buffer and flushes complete segments. */
            override fun write(b: Int) {
                if (closed) throw IllegalStateException("closed")
                buffer.writeByte(b)
                emitCompleteSegments()
            }

            /** Writes [byteCount] bytes from [data] starting at [offset]. */
            override fun write(data: ByteArray, offset: Int, byteCount: Int) {
                if (closed) throw IllegalStateException("closed")
                buffer.write(data, offset, byteCount)
                emitCompleteSegments()
            }

            /** Flushes all buffered data through to the underlying sink. */
            override fun flush() {
                if (!closed) {
                    this@RealBufferedSink.flush()
                }
            }

            /** Closes the enclosing buffered sink. */
            override fun close() {
                this@RealBufferedSink.close()
            }

            /** Returns a string identifying this output stream and its backing sink. */
            override fun toString(): String = "${this@RealBufferedSink}.outputStream()"
        }
    }

    /** Returns a string identifying this buffered sink and its downstream sink. */
    override fun toString(): String = "buffer($sink)"
}
