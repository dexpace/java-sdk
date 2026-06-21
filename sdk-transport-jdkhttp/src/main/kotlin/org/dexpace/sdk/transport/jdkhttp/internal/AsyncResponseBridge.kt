/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import java.io.InputStream
import java.net.http.HttpResponse
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import org.dexpace.sdk.core.http.response.Response as SdkResponse

/**
 * Bridges a JDK `sendAsync` future into the SDK's response future with two cancellation
 * guarantees that a bare `inFlight.thenApply { adapt(it) }` does not provide:
 *
 *  1. **Propagation** — cancelling the returned future cancels the in-flight JDK exchange, so the
 *     connection is released eagerly instead of running to completion unobserved. A plain
 *     `thenApply` future does not propagate cancellation to its upstream.
 *  2. **No leaked body** — if the JDK delivers the response *after* the consumer has cancelled
 *     (the race window where `thenApply`'s mapping function is simply skipped, orphaning the live
 *     `InputStream`), the adapted response is closed so its connection returns to the pool.
 *
 * @param inFlight the future from `HttpClient.sendAsync(..., BodyHandlers.ofInputStream())`.
 * @param adapt converts the JDK response into an SDK [SdkResponse]; it must close the response
 *   body itself if it throws (the SDK [ResponseAdapter] does).
 */
internal fun bridgeAsyncResponse(
    inFlight: CompletableFuture<HttpResponse<InputStream>>,
    adapt: (HttpResponse<InputStream>) -> SdkResponse,
): CompletableFuture<SdkResponse> {
    val result = CompletableFuture<SdkResponse>()
    inFlight.whenComplete { jdkResponse, error ->
        if (error != null) {
            result.completeExceptionally(error)
        } else {
            val adapted =
                try {
                    adapt(jdkResponse)
                } catch (t: Throwable) {
                    // adapt() closes the response body on its own failure; just surface the error.
                    result.completeExceptionally(t)
                    return@whenComplete
                }
            // The consumer may have cancelled `result` while the exchange was completing. In that
            // race complete() returns false and nothing else will ever close `adapted`, so close it
            // here to release the connection.
            if (!result.complete(adapted)) {
                closeQuietly(adapted)
            }
        }
    }
    // Propagate cancellation of the returned future to the in-flight JDK exchange.
    result.whenComplete { _, error ->
        if (error is CancellationException) {
            inFlight.cancel(true)
        }
    }
    return result
}

/** Best-effort close on a discard path; a failure to close has nothing actionable to surface. */
private fun closeQuietly(response: SdkResponse) {
    try {
        response.close()
    } catch (_: Exception) {
        // Intentionally ignored: the response is already being discarded.
    }
}
