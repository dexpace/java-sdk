package org.dexpace.sdk.core.http.response.exception

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.Buffer
import java.io.IOException

/**
 * Base type for all SDK exceptions that carry a parsed HTTP response.
 *
 * The hierarchy mirrors `gax`'s `ApiException` taxonomy in HTTP terms: one concrete subclass
 * per canonical status code (400 / 401 / 403 / 404 / 405 / 409 / 410 / 413 / 415 / 422 /
 * 429 / 500 / 502 / 503 / 504) plus two fallbacks (`ClientErrorException` for unknown 4xx,
 * `ServerErrorException` for unknown 5xx). Construction is normally driven by
 * [HttpExceptionFactory.fromResponse]; subclasses remain `open` only via the abstract base
 * so service-client codegen can introduce per-operation typed subclasses by deriving from a
 * concrete one and stamping a typed deserialized payload.
 *
 * ## Retryability
 *
 * [retryable] is a `val` baked at construction time. Each concrete subclass hardcodes its
 * own retryable flag based on canonical HTTP semantics (4xx are not retryable; the common
 * 5xx codes plus 429 are; transport-level [NetworkException] always is). A downstream retry
 * policy is expected to query this field rather than maintaining a parallel predicate map.
 *
 * ## Body access
 *
 * [body] is the response's lazy [ResponseBody] — **not** eagerly buffered. Callers may
 * stream it on demand (for typed error-body deserialization) or use [bodySnapshot] for a
 * non-consuming preview suitable for log lines. The snapshot uses
 * [org.dexpace.sdk.core.io.BufferedSource.peek] so the primary read path is undisturbed.
 *
 * ## Why `RuntimeException` and not `IOException`
 *
 * A successful transport produced a response — the failure is at the protocol level, not at
 * the I/O level. Forcing every call site to declare `throws IOException` for protocol errors
 * obscures the underlying I/O contract. Transport-level failures keep using [IOException]
 * (see [NetworkException]) so existing I/O `catch (IOException)` sites remain correct.
 *
 * @property status Parsed HTTP status from the originating response.
 * @property headers Response headers as received on the wire.
 * @property body Lazy response body, or `null` when the response carries no payload.
 * @property retryable Whether the exception represents a retryable condition. Set once at
 *   construction; subclasses choose the value based on canonical HTTP semantics.
 */
public abstract class HttpException
    @JvmOverloads
    constructor(
        public val status: Status,
        public val headers: Headers,
        public val body: ResponseBody?,
        public val retryable: Boolean,
        message: String? = null,
        cause: Throwable? = null,
    ) : RuntimeException(message ?: defaultMessage(status), cause) {
        /**
         * Returns a non-consuming preview of the body bytes, capped at [maxBytes].
         *
         * Reads from a fresh `peek()` view of the body's underlying source — the primary
         * read path (typed deserialization, caller-side `string()` / `bytes()`) is
         * undisturbed. Returns `null` when [body] itself is `null`; returns an empty array
         * when the body's source is exhausted.
         *
         * Useful for log-line previews of error bodies without committing to materializing
         * the whole payload. Typical sizes for human-readable error JSON are < 1 KiB, but a
         * misbehaving server may return megabytes — hence the explicit cap.
         *
         * @param maxBytes Maximum number of bytes to copy out of the peek view. Clamped to
         *   [Buffer.MAX_BYTE_ARRAY_SIZE]. Must be non-negative.
         * @return The preview bytes, or `null` if [body] is `null`.
         * @throws IllegalArgumentException if [maxBytes] is negative.
         * @throws IOException if reading the underlying source fails.
         */
        @JvmOverloads
        @Throws(IOException::class)
        public fun bodySnapshot(maxBytes: Int = DEFAULT_SNAPSHOT_BYTES): ByteArray? {
            require(maxBytes >= 0) { "maxBytes must be non-negative" }
            val b = body ?: return null
            val cap = minOf(maxBytes, Buffer.MAX_BYTE_ARRAY_SIZE)
            if (cap == 0) return ByteArray(0)
            // `peek()` returns a non-consuming view of the underlying source; reading from
            // it does NOT advance the primary source's position. `inputStream().read(buf, ..)`
            // returns up-to-N bytes (no EOF when the source is shorter than the request),
            // which matches the "snapshot whatever is available, no more than maxBytes"
            // contract — unlike `readByteArray(byteCount)` which insists on exactly that many.
            val peek = b.source().peek()
            val out = ByteArray(cap)
            var read = 0
            peek.inputStream().use { stream ->
                while (read < cap) {
                    val n = stream.read(out, read, cap - read)
                    if (n < 0) break
                    read += n
                }
            }
            return if (read == cap) out else out.copyOf(read)
        }

        public companion object {
            /**
             * Default cap for [bodySnapshot]. Sized to fit a typical JSON error body
             * (`{"code": "...", "message": "..."}`) plus headroom — large enough to be
             * useful in a log line, small enough to avoid OOM on rogue payloads.
             */
            public const val DEFAULT_SNAPSHOT_BYTES: Int = 4096

            private fun defaultMessage(status: Status): String = "HTTP ${status.code} ${status.name}"
        }
    }
