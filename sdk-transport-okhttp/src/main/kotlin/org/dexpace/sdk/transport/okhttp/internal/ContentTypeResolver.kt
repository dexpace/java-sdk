/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.okhttp.internal

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.dexpace.sdk.core.http.common.MediaType

/**
 * Resolves the `okhttp3.MediaType` reported by a request-body adapter's `contentType()`.
 *
 * The caller's explicit request `Content-Type` header ([explicitContentType]) wins when one was
 * set; otherwise the SDK body's [MediaType] ([sdkMediaType]) is used. Either way the value is
 * parsed via `toString()` → `okhttp3.MediaType`, and an unparseable string returns `null`.
 * Reporting the explicit header here is what keeps it authoritative: OkHttp's `BridgeInterceptor`
 * overwrites the request's `Content-Type` with `body.contentType()` when the latter is non-null,
 * so a `null` return (no explicit header AND no body media type, or an unparseable explicit header)
 * leaves the caller's own header untouched.
 */
internal fun resolveOkHttpContentType(
    explicitContentType: String?,
    sdkMediaType: MediaType?,
): okhttp3.MediaType? =
    if (explicitContentType != null) {
        explicitContentType.toMediaTypeOrNull()
    } else {
        sdkMediaType?.toString()?.toMediaTypeOrNull()
    }
