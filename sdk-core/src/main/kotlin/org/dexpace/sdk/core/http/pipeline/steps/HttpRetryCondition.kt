/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.response.Response

/**
 * Snapshot passed to the should-retry predicates and the [HttpRetryOptions.delayFromCondition]
 * hook of [DefaultRetryStep]. Read-only — describes the decision input.
 *
 * Exactly one of [response] / [exception] is non-null for a given decision; the predicate or
 * delay hook is invoked once per attempt with whichever outcome the downstream chain produced.
 *
 * `tryCount` is the number of retries already performed (`0` on the very first decision —
 * the original attempt is not counted). `retriedExceptions` is a snapshot of the exceptions
 * accumulated from prior failed attempts; it is empty on the success path or on the first
 * decision after the initial attempt.
 */
public data class HttpRetryCondition(
    val response: Response?,
    val exception: Exception?,
    val tryCount: Int,
    val retriedExceptions: List<Throwable>,
)
