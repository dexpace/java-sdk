/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.time.Duration as JDuration

/**
 * Small helpers for [CompletableFuture] that the SDK uses repeatedly. Kept in `util/` so the
 * adapter modules (`sdk-async-coroutines`, `-reactor`, `-netty`, `-virtualthreads`) can lean
 * on them without duplicating.
 */
public object Futures {
    /**
     * Returns a future already completed exceptionally with [t]. Equivalent to JDK 9's
     * `CompletableFuture.failedFuture(...)` but Java 8 compatible (the SDK targets Java 8
     * bytecode, see `CLAUDE.md`).
     */
    @JvmStatic
    public fun <T> failed(t: Throwable): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        f.completeExceptionally(t)
        return f
    }

    /**
     * Unwraps [CompletionException] / [java.util.concurrent.ExecutionException] wrappers to
     * surface the original cause. Returns [t] unchanged if it is not a wrapper.
     *
     * `CompletableFuture` wraps every exceptional completion in `CompletionException` when
     * the caller blocks via `join()` / `get()`; callers writing `catch (IOException e)` lose
     * the typing without this unwrap.
     *
     * Walks the cause chain through wrappers, terminating on the first non-wrapper, a null
     * cause, or a cycle (detected via identity in a `HashSet`).
     */
    @JvmStatic
    public fun unwrap(t: Throwable): Throwable {
        var current: Throwable = t
        val seen = HashSet<Throwable>()
        while (current is CompletionException || current is java.util.concurrent.ExecutionException) {
            if (!seen.add(current)) return current
            val cause = current.cause ?: return current
            current = cause
        }
        return current
    }

    /**
     * Schedules a future that completes (with `null`) after [delay] elapses on [scheduler].
     * The SDK's async retry/redirect steps compose this with `thenCompose` to insert async
     * delays into a future chain without blocking a thread.
     *
     * The return type is `CompletableFuture<Void>` (not `Unit`) for Java-interop ergonomics —
     * Java callers can compose with `CompletableFuture<Void>` chains naturally using
     * `thenRun`, `thenCompose`, etc., without needing to adapt a Kotlin `Unit` value.
     *
     * Cancellation of the returned future cancels the scheduled task.
     */
    @JvmStatic
    public fun delay(
        scheduler: ScheduledExecutorService,
        delay: JDuration,
    ): CompletableFuture<Void> {
        require(!delay.isNegative) { "delay must be non-negative (got $delay)" }
        val future = CompletableFuture<Void>()
        if (delay.isZero) {
            future.complete(null)
            return future
        }
        val scheduled =
            scheduler.schedule(
                { future.complete(null) },
                delay.toNanos(),
                TimeUnit.NANOSECONDS,
            )
        // If a caller cancels the resulting future, propagate to the scheduled task so the
        // scheduler thread isn't held.
        future.whenComplete { _, _ -> scheduled.cancel(false) }
        return future
    }
}

/**
 * A [CompletableFuture] that publishes the worker thread running a blocking task so that
 * `cancel(true)` can interrupt it. `cancel(false)` cancels without interrupting — mirroring the
 * `mayInterruptIfRunning` contract that plain [CompletableFuture.cancel] silently ignores.
 *
 * The worker reference is set when the task begins and cleared (in a `finally`) when it ends, so a
 * thread that has returned to its pool is never interrupted for a completed call.
 */
private class InterruptibleFuture<T> : CompletableFuture<T>() {
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
 * Submits [task] to [executor] on a [CompletableFuture] whose `cancel(true)` interrupts the worker
 * thread running the in-flight [task], while `cancel(false)` cancels without interrupting. A task
 * not yet started (still queued on [executor]) or already finished is never interrupted, and the
 * worker's interrupt flag is cleared before it returns to the pool so a pooled thread is handed
 * back clean.
 *
 * This is the single interrupting-future construction shared by the sync→async bridges
 * ([org.dexpace.sdk.core.client.asAsync] and
 * [HttpPipeline.toAsync][org.dexpace.sdk.core.http.pipeline.toAsync]), so both deliver identical
 * cancellation semantics. For the interrupt to actually abort blocking I/O, [task] must honour
 * `Thread.interrupt()` (the shipped transports do — see the cancellation contract in
 * `docs/architecture.md`).
 */
internal fun <T> interruptibleFuture(
    executor: Executor,
    task: () -> T,
): CompletableFuture<T> {
    val result = InterruptibleFuture<T>()
    executor.execute {
        // Don't start if the caller already cancelled while we were queued.
        if (result.isDone) return@execute
        result.bindWorker(Thread.currentThread())
        try {
            // Re-check after publishing the thread: a cancel between the isDone check and the
            // bind would otherwise miss us; if it already happened, skip the task entirely.
            if (result.isDone) return@execute
            result.complete(task())
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
