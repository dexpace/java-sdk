/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.io

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * A [Source] that adds the typed read surface needed for HTTP request and response body
 * deserialization, body-logging snapshots, and `java.io` interop.
 *
 * Implementations are obtained from an [IoProvider] — callers do not construct them directly.
 *
 * ## Thread-safety
 *
 * Instances are not safe for concurrent use; serialize external access if a source is shared
 * across threads. Slices and `peek()` views are independent and may move on different threads,
 * but each individual view is still single-threaded.
 */
public interface BufferedSource : Source {
    /**
     * The adapter's internal buffer. When this source IS a [Buffer], the property returns
     * `this`. Otherwise it returns whatever in-memory storage the adapter uses to stage bytes
     * between the underlying transport and typed read calls. Mutating the returned buffer
     * directly is undefined behavior.
     */
    public val buffer: Buffer

    /** Returns true when no more bytes are available. May block waiting on the source. */
    @Throws(IOException::class)
    public fun exhausted(): Boolean

    /** Reads a single byte. Throws `EOFException` if the source is exhausted. */
    @Throws(IOException::class)
    public fun readByte(): Byte

    /** Reads every remaining byte into a new byte array. */
    @Throws(IOException::class)
    public fun readByteArray(): ByteArray

    /**
     * Reads exactly [byteCount] bytes into a new byte array.
     *
     * @throws java.io.EOFException if the source is exhausted before [byteCount] bytes are read.
     */
    @Throws(IOException::class)
    public fun readByteArray(byteCount: Long): ByteArray

    /** Reads every remaining byte and decodes the result as UTF-8. */
    @Throws(IOException::class)
    public fun readUtf8(): String

    /** Reads exactly [byteCount] bytes and decodes the result as UTF-8. */
    @Throws(IOException::class)
    public fun readUtf8(byteCount: Long): String

    /**
     * Reads bytes up to (and consuming) the next `\n` or `\r\n` terminator and returns the
     * preceding bytes decoded as UTF-8. Returns null if the source is exhausted before any
     * bytes are read. A final line without a terminator is returned as-is.
     */
    @Throws(IOException::class)
    public fun readUtf8Line(): String?

    /** Reads every remaining byte and decodes it using [charset]. */
    @Throws(IOException::class)
    public fun readString(charset: Charset): String

    /**
     * Returns a non-consuming view of this source. Reads from the returned source do not
     * advance the position of this source.
     */
    @Throws(IOException::class)
    public fun peek(): BufferedSource

    /** Returns an [InputStream] that reads from this source. Closing the stream closes this. */
    @Throws(IOException::class)
    public fun inputStream(): InputStream

    /** Skips [byteCount] bytes. Throws `EOFException` if exhausted before the count is met. */
    @Throws(IOException::class)
    public fun skip(byteCount: Long)

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
    public fun slice(
        offset: Long,
        byteCount: Long,
    ): BufferedSource
}
