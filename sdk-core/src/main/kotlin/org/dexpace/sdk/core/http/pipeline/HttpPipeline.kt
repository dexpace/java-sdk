/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.io.IOException
import java.util.Collections

/**
 * Immutable HTTP pipeline runtime. Holds an ordered, stage-sorted [Array] of [HttpStep]s
 * and a transport [HttpClient]; [send] walks the chain in order and dispatches the request
 * to the client when the chain is exhausted.
 *
 * Thread-safety: the steps array and client reference are immutable after construction;
 * each [send] allocates exactly one [PipelineCallState] + one [PipelineNext], so concurrent
 * sends never share mutable state inside the pipeline itself. Steps must remain
 * thread-safe across concurrent invocations per the pipeline contract.
 *
 * Construct via [HttpPipelineBuilder]; the constructor is internal.
 */
public class HttpPipeline internal constructor(
    /** Transport invoked once the step chain is exhausted; the terminal slot of [Stage.SEND]. */
    public val httpClient: HttpClient,
    internal val stepArray: Array<HttpStep>,
) {
    /** Unmodifiable list view of the ordered steps. Backed by [stepArray]; for inspection only. */
    public val steps: List<HttpStep> = Collections.unmodifiableList(stepArray.asList())

    /**
     * Runs [request] through the pipeline. Empty pipelines short-circuit directly to
     * [HttpClient.execute] with no allocation.
     */
    @Throws(IOException::class)
    public fun send(request: Request): Response {
        if (stepArray.isEmpty()) return httpClient.execute(request)
        val state = PipelineCallState(this, request, httpClient)
        return PipelineNext(state).process()
    }

    public companion object {
        /**
         * Builds a step-less [HttpPipeline] that forwards every `send` directly to [client].
         * Equivalent to `HttpPipelineBuilder(client).build()` but spares the builder ceremony
         * for the common case of "I just want a pipeline that calls the client and nothing
         * else." Mirrors [AsyncHttpPipeline.of].
         */
        @JvmStatic
        public fun of(client: HttpClient): HttpPipeline = HttpPipelineBuilder(client).build()
    }
}
