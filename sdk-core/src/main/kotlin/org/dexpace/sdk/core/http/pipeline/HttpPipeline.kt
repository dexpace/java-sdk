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
import org.dexpace.sdk.core.http.response.Response
import java.io.IOException

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
 * ## Transport SPI conformance
 * `HttpPipeline` itself implements [HttpClient]: [execute] delegates to [send]. A fully
 * configured pipeline can therefore stand in anywhere a transport is expected — most usefully as
 * the client backing a [org.dexpace.sdk.core.pagination.Paginator], so every page request runs
 * through the same redirect / retry / auth / logging steps. The inherited [close] is a no-op:
 * the pipeline wraps a caller-supplied transport it does not own, so it never closes it.
 *
 * Construct via [HttpPipelineBuilder]; the constructor is internal.
 */
public class HttpPipeline internal constructor(
    /** Transport invoked once the step chain is exhausted; the terminal slot of [Stage.SEND]. */
    public val httpClient: HttpClient,
    internal val stepArray: Array<HttpStep>,
) : HttpClient {
    /** Read-only list view of the ordered steps. Backed by [stepArray]; for inspection only. */
    public val steps: List<HttpStep> = stepArray.asList()

    /**
     * Runs [request] through the pipeline with no per-call overrides. Equivalent to
     * `send(request, RequestOptions.EMPTY)`.
     */
    @Throws(IOException::class)
    public fun send(request: Request): Response = send(request, RequestOptions.EMPTY)

    /**
     * Runs [request] through the pipeline with per-call [options] applied. The options are carried
     * for the whole call: steps read them via [PipelineNext.options] (the stage retry step honours
     * a per-call [RequestOptions.maxRetries]), and the terminal dispatch threads them into
     * `HttpClient.execute(request, options)` so the transport applies a per-call timeout. Empty
     * pipelines short-circuit directly to the transport's per-call overload with no allocation.
     */
    @Throws(IOException::class)
    public fun send(
        request: Request,
        options: RequestOptions,
    ): Response {
        if (stepArray.isEmpty()) return httpClient.execute(request, options)
        val state = PipelineCallState(this, request, options)
        return PipelineNext(state).process()
    }

    /**
     * [HttpClient] SPI conformance: delegates to [send] so this pipeline can be used as a
     * transport. [send] remains the primary entry point.
     */
    @Throws(IOException::class)
    override fun execute(request: Request): Response = send(request)

    /**
     * [HttpClient] per-call SPI conformance: delegates to [send] so a caller's [options] reach the
     * pipeline (and the transport) even when the pipeline is used through the transport SPI.
     */
    @Throws(IOException::class)
    override fun execute(
        request: Request,
        options: RequestOptions,
    ): Response = send(request, options)

    public companion object {
        /**
         * Builds a step-less [HttpPipeline] that forwards every `send` directly to [client].
         * Equivalent to `HttpPipelineBuilder(client).build()` but spares the builder ceremony
         * for the common case of "I just want a pipeline that calls the client and nothing
         * else." Mirrors [AsyncHttpPipeline.of].
         */
        @JvmStatic
        public fun of(client: HttpClient): HttpPipeline = HttpPipelineBuilder(client).build()

        /**
         * Builds a pipeline over [transport] wired with the SDK's standard resilience pillars —
         * redirect following, retry with backoff, and instrumentation — via
         * [HttpPipelineBuilder.appendStandardResilience]. The turnkey preset for "give me a
         * sensible pipeline over my transport"; start from [HttpPipelineBuilder] when you need to
         * tune options or add error mapping / auth.
         */
        @JvmStatic
        public fun standard(transport: HttpClient): HttpPipeline =
            HttpPipelineBuilder(transport).appendStandardResilience().build()
    }
}
