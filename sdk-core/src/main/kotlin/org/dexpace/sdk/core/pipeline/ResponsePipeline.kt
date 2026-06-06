/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.pipeline.step.ResponsePipelineStep
import org.dexpace.sdk.core.pipeline.step.ResponseRecoveryStep
import java.util.Collections

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
 * Instances are immutable after construction (both step lists are wrapped in unmodifiable
 * views). Safe to share across threads provided the underlying steps are thread-safe.
 *
 * ## Exception semantics
 * [apply] does not throw. All exceptions raised by step execution are converted to
 * [ResponseOutcome.Failure] and threaded through the recovery chain. Callers should inspect
 * the returned outcome to decide whether to surface a [org.dexpace.sdk.core.http.response.Response]
 * or rethrow.
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
        /** Unmodifiable defensive view of the response steps. */
        public val responseSteps: List<ResponsePipelineStep> = Collections.unmodifiableList(responseSteps.toList())

        /** Unmodifiable defensive view of the recovery steps. */
        public val recoverySteps: List<ResponseRecoveryStep> = Collections.unmodifiableList(recoverySteps.toList())

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
            var current = applyResponseSteps(outcome, context)
            for (recovery in recoverySteps) {
                current = invokeRecovery(recovery, current)
            }
            return current
        }

        private fun applyResponseSteps(
            outcome: ResponseOutcome,
            context: DispatchContext,
        ): ResponseOutcome {
            var current = outcome
            for (step in responseSteps) {
                current =
                    when (current) {
                        is ResponseOutcome.Success ->
                            try {
                                ResponseOutcome.Success(step.execute(current.response, context))
                            } catch (t: Throwable) {
                                handleStepThrowable(t)
                            }
                        is ResponseOutcome.Failure -> return current
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
                handleStepThrowable(t)
            }

        /**
         * Converts a step-raised throwable into a [ResponseOutcome.Failure]. [InterruptedException]
         * preserves the interrupt flag on the current thread per the SDK's cancellation contract.
         */
        private fun handleStepThrowable(t: Throwable): ResponseOutcome.Failure {
            if (t is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return ResponseOutcome.Failure(t)
        }
    }
