package org.dexpace.sdk.core.http.common

/**
 * Enumeration of HTTP protocol versions the SDK can describe on a [org.dexpace.sdk.core.http.response.Response].
 *
 * The wire form (returned by [toString] and consumed by [get]) is lower-case with a slash
 * separator — matching the ALPN identifiers (`http/1.1`, `h2`-like). [H2_PRIOR_KNOWLEDGE]
 * is a Kotlin-side marker (no formal ALPN form) used when an HTTP/2 connection is opened
 * without prior HTTP/1.1 upgrade.
 */
@Suppress("unused")
enum class Protocol(
    private val protocolString: String
) {
    /** HTTP/1.0 — legacy; unlikely to be seen in practice. */
    HTTP_1_0("http/1.0"),

    /** HTTP/1.1 — the default for text-protocol HTTP. */
    HTTP_1_1("http/1.1"),

    /** HTTP/2 — multiplexed binary protocol negotiated via ALPN. */
    HTTP_2("http/2"),

    /** HTTP/2 opened without HTTP/1.1 upgrade (prior-knowledge mode, RFC 7540 §3.4). */
    H2_PRIOR_KNOWLEDGE("h2_prior_knowledge"),

    /** QUIC transport (HTTP/3 over UDP). */
    QUIC("quic")
    ;

    /** Returns the canonical wire form (e.g. `"http/1.1"`). */
    override fun toString(): String = protocolString

    companion object {
        private val lookup = entries.associateBy { it.protocolString.uppercase() }

        /**
         * Parses a protocol identifier (case-insensitively) and returns the matching
         * [Protocol]. Accepts the canonical forms emitted by [toString] plus the
         * alternative spellings `HTTP/2` and `HTTP/2.0` for HTTP/2.
         *
         * @throws IllegalArgumentException if [protocol] does not match a known value.
         */
        @JvmStatic
        fun get(protocol: String): Protocol = when (protocol.uppercase()) {
            "HTTP/2", "HTTP/2.0" -> HTTP_2
            else -> lookup[protocol.uppercase()] ?: throw IllegalArgumentException("Unexpected protocol: $protocol")
        }
    }
}
