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
public class HttpHeaderName private constructor(
    /** The original, case-preserved form of the name as supplied to [fromString]. */
    public val caseSensitiveName: String,
    /** The lower-cased (US locale) form used for hashing and equality. */
    public val caseInsensitiveName: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is HttpHeaderName && caseInsensitiveName == other.caseInsensitiveName)

    override fun hashCode(): Int = caseInsensitiveName.hashCode()

    /** Returns the case-preserved form, suitable for wire emission and logging. */
    override fun toString(): String = caseSensitiveName

    public companion object {
        // The intern map MUST be declared (and initialised) before the well-known
        // constants below, because every constant's initialiser calls `fromString`
        // which reads `INTERN`. Kotlin emits companion-object initialisers in
        // textual order; placing `INTERN` later would cause an NPE during class
        // init and surface as `ExceptionInInitializerError` / `NoClassDefFoundError`
        // on the very first access.
        private val INTERN: ConcurrentHashMap<String, HttpHeaderName> = ConcurrentHashMap()

        // --- Well-known header constants --------------------------------------------------
        // Exposed as @JvmField so Java sees them as static fields rather than getters.

        @JvmField public val ACCEPT: HttpHeaderName = fromString("Accept")
        @JvmField public val ACCEPT_CHARSET: HttpHeaderName = fromString("Accept-Charset")
        @JvmField public val ACCEPT_ENCODING: HttpHeaderName = fromString("Accept-Encoding")
        @JvmField public val ACCEPT_LANGUAGE: HttpHeaderName = fromString("Accept-Language")
        @JvmField public val ACCEPT_RANGES: HttpHeaderName = fromString("Accept-Ranges")
        @JvmField public val ACCESS_CONTROL_ALLOW_CREDENTIALS: HttpHeaderName = fromString("Access-Control-Allow-Credentials")
        @JvmField public val ACCESS_CONTROL_ALLOW_HEADERS: HttpHeaderName = fromString("Access-Control-Allow-Headers")
        @JvmField public val ACCESS_CONTROL_ALLOW_METHODS: HttpHeaderName = fromString("Access-Control-Allow-Methods")
        @JvmField public val ACCESS_CONTROL_ALLOW_ORIGIN: HttpHeaderName = fromString("Access-Control-Allow-Origin")
        @JvmField public val ACCESS_CONTROL_EXPOSE_HEADERS: HttpHeaderName = fromString("Access-Control-Expose-Headers")
        @JvmField public val ACCESS_CONTROL_MAX_AGE: HttpHeaderName = fromString("Access-Control-Max-Age")
        @JvmField public val ACCESS_CONTROL_REQUEST_HEADERS: HttpHeaderName = fromString("Access-Control-Request-Headers")
        @JvmField public val ACCESS_CONTROL_REQUEST_METHOD: HttpHeaderName = fromString("Access-Control-Request-Method")
        @JvmField public val AGE: HttpHeaderName = fromString("Age")
        @JvmField public val ALLOW: HttpHeaderName = fromString("Allow")
        @JvmField public val AUTHORIZATION: HttpHeaderName = fromString("Authorization")
        @JvmField public val CACHE_CONTROL: HttpHeaderName = fromString("Cache-Control")
        @JvmField public val CONNECTION: HttpHeaderName = fromString("Connection")
        @JvmField public val CONTENT_DISPOSITION: HttpHeaderName = fromString("Content-Disposition")
        @JvmField public val CONTENT_ENCODING: HttpHeaderName = fromString("Content-Encoding")
        @JvmField public val CONTENT_LANGUAGE: HttpHeaderName = fromString("Content-Language")
        @JvmField public val CONTENT_LENGTH: HttpHeaderName = fromString("Content-Length")
        @JvmField public val CONTENT_LOCATION: HttpHeaderName = fromString("Content-Location")
        @JvmField public val CONTENT_MD5: HttpHeaderName = fromString("Content-MD5")
        @JvmField public val CONTENT_RANGE: HttpHeaderName = fromString("Content-Range")
        @JvmField public val CONTENT_SECURITY_POLICY: HttpHeaderName = fromString("Content-Security-Policy")
        @JvmField public val CONTENT_TYPE: HttpHeaderName = fromString("Content-Type")
        @JvmField public val COOKIE: HttpHeaderName = fromString("Cookie")
        @JvmField public val DATE: HttpHeaderName = fromString("Date")
        @JvmField public val ETAG: HttpHeaderName = fromString("ETag")
        @JvmField public val EXPECT: HttpHeaderName = fromString("Expect")
        @JvmField public val EXPIRES: HttpHeaderName = fromString("Expires")
        @JvmField public val FORWARDED: HttpHeaderName = fromString("Forwarded")
        @JvmField public val FROM: HttpHeaderName = fromString("From")
        @JvmField public val HOST: HttpHeaderName = fromString("Host")
        @JvmField public val IF_MATCH: HttpHeaderName = fromString("If-Match")
        @JvmField public val IF_MODIFIED_SINCE: HttpHeaderName = fromString("If-Modified-Since")
        @JvmField public val IF_NONE_MATCH: HttpHeaderName = fromString("If-None-Match")
        @JvmField public val IF_RANGE: HttpHeaderName = fromString("If-Range")
        @JvmField public val IF_UNMODIFIED_SINCE: HttpHeaderName = fromString("If-Unmodified-Since")
        @JvmField public val LAST_MODIFIED: HttpHeaderName = fromString("Last-Modified")
        @JvmField public val LINK: HttpHeaderName = fromString("Link")
        @JvmField public val LOCATION: HttpHeaderName = fromString("Location")
        @JvmField public val MAX_FORWARDS: HttpHeaderName = fromString("Max-Forwards")
        @JvmField public val ORIGIN: HttpHeaderName = fromString("Origin")
        @JvmField public val PRAGMA: HttpHeaderName = fromString("Pragma")
        @JvmField public val PROXY_AUTHENTICATE: HttpHeaderName = fromString("Proxy-Authenticate")
        @JvmField public val PROXY_AUTHORIZATION: HttpHeaderName = fromString("Proxy-Authorization")
        @JvmField public val RANGE: HttpHeaderName = fromString("Range")
        @JvmField public val REFERER: HttpHeaderName = fromString("Referer")
        @JvmField public val REFERRER_POLICY: HttpHeaderName = fromString("Referrer-Policy")
        @JvmField public val RETRY_AFTER: HttpHeaderName = fromString("Retry-After")
        @JvmField public val SERVER: HttpHeaderName = fromString("Server")
        @JvmField public val SET_COOKIE: HttpHeaderName = fromString("Set-Cookie")
        @JvmField public val STRICT_TRANSPORT_SECURITY: HttpHeaderName = fromString("Strict-Transport-Security")
        @JvmField public val TE: HttpHeaderName = fromString("TE")
        @JvmField public val TRAILER: HttpHeaderName = fromString("Trailer")
        @JvmField public val TRANSFER_ENCODING: HttpHeaderName = fromString("Transfer-Encoding")
        @JvmField public val UPGRADE: HttpHeaderName = fromString("Upgrade")
        @JvmField public val USER_AGENT: HttpHeaderName = fromString("User-Agent")
        @JvmField public val VARY: HttpHeaderName = fromString("Vary")
        @JvmField public val VIA: HttpHeaderName = fromString("Via")
        @JvmField public val WARNING: HttpHeaderName = fromString("Warning")
        @JvmField public val WWW_AUTHENTICATE: HttpHeaderName = fromString("WWW-Authenticate")
        @JvmField public val X_CONTENT_TYPE_OPTIONS: HttpHeaderName = fromString("X-Content-Type-Options")
        @JvmField public val X_FORWARDED_FOR: HttpHeaderName = fromString("X-Forwarded-For")
        @JvmField public val X_FORWARDED_HOST: HttpHeaderName = fromString("X-Forwarded-Host")
        @JvmField public val X_FORWARDED_PROTO: HttpHeaderName = fromString("X-Forwarded-Proto")
        @JvmField public val X_FRAME_OPTIONS: HttpHeaderName = fromString("X-Frame-Options")
        @JvmField public val X_REQUEST_ID: HttpHeaderName = fromString("X-Request-Id")
        @JvmField public val X_XSS_PROTECTION: HttpHeaderName = fromString("X-XSS-Protection")

        // Microsoft retry headers — consumed by RetryStep (Phase B).
        @JvmField public val RETRY_AFTER_MS: HttpHeaderName = fromString("retry-after-ms")
        @JvmField public val X_MS_RETRY_AFTER_MS: HttpHeaderName = fromString("x-ms-retry-after-ms")
        @JvmField public val X_MS_REQUEST_ID: HttpHeaderName = fromString("x-ms-request-id")
        @JvmField public val X_MS_CLIENT_REQUEST_ID: HttpHeaderName = fromString("x-ms-client-request-id")
        @JvmField public val X_MS_CORRELATION_REQUEST_ID: HttpHeaderName = fromString("x-ms-correlation-request-id")

        /**
         * Returns an interned [HttpHeaderName] for [name].
         *
         * The input is trimmed of leading/trailing whitespace before being normalised to
         * lower-case (US locale) for the interning key. The case-preserved form of the
         * first caller to intern a given key wins; subsequent calls with different casing
         * yield the same shared instance.
         */
        @JvmStatic
        public fun fromString(name: String): HttpHeaderName {
            val trimmed = name.trim()
            val key = trimmed.lowercase(Locale.US)
            // computeIfAbsent is available on Java 8.
            return INTERN.computeIfAbsent(key) { HttpHeaderName(trimmed, key) }
        }
    }
}
