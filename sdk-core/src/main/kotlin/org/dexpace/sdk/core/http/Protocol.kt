package org.dexpace.sdk.core.http

/**
 * Enumeration of HTTP protocols.
 */
@Suppress("unused")
enum class Protocol(
    private val protocolString: String
) {
    HTTP_1_0("http/1.0"),
    HTTP_1_1("http/1.1"),
    HTTP_2("http/2"),
    H2_PRIOR_KNOWLEDGE("h2_prior_knowledge"),
    QUIC("quic")
    ;

    override fun toString(): String = protocolString

    companion object {
        private val lookup = entries.associateBy { it.protocolString.uppercase() }

        /**
         * Parses a protocol string to a [Protocol] enum.
         */
        @JvmStatic
        fun get(protocol: String): Protocol = when (protocol.uppercase()) {
            "HTTP/2", "HTTP/2.0" -> HTTP_2
            else -> lookup[protocol.uppercase()] ?: throw IllegalArgumentException("Unexpected protocol: $protocol")
        }
    }
}
