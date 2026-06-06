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
