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
 * ## Multi-byte charsets and the binary heuristic
 * The text/binary heuristic ([isProbablyText]) normally treats a NUL byte as a definitive binary
 * signal. That rule misfires for a fixed-width multi-byte charset such as UTF-16 or UTF-32, where
 * ASCII content is padded with NUL bytes (`'A'` is `0x00 0x41` in UTF-16BE). To avoid rendering
 * legitimate wide-charset text as `[binary N bytes]`, [render] consults the declared charset
 * first: when the [MediaType] explicitly declares a *resolvable* charset whose encoding is not
 * NUL-free for ASCII (UTF-16/UTF-32 and friends), the body is decoded with that charset and the
 * NUL-based heuristic is skipped. When no charset is declared, or the declared one is single-byte
 * (US-ASCII, ISO-8859-1, UTF-8), the heuristic still runs so a genuinely binary body is summarised
 * rather than decoded into noise.
 *
 * ## Best-effort detection
 * The heuristic is **best-effort**, not a guarantee. It samples only the first
 * [SNIFF_SAMPLE_BYTES] and counts C0 control bytes; bytes `>= 0x80` are not counted (they are
 * legitimate in UTF-8 and single-byte charsets). A binary payload whose leading bytes happen to
 * contain no NUL and few control bytes — e.g. a region dominated by high bytes — can therefore
 * slip through as text and decode to replacement-character noise. Most real binary formats carry
 * a NUL or control run early, so the practical miss rate is low, but callers should treat the
 * preview as a diagnostic aid rather than a reliable content-type classifier.
 *
 * This is an `internal` seam shared by [DefaultInstrumentationStep] and
 * [DefaultAsyncInstrumentationStep]; it has no public API surface.
 */
internal object BodyPreview {
    /** Charset used when a text body declares no charset, or names an unknown one. */
    private val DEFAULT_CHARSET: Charset = Charsets.UTF_8

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
     * Empty input yields the empty string. When [mediaType] explicitly declares a resolvable
     * multi-byte charset (UTF-16/UTF-32 and friends), the bytes are decoded with that charset and
     * the NUL-based binary heuristic is skipped — see the class KDoc. Otherwise a body that does
     * not pass [isProbablyText] is rendered as a size-only `[binary N bytes]` summary, and a body
     * that does is decoded with the charset from [mediaType] (or [DEFAULT_CHARSET] when
     * absent/unknown).
     */
    internal fun render(
        bytes: ByteArray,
        mediaType: MediaType?,
    ): String {
        if (bytes.isEmpty()) return ""
        val declared = mediaType?.charset
        if (declared != null && encodesAsciiWithNul(declared)) {
            // A declared wide charset (UTF-16/UTF-32) pads ASCII with NUL bytes, so the NUL-based
            // heuristic would misclassify it as binary. Trust the explicit declaration and decode.
            return previewText(bytes, declared)
        }
        if (!isProbablyText(bytes)) return binarySummary(bytes.size)
        return previewText(bytes, declared ?: DEFAULT_CHARSET)
    }

    /**
     * Decodes [bytes] using [charset]. Invalid byte sequences are replaced rather than throwing.
     */
    private fun previewText(
        bytes: ByteArray,
        charset: Charset,
    ): String {
        if (bytes.isEmpty()) return ""
        return String(bytes, charset)
    }

    /**
     * True when [charset] encodes a plain ASCII character to a byte sequence that contains a NUL
     * byte — the signature of a fixed-width multi-byte charset such as UTF-16 or UTF-32, where
     * `'A'` becomes e.g. `0x00 0x41`. Single-byte charsets (US-ASCII, ISO-8859-1) and UTF-8
     * encode ASCII without NUL padding and return false. The probe is computed from the charset's
     * own encoder, so it covers any such charset the JVM knows, not a hard-coded name list.
     */
    private fun encodesAsciiWithNul(charset: Charset): Boolean =
        try {
            "A".toByteArray(charset).any { it.toInt() == NUL }
        } catch (_: Exception) {
            false
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
