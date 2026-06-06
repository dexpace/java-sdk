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
import java.io.InputStream

/**
 * Represents the body of an HTTP response.
 *
 * A `ResponseBody` provides access to the raw bytes of an HTTP response through [byteStream],
 * with convenience methods [bytes] and [string] for common consumption patterns. The body
 * **must be closed** after use to release the underlying connection — prefer Kotlin's `use {}`
 * or Java's try-with-resources.
 *
 * This class uses only `java.io` APIs with no external dependencies, making it compatible
 * with JDK 8+ and safe to use from platform threads, virtual threads, Kotlin coroutines,
 * and reactive schedulers. The underlying [InputStream] performs blocking I/O; callers in
 * non-blocking contexts should dispatch to an appropriate scheduler (e.g., `Dispatchers.IO`,
 * `Schedulers.boundedElastic()`).
 *
 * ## Thread safety
 *
 * Instances are **not** thread-safe. The stream returned by [byteStream] should be read
 * from a single thread only. For concurrent access, wrap with
 * [LoggableResponseBody][org.dexpace.sdk.core.http.logging.LoggableResponseBody] which
 * buffers the content and provides thread-safe, repeatable reads.
 *
 * ## Single-use contract
 *
 * The base `ResponseBody` can only be read once — [byteStream] returns the same stream on
 * every call, and once consumed, the bytes are gone. Use [bytes] or [string] for a
 * one-shot read, or wrap with `LoggableResponseBody` for repeatable access.
 *
 * @see org.dexpace.sdk.core.http.logging.LoggableResponseBody for a buffered wrapper that
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
     * Returns a [BufferedSource] to read the response body.
     *
     * Note: The source can be read only once. Multiple calls will return the same source.
     *
     * @return The buffered source.
     */
    public abstract fun source(): BufferedSource

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
         * @param source The buffered source to read from.
         * @param contentLength The length of the content, or `-1` if unknown.
         * @param mediaType The media type, or `null` if unknown.
         * @return A new [ResponseBody] instance.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            source: BufferedSource,
            mediaType: MediaType? = null,
            contentLength: Long = -1L,
        ): ResponseBody =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = mediaType

                override fun contentLength(): Long = contentLength

                override fun source(): BufferedSource = source

                override fun close() {
                    source.close()
                }
            }
    }
}
