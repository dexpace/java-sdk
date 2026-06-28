/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture

/**
 * Async chain accessor passed to each [AsyncHttpStep.processAsync] call. Invoke [processAsync]
 * to drive the rest of the pipeline (returning a [CompletableFuture] that completes when the
 * downstream chain finishes); invoke [copy] before re-driving more than once (async retry /
 * redirect).
 *
 * Single instance per `pipeline.sendAsync(...)` call. Cloning is cheap — one
 * [AsyncPipelineCallState] allocation per [copy] — and the underlying steps array is shared
 * across copies.
 */
public class AsyncPipelineNext internal constructor(private val state: AsyncPipelineCallState) {
    /**
     * Advances to the next step and invokes it. If no further step exists, dispatches the
     * request to the pipeline's [org.dexpace.sdk.core.client.AsyncHttpClient]. Synchronous
     * exceptions thrown by the next step's `processAsync` (typically argument-validation
     * errors) are wrapped into the returned future via
     * [CompletableFuture.failedFuture] so callers receive a uniform async error model.
     */
    public fun processAsync(): CompletableFuture<Response> {
        val nextStep = state.advance()
        return try {
            if (nextStep == null) {
                state.pipeline.httpClient.executeAsync(state.request)
            } else {
                nextStep.processAsync(state.request, this)
            }
        } catch (e: Exception) {
            // The interface contract permits sync exceptions only for caller-bug cases
            // (IllegalArgument/NullPointer). Defensively normalise any sync `Exception`
            // throw into a failed future so a single step's mistake can't break the
            // pipeline's async contract. `Error` (OOM, StackOverflow) is intentionally
            // NOT caught — those propagate synchronously per Kotlin style guide §8.3.
            Futures.failed(e)
        }
    }

    /**
     * Substitutes the in-flight request with [request] before advancing to the next step.
     * The substitution sticks: every downstream step (and the terminal
     * `AsyncHttpClient.executeAsync`) sees [request] in place of the original. Mirrors the
     * synchronous [PipelineNext.process] overload.
     */
    public fun processAsync(request: Request): CompletableFuture<Response> {
        state.request = request
        return processAsync()
    }

    /**
     * Returns a fresh [AsyncPipelineNext] rooted at the current cursor position. Required for
     * any step that re-invokes the downstream chain more than once — re-using `this` would
     * advance past steps already visited on the previous invocation.
     */
    public fun copy(): AsyncPipelineNext = AsyncPipelineNext(state.copy())
}
