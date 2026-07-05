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
import org.dexpace.sdk.core.util.interruptibleFuture
import java.io.InterruptedIOException
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
    return AsyncHttpPipeline.of { request -> interruptibleFuture(executor) { sync.send(request) } }
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
    HttpPipeline.of(AsyncHttpClient { sendAsync(it) }.asBlocking())
