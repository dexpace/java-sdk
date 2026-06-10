/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

@file:JvmName("HttpPipelineBridges")

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.Futures
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

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
    return AsyncHttpPipeline.of { request -> sendInterruptibly(sync, request, executor) }
}

/**
 * A [CompletableFuture] that publishes the worker thread running a blocking task so that
 * `cancel(true)` can interrupt it. `cancel(false)` cancels without interrupting — mirroring
 * the `mayInterruptIfRunning` contract that plain `CompletableFuture.cancel` silently ignores.
 *
 * The worker reference is set when the task begins and cleared (in a `finally`) when it ends,
 * so a thread that has returned to its pool is never interrupted for a completed call.
 */
private class InterruptibleSendFuture : CompletableFuture<Response>() {
    private val worker = AtomicReference<Thread?>()

    fun bindWorker(thread: Thread) {
        worker.set(thread)
    }

    fun unbindWorker() {
        worker.set(null)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cancelled = super.cancel(mayInterruptIfRunning)
        if (cancelled && mayInterruptIfRunning) {
            worker.getAndSet(null)?.interrupt()
        }
        return cancelled
    }
}

/**
 * Submits `pipeline.send(request)` to [executor] on an [InterruptibleSendFuture] so the returned
 * future's `cancel(true)` interrupts the in-flight send. A send not yet started (still queued) or
 * already finished is never interrupted.
 */
private fun sendInterruptibly(
    pipeline: HttpPipeline,
    request: Request,
    executor: Executor,
): CompletableFuture<Response> {
    val result = InterruptibleSendFuture()
    executor.execute {
        // Don't start if the caller already cancelled while we were queued.
        if (result.isDone) return@execute
        result.bindWorker(Thread.currentThread())
        try {
            // Re-check after publishing the thread: a cancel between the isDone check and the
            // bind would otherwise miss us; if it already happened, skip the send entirely.
            if (result.isDone) return@execute
            val response = pipeline.send(request)
            result.complete(response)
        } catch (t: Throwable) {
            result.completeExceptionally(t)
        } finally {
            // Stop targeting this thread before it returns to the pool, then clear any interrupt
            // the cancel may have set so a pooled thread is handed back clean.
            result.unbindWorker()
            Thread.interrupted()
        }
    }
    return result
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
public fun AsyncHttpPipeline.toBlocking(): HttpPipeline {
    val async = this
    return HttpPipeline.of { request ->
        val future = async.sendAsync(request)
        try {
            future.get()
        } catch (ie: InterruptedException) {
            // `get()` parks interruptibly (unlike `join()`). Restore the interrupt flag, abort
            // the in-flight send, and surface an InterruptedIOException so the caller's I/O
            // error handling terminates cleanly.
            Thread.currentThread().interrupt()
            future.cancel(true)
            val ioe = InterruptedIOException("Interrupted while waiting for response")
            ioe.initCause(ie)
            throw ioe
        } catch (ee: ExecutionException) {
            // `get()` wraps exceptional completion in ExecutionException; unwrap so callers'
            // `catch (IOException)` sees the original failure rather than the JDK wrapper.
            throw Futures.unwrap(ee)
        }
    }
}
