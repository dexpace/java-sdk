package org.dexpace.sdk.core.testing

import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestRecorderTest {
    private fun req(path: String): Request =
        Request.builder().url("https://example.test/$path").method(Method.GET).build()

    @Test
    fun `records multiple requests in order`() {
        val recorder = RequestRecorder()
        val a = req("a")
        val b = req("b")
        val c = req("c")
        recorder.record(a)
        recorder.record(b)
        recorder.record(c)

        assertEquals(listOf(a, b, c), recorder.snapshot())
        assertEquals(3, recorder.callCount)
    }

    @Test
    fun `snapshot is detached from recorder state`() {
        val recorder = RequestRecorder()
        recorder.record(req("a"))
        val snapshot = recorder.snapshot()
        // Mutating the snapshot's backing list (whether legal or not) must not bleed back
        // into the recorder. We attempt the mutation defensively — `toList()` on a
        // `MutableList` returns an ArrayList copy, which is mutable via cast at runtime;
        // the contract we care about is detachment, not container immutability.
        try {
            @Suppress("UNCHECKED_CAST")
            (snapshot as MutableList<Request>).add(req("b"))
        } catch (_: UnsupportedOperationException) {
            // Either outcome is acceptable; what matters is the recorder's state.
        }
        assertEquals(1, recorder.callCount)
        assertEquals(1, recorder.snapshot().size)
    }

    @Test
    fun `snapshot taken at time T does not change when more records are added later`() {
        val recorder = RequestRecorder()
        recorder.record(req("a"))
        val snapshot = recorder.snapshot()
        recorder.record(req("b"))

        assertEquals(1, snapshot.size, "Earlier snapshot must not see later records")
        assertEquals(2, recorder.callCount)
    }

    @Test
    fun `concurrent record calls from multiple threads count correctly`() {
        val threadCount = 4
        val perThread = 250
        val recorder = RequestRecorder()
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            repeat(threadCount) { t ->
                executor.submit {
                    latch.await()
                    repeat(perThread) { i -> recorder.record(req("t$t-$i")) }
                }
            }
            latch.countDown()
            executor.shutdown()
            assertTrue(
                executor.awaitTermination(5, TimeUnit.SECONDS),
                "Recording threads did not finish in time",
            )
        } finally {
            executor.shutdownNow()
        }

        assertEquals(threadCount * perThread, recorder.callCount)
        assertEquals(threadCount * perThread, recorder.snapshot().size)
    }
}
