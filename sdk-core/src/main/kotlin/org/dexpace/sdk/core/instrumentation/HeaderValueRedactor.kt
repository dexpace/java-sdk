/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

import org.dexpace.sdk.core.http.common.HttpHeaderName

/**
 * Renders a header's values for structured logging, redacting URL-valued headers.
 *
 * Shared by the sync and async instrumentation steps so the redaction policy lives in one place
 * — a change here (or a new URL-valued header) applies to both without drift, which is what keeps
 * the credential-leak this closes from re-opening.
 */
internal object HeaderValueRedactor {
    /**
     * Allowed headers whose value is a URL: their query/fragment can carry credentials (an OAuth
     * code, a pre-signed signature, an implicit-flow token), so they are redacted through
     * [UrlRedactor] rather than logged verbatim. Kept to the plain-URL-valued headers only —
     * headers like `Link`/`Refresh` have non-plain-URL syntax [UrlRedactor.redactUrlValue] is not
     * built for.
     */
    internal val URL_VALUED_HEADERS: Set<HttpHeaderName> =
        setOf(HttpHeaderName.LOCATION, HttpHeaderName.CONTENT_LOCATION)

    /**
     * Joins [values] into a single loggable string. When [name] is a URL-valued header, each value
     * is redacted through [UrlRedactor.redactUrlValue] (using [allowedQueryParams] to gate which
     * query parameters survive); otherwise values pass through verbatim.
     */
    internal fun render(
        name: HttpHeaderName?,
        values: List<String>,
        allowedQueryParams: Set<String>,
    ): String {
        val rendered =
            if (name in URL_VALUED_HEADERS) {
                values.map { UrlRedactor.redactUrlValue(it, allowedQueryParams) }
            } else {
                values
            }
        return if (rendered.size == 1) rendered[0] else rendered.joinToString(", ")
    }
}
