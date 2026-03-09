package org.dexpace.sdk.core.http.logging

import org.dexpace.sdk.core.http.common.MediaType
import java.nio.charset.Charset

/**
 * An immutable capture of HTTP body content for logging and diagnostics.
 *
 * Provides safe access to captured bytes with text detection and charset-aware decoding.
 * The full body is always captured — display-level truncation is handled by [preview].
 *
 * @property bytes The captured body bytes.
 * @property mediaType The media type of the body, or null if unknown.
 */
class BodySnapshot(
    val bytes: ByteArray,
    val mediaType: MediaType?
) {
    /**
     * The number of captured bytes.
     */
    val size: Int get() = bytes.size

    /**
     * Whether the captured content is empty.
     */
    val isEmpty: Boolean get() = bytes.isEmpty()

    /**
     * Whether the body appears to be textual based on its media type.
     *
     * Returns `false` if no media type is available.
     */
    val isTextual: Boolean
        get() {
            val mt = mediaType ?: return false
            if (mt.type == "text") return true
            val sub = mt.subtype
            return sub == "json" || sub == "xml" || sub == "html"
                || sub == "x-www-form-urlencoded"
                || sub.endsWith("+json") || sub.endsWith("+xml")
        }

    /**
     * Decodes the captured bytes as a string.
     *
     * Uses the charset from the media type if available, otherwise falls back to UTF-8.
     *
     * @param charset Override charset; defaults to the media type's charset or UTF-8.
     * @return The decoded string.
     */
    @JvmOverloads
    fun string(charset: Charset = mediaType?.charset ?: Charsets.UTF_8): String = String(bytes, charset)

    /**
     * Returns a human-readable preview of the body content for log output.
     *
     * - Empty bodies produce `[empty]`.
     * - Binary bodies produce `[binary N bytes]`.
     * - Textual bodies are decoded and optionally truncated with an ellipsis.
     *
     * @param maxLength Maximum character length for textual previews.
     * @return A loggable content preview string.
     */
    @JvmOverloads
    fun preview(maxLength: Int = 512): String {
        if (isEmpty) return "[empty]"
        if (!isTextual) return "[binary $size bytes]"

        val text = string()
        return if (text.length > maxLength) {
            text.substring(0, maxLength) + "..."
        } else {
            text
        }
    }

    /** Returns the [preview] representation of this snapshot. */
    override fun toString(): String = preview()
}
