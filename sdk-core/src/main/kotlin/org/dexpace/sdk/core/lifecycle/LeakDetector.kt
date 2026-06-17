/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.lifecycle

import org.dexpace.sdk.core.instrumentation.ClientLogger
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Opt-in, log-only detector for closeable resources that are never closed.
 *
 * The detector watches resources (response bodies, sources, any [AutoCloseable]) for the
 * pattern where a caller forgets to `close()` and the object is reclaimed by the garbage
 * collector while still "open". When the JVM makes such a resource phantom-reachable, the
 * detector emits a single leak [LeakReport] — by default a `WARN` log line — optionally with
 * the stack trace of where the resource was created.
 *
 * ## What it does **not** do
 *
 * It never closes anything. Auto-closing a caller-owned resource from a GC callback is unsafe
 * (the close could run on an arbitrary thread, at an arbitrary time, against transport state
 * the caller may still expect to own) so the detector is strictly observational. Behaviour of
 * a program is identical whether the detector is on or off; only diagnostics change.
 *
 * ## Off by default
 *
 * [systemDefault] reads the `dexpace.sdk.leakDetection` system property and is **disabled**
 * unless that property is set to `true`. A [Builder] always lets an application enable it
 * explicitly regardless of the property.
 *
 * ## Mechanism (Java 8 compatible)
 *
 * Detection uses a [PhantomReference] per tracked resource plus a [ReferenceQueue] drained by
 * a single daemon thread. This works identically on every JDK from 8 up and needs no
 * `java.lang.ref.Cleaner` (which is 9+). A tracked resource shares an [AtomicBoolean] with its
 * [LeakTracker]; [LeakTracker.closed] flips the flag and de-registers the phantom reference so
 * a cleanly-closed resource is never reported.
 *
 * ## Determinism for tests
 *
 * The reaper thread is started lazily and only when [Builder.startReaperThread] is `true`
 * (the default for [systemDefault]). Tests can construct a detector with the thread disabled
 * and call [drainManually] after forcing a GC to get a deterministic, race-free check that
 * registration and detection fire.
 *
 * ## Thread-safety
 *
 * All public operations are thread-safe.
 */
public class LeakDetector private constructor(
    private val enabled: Boolean,
    private val captureCreationStack: Boolean,
    private val listener: LeakListener,
    private val threadName: String,
    startReaperThread: Boolean,
) {
    private val queue = ReferenceQueue<Any>()

    /** Strong refs keep each [TrackedRef] alive until it is enqueued (phantoms are not self-rooted). */
    private val live: MutableSet<TrackedRef> = Collections.synchronizedSet(HashSet())

    private val reaperLock = ReentrantLock()
    private var reaper: Thread? = null

    init {
        if (enabled && startReaperThread) {
            startReaper()
        }
    }

    /**
     * Begins tracking [resource]. The returned [LeakTracker] must have [LeakTracker.closed]
     * called from the resource's `close()` to mark a clean shutdown; otherwise the resource is
     * reported as a leak once it is reclaimed.
     *
     * When the detector is disabled this returns a shared no-op tracker and registers nothing,
     * so the overhead on the disabled path is a single field read and no allocation of detector
     * state.
     *
     * @param resource the object whose lifecycle is watched. The detector holds **no** strong
     *   reference to it, so tracking never keeps the resource alive.
     * @param description a non-blank label used in the leak report, e.g. `"ResponseBody"`.
     */
    public fun track(
        resource: Any,
        description: String,
    ): LeakTracker {
        if (!enabled) {
            return NoopTracker
        }
        val stack = if (captureCreationStack) Throwable(STACK_PROBE_MESSAGE).stackTrace else null
        val closedFlag = AtomicBoolean(false)
        val ref = TrackedRef(resource, queue, description, stack, closedFlag)
        live.add(ref)
        return Handle(ref, closedFlag)
    }

    /**
     * Wraps [closeable] so that the SDK detects when it is reclaimed without `close()` having
     * been called. The returned [AutoCloseable] delegates `close()` to [closeable] and, on the
     * first close, marks the tracker so no leak is reported. Callers use the returned wrapper in
     * place of the original (e.g. hand it to a caller via `try`-with-resources / `use {}`).
     *
     * This is the by-hand integration point a response-body or stream factory would use today:
     * create the underlying closeable, return `detector.trackCloseable(it, "ResponseBody")`, and
     * an unclosed wrapper surfaces as a `WARN` when the JVM reclaims it. Behaviour is unchanged
     * whether the detector is enabled or not — the wrapper only ever observes, never auto-closes.
     *
     * When the detector is disabled this returns [closeable] unchanged (no wrapper allocation).
     *
     * @param closeable the resource to delegate to and watch.
     * @param description a non-blank label used in the leak report.
     */
    public fun trackCloseable(
        closeable: AutoCloseable,
        description: String,
    ): AutoCloseable {
        if (!enabled) {
            return closeable
        }
        return TrackedCloseable(closeable, track(closeable, description))
    }

    /**
     * Drains every leak that has already been enqueued by the GC and reports each through the
     * configured [LeakListener], returning how many leaks were reported.
     *
     * This is the deterministic entry point for tests: force a GC (e.g. with a poll loop that
     * allocates pressure), then call `drainManually()` to process whatever the collector has
     * enqueued so far. It is safe to call even when the reaper thread is running, though in
     * production the thread normally drains the queue first.
     */
    public fun drainManually(): Int {
        var reported = 0
        while (true) {
            val ref = queue.poll() as? TrackedRef ?: break
            if (report(ref)) {
                reported++
            }
        }
        return reported
    }

    /** Reports [ref] if it was not closed; always de-registers it. Returns `true` if reported. */
    private fun report(ref: TrackedRef): Boolean {
        live.remove(ref)
        ref.clear()
        if (ref.closed.get()) {
            return false
        }
        try {
            listener.onLeak(LeakReport.create(ref.description, ref.creationStack))
        } catch (t: Throwable) {
            // A listener must never kill the reaper thread; swallow and keep draining.
            REAPER_LOGGER.atWarning()
                .event("leak.listener.error")
                .cause(t)
                .log("leak listener threw")
        }
        return true
    }

    private fun startReaper() {
        reaperLock.withLock {
            if (reaper != null) {
                return
            }
            val t =
                Thread({
                    while (true) {
                        try {
                            val ref = queue.remove() as? TrackedRef ?: continue
                            report(ref)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    }
                }, threadName)
            t.isDaemon = true
            reaper = t
            t.start()
        }
    }

    /**
     * Builder for a [LeakDetector]. Use this to enable detection programmatically (independent
     * of the `dexpace.sdk.leakDetection` system property) or to plug in a custom listener.
     */
    public class Builder {
        private var enabled: Boolean = false
        private var captureCreationStack: Boolean = false
        private var listener: LeakListener = loggingListener()
        private var threadName: String = DEFAULT_THREAD_NAME
        private var startReaperThread: Boolean = true

        /** Enables or disables detection. Disabled detectors allocate nothing per [track] call. */
        public fun enabled(enabled: Boolean): Builder =
            apply {
                this.enabled = enabled
            }

        /**
         * When `true`, captures the stack trace at each [track] call and attaches it to the
         * [LeakReport] so leaks can be traced to their creation site. Off by default because
         * stack capture allocates on every tracked resource.
         */
        public fun captureCreationStack(capture: Boolean): Builder =
            apply {
                this.captureCreationStack = capture
            }

        /** Sets the sink for leak reports. Defaults to a SLF4J `WARN` logger. */
        public fun listener(listener: LeakListener): Builder =
            apply {
                this.listener = listener
            }

        /** Overrides the name of the daemon reaper thread. */
        public fun threadName(name: String): Builder =
            apply {
                this.threadName = name
            }

        /**
         * Controls whether the background reaper daemon thread is started. Set to `false` for
         * deterministic tests that drive detection through [drainManually] only.
         */
        public fun startReaperThread(start: Boolean): Builder =
            apply {
                this.startReaperThread = start
            }

        /** Builds the detector. */
        public fun build(): LeakDetector =
            LeakDetector(
                enabled = enabled,
                captureCreationStack = captureCreationStack,
                listener = listener,
                threadName = threadName,
                startReaperThread = startReaperThread,
            )
    }

    public companion object {
        /** System property that enables the [systemDefault] detector when set to `true`. */
        public const val ENABLE_PROPERTY: String = "dexpace.sdk.leakDetection"

        /** System property that enables creation-stack capture for the [systemDefault] detector. */
        public const val CAPTURE_STACK_PROPERTY: String = "dexpace.sdk.leakDetection.captureStack"

        /** Default name of the background reaper daemon thread. */
        public const val DEFAULT_THREAD_NAME: String = "dexpace-leak-detector"

        private val REAPER_LOGGER = ClientLogger("org.dexpace.sdk.core.lifecycle.LeakDetector")

        private const val STACK_PROBE_MESSAGE = "leak-detector creation-stack probe"

        /**
         * The process-wide detector configured from system properties. It is enabled only when
         * `-Ddexpace.sdk.leakDetection=true` is set, and captures creation stacks only when
         * `-Ddexpace.sdk.leakDetection.captureStack=true` is also set. Off by default, so the
         * SDK never spends cycles on leak tracking unless an operator opts in.
         */
        @JvmField
        public val systemDefault: LeakDetector =
            Builder()
                .enabled("true".equals(System.getProperty(ENABLE_PROPERTY), ignoreCase = true))
                .captureCreationStack("true".equals(System.getProperty(CAPTURE_STACK_PROPERTY), ignoreCase = true))
                .build()

        /** Returns a [LeakListener] that logs each report at SLF4J `WARN`, with creation stack if present. */
        @JvmStatic
        public fun loggingListener(): LeakListener =
            LeakListener { report ->
                val event =
                    REAPER_LOGGER.atWarning()
                        .event("resource.leak")
                        .field("resource", report.description)
                val stack = report.creationStack
                if (stack != null) {
                    event.cause(creationTrace(report.description, stack))
                }
                event.log(report.summary())
            }

        /** Wraps a captured creation stack in a throwable so loggers render it as a "cause". */
        private fun creationTrace(
            description: String,
            stack: Array<StackTraceElement>,
        ): Throwable =
            Throwable("creation site of leaked resource: $description").apply {
                stackTrace = stack
            }

        private val NoopTracker: LeakTracker = LeakTracker { }
    }

    /**
     * Phantom reference to a tracked resource. Carries the metadata needed to report a leak
     * without ever dereferencing the (already-collected) resource.
     */
    private class TrackedRef(
        referent: Any,
        queue: ReferenceQueue<Any>,
        val description: String,
        val creationStack: Array<StackTraceElement>?,
        val closed: AtomicBoolean,
    ) : PhantomReference<Any>(referent, queue)

    /** Delegating [AutoCloseable] that marks its [LeakTracker] closed on the first `close()`. */
    private class TrackedCloseable(
        private val delegate: AutoCloseable,
        private val tracker: LeakTracker,
    ) : AutoCloseable {
        override fun close() {
            try {
                delegate.close()
            } finally {
                tracker.closed()
            }
        }
    }

    /** Caller-facing handle: flips the shared flag and de-registers on [closed]. */
    private inner class Handle(
        private val ref: TrackedRef,
        private val closedFlag: AtomicBoolean,
    ) : LeakTracker {
        override fun closed() {
            if (closedFlag.compareAndSet(false, true)) {
                live.remove(ref)
                ref.clear()
            }
        }
    }
}
