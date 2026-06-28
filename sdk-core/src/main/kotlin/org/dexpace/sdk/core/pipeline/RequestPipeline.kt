/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.pipeline.step.RequestPipelineStep

/**
 * Sequential request-processing pipeline. Applies an ordered list of [RequestPipelineStep]s
 * to a [Request]: the output of step N is the input of step N + 1. The Airbyte-equivalent of
 * the `BeforeRequest` chain.
 *
 * The pipeline preserves the original [Request] when the list is empty — an empty pipeline
 * is a no-op. Steps may throw; the [ExecutionPipeline] catches throwables from this pipeline
 * and routes them through the recovery chain rather than propagating them to the caller.
 *
 * ## Thread-safety
 * Instances are immutable after construction ([steps] is exposed as a read-only List); they
 * are safe to share across threads provided the steps themselves are thread-safe.
 *
 * ## Exception semantics
 * If any step throws, [execute] propagates the throwable to the caller without invoking the
 * remaining steps. The wider [ExecutionPipeline] is responsible for translating that throwable
 * into a [ResponseOutcome.Failure] so it can be observed by recovery steps.
 *
 * @property steps Ordered list of [RequestPipelineStep]s applied left-to-right.
 */
public class RequestPipeline
    @JvmOverloads
    constructor(
        public val steps: List<RequestPipelineStep> = emptyList(),
    ) {
        /**
         * Applies every step in [steps] to [request] sequentially under [context] and returns the
         * final transformed [Request]. Empty pipelines return [request] unchanged.
         *
         * @param request The initial request to feed into the chain.
         * @param context The shared dispatch context.
         * @return The request produced by the last step (or [request] if [steps] is empty).
         * @throws Throwable Any exception thrown by a step is rethrown unchanged. Callers should
         *  funnel this throwable through the recovery chain (see [ExecutionPipeline]).
         */
        public fun execute(
            request: Request,
            context: DispatchContext,
        ): Request = steps.fold(request) { current, step -> step.execute(current, context) }
    }
