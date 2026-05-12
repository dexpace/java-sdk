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
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * Default [RedirectStep]. Follows 301, 302, 307, and 308 responses (303 opt-in via
 * [HttpRedirectOptions.follow303]) up to [HttpRedirectOptions.maxAttempts] hops.
 *
 * ## Security
 *
 * The `Authorization` header is stripped before every redirect reissue — even when the
 * redirect target is the same origin. Re-stamping a credential for a known origin is the
 * job of an [org.dexpace.sdk.core.http.pipeline.Stage.AUTH] step, not this one. Stripping
 * unconditionally prevents credentials from leaking to a server-supplied URI.
 *
 * If the `Location` header carries `userinfo` (`https://user:pass@host`), it is dropped
 * before reissue — server-supplied credentials must not be used.
 *
 * ## Method / body matrix
 *
 * | Status | Allowed method | Action |
 * | --- | --- | --- |
 * | 301, 302 | GET, HEAD (configurable) | Follow with original method |
 * | 303      | any | `follow303 = true` → reissue as GET, drop body and `Content-*`. Else: do not follow. |
 * | 307, 308 | any allowed method | Follow with original method **and body**; body must be replayable. |
 * | anything else | — | Do not follow. |
 *
 * A 307/308 redirect of a request with a non-replayable body throws
 * [IllegalStateException] — the body cannot be safely re-sent.
 *
 * ## Loop detection
 *
 * Every visited URI (starting with the original request) is recorded in a
 * `LinkedHashSet<String>`. If a redirect would revisit a URI already in the set, the
 * current response is returned without throwing — the caller can inspect the 3xx itself.
 * [HttpRedirectOptions.maxAttempts] is the second line of defense.
 *
 * ## Performance
 *
 * - Iterative loop — stack-safe regardless of [HttpRedirectOptions.maxAttempts].
 * - [HttpRedirectCondition] is allocated only when [HttpRedirectOptions.shouldRedirect]
 *   is non-null, or after passing the cheap status-code prefilter.
 * - URL parsing uses [java.net.URI] for `resolve(...)`; the result is short-lived.
 *
 * ## Thread-safety
 *
 * Stateless after construction — the [ClientLogger] is reused. Per-call state lives on
 * the stack frame of [process].
 */
open class DefaultRedirectStep @JvmOverloads constructor(
    private val options: HttpRedirectOptions = HttpRedirectOptions(),
) : RedirectStep() {

    private val logger = ClientLogger(DefaultRedirectStep::class)

    @Throws(IOException::class)
    override fun process(request: Request, next: PipelineNext): Response {
        val seenUris = LinkedHashSet<String>()
        seenUris.add(request.url.toString())

        var current: Response = next.copy().process()
        var attempts = 0

        while (attempts < options.maxAttempts) {
            if (!isRedirectStatus(current.status.code)) return current

            val condition = HttpRedirectCondition(current, attempts, seenUris)
            val shouldRedirect = options.shouldRedirect?.invoke(condition)
                ?: defaultShouldRedirect(condition)
            if (!shouldRedirect) return current

            val nextRequest = recreateRedirectRequest(current) ?: return current
            val nextUrlString = nextRequest.url.toString()
            if (!seenUris.add(nextUrlString)) {
                // Cycle detected — return the current redirect response rather than looping.
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

    private fun isRedirectStatus(code: Int): Boolean =
        code == 301 || code == 302 || code == 303 || code == 307 || code == 308

    /**
     * Default predicate: follow if status is 301/302/307/308 (or 303 when opted in) AND
     * the original method is allowed AND the [HttpRedirectOptions.locationHeader] is
     * present. Loop detection is handled separately in the main loop.
     */
    private fun defaultShouldRedirect(condition: HttpRedirectCondition): Boolean {
        val response = condition.response
        val code = response.status.code
        val method = response.request.method

        // 303 — only when opted in; method matrix doesn't gate it because the reissue is GET.
        if (code == 303) return options.follow303

        // 301/302 — restricted to allowed methods (default: GET/HEAD).
        // 307/308 — restricted to allowed methods (any method is opt-in via options).
        if (code == 301 || code == 302 || code == 307 || code == 308) {
            if (!options.allowedMethods.contains(method)) return false
            val location = response.headers.get(options.locationHeader)
            return !location.isNullOrEmpty()
        }
        return false
    }

    /**
     * Builds the redirect target request. Returns null if the `Location` is missing,
     * empty, or malformed — the caller treats null as "don't redirect, return the
     * current response".
     */
    @Throws(IOException::class)
    private fun recreateRedirectRequest(response: Response): Request? {
        val rawLocation = response.headers.get(options.locationHeader)
        if (rawLocation.isNullOrEmpty()) return null

        val originalRequest = response.request
        val resolvedUrl = resolveLocation(originalRequest.url, rawLocation) ?: return null

        warnOnSchemeDowngrade(originalRequest.url, resolvedUrl)

        val code = response.status.code
        // 303 with follow303=true: reissue as GET, drop body and Content-* headers.
        if (code == 303 && options.follow303) {
            return buildGetRedirect(originalRequest, resolvedUrl)
        }

        // 307/308: original method AND body preserved. Replayability is required.
        if (code == 307 || code == 308) {
            val body = originalRequest.body
            check(body == null || body.isReplayable()) {
                "Redirect requires replayable body for 307/308; call Request.body.toReplayable() before send."
            }
        }

        return originalRequest.newBuilder()
            .url(resolvedUrl)
            .removeHeader(HttpHeaderName.AUTHORIZATION.caseSensitiveName)
            .build()
    }

    /**
     * Builds a fresh GET request that targets [resolvedUrl] and copies non-body, non-
     * content metadata from [original]. The `Authorization` header and every `Content-*`
     * header are stripped — the latter because the body is being dropped.
     */
    private fun buildGetRedirect(original: Request, resolvedUrl: URL): Request {
        val strippedHeaders = stripContentAndAuthHeaders(original.headers)
        return Request.builder()
            .method(Method.GET)
            .url(resolvedUrl)
            .headers(strippedHeaders)
            .build()
    }

    private fun stripContentAndAuthHeaders(headers: Headers): Headers {
        val builder = headers.newBuilder()
        builder.remove(HttpHeaderName.AUTHORIZATION)
        // Strip every header whose name starts with "content-" (case-insensitive). Iterate
        // the keys snapshot so we can remove without ConcurrentModificationException.
        val toRemove = ArrayList<String>()
        for (name in headers.names()) {
            if (name.startsWith("content-")) toRemove.add(name)
        }
        for (name in toRemove) builder.remove(name)
        return builder.build()
    }

    /**
     * Resolves [location] against [base]. Absolute URLs are returned as-is (with
     * userinfo stripped); relative URLs are resolved via [URI.resolve]. Returns null
     * for malformed inputs (logged at warning).
     */
    private fun resolveLocation(base: URL, location: String): URL? {
        return try {
            val baseUri = base.toURI()
            val resolved = baseUri.resolve(location)
            stripUserInfo(resolved).toURL()
        } catch (t: URISyntaxException) {
            logger.atWarning()
                .event("http.redirect.malformed_location")
                .field("location.raw", location)
                .field("error.type", t::class.java.simpleName ?: "URISyntaxException")
                .log()
            null
        } catch (t: IllegalArgumentException) {
            logger.atWarning()
                .event("http.redirect.malformed_location")
                .field("location.raw", location)
                .field("error.type", t::class.java.simpleName ?: "IllegalArgumentException")
                .log()
            null
        } catch (t: java.net.MalformedURLException) {
            logger.atWarning()
                .event("http.redirect.malformed_location")
                .field("location.raw", location)
                .field("error.type", t::class.java.simpleName ?: "MalformedURLException")
                .log()
            null
        }
    }

    /**
     * Returns [uri] with its userinfo component cleared. If [uri] has no userinfo the
     * input is returned unchanged.
     */
    private fun stripUserInfo(uri: URI): URI {
        if (uri.userInfo == null) return uri
        return URI(
            uri.scheme,
            null,
            uri.host,
            uri.port,
            uri.path,
            uri.query,
            uri.fragment,
        )
    }

    private fun warnOnSchemeDowngrade(from: URL, to: URL) {
        if (from.protocol.equals("https", ignoreCase = true) &&
            to.protocol.equals("http", ignoreCase = true)
        ) {
            logger.atInfo()
                .event("http.redirect.scheme_downgrade")
                .field("from", safeRedact(from))
                .field("to", safeRedact(to))
                .log()
        }
    }

    private fun logRedirectHop(response: Response, nextRequest: Request, attempt: Int) {
        logger.atInfo()
            .event("http.redirect")
            .field("http.response.status_code", response.status.code.toLong())
            .field("http.redirect.attempt", attempt.toLong())
            .field("url.from", safeRedact(response.request.url))
            .field("url.to", safeRedact(nextRequest.url))
            .log()
    }

    private fun logRedirectLoop(response: Response, nextRequest: Request) {
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
}
