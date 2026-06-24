/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.pipeline.AsyncHttpStep
import org.dexpace.sdk.core.http.pipeline.Stage

/**
 * Async pillar step at [Stage.RETRY] — the [AsyncHttpStep] counterpart of [RetryStep]. Drives
 * an async retry loop with the same classification policy, backoff schedule, and
 * `Retry-After` pacing as the synchronous stack, but schedules its delays on a
 * [java.util.concurrent.ScheduledExecutorService] instead of blocking a thread.
 *
 * The base is `abstract` because the stage is locked to [Stage.RETRY] at the type level —
 * users implementing custom async-retry behaviour override
 * [AsyncHttpStep.processAsync] but inherit the pillar slot. The shipped concrete
 * implementation is [DefaultAsyncRetryStep].
 */
public abstract class AsyncRetryStep : AsyncHttpStep {
    final override val stage: Stage = Stage.RETRY
}
