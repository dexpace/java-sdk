package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.response.Response

/**
 * Snapshot passed to [HttpRedirectOptions.shouldRedirect] for an in-progress redirect
 * decision. Read-only — the predicate inspects [response], [tryCount], and the set of
 * previously [redirectedUris] to decide whether to follow the next hop.
 *
 * `tryCount` is the number of redirects already followed (`0` on the very first
 * decision). `redirectedUris` is a snapshot of the URIs that have been visited so far,
 * including the current request's URI; the set's iteration order is insertion order.
 *
 * Construction is avoided on the no-redirect fast path (non-3xx status, no `Location`
 * header) — the step short-circuits before allocating one.
 */
public data class HttpRedirectCondition(
    val response: Response,
    val tryCount: Int,
    val redirectedUris: Set<String>,
)
