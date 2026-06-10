/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

// Public API surface — not every HTTP status-code entry is referenced within this module; SDK consumers may use any.

/**
 * An HTTP status code. [Status] is a **total** type: every integer code maps to a [Status], so a
 * transport can faithfully surface vendor-specific codes (nginx 499, Cloudflare 520–526/530, etc.)
 * without throwing or losing the wire value.
 *
 * Canonical codes recognized by the SDK are exposed as named constants in the companion object
 * (e.g. [OK], [NOT_FOUND]); each carries a human-readable [statusName]. Any other code is
 * represented by a [Status] with that [code] and a `null` [statusName].
 *
 * Two [Status] values are equal when their [code]s are equal, so `Status.fromCode(200) == Status.OK`.
 *
 * @property code Numeric status code as it appears on the wire.
 * @property statusName Human-readable name for canonical codes, or `null` for unrecognized codes.
 */
@Suppress("unused")
public class Status private constructor(
    public val code: Int,
    public val statusName: String?,
) {
    /** True when the code is in the 2xx success range. */
    public val isSuccess: Boolean get() = code in 200..299

    override fun equals(other: Any?): Boolean = this === other || (other is Status && other.code == code)

    override fun hashCode(): Int = code

    override fun toString(): String = if (statusName != null) "$statusName($code)" else "HTTP $code"

    public companion object {
        // Informational responses (100–199)
        @JvmField public val CONTINUE: Status = Status(100, "CONTINUE")

        @JvmField public val SWITCHING_PROTOCOLS: Status = Status(101, "SWITCHING_PROTOCOLS")

        @JvmField public val PROCESSING: Status = Status(102, "PROCESSING")

        @JvmField public val EARLY_HINTS: Status = Status(103, "EARLY_HINTS")

        // Successful responses (200–299)
        @JvmField public val OK: Status = Status(200, "OK")

        @JvmField public val CREATED: Status = Status(201, "CREATED")

        @JvmField public val ACCEPTED: Status = Status(202, "ACCEPTED")

        @JvmField public val NON_AUTHORITATIVE_INFORMATION: Status = Status(203, "NON_AUTHORITATIVE_INFORMATION")

        @JvmField public val NO_CONTENT: Status = Status(204, "NO_CONTENT")

        @JvmField public val RESET_CONTENT: Status = Status(205, "RESET_CONTENT")

        @JvmField public val PARTIAL_CONTENT: Status = Status(206, "PARTIAL_CONTENT")

        @JvmField public val MULTI_STATUS: Status = Status(207, "MULTI_STATUS")

        @JvmField public val ALREADY_REPORTED: Status = Status(208, "ALREADY_REPORTED")

        @JvmField public val IM_USED: Status = Status(226, "IM_USED")

        // Redirection messages (300–399)
        @JvmField public val MULTIPLE_CHOICES: Status = Status(300, "MULTIPLE_CHOICES")

        @JvmField public val MOVED_PERMANENTLY: Status = Status(301, "MOVED_PERMANENTLY")

        @JvmField public val FOUND: Status = Status(302, "FOUND")

        @JvmField public val SEE_OTHER: Status = Status(303, "SEE_OTHER")

        @JvmField public val NOT_MODIFIED: Status = Status(304, "NOT_MODIFIED")

        @JvmField public val USE_PROXY: Status = Status(305, "USE_PROXY")

        @JvmField public val TEMPORARY_REDIRECT: Status = Status(307, "TEMPORARY_REDIRECT")

        @JvmField public val PERMANENT_REDIRECT: Status = Status(308, "PERMANENT_REDIRECT")

        // Client error responses (400–499)
        @JvmField public val BAD_REQUEST: Status = Status(400, "BAD_REQUEST")

        @JvmField public val UNAUTHORIZED: Status = Status(401, "UNAUTHORIZED")

        @JvmField public val PAYMENT_REQUIRED: Status = Status(402, "PAYMENT_REQUIRED")

        @JvmField public val FORBIDDEN: Status = Status(403, "FORBIDDEN")

        @JvmField public val NOT_FOUND: Status = Status(404, "NOT_FOUND")

        @JvmField public val METHOD_NOT_ALLOWED: Status = Status(405, "METHOD_NOT_ALLOWED")

        @JvmField public val NOT_ACCEPTABLE: Status = Status(406, "NOT_ACCEPTABLE")

        @JvmField public val PROXY_AUTHENTICATION_REQUIRED: Status = Status(407, "PROXY_AUTHENTICATION_REQUIRED")

        @JvmField public val REQUEST_TIMEOUT: Status = Status(408, "REQUEST_TIMEOUT")

        @JvmField public val CONFLICT: Status = Status(409, "CONFLICT")

        @JvmField public val GONE: Status = Status(410, "GONE")

        @JvmField public val LENGTH_REQUIRED: Status = Status(411, "LENGTH_REQUIRED")

        @JvmField public val PRECONDITION_FAILED: Status = Status(412, "PRECONDITION_FAILED")

        @JvmField public val PAYLOAD_TOO_LARGE: Status = Status(413, "PAYLOAD_TOO_LARGE")

        @JvmField public val URI_TOO_LONG: Status = Status(414, "URI_TOO_LONG")

        @JvmField public val UNSUPPORTED_MEDIA_TYPE: Status = Status(415, "UNSUPPORTED_MEDIA_TYPE")

        @JvmField public val RANGE_NOT_SATISFIABLE: Status = Status(416, "RANGE_NOT_SATISFIABLE")

        @JvmField public val EXPECTATION_FAILED: Status = Status(417, "EXPECTATION_FAILED")

        @JvmField public val IM_A_TEAPOT: Status = Status(418, "IM_A_TEAPOT")

        @JvmField public val MISDIRECTED_REQUEST: Status = Status(421, "MISDIRECTED_REQUEST")

        @JvmField public val UNPROCESSABLE_ENTITY: Status = Status(422, "UNPROCESSABLE_ENTITY")

        @JvmField public val LOCKED: Status = Status(423, "LOCKED")

        @JvmField public val FAILED_DEPENDENCY: Status = Status(424, "FAILED_DEPENDENCY")

        @JvmField public val TOO_EARLY: Status = Status(425, "TOO_EARLY")

        @JvmField public val UPGRADE_REQUIRED: Status = Status(426, "UPGRADE_REQUIRED")

        @JvmField public val PRECONDITION_REQUIRED: Status = Status(428, "PRECONDITION_REQUIRED")

        @JvmField public val TOO_MANY_REQUESTS: Status = Status(429, "TOO_MANY_REQUESTS")

        @JvmField public val REQUEST_HEADER_FIELDS_TOO_LARGE: Status = Status(431, "REQUEST_HEADER_FIELDS_TOO_LARGE")

        @JvmField public val UNAVAILABLE_FOR_LEGAL_REASONS: Status = Status(451, "UNAVAILABLE_FOR_LEGAL_REASONS")

        // Server error responses (500–599)
        @JvmField public val INTERNAL_SERVER_ERROR: Status = Status(500, "INTERNAL_SERVER_ERROR")

        @JvmField public val NOT_IMPLEMENTED: Status = Status(501, "NOT_IMPLEMENTED")

        @JvmField public val BAD_GATEWAY: Status = Status(502, "BAD_GATEWAY")

        @JvmField public val SERVICE_UNAVAILABLE: Status = Status(503, "SERVICE_UNAVAILABLE")

        @JvmField public val GATEWAY_TIMEOUT: Status = Status(504, "GATEWAY_TIMEOUT")

        @JvmField public val HTTP_VERSION_NOT_SUPPORTED: Status = Status(505, "HTTP_VERSION_NOT_SUPPORTED")

        @JvmField public val VARIANT_ALSO_NEGOTIATES: Status = Status(506, "VARIANT_ALSO_NEGOTIATES")

        @JvmField public val INSUFFICIENT_STORAGE: Status = Status(507, "INSUFFICIENT_STORAGE")

        @JvmField public val LOOP_DETECTED: Status = Status(508, "LOOP_DETECTED")

        @JvmField public val NOT_EXTENDED: Status = Status(510, "NOT_EXTENDED")

        @JvmField public val NETWORK_AUTHENTICATION_REQUIRED: Status = Status(511, "NETWORK_AUTHENTICATION_REQUIRED")

        // Non-standard status codes (e.g., Apache)
        @JvmField public val THIS_IS_FINE: Status = Status(218, "THIS_IS_FINE") // Used by some Apache modules

        /**
         * The canonical [Status] constants recognized by the SDK, in declaration order.
         * Unrecognized codes produced by [fromCode] are **not** in this list.
         */
        @JvmField
        public val canonicalStatuses: List<Status> =
            listOf(
                CONTINUE, SWITCHING_PROTOCOLS, PROCESSING, EARLY_HINTS,
                OK, CREATED, ACCEPTED, NON_AUTHORITATIVE_INFORMATION, NO_CONTENT, RESET_CONTENT,
                PARTIAL_CONTENT, MULTI_STATUS, ALREADY_REPORTED, IM_USED,
                MULTIPLE_CHOICES, MOVED_PERMANENTLY, FOUND, SEE_OTHER, NOT_MODIFIED, USE_PROXY,
                TEMPORARY_REDIRECT, PERMANENT_REDIRECT,
                BAD_REQUEST, UNAUTHORIZED, PAYMENT_REQUIRED, FORBIDDEN, NOT_FOUND, METHOD_NOT_ALLOWED,
                NOT_ACCEPTABLE, PROXY_AUTHENTICATION_REQUIRED, REQUEST_TIMEOUT, CONFLICT, GONE,
                LENGTH_REQUIRED, PRECONDITION_FAILED, PAYLOAD_TOO_LARGE, URI_TOO_LONG,
                UNSUPPORTED_MEDIA_TYPE, RANGE_NOT_SATISFIABLE, EXPECTATION_FAILED, IM_A_TEAPOT,
                MISDIRECTED_REQUEST, UNPROCESSABLE_ENTITY, LOCKED, FAILED_DEPENDENCY, TOO_EARLY,
                UPGRADE_REQUIRED, PRECONDITION_REQUIRED, TOO_MANY_REQUESTS,
                REQUEST_HEADER_FIELDS_TOO_LARGE, UNAVAILABLE_FOR_LEGAL_REASONS,
                INTERNAL_SERVER_ERROR, NOT_IMPLEMENTED, BAD_GATEWAY, SERVICE_UNAVAILABLE,
                GATEWAY_TIMEOUT, HTTP_VERSION_NOT_SUPPORTED, VARIANT_ALSO_NEGOTIATES,
                INSUFFICIENT_STORAGE, LOOP_DETECTED, NOT_EXTENDED, NETWORK_AUTHENTICATION_REQUIRED,
                THIS_IS_FINE,
            )

        private val LOOKUP: Map<Int, Status> = canonicalStatuses.associateBy { it.code }

        /**
         * Returns a [Status] for [code]. This function is **total**: it returns the canonical
         * constant when [code] is recognized, otherwise a new [Status] carrying [code] with a
         * `null` [statusName]. It never throws, so transports can faithfully surface
         * vendor-specific codes.
         */
        @JvmStatic
        public fun fromCode(code: Int): Status = LOOKUP[code] ?: Status(code, null)

        /**
         * Returns the canonical [Status] constant whose [code] matches [code], or `null` when no
         * such constant exists. Use this to branch on whether a code is recognized by the SDK;
         * use [fromCode] when an unknown code should still produce a usable [Status].
         */
        @JvmStatic
        public fun fromCodeOrNull(code: Int): Status? = LOOKUP[code]
    }
}
