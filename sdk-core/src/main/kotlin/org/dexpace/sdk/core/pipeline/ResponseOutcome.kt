/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.http.response.Response

/**
 * Sealed sum type representing the result of a request/response exchange after it has passed
 * through the [ExecutionPipeline].
 *
 * The recovery-aware [ResponsePipeline] threads an outcome through its fold rather than a bare
 * [Response]: this lets [org.dexpace.sdk.core.pipeline.step.ResponseRecoveryStep]s observe and
 * rescue failures uniformly, regardless of whether the throwable originated in a request step,
 * the transport, or a downstream response step.
 *
 * Two variants:
 *  - [Success] — the exchange produced a [Response]. Response steps run on this variant only.
 *  - [Failure] — the exchange produced a [Throwable]. Recovery steps may rescue this into
 *    a [Success], replace the error with a different one, or pass through.
 *
 * Instances are immutable; equality is structural via [equals]/[hashCode] inherited from the
 * data-class variants. Safe to share across threads.
 *
 * @see ResponsePipeline
 * @see org.dexpace.sdk.core.pipeline.step.ResponseRecoveryStep
 */
public sealed class ResponseOutcome {
    /**
     * Successful outcome — the exchange produced [response].
     *
     * @property response The HTTP response produced by the transport or substituted by a
     *  recovery step.
     */
    public data class Success(public val response: Response) : ResponseOutcome()

    /**
     * Failed outcome — the exchange produced [error] instead of a response. Recovery steps may
     * inspect the throwable, swallow it (returning a [Success]), replace it with a different
     * throwable, or leave the outcome unchanged.
     *
     * @property error The throwable produced by a request step, the transport, or a response
     *  step.
     */
    public data class Failure(public val error: Throwable) : ResponseOutcome()

    /** Returns `true` if this outcome is a [Success]. */
    public fun isSuccess(): Boolean = this is Success

    /** Returns `true` if this outcome is a [Failure]. */
    public fun isFailure(): Boolean = this is Failure

    /** Returns the [Response] if this is a [Success], otherwise `null`. */
    public fun getOrNull(): Response? =
        when (this) {
            is Success -> response
            is Failure -> null
        }

    /** Returns the [Throwable] if this is a [Failure], otherwise `null`. */
    public fun errorOrNull(): Throwable? =
        when (this) {
            is Success -> null
            is Failure -> error
        }

    /**
     * Folds this outcome into a value of type [R] by applying [onSuccess] to a [Success.response]
     * or [onFailure] to a [Failure.error].
     *
     * Mirrors `kotlin.Result.fold` semantics so callers can collapse an outcome into a single
     * value without an exhaustive `when`. Neither lambda is invoked more than once per call.
     *
     * @param onSuccess Mapper applied to the [Response] when this is a [Success].
     * @param onFailure Mapper applied to the [Throwable] when this is a [Failure].
     * @return The result of the selected mapper.
     */
    public inline fun <R> fold(
        onSuccess: (Response) -> R,
        onFailure: (Throwable) -> R,
    ): R =
        when (this) {
            is Success -> onSuccess(response)
            is Failure -> onFailure(error)
        }
}
