/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.context.DispatchContext

/**
 * Generic single-input/single-output pipeline step. The base type for narrower step
 * specializations such as [RequestPipelineStep] and [ResponsePipelineStep].
 *
 * Steps are intended to be small, composable units of work. Each step receives the current
 * value and a [DispatchContext] carrying trace/instrumentation metadata, and returns the
 * (potentially transformed) value to feed the next step.
 *
 * ## Thread-safety
 * Implementations are shared across concurrent requests — they must be safe to invoke from
 * multiple threads. Per-request state belongs in the [DispatchContext] or in the value being
 * transformed, not on the step instance.
 *
 * ## Exception semantics
 * If a step throws, the surrounding pipeline (e.g. [org.dexpace.sdk.core.pipeline.RequestPipeline]
 * or [org.dexpace.sdk.core.pipeline.ResponsePipeline]) is responsible for routing the
 * throwable into the recovery chain rather than propagating it directly. Steps should not
 * swallow exceptions silently.
 *
 * @param T The input value type.
 * @param V The output value type.
 */
public fun interface PipelineStep<in T, out V> {
    /**
     * Executes this step against [input] under [context] and returns the transformed value.
     *
     * @param input The current pipeline value.
     * @param context The shared dispatch context.
     * @return The transformed value to feed the next step.
     */
    public fun execute(
        input: T,
        context: DispatchContext,
    ): V
}
