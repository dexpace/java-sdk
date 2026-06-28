/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

import org.dexpace.sdk.core.http.common.HttpHeaderName

/**
 * Static API key credential. The key value is stamped into [headerName] on every outbound
 * request by [org.dexpace.sdk.core.http.pipeline.steps.KeyCredentialAuthStep]; an optional
 * [prefix] is prepended with a single space — useful for schemes like
 * `Authorization: SharedAccessKey <key>`.
 *
 * Default placement is the `Authorization` header so common API-key services work without
 * configuration. Services that expect a custom header (e.g. `X-API-Key`) should pass it
 * explicitly via [headerName].
 *
 * @param apiKey the credential value; must not be blank.
 * @param headerName the header to stamp the key into; defaults to [HttpHeaderName.AUTHORIZATION].
 * @param prefix optional scheme prefix prepended to the key with a single separating space.
 * @throws IllegalArgumentException if [apiKey] is blank.
 */
public class KeyCredential
    @JvmOverloads
    constructor(
        public val apiKey: String,
        public val headerName: HttpHeaderName = HttpHeaderName.AUTHORIZATION,
        public val prefix: String? = null,
    ) : Credential {
        init {
            require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        }

        /**
         * Redacts the secret [apiKey]. Without this override any log line, exception message,
         * or debugger that stringifies the credential would expose the key; this emits
         * `apiKey=***` instead while keeping the non-secret [headerName] and [prefix] for
         * diagnostics. Identity equality is unaffected.
         */
        override fun toString(): String = "KeyCredential(apiKey=***, headerName=$headerName, prefix=$prefix)"
    }
