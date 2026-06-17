/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Test support: an async stub driven by canned responses keyed by request URL — the async
 * mirror of [StubHttpClient].
 *
 * Each registered responder produces a [Response] (or throws). The future returned by
 * [executeAsync] is completed either inline (synchronous completion, exercising the
 * trampoline's already-done fast path) or via [completionExecutor] when one is supplied
 * (genuinely deferred completion, exercising the callback re-arm path).
 */
internal class StubAsyncHttpClient(
    private val completionExecutor: Executor? = null,
) : AsyncHttpClient {
    private val responders: MutableMap<String, (Request) -> Response> = LinkedHashMap()
    private val urls: MutableList<String> = ArrayList()

    fun on(
        url: String,
        responseBuilder: (Request) -> Response,
    ): StubAsyncHttpClient {
        responders[url] = responseBuilder
        return this
    }

    /** All URLs received, in call order. */
    val receivedUrls: List<String> get() = urls.toList()

    /** Number of HTTP calls executed. */
    val callCount: Int get() = urls.size

    override fun executeAsync(request: Request): CompletableFuture<Response> {
        val url = request.url.toString()
        urls.add(url)
        val responder =
            responders[url]
                ?: error("StubAsyncHttpClient: no canned response for URL: $url\nKnown: ${responders.keys}")
        val executor = completionExecutor
        if (executor == null) {
            return try {
                CompletableFuture.completedFuture(responder(request))
            } catch (t: Throwable) {
                val f = CompletableFuture<Response>()
                f.completeExceptionally(t)
                f
            }
        }
        val future = CompletableFuture<Response>()
        executor.execute {
            try {
                future.complete(responder(request))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future
    }
}
