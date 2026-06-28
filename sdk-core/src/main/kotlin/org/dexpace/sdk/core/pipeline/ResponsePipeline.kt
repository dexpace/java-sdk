/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.pipeline.step.ResponsePipelineStep
import org.dexpace.sdk.core.pipeline.step.ResponseRecoveryStep

/**
 * Recovery-aware response-processing pipeline. Folds an inbound [ResponseOutcome] through two
 * step lists:
 *
 *  1. [responseSteps] — applied only when the current outcome is a [ResponseOutcome.Success].
 *     If a response step throws, the throwable is wrapped in a [ResponseOutcome.Failure] and
 *     fed to subsequent [recoverySteps].
 *  2. [recoverySteps] — applied to **every** outcome, success or failure. The Airbyte
 *     `AfterError` equivalent, generalized so it also observes successes (mirrors gax's
 *     bidirectional `ApiCallContext` hooks). Each recovery step may rescue a failure into a
 *     success, replace the throwable, or pass through.
 *
 * ## Fold order
 * The chain interleaves response steps and recovery steps in a specific order: response steps
 * run first (on a success), then **all** recovery steps run sequentially, regardless of how
 * many response steps ran. This guarantees recovery steps always observe the terminal outcome,
 * including failures thrown by response steps.
 *
 * ```
 * Outcome ──► [respStep1 ─► respStep2 ─► ...] ──► [recoveryStep1 ─► recoveryStep2 ─► ...]
 *             (skipped if outcome is Failure)
 * ```
 *
 * If a recovery step itself throws, its throwable is wrapped in a [ResponseOutcome.Failure]
 * and fed to the next recovery step — recovery exceptions never bypass downstream recovery.
 *
 * ## Thread-safety
 * Instances are immutable after construction (both step lists are defensively copied and
 * exposed as read-only Lists). Safe to share across threads provided the underlying steps are
 * thread-safe.
 *
 * ## Exception semantics
 * [apply] does not throw. All exceptions raised by step execution are converted to
 * [ResponseOutcome.Failure] and threaded through the recovery chain. Callers should inspect
 * the returned outcome to decide whether to surface a [org.dexpace.sdk.core.http.response.Response]
 * or rethrow.
 *
 * ## Close ownership of a discarded Success response
 * The pipeline closes the in-hand [ResponseOutcome.Success] response on exactly one path: when
 * a step *throws* while holding it. Both the success-path (`applyResponseSteps`) and the
 * recovery chain (`invokeRecovery`) close-before-propagate, attaching any close error to the
 * step's throwable as suppressed, so a throwing step never strands the open transport
 * connection. The pipeline does **not** close the response on the path where a step is handed a
 * [ResponseOutcome.Success] and deliberately *returns* a different outcome — a Success→Failure
 * transform (e.g. status-to-typed-exception mapping) or a substitute [ResponseOutcome.Success].
 * Returning a new outcome discards the original response without the pipeline observing it, so
 * the step that performs that transform owns closing the response it drops. See
 * [org.dexpace.sdk.core.pipeline.step.ResponseRecoveryStep] for the per-step contract.
 *
 * @property responseSteps Steps applied on the success path; skipped on failures.
 * @property recoverySteps Recovery steps applied to every outcome.
 */
public class ResponsePipeline
    @JvmOverloads
    constructor(
        responseSteps: List<ResponsePipelineStep> = emptyList(),
        recoverySteps: List<ResponseRecoveryStep> = emptyList(),
    ) {
        /** Defensive copy of the response steps, exposed as a read-only List. */
        public val responseSteps: List<ResponsePipelineStep> = responseSteps.toList()

        /** Defensive copy of the recovery steps, exposed as a read-only List. */
        public val recoverySteps: List<ResponseRecoveryStep> = recoverySteps.toList()

        /**
         * Threads [outcome] through the response-step chain (success path only) and then through
         * every recovery step. Returns the final outcome.
         *
         * @param outcome The starting outcome — typically a [ResponseOutcome.Success] wrapping the
         *  transport response, or a [ResponseOutcome.Failure] wrapping an upstream throwable.
         * @param context The shared dispatch context, propagated to each response step.
         * @return The final outcome after both chains have run.
         */
        public fun apply(
            outcome: ResponseOutcome,
            context: DispatchContext,
        ): ResponseOutcome {
            return recoverySteps.fold(applyResponseSteps(outcome, context)) { current, recovery ->
                invokeRecovery(recovery, current)
            }
        }

        private fun applyResponseSteps(
            outcome: ResponseOutcome,
            context: DispatchContext,
        ): ResponseOutcome {
            var current = outcome
            for (step in responseSteps) {
                current =
                    when (val inbound = current) {
                        is ResponseOutcome.Success -> {
                            // Capture the in-hand response before the step runs so the catch can
                            // release it. A throwing step would otherwise strand the open transport
                            // connection — mirrors the close-before-propagate discipline in
                            // DefaultRedirectStep / AuthStep.
                            val inResponse = inbound.response
                            try {
                                ResponseOutcome.Success(step.execute(inResponse, context))
                            } catch (t: Throwable) {
                                closeQuietly(inResponse, t)
                                failureOf(t)
                            }
                        }
                        is ResponseOutcome.Failure -> return inbound
                    }
            }
            return current
        }

        /**
         * Invokes a single recovery step, wrapping any throwable it raises into a
         * [ResponseOutcome.Failure] so the chain keeps flowing.
         */
        private fun invokeRecovery(
            step: ResponseRecoveryStep,
            outcome: ResponseOutcome,
        ): ResponseOutcome =
            try {
                step.invoke(outcome)
            } catch (t: Throwable) {
                // A recovery step that throws on a Success outcome strands the in-hand response:
                // close it before wrapping the throwable so the open connection is released.
                if (outcome is ResponseOutcome.Success) {
                    closeQuietly(outcome.response, t)
                }
                failureOf(t)
            }

        /**
         * Closes [response], swallowing any close error so it never masks [primary]. A failure to
         * close is attached to [primary] as a suppressed throwable for diagnostics.
         */
        private fun closeQuietly(
            response: Response,
            primary: Throwable,
        ) {
            try {
                response.close()
            } catch (closeError: Throwable) {
                primary.addSuppressed(closeError)
            }
        }
    }
