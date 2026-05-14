package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.util.RetryUtils
import java.io.IOException

/**
 * An [IOException] that carries an HTTP [Response] (when available) and an optional
 * deserialized error [value]. The exception classifies its own retryability at
 * construction time so the retry policy can query a precomputed field rather than
 * maintaining a parallel predicate.
 *
 * Retryability rules (see [RetryUtils]):
 * - If [response] is non-null, [isRetryable] reflects [RetryUtils.isRetryable] for the
 *   response status code: 408, 429, or 5xx excluding 501 and 505.
 * - Otherwise, if [cause] is non-null, [isRetryable] is `true` when the cause chain
 *   contains an [IOException] or [java.util.concurrent.TimeoutException].
 * - If both are null, [isRetryable] is `false`.
 *
 * Extends [IOException] so it propagates cleanly through APIs declaring
 * `@Throws(IOException::class)`. Marked `open` so service clients can introduce typed
 * subclasses; the [isRetryable] field is `final` and cannot be overridden.
 *
 * @param message Human-readable error description.
 * @property response Response that triggered the error, or `null` when the failure happened
 *   before a response was received.
 * @property value Optional deserialized payload describing the error (e.g. a JSON error body).
 * @param cause Underlying cause to chain into the [IOException], or `null`.
 */
public open class HttpResponseException
@JvmOverloads
constructor(
    message: String,
    public val response: Response?,
    public val value: Any? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {

    /**
     * Whether this exception represents a retryable condition. Computed once at
     * construction; querying is free.
     */
    public val isRetryable: Boolean = computeIsRetryable(response, cause)

    private companion object {
        private fun computeIsRetryable(response: Response?, cause: Throwable?): Boolean {
            response?.let { return RetryUtils.isRetryable(it.status.code) }
            cause?.let { return RetryUtils.isRetryable(it) }
            return false
        }
    }
}
