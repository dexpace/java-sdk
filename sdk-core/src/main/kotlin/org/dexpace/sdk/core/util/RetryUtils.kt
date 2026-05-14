package org.dexpace.sdk.core.util

import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Shared retryability classifier used by both [org.dexpace.sdk.core.http.response.HttpResponseException]
 * (to precompute its `isRetryable` flag at construction time) and the default retry
 * policy (Rank 4).
 *
 * Retryable conditions:
 * - **Status codes**: 408 (Request Timeout), 429 (Too Many Requests), all 5xx except
 *   501 (Not Implemented) and 505 (HTTP Version Not Supported).
 * - **Throwables**: any [IOException] or [TimeoutException] anywhere in the cause chain.
 *
 * 501 and 505 are explicitly excluded because they indicate the server cannot or will not
 * fulfill the request regardless of retry; see the Azure SDK comparison report §2.3.
 */
public object RetryUtils {
    // HTTP status codes participating in the retry classification. Spelled out as named
    // constants per the project's MagicNumber discipline; the values themselves are RFC
    // 7231 / 6585 / 7540 fixed wire constants and cannot vary.
    private const val SC_REQUEST_TIMEOUT = 408
    private const val SC_TOO_MANY_REQUESTS = 429
    private const val SC_SERVER_ERROR_MIN = 500
    private const val SC_NOT_IMPLEMENTED = 501
    private const val SC_HTTP_VERSION_NOT_SUPPORTED = 505
    private const val SC_SERVER_ERROR_MAX = 599

    /**
     * Returns `true` if the given HTTP [statusCode] is retryable.
     *
     * Retryable: 408, 429, and the 5xx range except for 501 and 505.
     */
    @JvmStatic
    public fun isRetryable(statusCode: Int): Boolean =
        statusCode == SC_REQUEST_TIMEOUT || statusCode == SC_TOO_MANY_REQUESTS ||
            (
                statusCode in SC_SERVER_ERROR_MIN..SC_SERVER_ERROR_MAX &&
                    statusCode != SC_NOT_IMPLEMENTED &&
                    statusCode != SC_HTTP_VERSION_NOT_SUPPORTED
            )

    /**
     * Returns `true` if [t] or any throwable in its cause chain is an [IOException] or
     * [TimeoutException]. Walks the chain iteratively and tracks visited references so a
     * self-referential chain (e.g. an exception whose `cause` is itself) terminates
     * cleanly instead of looping forever.
     */
    @JvmStatic
    public fun isRetryable(t: Throwable): Boolean {
        var current: Throwable? = t
        // Identity-based tracking: HashSet uses Object.equals/hashCode, which for
        // Throwables defaults to identity — exactly what we need to detect a cycle.
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is IOException || current is TimeoutException) return true
            current = current.cause
        }
        return false
    }
}
