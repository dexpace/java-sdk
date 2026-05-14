package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.pipeline.HttpStep
import org.dexpace.sdk.core.http.pipeline.Stage

/**
 * Pillar step at [Stage.RETRY]. Re-invokes the downstream pipeline on classified failures
 * with exponential backoff (or fixed delay) plus jitter, parses three `Retry-After` header
 * variants for server-driven pacing, walks the exception cause chain to classify
 * retryability, accumulates suppressed exceptions on the final failure, and aborts on
 * thread interruption.
 *
 * The base is `abstract` because the stage is locked to [Stage.RETRY] at the type level —
 * users implementing custom retry behaviour override [HttpStep.process] but inherit the
 * pillar slot. The shipped concrete implementation is [DefaultRetryStep].
 */
public abstract class RetryStep : HttpStep {
    final override val stage: Stage = Stage.RETRY
}
