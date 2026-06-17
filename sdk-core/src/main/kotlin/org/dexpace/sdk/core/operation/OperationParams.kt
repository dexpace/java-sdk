/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.operation

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.RequestBody

/**
 * SPI for projecting a single operation's inputs into the four parts of an HTTP request —
 * path, query, headers, body — that feed the SDK's context chain.
 *
 * This is the hand-written runtime contract a future operation/params object (whether written
 * by hand or emitted by a generator) implements to describe *what* a call sends, independent of
 * *how* it is dispatched. The SDK turns an `OperationParams` plus an operation's [Method] and
 * path template into a concrete `Request` via [RequestProjector], which then enters the
 * `DispatchContext -> RequestContext -> ExchangeContext` promotion chain like any other request.
 *
 * Each projection is intentionally narrow and side-effect-free:
 *  - [pathParams] supplies `{name}` template substitutions for the operation's path.
 *  - [queryParams] supplies ordered, possibly-repeated query entries.
 *  - [headers] supplies request headers (operation-level; client/auth headers are layered later).
 *  - [body] supplies the request payload, or `null` for bodyless operations.
 *
 * Every method has an empty/none default so an implementation overrides only the parts it
 * contributes — a bodyless `GET` with a couple of query params implements just [queryParams].
 *
 * ## Contract
 *
 * Implementations should be **pure and repeatable**: projections must not mutate shared state and
 * must return equivalent results across calls, since the projector may read them more than once.
 * Returned collections should be treated as read-only by the caller. Values are *raw / unencoded*
 * — the projector owns percent-encoding (see [PathParam], [QueryParam]). The contract says nothing
 * about thread-safety of the body itself: a [RequestBody] may carry single-use stream state per
 * its own contract.
 */
public interface OperationParams {
    /**
     * Path-template substitutions for this operation, matched by name against `{name}`
     * placeholders. Defaults to none.
     */
    public fun pathParams(): List<PathParam> = emptyList()

    /**
     * Ordered query-string entries for this operation. Order and repetition are preserved by
     * the projection. Defaults to none.
     */
    public fun queryParams(): List<QueryParam> = emptyList()

    /**
     * Operation-level request headers. Client-level and auth headers are layered on later by the
     * pipeline, so this returns only what the operation itself contributes. Defaults to empty.
     */
    public fun headers(): Headers = EMPTY_HEADERS

    /**
     * Request payload for this operation, or `null` for a bodyless operation (typical for
     * `GET`/`HEAD`/`DELETE`). Defaults to `null`.
     */
    public fun body(): RequestBody? = null

    public companion object {
        /** Shared empty headers instance returned by the [headers] default. */
        @JvmField
        public val EMPTY_HEADERS: Headers = Headers.Builder().build()

        /**
         * The empty projection: no path params, no query, no headers, no body. Useful as a
         * neutral element and for operations whose inputs live entirely in the path template.
         */
        @JvmField
        public val NONE: OperationParams = object : OperationParams {}
    }
}
