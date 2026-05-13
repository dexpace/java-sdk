package org.dexpace.sdk.core.http.response

/**
 * Canonical HTTP status codes recognized by the SDK. The enum is a closed set — codes not
 * listed here are unknown to the SDK and trigger [IllegalArgumentException] from [fromCode].
 *
 * @property code Numeric status code as it appears on the wire.
 */
@Suppress("unused")
enum class Status(
    val code: Int
) {
    // Informational responses (100–199)
    CONTINUE(100),
    SWITCHING_PROTOCOLS(101),
    PROCESSING(102),
    EARLY_HINTS(103),

    // Successful responses (200–299)
    OK(200),
    CREATED(201),
    ACCEPTED(202),
    NON_AUTHORITATIVE_INFORMATION(203),
    NO_CONTENT(204),
    RESET_CONTENT(205),
    PARTIAL_CONTENT(206),
    MULTI_STATUS(207),
    ALREADY_REPORTED(208),
    IM_USED(226),

    // Redirection messages (300–399)
    MULTIPLE_CHOICES(300),
    MOVED_PERMANENTLY(301),
    FOUND(302),
    SEE_OTHER(303),
    NOT_MODIFIED(304),
    USE_PROXY(305),
    TEMPORARY_REDIRECT(307),
    PERMANENT_REDIRECT(308),

    // Client error responses (400–499)
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    PAYMENT_REQUIRED(402),
    FORBIDDEN(403),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    NOT_ACCEPTABLE(406),
    PROXY_AUTHENTICATION_REQUIRED(407),
    REQUEST_TIMEOUT(408),
    CONFLICT(409),
    GONE(410),
    LENGTH_REQUIRED(411),
    PRECONDITION_FAILED(412),
    PAYLOAD_TOO_LARGE(413),
    URI_TOO_LONG(414),
    UNSUPPORTED_MEDIA_TYPE(415),
    RANGE_NOT_SATISFIABLE(416),
    EXPECTATION_FAILED(417),
    IM_A_TEAPOT(418),
    MISDIRECTED_REQUEST(421),
    UNPROCESSABLE_ENTITY(422),
    LOCKED(423),
    FAILED_DEPENDENCY(424),
    TOO_EARLY(425),
    UPGRADE_REQUIRED(426),
    PRECONDITION_REQUIRED(428),
    TOO_MANY_REQUESTS(429),
    REQUEST_HEADER_FIELDS_TOO_LARGE(431),
    UNAVAILABLE_FOR_LEGAL_REASONS(451),

    // Server error responses (500–599)
    INTERNAL_SERVER_ERROR(500),
    NOT_IMPLEMENTED(501),
    BAD_GATEWAY(502),
    SERVICE_UNAVAILABLE(503),
    GATEWAY_TIMEOUT(504),
    HTTP_VERSION_NOT_SUPPORTED(505),
    VARIANT_ALSO_NEGOTIATES(506),
    INSUFFICIENT_STORAGE(507),
    LOOP_DETECTED(508),
    NOT_EXTENDED(510),
    NETWORK_AUTHENTICATION_REQUIRED(511),

    // Non-standard status codes (e.g., Apache)
    THIS_IS_FINE(218) // Non-standard code, used by some Apache modules
    ;

    /** True when the code is in the 2xx success range. */
    val isSuccess: Boolean = code in 200..299

    companion object {
        private val LOOKUP: Map<Int, Status> = entries.associateBy { it.code }

        /**
         * Returns the [Status] constant whose [code] matches [code].
         *
         * @throws IllegalArgumentException if [code] is not in the SDK's recognized set.
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun fromCode(code: Int): Status =
            LOOKUP[code] ?: throw IllegalArgumentException("Invalid status code: $code")

        /**
         * Returns the [Status] constant whose [code] matches [code], or `null` when no
         * such constant exists. Use this for code paths that need to branch on unknown
         * codes (e.g. a custom transport that wants to pass through a non-canonical 6xx
         * code) rather than the throwing [fromCode].
         */
        @JvmStatic
        fun fromCodeOrNull(code: Int): Status? = LOOKUP[code]
    }
}