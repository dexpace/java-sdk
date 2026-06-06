/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.response.Response

/**
 * Specialization of [PipelineStep] that transforms a [Response] into a [Response]. The
 * Airbyte-equivalent of `AfterSuccess`: typical uses include status validation, body decoration,
 * header extraction, deserialization, and response logging.
 *
 * Applied by [org.dexpace.sdk.core.pipeline.ResponsePipeline] **only on the success path** —
 * the response-step chain is skipped when the current [org.dexpace.sdk.core.pipeline.ResponseOutcome]
 * is a [org.dexpace.sdk.core.pipeline.ResponseOutcome.Failure].
 *
 * ## Thread-safety
 * Steps are shared across concurrent requests. Implementations must be safe to invoke from
 * multiple threads.
 *
 * ## Exception semantics
 * If [execute] throws, [org.dexpace.sdk.core.pipeline.ResponsePipeline] converts the throwable
 * into a [org.dexpace.sdk.core.pipeline.ResponseOutcome.Failure] and routes it through any
 * subsequent [ResponseRecoveryStep]s — failures inside the response chain participate in the
 * same recovery pathway as transport failures.
 */
public fun interface ResponsePipelineStep : PipelineStep<Response, Response>
