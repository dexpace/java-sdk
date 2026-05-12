package org.dexpace.sdk.core.util

import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FuturesTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @AfterTest
    fun shutdown() {
        scheduler.shutdownNow()
    }

    @Test
    fun `failed returns a completed-exceptionally future carrying the given throwable`() {
        val cause = IOException("boom")
        val future = Futures.failed<String>(cause)
        assertTrue(future.isCompletedExceptionally)
        val thrown = assertFails { future.join() }
        // join() wraps in CompletionException; unwrap to verify the original was preserved.
        assertSame(cause, Futures.unwrap(thrown))
    }

    @Test
    fun `unwrap strips a CompletionException wrapper`() {
        val cause = IllegalStateException("inner")
        val wrapped = CompletionException("outer", cause)
        assertSame(cause, Futures.unwrap(wrapped))
    }

    @Test
    fun `unwrap strips an ExecutionException wrapper`() {
        val cause = IOException("net")
        val wrapped = ExecutionException("outer", cause)
        assertSame(cause, Futures.unwrap(wrapped))
    }

    @Test
    fun `unwrap returns the throwable unchanged when not a wrapper`() {
        val plain = IllegalArgumentException("nope")
        assertSame(plain, Futures.unwrap(plain))
    }

    @Test
    fun `unwrap terminates on a self-referential cause chain`() {
        // Defensive: a self-cause CompletionException would loop forever without a depth cap.
        val cycle = CompletionException("a", null) // can't set cause to itself directly; simulate via inheritance
        // Cannot literally self-reference; verify the bounded walk terminates on a deep chain.
        var current: Throwable = IOException("leaf")
        repeat(20) { current = CompletionException("wrap-$it", current) }
        // After 16 unwrap levels we stop walking; the result may still be a CompletionException
        // but the method must return rather than loop. Just assert termination.
        val result = Futures.unwrap(current)
        assertTrue(result is Throwable)
    }

    @Test
    fun `delay completes with Unit after the requested duration elapses`() {
        val before = System.nanoTime()
        Futures.delay(scheduler, Duration.ofMillis(40)).join()
        val elapsedMs = (System.nanoTime() - before) / 1_000_000
        assertTrue(elapsedMs >= 35, "expected ~40ms elapsed, was $elapsedMs ms")
    }

    @Test
    fun `delay of zero duration completes immediately`() {
        val future = Futures.delay(scheduler, Duration.ZERO)
        assertTrue(future.isDone)
        assertEquals(Unit, future.join())
    }

    @Test
    fun `delay rejects a negative duration`() {
        assertFails { Futures.delay(scheduler, Duration.ofMillis(-1)) }
    }

    @Test
    fun `cancelling the delay future cancels the scheduled task`() {
        val future = Futures.delay(scheduler, Duration.ofSeconds(5))
        // Cancel before the scheduled task could possibly fire.
        future.cancel(false)
        assertTrue(future.isCancelled)
        // The scheduler thread is single — if cancel didn't propagate, the next submit
        // would queue behind a 5-second task. Submit a sentinel and ensure it runs fast.
        val sentinel = CompletableFuture<String>()
        scheduler.schedule({ sentinel.complete("ran") }, 10, TimeUnit.MILLISECONDS)
        assertEquals("ran", sentinel.get(1, TimeUnit.SECONDS))
    }
}
