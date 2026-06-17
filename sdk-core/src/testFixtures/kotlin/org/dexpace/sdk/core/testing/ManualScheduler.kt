/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.testing

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Deterministic [ScheduledExecutorService] for testing async, time-dependent pipeline steps
 * (async retry backoff) without real sleeps or background threads.
 *
 * Only the `schedule(Runnable, delay, unit)` overload used by
 * [org.dexpace.sdk.core.util.Futures.delay] is implemented; every other method throws
 * [UnsupportedOperationException]. Scheduled tasks are NOT run automatically — the test drives
 * them explicitly via [runAll] (or [runNext]), so the test thread controls exactly when each
 * delayed continuation fires. Each scheduled delay is recorded in [recordedDelays] so a test can
 * assert on the requested backoff schedule (e.g. that a `Retry-After: 2` produced a 2-second
 * delay) without observing wall-clock time.
 *
 * Not thread-safe: tests run on a single thread.
 */
class ManualScheduler : ScheduledExecutorService {
    private val pending: ArrayDeque<ScheduledTask> = ArrayDeque()

    /** Every delay requested via [schedule], in submission order. Read-only snapshot semantics. */
    val recordedDelays: List<Duration> get() = pending.map { it.delay } + ran

    private val ran: MutableList<Duration> = mutableListOf()
    private var closed = false

    /** Number of tasks still queued and not yet run. */
    val pendingCount: Int get() = pending.size

    /**
     * Runs queued tasks until the queue is empty, including tasks that earlier tasks schedule
     * while running (the async retry loop re-arms by scheduling a new delay). Cancelled tasks are
     * skipped. Bounded so a misbehaving infinite re-schedule fails loudly instead of hanging.
     */
    fun runAll() {
        var guard = 0
        while (pending.isNotEmpty()) {
            check(guard++ < MAX_DRAIN_ITERATIONS) {
                "ManualScheduler.runAll exceeded $MAX_DRAIN_ITERATIONS iterations — likely an " +
                    "unbounded re-schedule loop"
            }
            runNext()
        }
    }

    /** Runs the next queued task (FIFO). No-op if the queue is empty. */
    fun runNext() {
        val task = pending.removeFirstOrNull() ?: return
        ran.add(task.delay)
        if (!task.cancelled) task.command.run()
    }

    override fun schedule(
        command: Runnable,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> {
        check(!closed) { "ManualScheduler is closed" }
        val task = ScheduledTask(command, Duration.ofNanos(unit.toNanos(delay)))
        pending.addLast(task)
        return task
    }

    /** Marks the scheduler closed and drops any queued tasks. Not an override on Java 8's
     *  [ScheduledExecutorService] (which gained `close()` only in Java 19); a plain helper. */
    fun close() {
        closed = true
        pending.clear()
    }

    override fun shutdown() {
        close()
    }

    override fun shutdownNow(): List<Runnable> {
        val drained = pending.map { it.command }
        close()
        return drained
    }

    override fun isShutdown(): Boolean = closed

    override fun isTerminated(): Boolean = closed

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = closed

    // -- Unused ScheduledExecutorService surface ------------------------------------------------

    override fun <V : Any?> schedule(
        callable: Callable<V>,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<V> = unsupported()

    override fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = unsupported()

    override fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = unsupported()

    override fun execute(command: Runnable) {
        command.run()
    }

    override fun <T : Any?> submit(task: Callable<T>): java.util.concurrent.Future<T> = unsupported()

    override fun <T : Any?> submit(
        task: Runnable,
        result: T,
    ): java.util.concurrent.Future<T> = unsupported()

    override fun submit(task: Runnable): java.util.concurrent.Future<*> = unsupported()

    override fun <T : Any?> invokeAll(
        tasks: MutableCollection<out Callable<T>>,
    ): MutableList<java.util.concurrent.Future<T>> = unsupported()

    override fun <T : Any?> invokeAll(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): MutableList<java.util.concurrent.Future<T>> = unsupported()

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T = unsupported()

    override fun <T : Any?> invokeAny(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): T = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("ManualScheduler only supports schedule(Runnable, delay, unit)")

    /** A queued task. [ScheduledFuture] is implemented minimally — only cancellation matters. */
    private class ScheduledTask(
        val command: Runnable,
        val delay: Duration,
    ) : ScheduledFuture<Any?> {
        var cancelled: Boolean = false
            private set

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled = true
            return true
        }

        override fun isCancelled(): Boolean = cancelled

        override fun isDone(): Boolean = cancelled

        override fun get(): Any? = null

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Any? = null

        override fun getDelay(unit: TimeUnit): Long = unit.convert(delay.toNanos(), TimeUnit.NANOSECONDS)

        override fun compareTo(other: Delayed): Int =
            getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
    }

    private companion object {
        private const val MAX_DRAIN_ITERATIONS = 100_000
    }
}
