/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.BufferedSource
import java.io.Closeable
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents the body of an HTTP response.
 *
 * A `ResponseBody` exposes the raw bytes of an HTTP response through a single [source] accessor
 * returning a [BufferedSource]. The body **must be closed** after use to release the underlying
 * connection — prefer Kotlin's `use {}` or Java's try-with-resources, and close it explicitly even
 * when the body is skipped without reading.
 *
 * This class depends only on the SDK's [BufferedSource] I/O seam with no external dependencies,
 * making it compatible with JDK 8+ and safe to use from platform threads, virtual threads, Kotlin
 * coroutines, and reactive schedulers. Reading from [source] performs blocking I/O; callers in
 * non-blocking contexts should dispatch to an appropriate scheduler (e.g., `Dispatchers.IO`,
 * `Schedulers.boundedElastic()`).
 *
 * ## Thread safety
 *
 * Instances are **not** thread-safe. The [BufferedSource] returned by [source] should be read
 * from a single thread only. For concurrent or repeatable access, wrap with
 * [LoggableResponseBody], which buffers the content and provides thread-safe, repeatable reads.
 *
 * ## Single-use contract
 *
 * The base `ResponseBody` can only be read once — [source] returns the same [BufferedSource] on
 * every call, and once that source is consumed, the bytes are gone. Wrap with
 * [LoggableResponseBody] for repeatable access.
 *
 * @see LoggableResponseBody for a buffered wrapper that
 *      supports repeatable reads and non-destructive logging.
 */
public abstract class ResponseBody : Closeable {
    /**
     * Returns the media type of the response body, or null if unknown.
     */
    public abstract fun mediaType(): MediaType?

    /**
     * Returns the content length, or -1 if unknown.
     */
    public abstract fun contentLength(): Long

    /**
     * Returns a [BufferedSource] to read the response body. The source can be read only
     * once; every call returns the same source.
     */
    public abstract fun source(): BufferedSource

    /**
     * Reads the entire body and returns it decoded as a [String]. The body is closed in a
     * `finally` block whether or not the read succeeds.
     *
     * @param charset The character set to use for decoding. Defaults to the charset declared in
     *   the body's Content-Type media type, falling back to UTF-8 when none is declared or it is
     *   unrecognized.
     * @throws IOException If an I/O error occurs while reading.
     */
    @JvmOverloads
    @Throws(IOException::class)
    public fun string(charset: Charset = mediaType()?.charset ?: Charsets.UTF_8): String =
        try {
            source().readString(charset)
        } finally {
            close()
        }

    /**
     * Reads the entire body and returns it as a [ByteArray]. The body is closed in a
     * `finally` block whether or not the read succeeds.
     *
     * @throws IOException If an I/O error occurs while reading.
     */
    @Throws(IOException::class)
    public fun bytes(): ByteArray =
        try {
            source().readByteArray()
        } finally {
            close()
        }

    /**
     * Closes the response body and releases any transport resources.
     *
     * Implementations must release the underlying source (or whatever transport handle the
     * body owns) and must be idempotent — `close()` may be called more than once and any
     * call after the first should be a no-op. Implementations must not assume [source] has
     * been invoked: a caller that decides to skip the body still needs `close()` to release
     * the connection.
     *
     * @throws IOException If an I/O error occurs while releasing resources.
     */
    @Throws(IOException::class)
    abstract override fun close()

    /**
     * Factory entry point for adapter / test code that already holds a [BufferedSource].
     */
    public companion object {
        /**
         * Creates a new response body wrapping [source]. The returned body is single-use
         * (single underlying [BufferedSource]); wrap with `LoggableResponseBody` for
         * repeatable reads.
         *
         * @param contentLength The length of the content, or `-1` if unknown.
         * @param mediaType The media type, or `null` if unknown.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            source: BufferedSource,
            mediaType: MediaType? = null,
            contentLength: Long = -1L,
        ): ResponseBody =
            object : ResponseBody() {
                private val closed = AtomicBoolean(false)

                override fun mediaType(): MediaType? = mediaType

                override fun contentLength(): Long = contentLength

                override fun source(): BufferedSource = source

                override fun close() {
                    // Honor the idempotent-close contract even if the wrapped source throws on a
                    // second close: only the first call reaches through to the source.
                    if (closed.compareAndSet(false, true)) {
                        source.close()
                    }
                }
            }
    }
}
