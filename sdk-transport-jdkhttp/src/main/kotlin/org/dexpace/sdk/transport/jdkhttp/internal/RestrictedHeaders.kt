package org.dexpace.sdk.transport.jdkhttp.internal

import java.util.Locale

/**
 * Critical-path headers the `java.net.http.HttpClient` recomputes from the body or connection.
 * Manually setting any of these on the SDK [org.dexpace.sdk.core.http.request.Request] would
 * either cause `java.net.http` to reject the request outright (via `IllegalArgumentException`
 * from `HttpRequest.Builder.header`) or silently produce a wire-format mismatch — so the
 * adapter silently drops them before handing the request to the JDK client, emitting a DEBUG
 * log naming each dropped header.
 *
 * The JDK client maintains an internal `DISALLOWED_HEADERS_SET` of header names that throw
 * `IllegalArgumentException` when set; the four below are the ones an SDK user is most
 * likely to set by accident:
 *
 *  - `Content-Length` — recomputed from the body publisher's `contentLength()`.
 *  - `Host` — always populated from the request URL.
 *  - `Transfer-Encoding` — determined by streaming semantics, not user choice.
 *  - `Connection` — JDK 11+ rejects manual values (see `jdk.httpclient.allowRestrictedHeaders`
 *    if a consumer truly needs to override this, but the SDK does not).
 *
 * This list is broader than the OkHttp transport's by exactly one entry (`Connection`) because
 * the JDK enforces stricter validation than OkHttp.
 *
 * Names are stored lower-case to match [org.dexpace.sdk.core.http.common.Headers]'
 * case-insensitive canonical form (sanitized via `Locale.US.lowercase`).
 */
internal object RestrictedHeaders {
    private val NAMES: Set<String> =
        setOf(
            "content-length".lowercase(Locale.US),
            "host".lowercase(Locale.US),
            "transfer-encoding".lowercase(Locale.US),
            "connection".lowercase(Locale.US),
        )

    /** Returns `true` when [name] (case-insensitive) is in the drop list. */
    fun isRestricted(name: String): Boolean = NAMES.contains(name.lowercase(Locale.US))
}
