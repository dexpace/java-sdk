/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestOptions

/**
 * Per-call mutable cursor over a [HttpPipeline]'s steps array. Holds the index of the
 * next step to invoke, the originating [Request], and the caller's [RequestOptions].
 *
 * Cloned via [copy] (exposed to user code through [PipelineNext.copy]) so retry / redirect
 * steps can re-drive the downstream chain. Cloning copies the current index and the
 * [options] — the new state resumes from the same position, advancing independently.
 *
 * Backed by an [Array] of steps for tight iteration (per the pipeline performance
 * guardrails); index advance is a single field write.
 *
 * Internal: cloning is reachable only through [PipelineNext.copy].
 */
internal class PipelineCallState internal constructor(
    val pipeline: HttpPipeline,
    initialRequest: Request,
    /**
     * The caller's per-call overrides, carried for the whole call. Read by steps via
     * [PipelineNext.options] and threaded into the terminal `HttpClient.execute(request, options)`.
     * Immutable and shared unchanged across [copy] (retry / redirect re-drives).
     */
    val options: RequestOptions = RequestOptions.EMPTY,
    private var index: Int = 0,
) {
    /**
     * The request currently being driven through the pipeline. Mutable so a step may
     * substitute the downstream request via [PipelineNext.process] — for example,
     * [org.dexpace.sdk.core.http.pipeline.steps.InstrumentationStep] wraps the body in
     * `LoggableRequestBody` before send. The substitution propagates to all subsequent
     * steps and to the terminal `HttpClient.execute(...)` when the cursor reaches the end.
     */
    var request: Request = initialRequest

    /**
     * Returns the step at the current cursor position and advances by one. Returns null
     * once the cursor moves past the last step — the caller (`PipelineNext.process`) then
     * dispatches to the terminal [HttpClient]. The cursor only moves forward; re-driving
     * the downstream chain requires [copy] to fork a fresh cursor at the current position.
     */
    fun advance(): HttpStep? {
        val steps = pipeline.stepArray
        return if (index < steps.size) steps[index++] else null
    }

    /** Returns an independent state cloned at the current cursor position. */
    fun copy(): PipelineCallState = PipelineCallState(pipeline, request, options, index)
}
