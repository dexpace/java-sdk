package org.dexpace.sdk.core.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.time.Duration as JDuration

/**
 * Small helpers for [CompletableFuture] that the SDK uses repeatedly. Kept in `util/` so the
 * adapter modules (`sdk-async-coroutines`, `-reactor`, `-netty`, `-virtualthreads`) can lean
 * on them without duplicating.
 */
object Futures {

    /**
     * Returns a future already completed exceptionally with [t]. Equivalent to JDK 9's
     * `CompletableFuture.failedFuture(...)` but Java 8 compatible (the SDK targets Java 8
     * bytecode, see `CLAUDE.md`).
     */
    @JvmStatic
    fun <T> failed(t: Throwable): CompletableFuture<T> {
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
    fun unwrap(t: Throwable): Throwable {
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
    fun delay(scheduler: ScheduledExecutorService, delay: JDuration): CompletableFuture<Void> {
        require(!delay.isNegative) { "delay must be non-negative (got $delay)" }
        val future = CompletableFuture<Void>()
        if (delay.isZero) {
            future.complete(null)
            return future
        }
        val scheduled = scheduler.schedule(
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
