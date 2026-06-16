/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response.exception

/**
 * Marks an exception that knows whether the condition it represents is worth retrying.
 *
 * The retry machinery keys off this interface rather than concrete exception types: a
 * [org.dexpace.sdk.core.pipeline.step.retry.RetryStep] asks `error is Retryable` and reads
 * [isRetryable] instead of matching every subclass it might encounter. New retryable
 * exception types participate automatically by implementing this interface — no change to the
 * classifier is required.
 *
 * Two SDK exceptions implement it today:
 *  - [HttpException] — a response was received; [isRetryable] is derived once at construction
 *    from the status code via [org.dexpace.sdk.core.util.RetryUtils.isRetryable].
 *  - [NetworkException] — the transport failed before any response arrived; [isRetryable] is
 *    always `true` (nothing reached the server, so a fresh attempt is safe at the SDK level).
 *
 * A `true` flag means the *condition* is transient. Whether a specific request is *safe* to
 * replay (HTTP-method idempotency, replayable body) is an orthogonal decision owned by the
 * retry step, not by this flag.
 */
public interface Retryable {
    /** Whether the condition this exception represents is a retryable one. */
    public val isRetryable: Boolean
}
