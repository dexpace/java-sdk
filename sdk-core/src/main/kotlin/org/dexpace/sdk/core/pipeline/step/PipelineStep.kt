package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.context.ExchangeContext


/**
 * Single executable step in a pipeline workflow, parameterized by input type [T] and output type [V].
 *
 * Steps compose into a chain — each takes the upstream's output plus the per-call [DispatchContext]
 * and emits the next stage's input. Declared as a `fun interface` so callers can write steps as
 * lambdas (`PipelineStep { req, ctx -> ... }`).
 *
 * Note: this is the legacy unidirectional pipeline package. The current SDK is migrating to the
 * pipeline machinery under `http/pipeline/`; this interface is retained for back-compat while that
 * transition completes.
 */
fun interface PipelineStep<in T, out V> {
    /**
     * Run this step against [input] using the per-call [context], returning the next stage's input.
     */
    fun execute(input: T, context: DispatchContext): V
}

/**
 * Mixin that adds a retry hook to a [PipelineStep].
 *
 * The retry entry point receives the richer [ExchangeContext] (post-dispatch, with the inflight
 * exchange's mutable state), allowing it to consult attempt count, last failure, and timing without
 * needing to re-thread state through [PipelineStep.execute].
 */
interface StepRetryTrait<in T, out V> : PipelineStep<T, V> {
    /**
     * Re-run this step's logic for the current exchange. Implementations typically read the attempt
     * count and last failure off [context] to decide whether the retry should produce a new request,
     * surface the prior error, or sleep before re-issuing.
     */
    fun retry(context: ExchangeContext): V
}

/** Convenience alias for steps that mutate a [Request] and emit a [Request] (the request side of the chain). */
interface RequestPipelineStep : PipelineStep<Request, Request>

/** Convenience alias for steps that mutate a [Response] and emit a [Response] (the response side of the chain). */
interface ResponsePipelineStep : PipelineStep<Response, Response>
