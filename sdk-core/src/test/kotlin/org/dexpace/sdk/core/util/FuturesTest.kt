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
    fun `unwrap walks an arbitrarily-deep wrapper chain to the leaf`() {
        // 20 levels: matches the deep-chain test pattern in RetryUtilsTest after M4 removed
        // the MAX_DEPTH cap. The HashSet-based cycle guard replaces the depth cap, so a
        // long-but-acyclic chain unwraps fully to the IOException leaf.
        val leaf = IOException("leaf")
        var current: Throwable = leaf
        repeat(20) { current = CompletionException("wrap-$it", current) }
        assertSame(leaf, Futures.unwrap(current))
    }

    @Test
    fun `unwrap terminates on a cyclic wrapper chain`() {
        // initCause forbids direct self-causation but allows a -> b -> a. Without the
        // identity-set cycle guard the walk would loop forever. The single-arg
        // CompletionException constructor is protected, so subclass to build cause-less
        // instances we can wire up via initCause.
        class NoCauseCE(message: String) : CompletionException(message)
        val a = NoCauseCE("a")
        val b = NoCauseCE("b")
        a.initCause(b)
        b.initCause(a)
        val result = Futures.unwrap(a)
        assertTrue(result === a || result === b, "expected the call to return on cycle detection")
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
