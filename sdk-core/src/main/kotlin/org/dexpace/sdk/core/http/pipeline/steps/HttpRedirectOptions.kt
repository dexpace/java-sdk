package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.request.Method
import java.util.EnumSet

/**
 * Configuration for [DefaultRedirectStep].
 *
 * Defaults mirror Azure Core's redirect policy:
 *  - [maxAttempts] = 3 hops before the step returns the last response without throwing.
 *  - [allowedMethods] = `{GET, HEAD}` — only idempotent methods follow redirects by default,
 *    preventing accidental re-issue of `POST` against a different URI.
 *  - [locationHeader] = `Location` — overridable for service-specific custom headers.
 *  - [follow303] = `false` — 303 (See Other) is NOT followed by default. Opt-in to
 *    RFC 7231-compliant behavior (re-issue as `GET`, drop body).
 *  - [allowSchemeDowngrade] = `false` — HTTPS → HTTP redirects throw
 *    [IllegalStateException]. Opt-in switches to a WARNING log and lets the downgrade
 *    proceed. Credentials are still stripped before reissue regardless of this flag.
 *
 * Pass a non-null [shouldRedirect] to fully override the built-in decision logic; the
 * default predicate inspects status, allowed method, presence of [locationHeader], and
 * loop detection.
 */
class HttpRedirectOptions @JvmOverloads constructor(
    val maxAttempts: Int = 3,
    val allowedMethods: EnumSet<Method> = EnumSet.of(Method.GET, Method.HEAD),
    val locationHeader: HttpHeaderName = HttpHeaderName.LOCATION,
    val follow303: Boolean = false,
    val allowSchemeDowngrade: Boolean = false,
    val shouldRedirect: ((HttpRedirectCondition) -> Boolean)? = null,
)
