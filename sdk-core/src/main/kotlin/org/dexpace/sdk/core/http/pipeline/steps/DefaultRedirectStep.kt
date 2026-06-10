/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.PipelineNext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.UrlRedactor
import java.io.IOException
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale

/**
 * Default [RedirectStep]. Follows 301, 302, 307, and 308 responses (303 opt-in via
 * [HttpRedirectOptions.follow303]) up to [HttpRedirectOptions.maxHops] hops.
 *
 * ## Security
 *
 * The `Authorization` header is stripped before every redirect reissue — even when the
 * redirect target is the same origin. Re-stamping a credential for a *known* origin is the
 * job of an [org.dexpace.sdk.core.http.pipeline.Stage.AUTH] step, not this one.
 *
 * Because the AUTH stage runs *inside* the redirect loop, it would otherwise re-stamp the
 * caller's credential onto whatever host the redirect targets. To prevent a credential leak
 * to a server-chosen foreign origin, a **cross-origin** re-issue — one whose scheme, host, or
 * port differs from the **original** request's origin — is tagged with an internal marker (see
 * [CrossOriginRedirectMarker]); the shipped [AuthStep] implementations skip stamping when
 * the marker is present and strip it before the request reaches the wire. The comparison is
 * against the original origin, not the immediately preceding hop, so every hop of a multi-hop
 * chain that has left the caller's origin stays credential-free — including a same-origin
 * sub-redirect on the foreign host. The concrete guarantee: with the shipped
 * [BearerTokenAuthStep] / [KeyCredentialAuthStep], a cross-origin redirect never carries the
 * caller's bearer/key credential to the foreign host. A custom AUTH step that ignores the
 * marker does not inherit this guarantee.
 *
 * On a cross-origin redirect the `Cookie` and `Proxy-Authorization` headers are also
 * stripped, matching browser / OkHttp / JDK behaviour — they are origin-scoped credentials
 * that must not follow a redirect to a different host.
 *
 * If the `Location` header carries `userinfo` (`https://user:pass@host`), it is dropped
 * before reissue — server-supplied credentials must not be used.
 *
 * ## Method / body matrix
 *
 * | Status | Allowed method | Action |
 * | --- | --- | --- |
 * | 301, 302 | GET, HEAD (configurable) | Follow with original method **and body**; body must be replayable. |
 * | 303      | any | `follow303 = true` → reissue as GET, drop body and `Content-*`. Else: do not follow. |
 * | 307, 308 | any allowed method | Follow with original method **and body**; body must be replayable. |
 * | anything else | — | Do not follow. |
 *
 * Any method-preserving redirect (301/302/307/308) re-sends the original body, so the body
 * must be replayable; otherwise [IllegalStateException] is thrown — the body cannot be safely
 * re-sent. (303 drops the body, so it is exempt.)
 *
 * ## Loop detection
 *
 * Every visited URI (starting with the original request) is recorded in a
 * `LinkedHashSet<String>`. If a redirect would revisit a URI already in the set, the
 * current response is returned without throwing — the caller can inspect the 3xx itself.
 * [HttpRedirectOptions.maxHops] is the second line of defense.
 *
 * ## Performance
 *
 * - Iterative loop — stack-safe regardless of [HttpRedirectOptions.maxHops].
 * - [HttpRedirectCondition] is allocated only when [HttpRedirectOptions.shouldRedirect]
 *   is non-null, or after passing the cheap status-code pre-filter.
 * - URL parsing uses [java.net.URI] for `resolve(...)`; the result is short-lived.
 *
 * ## Thread-safety
 *
 * Stateless after construction — the [ClientLogger] is reused. Per-call state lives on
 * the stack frame of [process].
 *
 * ## Subclassing contract
 *
 * This class is `open` to permit extension, but it exposes **no** `protected open`
 * extension points today. Subclasses that need to alter redirect behaviour should
 * override [process] directly.
 *
 * If you override [process]:
 * - You own the full redirect loop; none of the private helpers ([recreateRedirectRequest],
 *   [resolveLocation], [enforceSchemeDowngradePolicy], etc.) are accessible from a subclass.
 * - You must close any in-flight response body before issuing the follow-up request to
 *   avoid resource leaks, matching the contract maintained by the base implementation.
 * - The `Authorization` header stripping and `userinfo` removal from `Location` URLs are
 *   security-critical; preserve them in any override.
 */
public open class DefaultRedirectStep
    @JvmOverloads
    constructor(
        private val options: HttpRedirectOptions = HttpRedirectOptions(),
        internal val logger: ClientLogger = ClientLogger(DefaultRedirectStep::class),
    ) : RedirectStep() {
        // Each branch (not-a-redirect, opted-out, recreate-returned-null, cycle-detected,
        // max-hops-reached) is a distinct semantic outcome with its own log/close discipline;
        // collapsing to a single return would require a sentinel and an outer switch.
        @Suppress("ReturnCount")
        @Throws(IOException::class)
        override fun process(
            request: Request,
            next: PipelineNext,
        ): Response {
            val seenUris = LinkedHashSet<String>()
            seenUris.add(request.url.toString())

            var current: Response = next.copy().process()
            var attempts = 0

            while (attempts < options.maxHops) {
                if (!isRedirectStatus(current.status.code)) return current

                // Defensive copy: HttpRedirectCondition.redirectedUris is documented as a
                // snapshot, so a user predicate must not be able to mutate the live cycle-
                // detection set through it.
                val condition = HttpRedirectCondition(current, attempts, LinkedHashSet(seenUris))
                val shouldRedirect =
                    options.shouldRedirect?.shouldRedirect(condition)
                        ?: defaultShouldRedirect(condition)
                if (!shouldRedirect) return current

                val nextRequest =
                    try {
                        // Cross-origin is judged against the seed (request.url), not the current
                        // hop, so a foreign host can't launder the credential back via a same-
                        // origin sub-redirect.
                        recreateRedirectRequest(current, request.url)
                    } catch (t: Throwable) {
                        current.close()
                        throw t
                    } ?: return current
                val nextUrlString = nextRequest.url.toString()
                if (!seenUris.add(nextUrlString)) {
                    // Cycle detected — return the current redirect response rather than looping.
                    // The caller receives the in-flight response with close-responsibility intact,
                    // matching the contract of every other `return current` path in this function.
                    // Closing here would deliver an already-closed body to the caller.
                    logRedirectLoop(current, nextRequest)
                    return current
                }
                logRedirectHop(current, nextRequest, attempts)
                current.close()
                current = next.copy().process(nextRequest)
                attempts++
            }
            // Max attempts reached — the response IS the result, even if it's another redirect.
            return current
        }

        private fun isRedirectStatus(code: Int): Boolean = code in REDIRECT_STATUS_CODES

        /**
         * Default predicate: follow if status is 301/302/307/308 (or 303 when opted in) AND
         * the original method is allowed AND the [HttpRedirectOptions.locationHeader] is
         * present. Loop detection is handled separately in the main loop.
         */
        private fun defaultShouldRedirect(condition: HttpRedirectCondition): Boolean {
            val response = condition.response
            val code = response.status.code

            // 303 doesn't go through the method matrix — the reissue is always GET, so the
            // original method is irrelevant. Opt-in via options.follow303.
            if (code == SC_SEE_OTHER) return options.follow303

            // 301/302 — default policy is GET/HEAD only.
            // 307/308 — original method preserved; any method is opt-in via allowedMethods.
            if (code !in METHOD_PRESERVING_REDIRECTS) return false
            if (response.request.method !in options.allowedMethods) return false
            val location = response.headers.get(options.locationHeader)
            return !location.isNullOrEmpty()
        }

        /**
         * Builds the redirect target request. Returns null if the `Location` is missing,
         * empty, or malformed — the caller treats null as "don't redirect, return the
         * current response". Throws [IllegalStateException] when the redirect would downgrade
         * the scheme from HTTPS to HTTP and [HttpRedirectOptions.allowSchemeDowngrade] is
         * false, or when a method-preserving redirect carries a non-replayable body.
         */
        @Throws(IOException::class)
        private fun recreateRedirectRequest(
            response: Response,
            originUrl: URL,
        ): Request? {
            val rawLocation = response.headers.get(options.locationHeader)
            if (rawLocation.isNullOrEmpty()) return null

            val originalRequest = response.request
            // A relative Location is resolved against the CURRENT hop's URL (RFC 3986), and the
            // HTTPS->HTTP downgrade check looks at this single hop's transition. The cross-origin
            // credential decision, however, is made against [originUrl] — the original seed origin
            // — so that a same-origin sub-redirect on a foreign host does not re-expose the
            // credential. These three comparisons use deliberately different baselines.
            val resolvedUrl = resolveLocation(originalRequest.url, rawLocation) ?: return null

            enforceSchemeDowngradePolicy(originalRequest.url, resolvedUrl)

            val crossOrigin = CrossOriginRedirectMarker.isCrossOrigin(originUrl, resolvedUrl)
            val code = response.status.code
            // 303 with follow303=true: reissue as GET, drop body and Content-* headers.
            if (code == SC_SEE_OTHER && options.follow303) {
                return buildGetRedirect(originalRequest, resolvedUrl, crossOrigin)
            }

            // Any method-preserving redirect retains the original body. 307/308 always preserve
            // the method; 301/302 preserve it too when the caller widened allowedMethods beyond
            // GET/HEAD. In every such case the body is re-sent, so it must be replayable.
            val body = originalRequest.body
            check(body == null || body.isReplayable()) {
                "Redirect requires replayable body when method and body are preserved; " +
                    "call Request.body.toReplayable() before send."
            }

            return originalRequest.newBuilder()
                .url(resolvedUrl)
                .headers(stripCredentialHeaders(originalRequest.headers, crossOrigin))
                .build()
        }

        /**
         * Builds a fresh GET request that targets [resolvedUrl] and copies non-body, non-
         * content metadata from [original]. The `Authorization` header and every `Content-*`
         * header are stripped — the latter because the body is being dropped. On a cross-origin
         * redirect [Cookie] and [Proxy-Authorization] are stripped too and the request is
         * marked so the AUTH stage does not re-stamp a credential for the foreign host.
         */
        private fun buildGetRedirect(
            original: Request,
            resolvedUrl: URL,
            crossOrigin: Boolean,
        ): Request {
            val strippedHeaders = stripContentAndAuthHeaders(original.headers, crossOrigin)
            return Request.builder()
                .method(Method.GET)
                .url(resolvedUrl)
                .headers(strippedHeaders)
                .build()
        }

        private fun stripContentAndAuthHeaders(
            headers: Headers,
            crossOrigin: Boolean,
        ): Headers {
            val builder = stripCredentialHeaders(headers, crossOrigin).newBuilder()
            // Strip every header whose name starts with "content-" (case-insensitive). The
            // underlying store may return names in mixed case (`Content-Type`), so lower-case
            // before the prefix test. Iterate a snapshot of the keys to avoid concurrent
            // modification while mutating the builder.
            val toRemove = ArrayList<String>()
            for (name in headers.names()) {
                if (name.lowercase(Locale.US).startsWith("content-")) toRemove.add(name)
            }
            for (name in toRemove) builder.remove(name)
            return builder.build()
        }

        /**
         * Drops `Authorization` unconditionally (re-stamping a known origin is the AUTH stage's
         * job). On a cross-origin redirect also drops the origin-scoped `Cookie` and
         * `Proxy-Authorization` credentials and tags the request with the internal cross-origin
         * marker so the AUTH stage skips re-stamping for the foreign host.
         */
        private fun stripCredentialHeaders(
            headers: Headers,
            crossOrigin: Boolean,
        ): Headers {
            val builder = headers.newBuilder()
            builder.remove(HttpHeaderName.AUTHORIZATION)
            // Clear any caller-supplied marker first so it can never be forged into an auth
            // bypass; the redirect step is the only legitimate source of the marker.
            builder.remove(CrossOriginRedirectMarker.MARKER_HEADER)
            if (crossOrigin) {
                builder.remove(HttpHeaderName.COOKIE)
                builder.remove(HttpHeaderName.PROXY_AUTHORIZATION)
                builder.set(CrossOriginRedirectMarker.MARKER_HEADER, CrossOriginRedirectMarker.MARKER_VALUE)
            }
            return builder.build()
        }

        /**
         * Resolves [location] against [base]. Absolute URLs are returned as-is (with
         * userinfo stripped); relative URLs are resolved via [URI.resolve]. Returns null
         * for malformed inputs (logged at warning).
         */
        private fun resolveLocation(
            base: URL,
            location: String,
        ): URL? {
            return try {
                val baseUri = base.toURI()
                val resolved = baseUri.resolve(location)
                stripUserInfo(resolved).toURL()
            } catch (t: URISyntaxException) {
                logMalformedLocation(location, t)
                null
            } catch (t: IllegalArgumentException) {
                logMalformedLocation(location, t)
                null
            } catch (t: MalformedURLException) {
                logMalformedLocation(location, t)
                null
            }
        }

        private fun logMalformedLocation(
            location: String,
            cause: Throwable,
        ) {
            logger.atWarning()
                .event("http.redirect.malformed_location")
                .field("location.raw", location)
                .field("error.type", cause::class.java.simpleName ?: "Throwable")
                .log()
        }

        /**
         * Returns [uri] with its userinfo component cleared. If [uri] has no userinfo the
         * input is returned unchanged.
         *
         * The URI is rebuilt **textually** from its already-encoded (`raw*`) components rather
         * than via the multi-argument [URI] constructor. That constructor takes *decoded*
         * components and re-encodes them, which would corrupt a `Location` whose path or query
         * carries percent-escaped reserved characters: `%2F` would decode to `/` and `%26` to
         * `&`, silently changing the path/query structure. Reassembling from `rawPath` /
         * `rawQuery` / `rawFragment` preserves the wire-exact encoding.
         */
        private fun stripUserInfo(uri: URI): URI {
            if (uri.userInfo == null) return uri
            val sb = StringBuilder()
            sb.append(uri.scheme).append("://").append(uri.host)
            if (uri.port != -1) sb.append(':').append(uri.port)
            uri.rawPath?.let { sb.append(it) }
            uri.rawQuery?.let { sb.append('?').append(it) }
            uri.rawFragment?.let { sb.append('#').append(it) }
            return URI(sb.toString())
        }

        /**
         * Enforces the HTTPS-to-HTTP downgrade policy. By default ([HttpRedirectOptions.allowSchemeDowngrade]
         * = false) any HTTPS → HTTP redirect throws [IllegalStateException] before reissue,
         * preventing credentials / TLS guarantees from silently disappearing. When the caller
         * opts in, the downgrade is permitted but logged at WARNING so the deviation stays
         * observable.
         */
        private fun enforceSchemeDowngradePolicy(
            from: URL,
            to: URL,
        ) {
            if (!isHttpsToHttp(from, to)) return
            check(options.allowSchemeDowngrade) {
                "Redirect scheme downgrade from HTTPS to HTTP is not allowed; " +
                    "set allowSchemeDowngrade=true to override"
            }
            logger.atWarning()
                .event("http.redirect.scheme_downgrade")
                .field("from", safeRedact(from))
                .field("to", safeRedact(to))
                .log()
        }

        private fun isHttpsToHttp(
            from: URL,
            to: URL,
        ): Boolean =
            from.protocol.equals("https", ignoreCase = true) &&
                to.protocol.equals("http", ignoreCase = true)

        /**
         * Emits the `http.redirect` log event for a single hop.
         *
         * **Breaking change (backwards-incompatible event-field change):** the older `url.from` /
         * `url.to` fields have been removed. Operators and log parsers that referenced those keys
         * must migrate to `redirect.from_url` / `redirect.target_url`.
         */
        private fun logRedirectHop(
            response: Response,
            nextRequest: Request,
            attempt: Int,
        ) {
            logger.atInfo()
                .event("http.redirect")
                .field("http.response.status_code", response.status.code.toLong())
                // Log as 1-based so "attempt 1" is the first redirect hop; the internal counter
                // remains 0-based to keep the while-loop condition simple.
                .field("http.redirect.attempt", (attempt + 1).toLong())
                .field("redirect.from_url", safeRedact(response.request.url))
                .field("redirect.target_url", safeRedact(nextRequest.url))
                .log()
        }

        private fun logRedirectLoop(
            response: Response,
            nextRequest: Request,
        ) {
            logger.atWarning()
                .event("http.redirect.loop_detected")
                .field("http.response.status_code", response.status.code.toLong())
                .field("url.repeat", safeRedact(nextRequest.url))
                .log()
        }

        private fun safeRedact(url: URL): String =
            try {
                UrlRedactor.redact(url)
            } catch (t: Throwable) {
                "[malformed url]"
            }

        private companion object {
            // HTTP redirect status codes recognised by the step. 304 (Not Modified) and 305
            // (Use Proxy) are intentionally excluded — 304 isn't a redirect and 305 is
            // deprecated by RFC 7231.
            private const val SC_MOVED_PERMANENTLY = 301
            private const val SC_FOUND = 302
            private const val SC_SEE_OTHER = 303
            private const val SC_TEMPORARY_REDIRECT = 307
            private const val SC_PERMANENT_REDIRECT = 308

            // Status codes for which a redirect is attempted. 303 included; method-rewrite
            // semantics are handled separately in [defaultShouldRedirect].
            private val REDIRECT_STATUS_CODES: Set<Int> =
                setOf(
                    SC_MOVED_PERMANENTLY,
                    SC_FOUND,
                    SC_SEE_OTHER,
                    SC_TEMPORARY_REDIRECT,
                    SC_PERMANENT_REDIRECT,
                )

            // Redirect codes that preserve the request method (vs 303 which always rewrites
            // to GET). 301/302 default to GET/HEAD only; 307/308 preserve the original method.
            private val METHOD_PRESERVING_REDIRECTS: Set<Int> =
                setOf(
                    SC_MOVED_PERMANENTLY,
                    SC_FOUND,
                    SC_TEMPORARY_REDIRECT,
                    SC_PERMANENT_REDIRECT,
                )
        }
    }
