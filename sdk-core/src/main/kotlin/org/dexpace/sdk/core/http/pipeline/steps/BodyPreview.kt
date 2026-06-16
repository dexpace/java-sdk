/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.MediaType
import java.nio.charset.Charset

/**
 * Renders captured request/response body bytes into a short, log-safe preview string.
 *
 * The default instrumentation steps previously decoded every preview as UTF-8, which produced
 * mojibake for a text body declared in another charset (e.g. ISO-8859-1) and a stream of
 * `U+FFFD` replacement characters for a binary body (a gzip payload, an image, …). This helper
 * makes previews:
 *
 * - **Charset-aware** — a text body is decoded with the charset declared on its [MediaType]
 *   (`Content-Type: text/plain;charset=ISO-8859-1`). When the media type omits a charset, or
 *   names one the JVM does not recognise, the preview falls back to [DEFAULT_CHARSET] (UTF-8),
 *   matching the HTTP default for most text types and the previous behaviour.
 * - **Binary-safe** — a body that does not look like text is never decoded. Instead it is
 *   summarised as `[binary N bytes]`, so an operator sees a stable, greppable marker rather
 *   than a line of replacement characters that can corrupt log viewers.
 *
 * Decoding itself never throws: [Charset]-based decoding substitutes the replacement character
 * for malformed input, so a snapshot that ends mid-multibyte-sequence (a real possibility on a
 * bounded capture) still yields a usable preview rather than crashing the log line.
 *
 * This is an `internal` seam shared by [DefaultInstrumentationStep] and
 * [DefaultAsyncInstrumentationStep]; it has no public API surface.
 */
internal object BodyPreview {
    /** Charset used when a text body declares no charset, or names an unknown one. */
    internal val DEFAULT_CHARSET: Charset = Charsets.UTF_8

    /**
     * Number of leading bytes inspected by [isProbablyText]. A small fixed sample keeps the
     * heuristic O(1) regardless of body size while still catching the common binary signatures
     * (NUL bytes, control-byte runs) that appear near the start of a payload.
     */
    private const val SNIFF_SAMPLE_BYTES = 1024

    /**
     * Fraction of sampled bytes that may be non-text control bytes before the body is judged
     * binary. Real text can carry the odd control byte (an ESC sequence, a stray form feed), so
     * the cutoff sits well above zero rather than rejecting on the first control byte.
     */
    private const val MAX_CONTROL_BYTE_RATIO = 0.30

    // Byte values used by the text/binary heuristic, named so the rule logic reads clearly and
    // so the values are not flagged as inline magic numbers.
    private const val UNSIGNED_BYTE_MASK = 0xFF
    private const val NUL = 0x00
    private const val FIRST_PRINTABLE = 0x20 // space; bytes below this are C0 control bytes
    private const val TAB = 0x09
    private const val LINE_FEED = 0x0A
    private const val CARRIAGE_RETURN = 0x0D
    private const val DEL = 0x7F

    /**
     * Renders [bytes] as a preview string.
     *
     * Empty input yields the empty string. A body that does not pass [isProbablyText] is rendered
     * as a size-only `[binary N bytes]` summary. Otherwise the bytes are decoded with the charset
     * from [mediaType] (or [DEFAULT_CHARSET] when absent/unknown).
     */
    internal fun render(
        bytes: ByteArray,
        mediaType: MediaType?,
    ): String {
        if (bytes.isEmpty()) return ""
        if (!isProbablyText(bytes)) return binarySummary(bytes.size)
        return previewText(bytes, mediaType)
    }

    /**
     * Decodes [bytes] using the charset declared on [mediaType], falling back to
     * [DEFAULT_CHARSET] when the media type is null, declares no charset, or names a charset the
     * JVM cannot resolve ([MediaType.charset] returns null in the latter two cases). Invalid byte
     * sequences are replaced rather than throwing.
     */
    internal fun previewText(
        bytes: ByteArray,
        mediaType: MediaType?,
    ): String {
        if (bytes.isEmpty()) return ""
        val charset = mediaType?.charset ?: DEFAULT_CHARSET
        return String(bytes, charset)
    }

    /**
     * Heuristically decides whether [bytes] is text. Samples the first [SNIFF_SAMPLE_BYTES]:
     * a single NUL byte is treated as a strong binary signal, and a control-byte ratio above
     * [MAX_CONTROL_BYTE_RATIO] (excluding the common text whitespace `\t`, `\n`, `\r`) also marks
     * the body binary. Bytes >= 0x80 are not counted against the body — they are legitimate in
     * UTF-8 multibyte sequences and in single-byte charsets such as ISO-8859-1.
     */
    @Suppress("ReturnCount")
    internal fun isProbablyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val sample = minOf(bytes.size, SNIFF_SAMPLE_BYTES)
        var controlBytes = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and UNSIGNED_BYTE_MASK
            // A NUL byte essentially never appears in real text and is the canonical marker of a
            // binary payload (gzip, images, protobuf, …); reject immediately.
            if (b == NUL) return false
            if (isControlByte(b)) controlBytes++
        }
        return controlBytes.toDouble() / sample <= MAX_CONTROL_BYTE_RATIO
    }

    /**
     * True for an ASCII C0 control byte that is not one of the whitespace characters routinely
     * found in text ([TAB], [LINE_FEED], [CARRIAGE_RETURN]), or for the [DEL] byte. High bytes
     * (>= 0x80) are deliberately excluded — see [isProbablyText].
     */
    private fun isControlByte(b: Int): Boolean =
        (b < FIRST_PRINTABLE && b != TAB && b != LINE_FEED && b != CARRIAGE_RETURN) || b == DEL

    /** The size-only summary emitted for a binary body. */
    private fun binarySummary(size: Int): String = "[binary $size bytes]"
}
