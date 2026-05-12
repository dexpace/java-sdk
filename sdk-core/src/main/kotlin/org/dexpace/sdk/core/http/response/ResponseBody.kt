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
abstract class ResponseBody : Closeable {
    /**
     * Returns the media type of the response body, or null if unknown.
     */
    abstract fun mediaType(): MediaType?

    /**
     * Returns the content length, or -1 if unknown.
     */
    abstract fun contentLength(): Long

    /**
     * Returns a [BufferedSource] to read the response body.
     *
     * Note: The source can be read only once. Multiple calls will return the same source.
     *
     * @return The buffered source.
     */
    abstract fun source(): BufferedSource

    /**
     * Closes the response body and releases any resources.
     *
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    override fun close() {
        source().close()
    }

    companion object {

        /**
         * Creates a new response body from a [BufferedSource] and [mediaType].
         *
         * @param source The buffered source to read from.
         * @param contentLength The length of the content, or -1 if unknown.
         * @param mediaType The media type, or null if unknown.
         * @return A new [ResponseBody] instance.
         */
        @JvmStatic
        @JvmOverloads
        fun create(source: BufferedSource, mediaType: MediaType? = null, contentLength: Long = -1L): ResponseBody =
            object : ResponseBody() {
                override fun mediaType(): MediaType? = mediaType

                override fun contentLength(): Long = contentLength

                override fun source(): BufferedSource = source
            }
    }
}
