package org.dexpace.sdk.core.util

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryUtilsTest {

    // ---- Status-code classification ----------------------------------------------------

    @Test
    fun `408 Request Timeout is retryable`() {
        assertTrue(RetryUtils.isRetryable(408))
    }

    @Test
    fun `429 Too Many Requests is retryable`() {
        assertTrue(RetryUtils.isRetryable(429))
    }

    @Test
    fun `5xx range is retryable except 501 and 505`() {
        // Spot-check boundaries and a handful in between.
        assertTrue(RetryUtils.isRetryable(500))
        assertTrue(RetryUtils.isRetryable(502))
        assertTrue(RetryUtils.isRetryable(503))
        assertTrue(RetryUtils.isRetryable(504))
        assertTrue(RetryUtils.isRetryable(599))
    }

    @Test
    fun `501 Not Implemented is NOT retryable`() {
        assertFalse(RetryUtils.isRetryable(501))
    }

    @Test
    fun `505 HTTP Version Not Supported is NOT retryable`() {
        assertFalse(RetryUtils.isRetryable(505))
    }

    @Test
    fun `2xx and 3xx and most 4xx are NOT retryable`() {
        assertFalse(RetryUtils.isRetryable(200))
        assertFalse(RetryUtils.isRetryable(301))
        assertFalse(RetryUtils.isRetryable(400))
        assertFalse(RetryUtils.isRetryable(401))
        assertFalse(RetryUtils.isRetryable(403))
        assertFalse(RetryUtils.isRetryable(404))
    }

    @Test
    fun `out-of-range codes are NOT retryable`() {
        assertFalse(RetryUtils.isRetryable(0))
        assertFalse(RetryUtils.isRetryable(-1))
        assertFalse(RetryUtils.isRetryable(99))
        assertFalse(RetryUtils.isRetryable(600))
        assertFalse(RetryUtils.isRetryable(999))
    }

    // ---- Throwable classification ------------------------------------------------------

    @Test
    fun `direct IOException is retryable`() {
        assertTrue(RetryUtils.isRetryable(IOException("network")))
    }

    @Test
    fun `direct TimeoutException is retryable`() {
        assertTrue(RetryUtils.isRetryable(TimeoutException("timed out")))
    }

    @Test
    fun `IOException subclasses are retryable`() {
        assertTrue(RetryUtils.isRetryable(SocketTimeoutException("read timed out")))
        assertTrue(RetryUtils.isRetryable(EOFException("eof")))
    }

    @Test
    fun `RuntimeException with no relevant cause is NOT retryable`() {
        assertFalse(RetryUtils.isRetryable(RuntimeException("oops")))
    }

    @Test
    fun `IllegalStateException with no cause is NOT retryable`() {
        assertFalse(RetryUtils.isRetryable(IllegalStateException("bad state")))
    }

    @Test
    fun `IOException nested two levels deep is retryable`() {
        val cause = IOException("network")
        val mid = RuntimeException("wrap", cause)
        val outer = IllegalStateException("outer", mid)
        assertTrue(RetryUtils.isRetryable(outer))
    }

    @Test
    fun `15-level-deep chain ending in IOException is retryable`() {
        // Build a chain of plain RuntimeExceptions with the IOException at the bottom.
        // Depth of 15 fits inside MAX_DEPTH (16) so we should walk the whole chain.
        var current: Throwable = IOException("bottom")
        repeat(14) { i -> current = RuntimeException("level-$i", current) }
        assertTrue(RetryUtils.isRetryable(current))
    }

    @Test
    fun `20-level-deep chain ending in IOException is retryable`() {
        // Without a MAX_DEPTH cap the full chain is walked; the IOException at the
        // bottom must be found regardless of depth.
        var current: Throwable = IOException("bottom")
        repeat(19) { i -> current = RuntimeException("level-$i", current) }
        assertTrue(RetryUtils.isRetryable(current))
    }

    @Test
    fun `self-referential cycle terminates and returns false`() {
        val a = SelfReferential("a")
        a.setSelfCause()
        assertFalse(RetryUtils.isRetryable(a))
    }

    @Test
    fun `two-node cycle terminates and returns false`() {
        val a = LinkableThrowable("a")
        val b = LinkableThrowable("b")
        a.linkCause(b)
        b.linkCause(a)
        assertFalse(RetryUtils.isRetryable(a))
    }

    /**
     * Helper that exposes a way to install itself as its own cause. `Throwable.initCause`
     * disallows self-causation by design, so we override `getCause` directly.
     */
    private class SelfReferential(message: String) : RuntimeException(message) {
        private var selfCause: Boolean = false
        fun setSelfCause() {
            selfCause = true
        }
        override val cause: Throwable?
            get() = if (selfCause) this else null
    }

    /**
     * Helper that lets us point its `cause` at any other throwable (including another
     * [LinkableThrowable]) so we can build a multi-node cycle.
     */
    private class LinkableThrowable(message: String) : RuntimeException(message) {
        private var linked: Throwable? = null
        fun linkCause(target: Throwable) {
            linked = target
        }
        override val cause: Throwable?
            get() = linked
    }
}
