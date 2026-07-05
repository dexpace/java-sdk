/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestOptions

/**
 * Per-call mutable cursor over an [AsyncHttpPipeline]'s steps array. Async counterpart of
 * [PipelineCallState]: holds the index of the next step to invoke, the in-flight [Request], and
 * the caller's [RequestOptions].
 *
 * Cloned via [copy] (exposed to user code through [AsyncPipelineNext.copy]) so async retry /
 * redirect steps can re-drive the downstream chain. Cloning copies the current index and the
 * [options] — the new state resumes from the same position, advancing independently.
 *
 * Internal: cloning is reachable only through [AsyncPipelineNext.copy].
 */
internal class AsyncPipelineCallState internal constructor(
    val pipeline: AsyncHttpPipeline,
    initialRequest: Request,
    /**
     * The caller's per-call overrides, carried for the whole call. Read by steps via
     * [AsyncPipelineNext.options] and threaded into the terminal
     * `AsyncHttpClient.executeAsync(request, options)`. Immutable and shared unchanged across
     * [copy] (retry / redirect re-drives).
     */
    val options: RequestOptions = RequestOptions.EMPTY,
    private var index: Int = 0,
) {
    /**
     * In-flight request; mutable so a step may substitute the downstream request (e.g.
     * wrap the body in `LoggableRequestBody` before send). The substitution propagates to
     * all subsequent steps and to the terminal `AsyncHttpClient.executeAsync(...)` when
     * the cursor reaches the end of the chain.
     */
    var request: Request = initialRequest

    /** Returns the next step to invoke, or null if the cursor has reached the end. */
    fun advance(): AsyncHttpStep? {
        val steps = pipeline.stepArray
        return if (index < steps.size) steps[index++] else null
    }

    /** Returns an independent state cloned at the current cursor position. */
    fun copy(): AsyncPipelineCallState = AsyncPipelineCallState(pipeline, request, options, index)
}
