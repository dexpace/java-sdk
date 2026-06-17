/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.lifecycle

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Detection is driven through [LeakDetector.drainManually] with the background reaper thread
 * disabled so the assertions are race-free. The only non-determinism is whether the GC has
 * reclaimed the resource; that is bounded by [forceCollection], which polls a [WeakReference]
 * sentinel through repeated `System.gc()` calls and fails fast (rather than flaking) if the
 * collector never runs.
 */
class LeakDetectorTest {
    private val captured = CopyOnWriteArrayList<LeakReport>()

    private fun detector(captureStack: Boolean = false): LeakDetector =
        LeakDetector.Builder()
            .enabled(true)
            .startReaperThread(false)
            .captureCreationStack(captureStack)
            .listener { captured.add(it) }
            .build()

    @Test
    fun `reports an unclosed resource once it is reclaimed`() {
        val det = detector()
        // Allocate, track, and immediately drop the only strong reference — all inside a helper
        // so no local on this frame keeps the resource alive.
        val sentinel = trackThenDrop(det, "ResponseBody")

        forceCollection(sentinel)
        val reported = det.drainManually()

        assertEquals(1, reported)
        assertEquals(1, captured.size)
        assertEquals("ResponseBody", captured[0].description)
        assertTrue(captured[0].summary().contains("ResponseBody"))
    }

    @Test
    fun `does not report a resource that was closed`() {
        val det = detector()
        val sentinel = trackCloseThenDrop(det, "ClosedBody")

        forceCollection(sentinel)
        val reported = det.drainManually()

        assertEquals(0, reported)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `disabled detector returns a no-op tracker and never reports`() {
        val det = LeakDetector.Builder().enabled(false).build()
        val tracker = det.track(Any(), "Ignored")
        tracker.closed() // must not throw

        // Even after the (already-dropped) resource is gone there is nothing to drain.
        forceCollectionBestEffort()
        assertEquals(0, det.drainManually())
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `closed is idempotent`() {
        val det = detector()
        val tracker = det.track(Any(), "Body")
        tracker.closed()
        tracker.closed()
        tracker.closed()
        // No exception; nothing reported because the resource was marked closed.
        assertEquals(0, det.drainManually())
    }

    @Test
    fun `captures a creation stack when enabled`() {
        val det = detector(captureStack = true)
        val sentinel = trackThenDrop(det, "StackBody")

        forceCollection(sentinel)
        det.drainManually()

        assertEquals(1, captured.size)
        val stack = captured[0].creationStack
        assertNotNull(stack)
        assertTrue(stack.isNotEmpty())
    }

    @Test
    fun `omits the creation stack by default`() {
        val det = detector(captureStack = false)
        val sentinel = trackThenDrop(det, "NoStackBody")

        forceCollection(sentinel)
        det.drainManually()

        assertEquals(1, captured.size)
        assertNull(captured[0].creationStack)
    }

    @Test
    fun `a throwing listener does not stop subsequent draining`() {
        var calls = 0
        val det =
            LeakDetector.Builder()
                .enabled(true)
                .startReaperThread(false)
                .listener {
                    calls++
                    error("boom")
                }
                .build()
        val sentinel = trackThenDrop(det, "Boom")

        forceCollection(sentinel)
        // drainManually must swallow the listener exception and count the leak.
        val reported = det.drainManually()
        assertEquals(1, reported)
        assertEquals(1, calls)
    }

    @Test
    fun `system default detector is off unless the property is set`() {
        // The property is not set in the test JVM, so the process-wide detector is disabled.
        assertFalse(System.getProperty(LeakDetector.ENABLE_PROPERTY).equals("true", ignoreCase = true))
        val tracker = LeakDetector.systemDefault.track(Any(), "X")
        tracker.closed()
        assertEquals(0, LeakDetector.systemDefault.drainManually())
    }

    @Test
    fun `trackCloseable reports a wrapper that is never closed`() {
        val det = detector()
        val sentinel = wrapThenDrop(det, close = false)

        forceCollection(sentinel)
        val reported = det.drainManually()

        assertEquals(1, reported)
        assertEquals("CloseableBody", captured.single().description)
    }

    @Test
    fun `trackCloseable does not report a wrapper that is closed and delegates close`() {
        val det = detector()
        val closed = booleanArrayOf(false)
        val wrapper = det.trackCloseable({ closed[0] = true }, "CloseableBody")
        wrapper.close()

        assertTrue(closed[0], "delegate close must run")
        forceCollectionBestEffort()
        assertEquals(0, det.drainManually())
    }

    @Test
    fun `trackCloseable returns the original instance when disabled`() {
        val det = LeakDetector.Builder().enabled(false).build()
        val original = AutoCloseable { }
        assertTrue(det.trackCloseable(original, "X") === original)
    }

    @Test
    fun `loggingListener tolerates reports with and without a stack`() {
        val listener = LeakDetector.loggingListener()
        // Should not throw for either shape.
        listener.onLeak(LeakReport.create("NoStack", null))
        listener.onLeak(LeakReport.create("WithStack", Throwable().stackTrace))
    }

    // --- helpers -------------------------------------------------------------------------

    /** Tracks a fresh resource, returns a [WeakReference] sentinel, and lets the resource die. */
    private fun trackThenDrop(
        det: LeakDetector,
        description: String,
    ): WeakReference<Any> {
        val resource = Any()
        det.track(resource, description)
        return WeakReference(resource)
    }

    private fun trackCloseThenDrop(
        det: LeakDetector,
        description: String,
    ): WeakReference<Any> {
        val resource = Any()
        val tracker = det.track(resource, description)
        tracker.closed()
        return WeakReference(resource)
    }

    /** Wraps a fresh closeable via [LeakDetector.trackCloseable], optionally closing it, then drops it. */
    private fun wrapThenDrop(
        det: LeakDetector,
        close: Boolean,
    ): WeakReference<Any> {
        // A fresh instance per call (not a no-capture lambda, which Kotlin would intern as a
        // singleton and never collect).
        val delegate = NoopCloseable()
        val wrapper = det.trackCloseable(delegate, "CloseableBody")
        if (close) {
            wrapper.close()
        }
        // The sentinel watches the delegate, which is the phantom referent the detector tracks.
        return WeakReference(delegate)
    }

    /** Polls `System.gc()` until [sentinel] is cleared, or fails the test if the GC never runs. */
    private fun forceCollection(sentinel: WeakReference<Any>) {
        repeat(MAX_GC_ATTEMPTS) {
            if (sentinel.get() == null) return
            System.gc()
            allocatePressure()
            Thread.sleep(GC_POLL_MILLIS)
        }
        if (sentinel.get() != null) {
            fail("resource was not collected after $MAX_GC_ATTEMPTS GC attempts")
        }
    }

    /** Best-effort GC nudge for the disabled-path test, which does not assert on collection. */
    private fun forceCollectionBestEffort() {
        repeat(3) {
            System.gc()
            allocatePressure()
        }
    }

    private fun allocatePressure() {
        // Allocate and discard to encourage the collector to act. The sink keeps the
        // allocation from being elided without leaking a strong reference past this frame.
        sink = ByteArray(PRESSURE_BYTES)
        sink = null
    }

    @Volatile
    private var sink: ByteArray? = null

    private class NoopCloseable : AutoCloseable {
        override fun close() = Unit
    }

    private companion object {
        private const val MAX_GC_ATTEMPTS = 100
        private const val GC_POLL_MILLIS = 10L
        private const val PRESSURE_BYTES = 1 shl 20
    }
}
