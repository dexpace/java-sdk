/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

@file:JvmName("HttpPipelineBridges")

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.asBlocking
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestOptions
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.interruptibleFuture
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Adapts a synchronous [HttpPipeline] into an [AsyncHttpPipeline] by submitting each
 * `send(request)` call to [executor]. Callers MUST supply an executor — there is intentionally
 * no default. Recommended setups:
 *  - JDK 21+: `Executors.newVirtualThreadPerTaskExecutor()` (or use `sdk-async-virtualthreads`
 *    which wires this for you with MDC propagation).
 *  - JDK 8–20: a `ThreadPoolExecutor` sized for your concurrent-request ceiling.
 *
 * `ForkJoinPool.commonPool()` is **not** an acceptable production choice — blocking HTTP calls
 * starve every other commonPool consumer (parallel streams, CompletableFuture default chains).
 *
 * The returned `AsyncHttpPipeline` is a single-step facade around the wrapped sync pipeline;
 * the wrapped pipeline's individual steps remain synchronous and run on the dispatch thread.
 * For per-step async behavior (true concurrency inside the pipeline), implement [AsyncHttpStep]
 * directly and use [AsyncHttpPipelineBuilder].
 *
 * ## Cancellation
 * Cancelling the returned future with `cancel(true)` interrupts the worker thread running the
 * in-flight `sync.send(...)`, matching the native transports' `executeAsync` cancellation
 * semantics. The interrupt is delivered only while the send is actually executing — a
 * not-yet-started task (still queued on [executor]) is simply abandoned, and an
 * already-completed send is unaffected. `cancel(false)` completes the future as cancelled
 * without interrupting the worker, so a blocking `sync.send` that ignores interrupts runs to
 * completion in the background. For the interrupt to abort I/O, the wrapped transport must
 * honour `Thread.interrupt()` (the shipped transports do — see the cancellation contract in
 * `docs/architecture.md`).
 */
public fun HttpPipeline.toAsync(executor: Executor): AsyncHttpPipeline {
    val sync = this
    val asyncClient =
        object : AsyncHttpClient {
            override fun executeAsync(request: Request): CompletableFuture<Response> =
                executeAsync(request, RequestOptions.EMPTY)

            // Thread the caller's per-call options into the wrapped sync `send` so overrides
            // (timeout, per-call retry budget, tags) survive the sync→async bridge instead of
            // being dropped by the SPI's options-ignoring default.
            override fun executeAsync(
                request: Request,
                options: RequestOptions,
            ): CompletableFuture<Response> = interruptibleFuture(executor) { sync.send(request, options) }
        }
    return AsyncHttpPipeline.of(asyncClient)
}

/**
 * Adapts an [AsyncHttpPipeline] into a synchronous [HttpPipeline] by blocking on
 * `sendAsync(request).get()` for each `send(...)` call. The current thread blocks until the
 * future completes; pair with virtual threads (JDK 21+) on the caller side to keep carrier
 * threads available.
 *
 * The blocking wait honours `Thread.interrupt()`: interrupting the calling thread restores the
 * interrupt flag, cancels the in-flight future, and throws an [InterruptedIOException].
 */
public fun AsyncHttpPipeline.toBlocking(): HttpPipeline =
    // AsyncHttpPipeline already implements AsyncHttpClient, and its `executeAsync(request, options)`
    // override threads options into `sendAsync(request, options)`. Bridging it directly (rather than
    // through a 1-arg SAM literal) preserves per-call options across the async→sync bridge.
    HttpPipeline.of(asBlocking())
