/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response.exception

import java.io.IOException

/**
 * Exception thrown when the transport fails before a complete HTTP response is available —
 * connection refused, TLS handshake failure, DNS lookup failure, socket read timeout, peer
 * reset, and similar transport-level conditions.
 *
 * Unlike [HttpException], `NetworkException` has no [org.dexpace.sdk.core.http.response.Response]
 * to carry (status, headers, and body are unknown). It therefore deliberately does **not**
 * extend [HttpException]; it is a sibling type so that existing `catch (IOException)` call
 * sites keep working — making [IOException] the supertype matches what every JVM HTTP client
 * already throws for transport failures and avoids forcing consumers to re-declare a wider
 * catch.
 *
 * ## Retryability
 *
 * Always [retryable] = `true`. A transport-level failure with no response on the wire is by
 * definition transient from the SDK's standpoint: nothing was processed by the server, so a
 * retry can attempt the request from scratch. Whether the operation is *safe* to retry
 * still depends on idempotency (HTTP method + replayable body) — that decision lives at the
 * retry step, not here.
 *
 * @property retryable Always `true`; exposed as a `val` for API symmetry with [HttpException].
 */
public open class NetworkException
    @JvmOverloads
    constructor(
        message: String? = null,
        cause: Throwable? = null,
    ) : IOException(message, cause) {
        /**
         * Whether this exception represents a retryable condition. Always `true` for
         * `NetworkException` — transport failures with no response are by definition
         * transient at the SDK level.
         */
        public val retryable: Boolean = true
    }
