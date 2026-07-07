/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response

/**
 * Top-level chain that ties together the request chain, the transport, and the recovery-aware
 * response chain. This is the single entry point an SDK consumer calls to dispatch a request:
 * `recoveryChain.recover(request, context)`.
 *
 * ## Execution flow
 *
 * ```
 *   Request
 *     │
 *     ▼
 *   RequestRecoveryChain  ──── throws ────────────────┐
 *     │                                                 │
 *     ▼                                                 │
 *   HttpClient.execute  ──── throws ────────────────┐ │
 *     │                                              │ │
 *     ▼                                              ▼ ▼
 *   ResponseOutcome.Success  ─────►  ResponseRecoveryChain (recovery chain runs uniformly)
 *                                       │
 *                                       ▼
 *                                     unwrap: Success → Response, Failure → throw
 * ```
 *
 * **All** exceptions raised by request steps and the transport are caught and converted into
 * a [ResponseOutcome.Failure], then handed to the [ResponseRecoveryChain]. Recovery steps see
 * every failure regardless of where in the pipeline it originated — this fixes Airbyte's design
 * defect where a `BeforeRequest` exception bypassed `AfterError`.
 *
 * ## Thread-safety
 * The chain is immutable after construction (the underlying [requestChain],
 * [responseChain], and [httpClient] references are final). Concurrent calls to [recover]
 * are safe provided each component is thread-safe.
 *
 * ## Exception semantics
 * [recover] returns the [Response] from a [ResponseOutcome.Success] outcome, or rethrows the
 * [Throwable] from a [ResponseOutcome.Failure] outcome unchanged. Recovery steps that wish to
 * surface a typed exception should construct it themselves and return
 * [ResponseOutcome.Failure].
 *
 * @property requestChain Pre-transport request chain.
 * @property httpClient Transport that produces the raw [Response].
 * @property responseChain Recovery-aware response chain.
 */
public class RecoveryChain
    @JvmOverloads
    constructor(
        public val httpClient: HttpClient,
        public val requestChain: RequestRecoveryChain = RequestRecoveryChain(),
        public val responseChain: ResponseRecoveryChain = ResponseRecoveryChain(),
    ) {
        /**
         * Dispatches [request] through the full pipeline:
         *  1. Runs [requestChain] (catching throwables).
         *  2. On success, invokes [HttpClient.execute] (catching throwables).
         *  3. Folds the resulting [ResponseOutcome] through [responseChain].
         *  4. Unwraps the final outcome: [ResponseOutcome.Success] returns the [Response];
         *     [ResponseOutcome.Failure] rethrows.
         *
         * @param request The initial request.
         * @param context The shared dispatch context (carries trace/instrumentation metadata).
         * @return The final [Response] produced by the pipeline.
         * @throws Throwable The terminal failure if no recovery step rescued it. The throwable is
         *  rethrown unchanged — wrapping is the recovery chain's job.
         */
        public fun recover(
            request: Request,
            context: DispatchContext,
        ): Response {
            val outcome = produceOutcome(request, context)
            val finalOutcome = responseChain.apply(outcome, context)
            return when (finalOutcome) {
                is ResponseOutcome.Success -> finalOutcome.response
                is ResponseOutcome.Failure -> throw finalOutcome.error
            }
        }

        /**
         * Runs the [requestChain] and transport to produce a [ResponseOutcome]. Any throwable
         * raised by a request step or the transport is converted to a [ResponseOutcome.Failure];
         * the recovery chain receives them uniformly.
         */
        private fun produceOutcome(
            request: Request,
            context: DispatchContext,
        ): ResponseOutcome {
            val transformedRequest =
                try {
                    requestChain.recover(request, context)
                } catch (t: Throwable) {
                    return failureOf(t)
                }
            return try {
                ResponseOutcome.Success(httpClient.execute(transformedRequest))
            } catch (t: Throwable) {
                failureOf(t)
            }
        }
    }
