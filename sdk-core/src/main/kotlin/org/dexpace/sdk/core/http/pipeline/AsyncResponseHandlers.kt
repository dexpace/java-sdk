/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

@file:JvmName("AsyncResponseHandlers")

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseHandler
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture

/**
 * Terminal operator that maps an async [Response] future to a typed result via [handler] — the
 * async counterpart of invoking a [ResponseHandler] on a synchronous [Response].
 *
 * On successful completion, [handler]'s [ResponseHandler.handle] is applied to the [Response] and
 * the response is then closed. A [ResponseHandler] that reads the body already closes it (per its
 * contract), but this operator closes again to guarantee the response is released even for a
 * handler that reads nothing; [Response.close] is idempotent, so the double close is safe.
 *
 * On exceptional completion, the failure is unwrapped through any
 * [java.util.concurrent.CompletionException] / [java.util.concurrent.ExecutionException] layers
 * (via [Futures.unwrap]) before the returned future is failed, so a caller that inspects the cause
 * sees the original transport or step failure rather than a wrapper. Should a [Response] somehow
 * accompany a failure, it is closed to avoid leaking the body.
 *
 * The mapping runs on the thread that completes the upstream future (or the caller's thread if it
 * is already complete), matching the non-async [CompletableFuture.handle] contract. Reading the
 * body may therefore block that thread; supply an async transport whose completion runs off the
 * caller's critical path when that matters.
 *
 * @param handler Maps the raw [Response] to a typed [T].
 * @return A future completing with the handler's result, or failed with the unwrapped cause.
 */
public fun <T> CompletableFuture<Response>.handleWith(handler: ResponseHandler<T>): CompletableFuture<T> =
    handle { response, throwable ->
        if (throwable != null) {
            // Defensive: a failed completion normally carries no Response, but close one if present.
            response?.close()
            throw Futures.unwrap(throwable)
        }
        // `use` closes the response once the handler returns (or throws); safe even if the handler
        // already closed it, since Response.close is idempotent.
        response.use { handler.handle(it) }
    }
