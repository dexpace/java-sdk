package org.dexpace.sdk.core.io

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * A [Source] that adds the typed read surface needed for HTTP request and response body
 * deserialization, body-logging snapshots, and `java.io` interop.
 *
 * Implementations are obtained from an [IoProvider] — callers do not construct them directly.
 */
interface BufferedSource : Source {
    /**
     * The adapter's internal buffer. When this source IS a [Buffer], the property returns
     * `this`. Otherwise it returns whatever in-memory storage the adapter uses to stage bytes
     * between the underlying transport and typed read calls. Mutating the returned buffer
     * directly is undefined behavior.
     */
    val buffer: Buffer

    /** Returns true when no more bytes are available. May block waiting on the source. */
    @Throws(IOException::class)
    fun exhausted(): Boolean

    /** Reads a single byte. Throws `EOFException` if the source is exhausted. */
    @Throws(IOException::class)
    fun readByte(): Byte

    /** Reads every remaining byte into a new byte array. */
    @Throws(IOException::class)
    fun readByteArray(): ByteArray

    /**
     * Reads exactly [byteCount] bytes into a new byte array.
     *
     * @throws java.io.EOFException if the source is exhausted before [byteCount] bytes are read.
     */
    @Throws(IOException::class)
    fun readByteArray(byteCount: Long): ByteArray

    /** Reads every remaining byte and decodes the result as UTF-8. */
    @Throws(IOException::class)
    fun readUtf8(): String

    /** Reads exactly [byteCount] bytes and decodes the result as UTF-8. */
    @Throws(IOException::class)
    fun readUtf8(byteCount: Long): String

    /**
     * Reads bytes up to (and consuming) the next `\n` or `\r\n` terminator and returns the
     * preceding bytes decoded as UTF-8. Returns null if the source is exhausted before any
     * bytes are read. A final line without a terminator is returned as-is.
     */
    @Throws(IOException::class)
    fun readUtf8Line(): String?

    /** Reads every remaining byte and decodes it using [charset]. */
    @Throws(IOException::class)
    fun readString(charset: Charset): String

    /**
     * Returns a non-consuming view of this source. Reads from the returned source do not
     * advance the position of this source.
     */
    fun peek(): BufferedSource

    /** Returns an [InputStream] that reads from this source. Closing the stream closes this. */
    fun inputStream(): InputStream

    /** Skips [byteCount] bytes. Throws `EOFException` if exhausted before the count is met. */
    @Throws(IOException::class)
    fun skip(byteCount: Long)

    /**
     * Returns a non-consuming, length-bounded view over this source starting at [offset]
     * bytes from the current cursor and exposing at most [byteCount] bytes.
     *
     * Reads from the returned source do not advance this source. Closing the slice does NOT
     * close the parent; closing the parent invalidates the slice — subsequent reads throw
     * [IllegalStateException] or [IOException]. Multiple slices of the same source are
     * independent.
     *
     * Detection of `offset > source.size` is lazy: construction succeeds and the first read
     * fails with `EOFException` / behaves as an empty source. Negative [offset] or
     * [byteCount] throw [IllegalArgumentException] at construction.
     */
    @Throws(IOException::class)
    fun slice(offset: Long, byteCount: Long): BufferedSource
}
