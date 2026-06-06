/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

import org.dexpace.sdk.core.http.common.Headers

/**
 * Named, RPC-shape-aware tracer for a single in-flight HTTP operation.
 *
 * Scaled to HTTP from Google gax's `ApiTracer` (`com.google.api.gax.tracing.ApiTracer`),
 * which models retry, attempts, and transport milestones as distinct, well-typed events.
 * A generic OpenTelemetry tracer that only sees "span started" and "span ended" cannot
 * render retry dashboards or attempt histograms without operator convention; an
 * event-rich tracer can, because each callback below carries the structured data the
 * observability backend needs.
 *
 * Every method is a `default` no-op so adding a new event in this interface is a
 * non-breaking change for existing implementations. Implementations override only the
 * events they care about.
 *
 * Tracer instances correspond 1:1 with an operation lifecycle:
 *  - One tracer per operation (created via [HttpTracerFactory.newTracer]).
 *  - [operationStarted] is invoked once, at the start.
 *  - [attemptStarted] / [attemptFailed] / [attemptRetriesExhausted] may fire many times
 *    as the retry layer (WU-3) drives subsequent attempts.
 *  - Transport milestones ([requestUrlResolved], [connectionAcquired], [requestSent],
 *    [responseHeadersReceived], [responseReceived]) fire on every attempt that reaches
 *    them.
 *  - [operationSucceeded] or [operationFailed] is invoked exactly once at the end.
 *
 * ## Thread-safety
 *
 * Callbacks may run on transport threads (OkHttp dispatcher, JDK `HttpClient` selector,
 * Netty event loop) that are not the calling thread. Implementations **must** be safe
 * to invoke concurrently — typically by guarding mutable state with a [java.util.concurrent.locks.ReentrantLock]
 * (per project policy, not `synchronized`) or by using thread-safe collectors such as
 * `LongAdder` / `ConcurrentHashMap`.
 *
 * Implementations **must not** throw from any callback. A throwing tracer would
 * corrupt the pipeline that emitted the event. Catch and log instead.
 *
 * ## Wiring
 *
 * Pipeline / transport wiring is a follow-up — this WU only defines the vocabulary so
 * downstream WUs (WU-3 retry, transport adapters) have a stable target to emit against.
 */
public interface HttpTracer {
    /**
     * Operation started — fired once when the SDK begins working on a logical operation
     * (a single user-facing HTTP call, which may translate to multiple network attempts).
     *
     * @param operationName Schema-defined operation id (e.g. `"GetUser"`, `"CreateOrder"`),
     *   or `null` when the operation is unnamed (raw `HttpClient.execute(...)` call).
     */
    public fun operationStarted(operationName: String?) {}

    /**
     * Operation succeeded — fired exactly once after a successful final response has been
     * dispatched to the caller. Mutually exclusive with [operationFailed].
     */
    public fun operationSucceeded() {}

    /**
     * Operation failed — fired exactly once when the SDK propagates a terminal failure to
     * the caller. Mutually exclusive with [operationSucceeded].
     *
     * @param error The throwable propagated to the caller. For retried operations this is
     *   the failure from the final attempt (or the retry budget being exhausted).
     */
    public fun operationFailed(error: Throwable) {}

    /**
     * Attempt started — fired at the beginning of each network attempt. The first attempt
     * is `1`; subsequent retries are `2`, `3`, ….
     *
     * @param attemptNumber 1-based attempt counter.
     */
    public fun attemptStarted(attemptNumber: Int) {}

    /**
     * Attempt failed — fired when an attempt fails but the retry layer has decided to try
     * again. Followed by another [attemptStarted] after the configured delay.
     *
     * @param error The throwable that caused this attempt to fail.
     * @param nextDelayMillis Milliseconds the retry layer will wait before the next attempt,
     *   or `null` when the next attempt is scheduled immediately or when the delay is not
     *   known at emit time.
     */
    public fun attemptFailed(
        error: Throwable,
        nextDelayMillis: Long?,
    ) {}

    /**
     * Attempt retries exhausted — fired once when the retry budget (max attempts, total
     * deadline, etc.) has been spent. Immediately followed by [operationFailed] with the
     * same throwable.
     *
     * @param error The throwable from the last attempt.
     */
    public fun attemptRetriesExhausted(error: Throwable) {}

    /**
     * Request URL resolved — fired after pipeline steps (path templating, query
     * parameters, endpoint resolution) have produced the final URL that will be put on
     * the wire. Useful for retry redaction policies and per-host metrics.
     *
     * @param url The resolved request URL.
     */
    public fun requestUrlResolved(url: String) {}

    /**
     * Request sent — fired after the request has been fully written to the transport.
     *
     * @param byteCount Number of body bytes written, or `null` when the body length is
     *   not known (chunked / streaming) or when the transport does not report it.
     */
    public fun requestSent(byteCount: Long?) {}

    /**
     * Response headers received — fired as soon as the response status line and headers
     * are available, before the body is consumed. Useful for time-to-first-byte metrics
     * and for early routing decisions in tracer policies.
     *
     * @param status HTTP status code.
     * @param headers Response headers.
     */
    public fun responseHeadersReceived(
        status: Int,
        headers: Headers,
    ) {}

    /**
     * Response received — fired after the response body has been fully consumed.
     *
     * @param byteCount Number of body bytes read, or `null` when the body length is not
     *   known or when the transport does not report it.
     */
    public fun responseReceived(byteCount: Long?) {}

    /**
     * Connection acquired — fired when the transport obtains a connection for this
     * attempt, whether freshly opened or pulled from the pool. Useful for pool-saturation
     * dashboards.
     *
     * @param host Resolved host.
     * @param port Resolved port.
     */
    public fun connectionAcquired(
        host: String,
        port: Int,
    ) {}
}
