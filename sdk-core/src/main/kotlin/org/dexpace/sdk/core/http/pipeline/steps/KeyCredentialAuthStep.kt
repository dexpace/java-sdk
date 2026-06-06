/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.auth.KeyCredential
import org.dexpace.sdk.core.http.request.Request

/**
 * [AuthStep] that stamps a static [KeyCredential] onto the configured header (default
 * `Authorization`). If the credential carries a [KeyCredential.prefix], it is prepended
 * to the key value with a single space.
 *
 * Thread-safety: stateless after construction — safe to share across concurrent requests.
 * Cancellation: blocking-free; no interrupt handling needed.
 *
 * Open for subclassing — users wanting to override [authorizeRequestOnChallenge] (e.g.
 * to swap to a fallback credential on 401) can extend this class.
 */
public open class KeyCredentialAuthStep(private val credential: KeyCredential) : AuthStep() {
    override fun authorizeRequest(request: Request): Request {
        val value = credential.prefix?.let { "$it ${credential.apiKey}" } ?: credential.apiKey
        return request.newBuilder()
            .setHeader(credential.headerName.caseSensitiveName, value)
            .build()
    }
}
