/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.async.virtualthreads

import org.dexpace.sdk.core.instrumentation.MdcSnapshot
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * [ExecutorService] decorator that captures SLF4J MDC on the calling thread for each task
 * submission and restores it inside the task's worker thread. Each `execute`/`submit` call
 * snapshots the caller's MDC and the worker installs the snapshot for the duration of the
 * task — including on exception — before reverting to the worker's prior MDC.
 *
 * Used by [asAsyncVirtualThreads] so log events emitted from inside a virtual-thread task
 * carry the calling thread's `trace.id` / `span.id`. Virtual threads do not inherit MDC by
 * default — the default `MDCAdapter` uses a plain (non-inheritable) `ThreadLocal`, so a
 * newly spawned virtual thread starts with an empty MDC even when the caller has entries.
 * This wrapper closes that gap.
 *
 * ## Loom carrier-hop guarantee is a separate concern
 *
 * Project Loom already guarantees that a virtual thread's *own* ThreadLocal state follows
 * the thread across carrier-platform-thread hops on suspend/resume. That guarantee is the
 * JVM's responsibility and is orthogonal to what this decorator addresses. This wrapper
 * exists for the thread-CREATION boundary — when one thread spawns a virtual thread via
 * the executor, the spawned thread starts with an empty ThreadLocal map. Without this
 * decorator, MDC entries set on the calling thread would not reach the worker.
 *
 * Note that JDK 25+ began making virtual threads inherit `InheritableThreadLocal` entries
 * from the spawning thread. If a future SLF4J `MDCAdapter` is implemented on top of
 * `InheritableThreadLocal`, this wrapper would become redundant on JDK 25+ but remains
 * necessary on JDK 21–24 (the SDK's minimum target). The explicit capture/restore is
 * correct on every JDK regardless.
 *
 * The wrapper does NOT own [delegate]; the original executor is exposed back to
 * [VirtualThreadAsyncHttpClient] for the `close()` path so shutdown semantics are unchanged.
 */
internal class MdcAwareExecutor(private val delegate: ExecutorService) : ExecutorService by delegate {
    override fun execute(command: Runnable) {
        val snapshot = MdcSnapshot.capture()
        delegate.execute { snapshot.withMdc { command.run() } }
    }

    override fun <T : Any?> submit(task: Callable<T>): Future<T> {
        val snapshot = MdcSnapshot.capture()
        return delegate.submit(Callable { snapshot.withMdc { task.call() } })
    }

    override fun submit(task: Runnable): Future<*> {
        val snapshot = MdcSnapshot.capture()
        return delegate.submit { snapshot.withMdc { task.run() } }
    }

    override fun <T : Any?> submit(
        task: Runnable,
        result: T,
    ): Future<T> {
        val snapshot = MdcSnapshot.capture()
        return delegate.submit({ snapshot.withMdc { task.run() } }, result)
    }

    override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> {
        val snapshot = MdcSnapshot.capture()
        return delegate.invokeAll(tasks.map { task -> Callable { snapshot.withMdc { task.call() } } })
    }

    override fun <T : Any?> invokeAll(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): MutableList<Future<T>> {
        val snapshot = MdcSnapshot.capture()
        return delegate.invokeAll(tasks.map { task -> Callable { snapshot.withMdc { task.call() } } }, timeout, unit)
    }

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T {
        val snapshot = MdcSnapshot.capture()
        return delegate.invokeAny(tasks.map { task -> Callable { snapshot.withMdc { task.call() } } })
    }

    override fun <T : Any?> invokeAny(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): T {
        val snapshot = MdcSnapshot.capture()
        return delegate.invokeAny(tasks.map { task -> Callable { snapshot.withMdc { task.call() } } }, timeout, unit)
    }
}
