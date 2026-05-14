package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.io.IoProvider
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Wraps a [ResponseBody] to provide repeatable reads.
 *
 * On the first call to [source], [snapshot], or [captureException], the wrapped body is
 * drained into an internal [Buffer] using a single `writeAll` (segment-transferring on
 * Okio-backed providers). Subsequent calls to [source] return a fresh non-consuming view of
 * the same bytes, and [snapshot] returns the captured bytes for logging.
 *
 * The wrapped body is closed after draining (success or failure). [close] on this wrapper
 * is idempotent and stops further drains; the captured buffer survives close so post-mortem
 * snapshots remain available.
 *
 * ## Failure semantics
 *
 * A network error during drain does not silently truncate. The wrapper retains whatever
 * bytes were read before the failure and caches the exception:
 *
 * - [source] **re-throws** the cached exception every time it is called, so callers see the
 *   failure deterministically (no silent zero-byte body).
 * - [snapshot] **always returns** the partial bytes — useful for post-mortem logging that
 *   wants to record "what we got" alongside the exception.
 * - [captureException] surfaces the cached exception (or null) for callers that want to
 *   branch on it without consuming the body.
 *
 * ## Memory
 *
 * The whole body is buffered in memory. Only suitable for bodies that fit comfortably in
 * RAM. For multi-MB bodies whose only consumer is logging, prefer [snapshot] with a
 * `maxBytes` cap.
 *
 * ## Thread safety
 *
 * The drain itself is single-fire — concurrent first calls to [source]/[snapshot] are
 * serialized on an internal lock so the upstream is read exactly once. After drain, the
 * captured buffer is read-only from this wrapper's perspective; concurrent readers each
 * receive a separate `peek()` view, which is safe as long as no external code mutates the
 * captured buffer.
 *
 * @param delegate Body whose bytes will be captured and re-served.
 * @param provider I/O provider used to allocate the capture [Buffer]. Defaults to the
 *   globally installed provider.
 */
public class LoggableResponseBody @JvmOverloads constructor(
    private val delegate: ResponseBody,
    private val provider: IoProvider = Io.provider,
) : ResponseBody() {

    private val lock = ReentrantLock()

    @Volatile
    private var captured: Buffer? = null

    @Volatile
    private var drainError: Throwable? = null

    @Volatile
    private var closed = false

    // Set after a successful drain: the drain path closes the source via `.use {}`, so a
    // subsequent close() must not close the delegate again — some sockets / streams throw
    // on double-close.
    @Volatile
    private var delegateClosed = false

    override fun mediaType(): MediaType? = delegate.mediaType()

    override fun contentLength(): Long = captured?.size ?: delegate.contentLength()

    /**
     * Returns a fresh non-consuming view of the captured body. Drains the wrapped body on
     * first call. If the drain failed (partial capture), this method re-throws the captured
     * exception every time — callers see the failure rather than a silent empty source.
     */
    override fun source(): BufferedSource {
        val buf = ensureCaptured()
        drainError?.let { throw it.asIOException() }
        return buf.peek()
    }

    /**
     * Returns the captured body bytes. Returns whatever was read before any drain failure —
     * a drain error does not prevent this method from returning the partial capture. Use
     * [captureException] alongside this method when you need to know whether the capture
     * was complete.
     *
     * @throws IllegalStateException if the captured body exceeds [Buffer.MAX_BYTE_ARRAY_SIZE].
     *         Use [snapshot] with a `maxBytes` cap for bodies that may be unbounded.
     */
    public fun snapshot(): ByteArray = ensureCaptured().snapshot()

    /**
     * Returns up to [maxBytes] bytes of the captured body. Use for log previews of large
     * bodies — avoids materializing the entire body as a `ByteArray`. The captured buffer
     * itself remains intact. Returns the partial prefix on failure (does not throw).
     *
     * [maxBytes] is silently clamped to [Buffer.MAX_BYTE_ARRAY_SIZE] so callers that pass
     * `Int.MAX_VALUE` on a multi-gigabyte body still receive a safely bounded array.
     */
    public fun snapshot(maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        val cap = minOf(maxBytes, Buffer.MAX_BYTE_ARRAY_SIZE)
        val buf = ensureCaptured()
        if (buf.size <= cap) return buf.snapshot()
        return buf.peek().readByteArray(cap.toLong())
    }

    /**
     * Returns the exception that aborted the drain, or null if the body was captured
     * successfully (or has not been read yet). Calling this method does not trigger a drain.
     */
    public val captureException: Throwable? get() = drainError

    @Throws(IOException::class)
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            // The drain path closes the underlying source via `.use {}` on success; in that
            // case we must not close the delegate again — some sockets / streams throw on
            // double-close.
            if (!delegateClosed) {
                delegate.close()
                delegateClosed = true
            }
            // The captured buffer intentionally survives close — it holds in-memory bytes
            // and no network resources, and is needed for post-mortem snapshot logging.
        }
    }

    private fun ensureCaptured(): Buffer {
        captured?.let { return it }
        return lock.withLock {
            captured?.let { return it }
            check(!closed) {
                "LoggableResponseBody was closed before the body was read; nothing to capture."
            }
            drainAndCache()
        }
    }

    /**
     * Drains the delegate body into an in-memory [Buffer] and caches the result.
     *
     * The capture buffer is allocated inside the try block so that a provider failure
     * is also caught and cached in [drainError]. When `delegate.source()` throws before
     * `.use {}` is entered the source was never obtained, so the delegate remains open
     * and a subsequent [close] will still close it — `delegateClosed` is only set to
     * `true` when we know the source ownership chain has already been closed via `.use {}`.
     *
     * If `provider.buffer()` throws (rare; would indicate a misconfigured provider),
     * the error is cached in [drainError] and an empty buffer is used as the fallback
     * capture so [captured] is never left null.
     */
    private fun drainAndCache(): Buffer {
        var buf: Buffer? = null
        var src: BufferedSource? = null
        try {
            buf = provider.buffer()
            src = delegate.source()        // may throw — if it does, delegate is NOT yet closed
            src.use { buf.writeAll(it) }   // closes src (and via ownership the delegate) on exit
            delegateClosed = true
        } catch (t: Throwable) {
            // Keep whatever was read before the failure so logging can still inspect partial bytes.
            drainError = t
            // If src was obtained, .use{} already closed it (and the delegate via ownership).
            // If src is null, delegate.source() threw — delegate stays open; close() will close it.
            if (src != null) delegateClosed = true
        }
        // Ensure captured is always non-null even when buf allocation itself failed.
        // The second provider.buffer() call here is only reached when the first one threw
        // (extremely rare), so if it also throws the exception propagates to the caller —
        // acceptable since the SDK invariant "provider is well-behaved" is violated.
        val result = buf ?: provider.buffer()
        captured = result
        return result
    }

    private fun Throwable.asIOException(): IOException =
        if (this is IOException) this else IOException("Body capture failed", this)
}
