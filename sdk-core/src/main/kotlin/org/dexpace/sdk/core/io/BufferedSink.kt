package org.dexpace.sdk.core.io

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * A [Sink] that adds the typed write surface needed for HTTP request body serialization and
 * `java.io` interop.
 *
 * Implementations are obtained from an [IoProvider] — callers do not construct them directly.
 *
 * ## Thread-safety
 *
 * Instances are not safe for concurrent use; serialize external access if a sink is shared
 * across threads. Adapters skip synchronized lazy-init on [buffer] for the same reason.
 */
interface BufferedSink : Sink {
    /**
     * The adapter's internal buffer. When this sink IS a [Buffer], the property returns `this`.
     * Otherwise it returns the in-memory staging buffer the adapter uses between typed write
     * calls and the underlying transport. Mutating the returned buffer directly is undefined
     * behavior.
     */
    val buffer: Buffer

    /** Writes every byte of [source] into this sink. */
    @Throws(IOException::class)
    fun write(source: ByteArray): BufferedSink

    /**
     * Writes [byteCount] bytes from [source] starting at [offset].
     *
     * @throws IndexOutOfBoundsException if [offset] or [byteCount] is negative, or if
     *         `offset + byteCount` exceeds `source.size`.
     */
    @Throws(IOException::class)
    fun write(source: ByteArray, offset: Int, byteCount: Int): BufferedSink

    /** Drains every remaining byte of [source] into this sink. Returns the number transferred. */
    @Throws(IOException::class)
    fun writeAll(source: Source): Long

    /** UTF-8 encodes [string] and writes it. */
    @Throws(IOException::class)
    fun writeUtf8(string: String): BufferedSink

    /**
     * UTF-8 encodes the substring of [string] from [beginIndex] (inclusive) to [endIndex]
     * (exclusive) and writes it.
     */
    @Throws(IOException::class)
    fun writeUtf8(string: String, beginIndex: Int, endIndex: Int): BufferedSink

    /** Encodes [string] using [charset] and writes the resulting bytes. */
    @Throws(IOException::class)
    fun writeString(string: String, charset: Charset): BufferedSink

    /** Returns an [OutputStream] that writes to this sink. Closing the stream closes this. */
    fun outputStream(): OutputStream

    /**
     * Pushes buffered bytes one level toward their final destination without forcing a
     * system-level flush. Use [flush] when system-level flush semantics are required.
     */
    @Throws(IOException::class)
    fun emit(): BufferedSink
}
