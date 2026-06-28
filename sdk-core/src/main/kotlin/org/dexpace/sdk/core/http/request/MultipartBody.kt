/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.serde.Serde
import java.io.IOException
import java.nio.file.Path
import java.security.SecureRandom

/**
 * A `multipart/form-data` [RequestBody] (RFC 7578) made up of one or more [Part]s separated by a
 * generated [boundary].
 *
 * Each part contributes a fixed frame — the `--boundary` delimiter, the part's headers, a blank
 * line, the part body, and a trailing CRLF — followed by a single closing `--boundary--` delimiter
 * for the whole body. The frame layout is produced by **one** shared function ([frameOf]) that both
 * [writeTo] and [contentLength] consume, so the declared length can never drift from the bytes
 * actually written.
 *
 * ## Part bodies
 *
 * Parts wrap an ordinary [RequestBody]:
 *
 * - **File parts** ([Part.file]) wrap a [FileRequestBody]; the bytes stream straight off disk, and a
 *   transport that recognises [FileRequestBody] could still dispatch a zero-copy transfer for the
 *   file region (the multipart framing around it is written normally).
 * - **Value parts** ([Part.serialized]) encode an arbitrary object through the [Serde] SPI at
 *   construction time, so no serializer is required at send time and `sdk-core` keeps zero runtime
 *   serialization deps.
 * - **Raw parts** ([Part.create]) wrap any [RequestBody] you already have.
 *
 * ## Length and replayability
 *
 * [contentLength] is exact only when every part body reports a known length; if any part body
 * returns `-1`, the whole multipart body returns `-1`. [isReplayable] is true only when every part
 * body is replayable, in which case the multipart body can be re-sent across retries without
 * buffering.
 *
 * ## Thread-safety
 *
 * Immutable after construction. As with all [RequestBody] implementations, a single instance is not
 * safe for concurrent [writeTo]; the per-part bodies enforce their own single-use guards where they
 * apply.
 */
public class MultipartBody private constructor(
    /** The generated (or supplied) multipart boundary token, without surrounding dashes. */
    public val boundary: String,
    parts: List<Part>,
) : RequestBody() {
    /** A defensive copy of the parts in send order, exposed as a read-only List. */
    public val parts: List<Part> = ArrayList(parts)

    private val contentType: MediaType =
        MediaType.of("multipart", "form-data", mapOf("boundary" to boundary))

    init {
        require(this.parts.isNotEmpty()) { "A multipart body must contain at least one part" }
    }

    override fun mediaType(): MediaType = contentType

    /**
     * Returns the exact total byte count, or `-1` when any part body's length is unknown.
     *
     * Every byte counted here is produced by [frameOf]/[closingDelimiter] — the same functions
     * [writeTo] writes — so the two cannot disagree.
     */
    override fun contentLength(): Long {
        var total = 0L
        for (part in parts) {
            val bodyLength = part.body.contentLength()
            if (bodyLength < 0) return -1
            total += frameOf(part).size.toLong() + bodyLength + CRLF.size.toLong()
        }
        return total + closingDelimiter().size.toLong()
    }

    override fun isReplayable(): Boolean = parts.all { it.body.isReplayable() }

    // toReplayable is inherited from RequestBody: it returns this when isReplayable() is true and
    // otherwise drains writeTo once into a buffer — exactly the behaviour this body needs.

    /**
     * Returns a [Builder] pre-filled with this body's [boundary] and [parts], so a modified copy
     * (for example one with an extra part appended) can be derived without rebuilding from scratch.
     */
    public fun newBuilder(): Builder {
        val builder = Builder().boundary(boundary)
        for (part in parts) {
            builder.addPart(part)
        }
        return builder
    }

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        for (part in parts) {
            sink.write(frameOf(part))
            part.body.writeTo(sink)
            sink.write(CRLF)
        }
        sink.write(closingDelimiter())
    }

    /**
     * Builds the leading frame bytes for [part]: the `--boundary` delimiter, the part headers, and
     * the blank line that separates headers from the part body. This is the **single** definition of
     * a part's framing — both [writeTo] and [contentLength] go through it, so a change here updates
     * both at once and they cannot drift.
     */
    private fun frameOf(part: Part): ByteArray {
        val sb = StringBuilder()
        sb.append(DASHES).append(boundary).append(CRLF_STR)
        for ((name, value) in part.headers) {
            sb.append(name).append(": ").append(value).append(CRLF_STR)
        }
        sb.append(CRLF_STR)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** The closing `--boundary--` delimiter plus its trailing CRLF that terminates the body. */
    private fun closingDelimiter(): ByteArray = (DASHES + boundary + DASHES + CRLF_STR).toByteArray(Charsets.UTF_8)

    /**
     * One field of a [MultipartBody]: a `Content-Disposition` (built from `name` and an optional
     * filename), any extra headers, and a [body].
     *
     * Construct parts through the [companion object][Part.Companion] factories rather than directly.
     */
    public class Part private constructor(
        internal val headers: List<Pair<String, String>>,
        internal val body: RequestBody,
    ) {
        public companion object {
            /**
             * Wraps an existing [body] as a form field named [name], optionally carrying a
             * [filename] in its `Content-Disposition`. A `Content-Type` header is emitted when the
             * [body] reports a [RequestBody.mediaType].
             */
            @JvmStatic
            @JvmOverloads
            public fun create(
                name: String,
                body: RequestBody,
                filename: String? = null,
            ): Part = Part(buildHeaders(name, filename, body.mediaType()), body)

            /**
             * A file field named [name]. The file streams off disk via [FileRequestBody]; the
             * supplied [filename] (defaulting to the file's own name) and [mediaType] populate the
             * part headers.
             */
            @JvmStatic
            @JvmOverloads
            public fun file(
                name: String,
                file: Path,
                filename: String? = file.fileName?.toString(),
                mediaType: MediaType? = null,
            ): Part {
                val body = FileRequestBody(file, mediaType)
                return Part(buildHeaders(name, filename, body.mediaType()), body)
            }

            /**
             * A value field named [name] whose [value] is encoded through [serde]'s serializer at
             * construction time. The resulting bytes are replayable; the part carries the supplied
             * [mediaType] (the serialized bytes are opaque to `sdk-core`, so the caller names the
             * type).
             */
            @JvmStatic
            @JvmOverloads
            public fun serialized(
                name: String,
                value: Any,
                serde: Serde,
                mediaType: MediaType? = null,
            ): Part {
                val bytes = serde.serializer.serializeToByteArray(value)
                val body = RequestBody.create(bytes, mediaType)
                return Part(buildHeaders(name, null, mediaType), body)
            }

            private fun buildHeaders(
                name: String,
                filename: String?,
                mediaType: MediaType?,
            ): List<Pair<String, String>> {
                require(name.isNotEmpty()) { "Part name must not be empty" }
                val headers = ArrayList<Pair<String, String>>(2)
                val disposition =
                    StringBuilder("form-data; name=").append(quote(name)).apply {
                        if (filename != null) append("; filename=").append(quote(filename))
                    }
                headers.add("Content-Disposition" to disposition.toString())
                if (mediaType != null) {
                    headers.add("Content-Type" to mediaType.toString())
                }
                return headers
            }

            /**
             * Renders [value] as an RFC 7578 quoted-string: wrapped in double quotes with `"` and
             * any CR/LF percent-encoded so a header value can never be broken across lines or
             * smuggle extra headers.
             */
            private fun quote(value: String): String {
                val sb = StringBuilder(value.length + 2)
                sb.append('"')
                for (ch in value) {
                    when (ch) {
                        '"' -> sb.append("%22")
                        '\r' -> sb.append("%0D")
                        '\n' -> sb.append("%0A")
                        else -> sb.append(ch)
                    }
                }
                sb.append('"')
                return sb.toString()
            }
        }
    }

    /**
     * Builds [MultipartBody] instances. Append parts with the typed helpers or [addPart], then call
     * [build]. A [boundary] is generated when none is set.
     */
    public class Builder : org.dexpace.sdk.core.generics.Builder<MultipartBody> {
        private val parts = ArrayList<Part>()
        private var boundary: String? = null

        /**
         * Overrides the generated boundary token (without surrounding dashes). The value must be a
         * valid RFC 2046 boundary: 1–70 characters drawn only from `bcharsnospace` (ASCII letters,
         * digits, and `'()+_,-./:=?`). This rejects a boundary that would inject CR/LF into the
         * delimiter lines or that no RFC 7578 parser could re-segment (e.g. one containing spaces,
         * a trailing space servers strip, or more than 70 characters).
         */
        public fun boundary(boundary: String): Builder =
            apply {
                validateBoundary(boundary)
                this.boundary = boundary
            }

        /** Appends [part] in send order. */
        public fun addPart(part: Part): Builder = apply { parts.add(part) }

        /** Convenience: appends a value field named [name] encoded through [serde]. */
        @JvmOverloads
        public fun addPart(
            name: String,
            value: Any,
            serde: Serde,
            mediaType: MediaType? = null,
        ): Builder = apply { parts.add(Part.serialized(name, value, serde, mediaType)) }

        /** Convenience: appends a file field named [name]. */
        @JvmOverloads
        public fun addFile(
            name: String,
            file: Path,
            filename: String? = file.fileName?.toString(),
            mediaType: MediaType? = null,
        ): Builder = apply { parts.add(Part.file(name, file, filename, mediaType)) }

        override fun build(): MultipartBody = MultipartBody(boundary ?: generateBoundary(), parts)
    }

    public companion object {
        private const val DASHES: String = "--"
        private const val CRLF_STR: String = "\r\n"
        private val CRLF: ByteArray = CRLF_STR.toByteArray(Charsets.UTF_8)

        /** Characters used for the random portion of a generated boundary (RFC 7230 token chars). */
        private const val BOUNDARY_ALPHABET: String =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val BOUNDARY_RANDOM_LENGTH: Int = 32
        private val RANDOM: SecureRandom = SecureRandom()

        /** RFC 2046 `bcharsnospace` punctuation — the non-alphanumeric boundary characters. */
        private const val BOUNDARY_SPECIALS: String = "'()+_,-./:=?"

        /** RFC 2046 caps a boundary at 70 characters. */
        private const val MAX_BOUNDARY_LENGTH: Int = 70

        private fun isBoundaryChar(ch: Char): Boolean =
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in BOUNDARY_SPECIALS

        /**
         * Validates a caller-supplied boundary against RFC 2046: 1–70 characters drawn only from
         * `bcharsnospace`. Generated boundaries always satisfy this; the check guards the
         * [Builder.boundary] override so a custom boundary cannot smuggle CR/LF into a delimiter
         * line or contain characters (spaces, a trailing space, control bytes) that break framing.
         */
        private fun validateBoundary(boundary: String) {
            require(boundary.isNotEmpty()) { "boundary must not be empty" }
            require(boundary.length <= MAX_BOUNDARY_LENGTH) {
                "boundary must be at most $MAX_BOUNDARY_LENGTH characters (RFC 2046), got ${boundary.length}"
            }
            require(boundary.all(::isBoundaryChar)) {
                "boundary must contain only RFC 2046 bcharsnospace characters " +
                    "(letters, digits, and $BOUNDARY_SPECIALS)"
            }
        }

        /**
         * Generates a fresh boundary unlikely to collide with any part body's bytes: a fixed
         * `dexpace-` prefix followed by [BOUNDARY_RANDOM_LENGTH] random token characters.
         */
        @JvmStatic
        public fun generateBoundary(): String {
            val sb = StringBuilder("dexpace-")
            repeat(BOUNDARY_RANDOM_LENGTH) {
                sb.append(BOUNDARY_ALPHABET[RANDOM.nextInt(BOUNDARY_ALPHABET.length)])
            }
            return sb.toString()
        }

        /** Starts a new [Builder]. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
