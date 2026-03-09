package org.dexpace.sdk.core.io

import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * A [BufferedSource] that wraps a raw [Source] and provides efficient buffered reading.
 *
 * Reads from the underlying source fill the internal [Buffer] in [Segment.SIZE] increments.
 * Typed read operations then consume from the buffer.
 *
 * @see Source.buffered
 */
internal class RealBufferedSource(
    /** The upstream source from which bytes are read into [buffer]. */
    private val source: Source
) : BufferedSource {

    /** The internal buffer that caches bytes read from [source]. */
    override val buffer: Buffer = Buffer()

    /** Whether this buffered source has been closed. */
    private var closed = false

    /**
     * Reads up to [byteCount] bytes from this source into [sink].
     *
     * If the buffer is empty, reads one segment from the upstream [source] first.
     *
     * @return the number of bytes read, or -1 if the source is exhausted.
     */
    override fun read(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "closed" }
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L

        if (buffer.size == 0L) {
            val read = source.read(buffer, Segment.SIZE.toLong())
            if (read == -1L) return -1L
        }

        val toRead = minOf(byteCount, buffer.size)
        return buffer.read(sink, toRead)
    }

    /**
     * Returns true if the buffer is empty and the upstream [source] has no more bytes.
     *
     * May trigger a read from the upstream source to determine exhaustion.
     */
    override fun exhausted(): Boolean {
        check(!closed) { "closed" }
        return buffer.exhausted() && source.read(buffer, Segment.SIZE.toLong()) == -1L
    }

    /**
     * Ensures the buffer contains at least [byteCount] bytes.
     *
     * @throws EOFException if the source is exhausted before [byteCount] bytes are buffered.
     */
    override fun require(byteCount: Long) {
        if (!request(byteCount)) {
            throw EOFException("required $byteCount but only ${buffer.size} available")
        }
    }

    /**
     * Attempts to fill the buffer until it contains at least [byteCount] bytes.
     *
     * Reads from the upstream [source] in segment-sized increments until the
     * buffer has enough data or the source is exhausted.
     *
     * @return true if at least [byteCount] bytes are available; false if the source is exhausted.
     */
    override fun request(byteCount: Long): Boolean {
        check(!closed) { "closed" }
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        while (buffer.size < byteCount) {
            if (source.read(buffer, Segment.SIZE.toLong()) == -1L) return false
        }
        return true
    }

    /** Ensures at least 1 byte is buffered, then reads and returns a single byte. */
    override fun readByte(): Byte {
        require(1)
        return buffer.readByte()
    }

    /** Ensures at least 2 bytes are buffered, then reads and returns a big-endian short. */
    override fun readShort(): Short {
        require(2)
        return buffer.readShort()
    }

    /** Ensures at least 2 bytes are buffered, then reads and returns a little-endian short. */
    override fun readShortLe(): Short {
        require(2)
        return buffer.readShortLe()
    }

    /** Ensures at least 4 bytes are buffered, then reads and returns a big-endian int. */
    override fun readInt(): Int {
        require(4)
        return buffer.readInt()
    }

    /** Ensures at least 4 bytes are buffered, then reads and returns a little-endian int. */
    override fun readIntLe(): Int {
        require(4)
        return buffer.readIntLe()
    }

    /** Ensures at least 8 bytes are buffered, then reads and returns a big-endian long. */
    override fun readLong(): Long {
        require(8)
        return buffer.readLong()
    }

    /** Ensures at least 8 bytes are buffered, then reads and returns a little-endian long. */
    override fun readLongLe(): Long {
        require(8)
        return buffer.readLongLe()
    }

    /** Reads all remaining bytes from the upstream source into the buffer, then returns them as a byte array. */
    override fun readByteArray(): ByteArray {
        buffer.writeAll(source)
        return buffer.readByteArray()
    }

    /**
     * Ensures at least [byteCount] bytes are buffered, then reads and returns them as a byte array.
     *
     * @throws EOFException if the source is exhausted before [byteCount] bytes are available.
     */
    override fun readByteArray(byteCount: Long): ByteArray {
        require(byteCount)
        return buffer.readByteArray(byteCount)
    }

    /**
     * Reads up to `sink.length` bytes into [sink].
     *
     * @return the number of bytes read, or -1 if the source is exhausted.
     */
    override fun read(sink: ByteArray): Int {
        return read(sink, 0, sink.size)
    }

    /**
     * Reads up to [byteCount] bytes into [sink] starting at [offset].
     *
     * If the buffer is empty, reads one segment from the upstream [source] first.
     *
     * @return the number of bytes read, or -1 if the source is exhausted.
     */
    override fun read(sink: ByteArray, offset: Int, byteCount: Int): Int {
        check(!closed) { "closed" }

        if (buffer.size == 0L) {
            val read = source.read(buffer, Segment.SIZE.toLong())
            if (read == -1L) return -1
        }

        val toRead = minOf(byteCount.toLong(), buffer.size).toInt()
        return buffer.read(sink, offset, toRead)
    }

    /** Reads all remaining bytes from the upstream source and decodes them as UTF-8. */
    override fun readUtf8(): String {
        buffer.writeAll(source)
        return buffer.readUtf8()
    }

    /**
     * Reads exactly [byteCount] bytes and decodes them as UTF-8.
     *
     * @throws EOFException if the source is exhausted before [byteCount] bytes are available.
     */
    override fun readUtf8(byteCount: Long): String {
        require(byteCount)
        return buffer.readUtf8(byteCount)
    }

    /**
     * Reads a single line of UTF-8 text, consuming the trailing newline (`\n` or `\r\n`).
     *
     * If no newline is found and the source is not exhausted, returns the remaining
     * buffered content as the final line.
     *
     * @return the line without its terminator, or `null` if the source is exhausted.
     */
    override fun readUtf8Line(): String? {
        val newline = indexOf('\n'.code.toByte())
        return if (newline != -1L) {
            buffer.readUtf8Line(newline)
        } else if (!exhausted()) {
            buffer.readUtf8(buffer.size)
        } else {
            null
        }
    }

    /**
     * Reads a single line of UTF-8 text, consuming the trailing newline.
     *
     * @throws EOFException if the source is exhausted before a newline is found.
     */
    override fun readUtf8LineStrict(): String {
        val newline = indexOf('\n'.code.toByte())
        if (newline == -1L) {
            val snippet = Buffer()
            buffer.copyTo(snippet, 0L, minOf(32, buffer.size))
            throw EOFException(
                "\\n not found: size=${buffer.size} content=${snippet.readUtf8()}..."
            )
        }
        return buffer.readUtf8Line(newline)
    }

    /** Reads all remaining bytes from the upstream source and decodes them with [charset]. */
    override fun readString(charset: Charset): String {
        buffer.writeAll(source)
        return buffer.readString(charset)
    }

    /**
     * Reads exactly [byteCount] bytes and decodes them with [charset].
     *
     * @throws EOFException if the source is exhausted before [byteCount] bytes are available.
     */
    override fun readString(byteCount: Long, charset: Charset): String {
        require(byteCount)
        return buffer.readString(byteCount, charset)
    }

    /**
     * Reads all bytes from this source and writes them to [sink].
     *
     * Emits complete segments during transfer to limit buffer growth.
     *
     * @return the total number of bytes written to [sink].
     */
    override fun readAll(sink: Sink): Long {
        var totalBytesWritten = 0L
        while (source.read(buffer, Segment.SIZE.toLong()) != -1L) {
            val emitByteCount = buffer.completeSegmentByteCount()
            if (emitByteCount > 0L) {
                totalBytesWritten += emitByteCount
                sink.write(buffer, emitByteCount)
            }
        }
        if (buffer.size > 0L) {
            totalBytesWritten += buffer.size
            sink.write(buffer, buffer.size)
        }
        return totalBytesWritten
    }

    /**
     * Discards [byteCount] bytes from this source.
     *
     * Reads from the upstream [source] as needed to skip the requested amount.
     *
     * @throws EOFException if the source is exhausted before [byteCount] bytes are skipped.
     */
    override fun skip(byteCount: Long) {
        check(!closed) { "closed" }
        var remaining = byteCount
        while (remaining > 0L) {
            if (buffer.size == 0L && source.read(buffer, Segment.SIZE.toLong()) == -1L) {
                throw EOFException("source exhausted before skipping $byteCount bytes")
            }
            val toSkip = minOf(remaining, buffer.size)
            buffer.skip(toSkip)
            remaining -= toSkip
        }
    }

    /** Returns the index of the first occurrence of [b], or -1 if not found. */
    override fun indexOf(b: Byte): Long = indexOf(b, 0L)

    /**
     * Returns the index of [b] starting the search at [fromIndex].
     *
     * Reads from the upstream [source] as needed to scan beyond the current buffer.
     *
     * @return the index of [b], or -1 if the source is exhausted before [b] is found.
     */
    override fun indexOf(b: Byte, fromIndex: Long): Long {
        check(!closed) { "closed" }
        var searchFrom = fromIndex
        while (true) {
            val result = buffer.indexOf(b, searchFrom)
            if (result != -1L) return result

            val lastBufferSize = buffer.size
            if (source.read(buffer, Segment.SIZE.toLong()) == -1L) return -1L
            searchFrom = maxOf(searchFrom, lastBufferSize)
        }
    }

    /** Returns a [BufferedSource] that reads from this source without consuming its bytes. */
    override fun peek(): BufferedSource = PeekSource(this).buffered()

    /**
     * Returns an [InputStream] that reads from this buffered source.
     *
     * The returned stream delegates to the buffer and upstream source,
     * filling the buffer on demand.
     */
    override fun inputStream(): InputStream {
        return object : InputStream() {
            /**
             * Reads a single byte from the buffer.
             *
             * Fills the buffer from the upstream source if empty.
             *
             * @return the byte as an unsigned int (0–255), or -1 if the source is exhausted.
             */
            override fun read(): Int {
                if (this@RealBufferedSource.closed) throw IllegalStateException("closed")
                if (buffer.size == 0L) {
                    val count = source.read(buffer, Segment.SIZE.toLong())
                    if (count == -1L) return -1
                }
                return buffer.readByte().toInt() and 0xFF
            }

            /** Reads up to [byteCount] bytes into [data] starting at [offset]. */
            override fun read(data: ByteArray, offset: Int, byteCount: Int): Int {
                if (this@RealBufferedSource.closed) throw IllegalStateException("closed")
                return this@RealBufferedSource.read(data, offset, byteCount)
            }

            /** Returns the number of bytes currently buffered and available without blocking. */
            override fun available(): Int {
                if (this@RealBufferedSource.closed) throw IllegalStateException("closed")
                return minOf(buffer.size, Int.MAX_VALUE.toLong()).toInt()
            }

            /** Closes the enclosing buffered source. */
            override fun close() {
                this@RealBufferedSource.close()
            }

            /** Returns a string identifying this input stream and its backing source. */
            override fun toString(): String = "${this@RealBufferedSource}.inputStream()"
        }
    }

    /** Closes the upstream [source] and clears the buffer. */
    override fun close() {
        if (!closed) {
            closed = true
            source.close()
            buffer.clear()
        }
    }

    /** Returns a string identifying this buffered source and its upstream source. */
    override fun toString(): String = "buffer($source)"
}
