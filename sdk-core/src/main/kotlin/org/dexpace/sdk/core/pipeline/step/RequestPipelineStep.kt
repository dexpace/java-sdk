package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.request.Request

/**
 * Specialization of [PipelineStep] that transforms a [Request] into a [Request]. The
 * Airbyte-equivalent of `BeforeRequest`: typical uses include header injection, URL rewriting,
 * authentication, body serialization, and request logging.
 *
 * ## Thread-safety
 * Steps are shared across concurrent requests. Implementations must be safe to invoke from
 * multiple threads.
 *
 * ## Exception semantics
 * If [execute] throws, [org.dexpace.sdk.core.pipeline.ExecutionPipeline] catches the throwable
 * and routes it through the recovery chain — the failure does not bypass
 * [org.dexpace.sdk.core.pipeline.step.ResponseRecoveryStep]s. This is a deliberate departure
 * from Airbyte's hook model, where a `BeforeRequest` exception skips `AfterError`.
 */
public fun interface RequestPipelineStep : PipelineStep<Request, Request>
