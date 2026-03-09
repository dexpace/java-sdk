package org.dexpace.sdk.core.io

import java.io.InputStream
import java.nio.charset.Charset

/**
 * A [Source] with an internal buffer for efficient, typed read operations.
 *
 * Callers read structured data (bytes, integers, strings) through the convenience methods,
 * and the implementation automatically fills the internal buffer from the underlying source
 * as needed.
 *
 * @see Buffer which implements this interface directly (with no upstream source).
 * @see RealBufferedSource which wraps a raw [Source].
 */
interface BufferedSource : Source {

    /**
     * The internal buffer. Reads from this source populate this buffer; reads from
     * this buffer drain it.
     */
    val buffer: Buffer

    /**
     * Returns true when the buffer is exhausted and the underlying source has no more bytes.
     */
    fun exhausted(): Boolean

    /**
     * Ensures the buffer contains at least [byteCount] bytes, throwing [java.io.EOFException]
     * if the source is exhausted before that.
     */
    fun require(byteCount: Long)

    /**
     * Attempts to ensure the buffer contains at least [byteCount] bytes.
     *
     * @return true if the required bytes are available; false if the source is exhausted.
     */
    fun request(byteCount: Long): Boolean

    /** Reads and returns a single byte. */
    fun readByte(): Byte

    /** Reads and returns a big-endian short. */
    fun readShort(): Short

    /** Reads and returns a little-endian short. */
    fun readShortLe(): Short

    /** Reads and returns a big-endian int. */
    fun readInt(): Int

    /** Reads and returns a little-endian int. */
    fun readIntLe(): Int

    /** Reads and returns a big-endian long. */
    fun readLong(): Long

    /** Reads and returns a little-endian long. */
    fun readLongLe(): Long

    /** Reads and returns all bytes as a byte array. */
    fun readByteArray(): ByteArray

    /** Reads and returns [byteCount] bytes as a byte array. */
    fun readByteArray(byteCount: Long): ByteArray

    /**
     * Reads up to `sink.length` bytes into [sink].
     *
     * @return the number of bytes read, or -1 if the source is exhausted.
     */
    fun read(sink: ByteArray): Int

    /**
     * Reads up to [byteCount] bytes into [sink] starting at [offset].
     *
     * @return the number of bytes read, or -1 if the source is exhausted.
     */
    fun read(sink: ByteArray, offset: Int, byteCount: Int): Int

    /** Reads all remaining bytes and decodes them as UTF-8. */
    fun readUtf8(): String

    /** Reads [byteCount] bytes and decodes them as UTF-8. */
    fun readUtf8(byteCount: Long): String

    /**
     * Reads a line of UTF-8 text, consuming the newline (`\n` or `\r\n`).
     *
     * @return the line without its terminator, or `null` if the source is exhausted.
     */
    fun readUtf8Line(): String?

    /**
     * Reads a line of UTF-8 text, consuming the newline. Throws [java.io.EOFException]
     * if the source is exhausted before a newline is found.
     */
    fun readUtf8LineStrict(): String

    /** Reads all remaining bytes and decodes them with [charset]. */
    fun readString(charset: Charset): String

    /** Reads [byteCount] bytes and decodes them with [charset]. */
    fun readString(byteCount: Long, charset: Charset): String

    /**
     * Reads all bytes from this source and writes them to [sink].
     *
     * @return the total number of bytes written.
     */
    fun readAll(sink: Sink): Long

    /** Discards [byteCount] bytes from this source. */
    fun skip(byteCount: Long)

    /**
     * Returns the index of the first occurrence of [b] in the buffer, or -1 if not found
     * before the source is exhausted.
     */
    fun indexOf(b: Byte): Long

    /**
     * Returns the index of [b] starting from [fromIndex], or -1 if not found.
     */
    fun indexOf(b: Byte, fromIndex: Long): Long

    /**
     * Returns a new [BufferedSource] that can read data from this source without consuming it.
     * The returned source becomes invalid once this source is read past the peeked data.
     */
    fun peek(): BufferedSource

    /** Returns an [InputStream] that reads from this source. */
    fun inputStream(): InputStream
}
