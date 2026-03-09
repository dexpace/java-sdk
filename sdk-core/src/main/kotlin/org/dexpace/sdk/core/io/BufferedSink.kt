package org.dexpace.sdk.core.io

import java.io.OutputStream
import java.nio.charset.Charset

/**
 * A [Sink] with an internal buffer for efficient, typed write operations.
 *
 * Callers write structured data (bytes, integers, strings) through the convenience methods.
 * The internal buffer batches writes and flushes complete segments to the underlying sink
 * automatically.
 *
 * @see Buffer which implements this interface directly (with no downstream sink).
 * @see RealBufferedSink which wraps a raw [Sink].
 */
interface BufferedSink : Sink {

    /**
     * The internal buffer. Writes to this sink populate this buffer; flushes drain it
     * to the downstream sink.
     */
    val buffer: Buffer

    /** Writes a single byte. */
    fun writeByte(b: Int): BufferedSink

    /** Writes a big-endian short. */
    fun writeShort(s: Int): BufferedSink

    /** Writes a little-endian short. */
    fun writeShortLe(s: Int): BufferedSink

    /** Writes a big-endian int. */
    fun writeInt(i: Int): BufferedSink

    /** Writes a little-endian int. */
    fun writeIntLe(i: Int): BufferedSink

    /** Writes a big-endian long. */
    fun writeLong(v: Long): BufferedSink

    /** Writes a little-endian long. */
    fun writeLongLe(v: Long): BufferedSink

    /** Writes all bytes from [source]. */
    fun write(source: ByteArray): BufferedSink

    /** Writes [byteCount] bytes from [source] starting at [offset]. */
    fun write(source: ByteArray, offset: Int, byteCount: Int): BufferedSink

    /** Writes all bytes from [source]. */
    fun write(source: Source, byteCount: Long): BufferedSink

    /**
     * Reads all bytes from [source] and writes them to this sink.
     *
     * @return the total number of bytes read.
     */
    fun writeAll(source: Source): Long

    /** Encodes [string] as UTF-8 and writes it. */
    fun writeUtf8(string: String): BufferedSink

    /** Encodes a substring of [string] as UTF-8 and writes it. */
    fun writeUtf8(string: String, beginIndex: Int, endIndex: Int): BufferedSink

    /** Encodes [string] with [charset] and writes it. */
    fun writeString(string: String, charset: Charset): BufferedSink

    /**
     * Writes all buffered data to the underlying sink without flushing it.
     *
     * This is like [flush] but does not propagate the flush downstream.
     */
    fun emit(): BufferedSink

    /**
     * Writes complete segments to the underlying sink, keeping partial segments buffered.
     *
     * Use this after each write to limit buffer growth.
     */
    fun emitCompleteSegments(): BufferedSink

    /** Returns an [OutputStream] that writes to this sink. */
    fun outputStream(): OutputStream
}
