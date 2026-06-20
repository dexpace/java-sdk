/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.pipeline.ResponseOutcome

/**
 * Recovery hook applied to every [ResponseOutcome] produced by the
 * [org.dexpace.sdk.core.pipeline.ExecutionPipeline]. The Airbyte-equivalent of `AfterError`,
 * but with broader scope: this step is invoked uniformly on both successes and failures, and
 * on failures originating from any phase of the pipeline (request steps, transport, response
 * steps).
 *
 * Possible behaviours for a recovery step:
 *  - **Rescue.** Receive a [ResponseOutcome.Failure], return a [ResponseOutcome.Success]
 *    (typical for cached fallback or stale-while-revalidate patterns).
 *  - **Replace.** Receive a [ResponseOutcome.Failure] with one throwable, return a
 *    [ResponseOutcome.Failure] with a different throwable (typical for exception mapping —
 *    e.g. wrapping a transport `IOException` into a typed SDK exception).
 *  - **Pass-through.** Return the input outcome unchanged when the step does not apply.
 *  - **Retry (delegated).** Re-invoke the underlying transport via a captured
 *    [org.dexpace.sdk.core.client.HttpClient] reference and return the resulting outcome.
 *    See `RetryStep` (WU-3) for the canonical implementation.
 *
 * Recovery steps **must not** throw. If a recovery step itself raises an exception, the
 * surrounding [org.dexpace.sdk.core.pipeline.ResponsePipeline] wraps the throwable into a
 * [ResponseOutcome.Failure] and feeds it to the next recovery step. Implementations should
 * still avoid this path — surface errors as [ResponseOutcome.Failure] explicitly.
 *
 * ## Close ownership when discarding a Success
 * The [org.dexpace.sdk.core.pipeline.ResponsePipeline] closes the in-hand
 * [ResponseOutcome.Success] response on exactly one path: when a step *throws*. A step that is
 * handed a [ResponseOutcome.Success] and instead deliberately **returns** a different outcome
 * — a [ResponseOutcome.Failure] (the Success→Failure transform, e.g. status-to-typed-exception
 * mapping) or a different [ResponseOutcome.Success] (response substitution) — discards the
 * original response, and the pipeline does **not** close it for you. That original
 * [ResponseOutcome.Success.response] holds an open transport connection / body stream, so the
 * step that drops it **owns closing it**: call `outcome.response.close()` on the response you
 * are discarding before returning the replacement outcome, or the connection leaks. The
 * "Replace" path above operates on a [ResponseOutcome.Failure], which carries no response, so
 * there is nothing to close there.
 *
 * ## Thread-safety
 * Steps are shared across concurrent requests. Implementations must be safe to invoke from
 * multiple threads.
 *
 * @see org.dexpace.sdk.core.pipeline.ResponseOutcome
 * @see org.dexpace.sdk.core.pipeline.ResponsePipeline
 */
public fun interface ResponseRecoveryStep {
    /**
     * Inspects [outcome] and returns the (possibly transformed) outcome to feed to the next
     * step in the recovery chain.
     *
     * @param outcome The current outcome — may be a success or failure.
     * @return The transformed outcome.
     */
    public fun invoke(outcome: ResponseOutcome): ResponseOutcome
}
