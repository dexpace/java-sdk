package org.dexpace.sdk.core.instrumentation

import org.slf4j.MDC

/**
 * Immutable snapshot of a thread's SLF4J MDC at the moment of [capture]. Used to bridge MDC
 * across async boundaries — the boundary captures on the caller's thread, then the executing
 * thread (which may be a different worker, coroutine dispatcher, event-loop, etc.) calls
 * [withMdc] or [restore] to install the captured map for the duration of the work.
 *
 * Thread-safety: the snapshot itself is immutable and safe to share. The MDC mutations done
 * by [restore] / [withMdc] affect only the calling thread's MDC, per SLF4J semantics.
 *
 * ## Why this exists
 *
 * `Span.makeCurrentWithLoggingContext()` pushes `trace.id` / `span.id` onto MDC for the
 * lifetime of the scope. MDC is per-thread, so any work that hops to another thread (a
 * `CompletableFuture` continuation, an executor task, a Reactor signal, a Netty event-loop
 * callback) loses the entries. Adapter modules use [MdcSnapshot] to bridge that gap.
 */
public class MdcSnapshot private constructor(
    @PublishedApi internal val snapshot: Map<String, String>?,
) {
    /**
     * Replaces the current thread's MDC with the captured snapshot. Calling this on a thread
     * that already has MDC entries discards them — use [withMdc] instead when you want to
     * preserve the executing thread's MDC after the block.
     */
    public fun restore() {
        if (snapshot == null) MDC.clear() else MDC.setContextMap(snapshot)
    }

    /**
     * Captures the current thread's MDC, installs the snapshot for the duration of [block],
     * then restores the previously-captured MDC on exit — including when [block] throws.
     * Use this at adapter-internal callback sites (Reactor `.doOn*`, Netty listeners, etc.)
     * to ensure log events emitted inside the block see the caller's MDC.
     */
    public inline fun <T> withMdc(block: () -> T): T {
        val previous = MDC.getCopyOfContextMap()
        if (snapshot == null) MDC.clear() else MDC.setContextMap(snapshot)
        try {
            return block()
        } finally {
            if (previous == null) MDC.clear() else MDC.setContextMap(previous)
        }
    }

    public companion object {
        /**
         * Captures the current thread's MDC into an immutable snapshot. Safe to call even when
         * no MDC adapter is installed (the captured snapshot will be `null`, treated as
         * "empty" by [restore] and [withMdc]).
         */
        @JvmStatic
        public fun capture(): MdcSnapshot = MdcSnapshot(MDC.getCopyOfContextMap())
    }
}
