package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.http.common.MediaType
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * Represents an HTTP request body.
 *
 * A `RequestBody` encapsulates the content to be sent with an HTTP request. Implementations
 * are either **single-use** (backed by an [InputStream] that is consumed on write) or
 * **reusable** (backed by a [ByteArray] that can be written multiple times). Each factory
 * method documents which variant it produces.
 *
 * This class uses only `java.io` APIs with no external dependencies, making it compatible
 * with JDK 8+ and safe to use from platform threads, virtual threads, Kotlin coroutines,
 * and reactive schedulers.
 *
 * ## Thread safety
 *
 * Instances are **not** thread-safe. A single `RequestBody` should be written from one thread
 * at a time. If concurrent writes are needed, create separate instances.
 *
 * @see org.dexpace.sdk.core.http.logging.LoggableRequestBody for a wrapper that captures
 *      body content for logging without consuming the write.
 */
abstract class RequestBody {
    /**
     * Returns the media type of the request body.
     */
    abstract fun mediaType(): MediaType?

    /**
     * Returns the number of bytes that will be written to [writeTo], or -1 if unknown.
     */
    open fun contentLength(): Long = -1

    /**
     * Writes the request body to the given [stream].
     *
     * @param stream the output stream to write to.
     * @throws IOException if an I/O error occurs.
     */
    @Throws(IOException::class)
    abstract fun writeTo(stream: OutputStream)

    companion object {
        /**
         * Creates a new request body that reads from the given [inputStream].
         *
         * This body can only be written once as the underlying stream is consumed and closed on [writeTo].
         *
         * @param inputStream the input stream to read from.
         * @param mediaType the media type, or null if unknown.
         * @param contentLength the length of the content, or -1 if unknown.
         * @return a new [RequestBody] instance.
         */
        @JvmStatic
        @JvmOverloads
        fun create(inputStream: InputStream, mediaType: MediaType? = null, contentLength: Long = -1): RequestBody =
            object : RequestBody() {
                override fun mediaType(): MediaType? = mediaType

                override fun contentLength(): Long = contentLength

                @Throws(IOException::class)
                override fun writeTo(stream: OutputStream) {
                    inputStream.use { src ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (src.read(buffer).also { bytesRead = it } != -1) {
                            stream.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }

        /**
         * Creates a new request body from the given [bytes].
         *
         * This body is reusable — [writeTo] can be called multiple times.
         *
         * @param bytes the byte array to write.
         * @param mediaType the media type, or null if unknown.
         * @return a new [RequestBody] instance.
         */
        @JvmStatic
        @JvmOverloads
        fun create(bytes: ByteArray, mediaType: MediaType? = null): RequestBody =
            object : RequestBody() {
                override fun mediaType(): MediaType? = mediaType

                override fun contentLength(): Long = bytes.size.toLong()

                @Throws(IOException::class)
                override fun writeTo(stream: OutputStream) {
                    stream.write(bytes)
                }
            }

        /**
         * Creates a new request body from the given [content] string.
         *
         * This body is reusable — [writeTo] can be called multiple times.
         *
         * @param content the string content to write.
         * @param mediaType the media type, or null if unknown.
         * @param charset the character set to encode with; defaults to UTF-8.
         * @return a new [RequestBody] instance.
         */
        @JvmStatic
        @JvmOverloads
        fun create(content: String, mediaType: MediaType? = null, charset: Charset = Charsets.UTF_8): RequestBody =
            create(content.toByteArray(charset), mediaType)

        /**
         * Creates a new request body for form data with content type "application/x-www-form-urlencoded".
         *
         * This body is reusable — [writeTo] can be called multiple times.
         *
         * @param formData The form data as a map of parameter names and values.
         * @param charset The character set to use; defaults to UTF-8.
         * @return A new [RequestBody] instance.
         */
        @JvmStatic
        @JvmOverloads
        fun create(formData: Map<String, String>, charset: Charset = Charsets.UTF_8): RequestBody {
            val encodedForm =
                formData
                    .map { (key, value) ->
                        "${URLEncoder.encode(key, charset.name())}=${URLEncoder.encode(value, charset.name())}"
                    }.joinToString("&")

            return create(encodedForm.toByteArray(charset), CommonMediaTypes.APPLICATION_FORM_URLENCODED)
        }
    }
}
