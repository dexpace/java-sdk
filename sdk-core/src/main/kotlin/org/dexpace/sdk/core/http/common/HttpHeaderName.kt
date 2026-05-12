package org.dexpace.sdk.core.http.common

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * An interned, case-insensitive HTTP header name.
 *
 * Two [HttpHeaderName] instances are considered equal when their [caseInsensitiveName]
 * forms match. The original case supplied to [fromString] is preserved in
 * [caseSensitiveName] so it can be used on the wire or in logs, but identity, hashing
 * and equality are driven entirely by the lower-cased form.
 *
 * All instances returned by [fromString] (and by the well-known constants below) are
 * interned through a process-wide [java.util.concurrent.ConcurrentHashMap], so repeated
 * lookups of the same name return the same instance. The case-preserving form of the
 * first caller to intern a given name "wins"; subsequent lookups with different casing
 * yield the same shared instance.
 *
 * Whitespace is trimmed from the input before interning.
 *
 * Designed for Java 8 bytecode compatibility — no APIs newer than Java 8 are used.
 */
class HttpHeaderName private constructor(
    /** The original, case-preserved form of the name as supplied to [fromString]. */
    val caseSensitiveName: String,
    /** The lower-cased (US locale) form used for hashing and equality. */
    val caseInsensitiveName: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is HttpHeaderName && caseInsensitiveName == other.caseInsensitiveName)

    override fun hashCode(): Int = caseInsensitiveName.hashCode()

    /** Returns the case-preserved form, suitable for wire emission and logging. */
    override fun toString(): String = caseSensitiveName

    companion object {
        // The intern map MUST be declared (and initialised) before the well-known
        // constants below, because every constant's initialiser calls `fromString`
        // which reads `INTERN`. Kotlin emits companion-object initialisers in
        // textual order; placing `INTERN` later would cause an NPE during class
        // init and surface as `ExceptionInInitializerError` / `NoClassDefFoundError`
        // on the very first access.
        private val INTERN: ConcurrentHashMap<String, HttpHeaderName> = ConcurrentHashMap()

        // --- Well-known header constants --------------------------------------------------
        // Exposed as @JvmField so Java sees them as static fields rather than getters.

        @JvmField val ACCEPT: HttpHeaderName = fromString("Accept")
        @JvmField val ACCEPT_CHARSET: HttpHeaderName = fromString("Accept-Charset")
        @JvmField val ACCEPT_ENCODING: HttpHeaderName = fromString("Accept-Encoding")
        @JvmField val ACCEPT_LANGUAGE: HttpHeaderName = fromString("Accept-Language")
        @JvmField val ACCEPT_RANGES: HttpHeaderName = fromString("Accept-Ranges")
        @JvmField val ACCESS_CONTROL_ALLOW_CREDENTIALS: HttpHeaderName = fromString("Access-Control-Allow-Credentials")
        @JvmField val ACCESS_CONTROL_ALLOW_HEADERS: HttpHeaderName = fromString("Access-Control-Allow-Headers")
        @JvmField val ACCESS_CONTROL_ALLOW_METHODS: HttpHeaderName = fromString("Access-Control-Allow-Methods")
        @JvmField val ACCESS_CONTROL_ALLOW_ORIGIN: HttpHeaderName = fromString("Access-Control-Allow-Origin")
        @JvmField val ACCESS_CONTROL_EXPOSE_HEADERS: HttpHeaderName = fromString("Access-Control-Expose-Headers")
        @JvmField val ACCESS_CONTROL_MAX_AGE: HttpHeaderName = fromString("Access-Control-Max-Age")
        @JvmField val ACCESS_CONTROL_REQUEST_HEADERS: HttpHeaderName = fromString("Access-Control-Request-Headers")
        @JvmField val ACCESS_CONTROL_REQUEST_METHOD: HttpHeaderName = fromString("Access-Control-Request-Method")
        @JvmField val AGE: HttpHeaderName = fromString("Age")
        @JvmField val ALLOW: HttpHeaderName = fromString("Allow")
        @JvmField val AUTHORIZATION: HttpHeaderName = fromString("Authorization")
        @JvmField val CACHE_CONTROL: HttpHeaderName = fromString("Cache-Control")
        @JvmField val CONNECTION: HttpHeaderName = fromString("Connection")
        @JvmField val CONTENT_DISPOSITION: HttpHeaderName = fromString("Content-Disposition")
        @JvmField val CONTENT_ENCODING: HttpHeaderName = fromString("Content-Encoding")
        @JvmField val CONTENT_LANGUAGE: HttpHeaderName = fromString("Content-Language")
        @JvmField val CONTENT_LENGTH: HttpHeaderName = fromString("Content-Length")
        @JvmField val CONTENT_LOCATION: HttpHeaderName = fromString("Content-Location")
        @JvmField val CONTENT_MD5: HttpHeaderName = fromString("Content-MD5")
        @JvmField val CONTENT_RANGE: HttpHeaderName = fromString("Content-Range")
        @JvmField val CONTENT_SECURITY_POLICY: HttpHeaderName = fromString("Content-Security-Policy")
        @JvmField val CONTENT_TYPE: HttpHeaderName = fromString("Content-Type")
        @JvmField val COOKIE: HttpHeaderName = fromString("Cookie")
        @JvmField val DATE: HttpHeaderName = fromString("Date")
        @JvmField val ETAG: HttpHeaderName = fromString("ETag")
        @JvmField val EXPECT: HttpHeaderName = fromString("Expect")
        @JvmField val EXPIRES: HttpHeaderName = fromString("Expires")
        @JvmField val FORWARDED: HttpHeaderName = fromString("Forwarded")
        @JvmField val FROM: HttpHeaderName = fromString("From")
        @JvmField val HOST: HttpHeaderName = fromString("Host")
        @JvmField val IF_MATCH: HttpHeaderName = fromString("If-Match")
        @JvmField val IF_MODIFIED_SINCE: HttpHeaderName = fromString("If-Modified-Since")
        @JvmField val IF_NONE_MATCH: HttpHeaderName = fromString("If-None-Match")
        @JvmField val IF_RANGE: HttpHeaderName = fromString("If-Range")
        @JvmField val IF_UNMODIFIED_SINCE: HttpHeaderName = fromString("If-Unmodified-Since")
        @JvmField val LAST_MODIFIED: HttpHeaderName = fromString("Last-Modified")
        @JvmField val LINK: HttpHeaderName = fromString("Link")
        @JvmField val LOCATION: HttpHeaderName = fromString("Location")
        @JvmField val MAX_FORWARDS: HttpHeaderName = fromString("Max-Forwards")
        @JvmField val ORIGIN: HttpHeaderName = fromString("Origin")
        @JvmField val PRAGMA: HttpHeaderName = fromString("Pragma")
        @JvmField val PROXY_AUTHENTICATE: HttpHeaderName = fromString("Proxy-Authenticate")
        @JvmField val PROXY_AUTHORIZATION: HttpHeaderName = fromString("Proxy-Authorization")
        @JvmField val RANGE: HttpHeaderName = fromString("Range")
        @JvmField val REFERER: HttpHeaderName = fromString("Referer")
        @JvmField val REFERRER_POLICY: HttpHeaderName = fromString("Referrer-Policy")
        @JvmField val RETRY_AFTER: HttpHeaderName = fromString("Retry-After")
        @JvmField val SERVER: HttpHeaderName = fromString("Server")
        @JvmField val SET_COOKIE: HttpHeaderName = fromString("Set-Cookie")
        @JvmField val STRICT_TRANSPORT_SECURITY: HttpHeaderName = fromString("Strict-Transport-Security")
        @JvmField val TE: HttpHeaderName = fromString("TE")
        @JvmField val TRAILER: HttpHeaderName = fromString("Trailer")
        @JvmField val TRANSFER_ENCODING: HttpHeaderName = fromString("Transfer-Encoding")
        @JvmField val UPGRADE: HttpHeaderName = fromString("Upgrade")
        @JvmField val USER_AGENT: HttpHeaderName = fromString("User-Agent")
        @JvmField val VARY: HttpHeaderName = fromString("Vary")
        @JvmField val VIA: HttpHeaderName = fromString("Via")
        @JvmField val WARNING: HttpHeaderName = fromString("Warning")
        @JvmField val WWW_AUTHENTICATE: HttpHeaderName = fromString("WWW-Authenticate")
        @JvmField val X_CONTENT_TYPE_OPTIONS: HttpHeaderName = fromString("X-Content-Type-Options")
        @JvmField val X_FORWARDED_FOR: HttpHeaderName = fromString("X-Forwarded-For")
        @JvmField val X_FORWARDED_HOST: HttpHeaderName = fromString("X-Forwarded-Host")
        @JvmField val X_FORWARDED_PROTO: HttpHeaderName = fromString("X-Forwarded-Proto")
        @JvmField val X_FRAME_OPTIONS: HttpHeaderName = fromString("X-Frame-Options")
        @JvmField val X_REQUEST_ID: HttpHeaderName = fromString("X-Request-Id")
        @JvmField val X_XSS_PROTECTION: HttpHeaderName = fromString("X-XSS-Protection")

        // Microsoft retry headers — consumed by RetryStep (Phase B).
        @JvmField val RETRY_AFTER_MS: HttpHeaderName = fromString("retry-after-ms")
        @JvmField val X_MS_RETRY_AFTER_MS: HttpHeaderName = fromString("x-ms-retry-after-ms")
        @JvmField val X_MS_REQUEST_ID: HttpHeaderName = fromString("x-ms-request-id")
        @JvmField val X_MS_CLIENT_REQUEST_ID: HttpHeaderName = fromString("x-ms-client-request-id")
        @JvmField val X_MS_CORRELATION_REQUEST_ID: HttpHeaderName = fromString("x-ms-correlation-request-id")

        /**
         * Returns an interned [HttpHeaderName] for [name].
         *
         * The input is trimmed of leading/trailing whitespace before being normalised to
         * lower-case (US locale) for the interning key. The case-preserved form of the
         * first caller to intern a given key wins; subsequent calls with different casing
         * yield the same shared instance.
         */
        @JvmStatic
        fun fromString(name: String): HttpHeaderName {
            val trimmed = name.trim()
            val key = trimmed.lowercase(Locale.US)
            // computeIfAbsent is available on Java 8.
            return INTERN.computeIfAbsent(key) { HttpHeaderName(trimmed, key) }
        }
    }
}
