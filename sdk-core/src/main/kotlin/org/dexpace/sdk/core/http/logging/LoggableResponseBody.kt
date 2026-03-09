package org.dexpace.sdk.core.http.logging

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.io.Buffer
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [ResponseBody] wrapper that eagerly buffers the delegate's stream into a segment-based
 * [Buffer], enabling:
 *
 * - **Repeatable reads**: [byteStream] returns a fresh [InputStream] on each call.
 * - **Non-destructive logging**: [snapshot] captures the full body for diagnostics.
 * - **Segment streaming**: When a [BodySegmentHandler] is provided, the body is emitted in
 *   fixed-size segments during buffering — useful for telemetry or audit logs.
 *
 * The delegate body is fully read and closed on the first access to [byteStream], [bytes],
 * [string], or [snapshot]. After buffering, all reads are served from the in-memory buffer.
 *
 * ## Usage
 *
 * ```
 * val loggable = LoggableResponseBody(originalBody)
 * logger.debug("Response: {}", loggable.snapshot().preview())
 * val data = loggable.bytes()
 * ```
 *
 * ## Thread safety
 *
 * This class is **thread-safe**. The one-time buffering is guarded by a [ReentrantLock] with
 * double-checked locking on a `@Volatile` buffer reference. `ReentrantLock` is used instead
 * of `synchronized` to avoid pinning virtual threads (Project Loom) to their carrier. After
 * the initial buffering, all subsequent reads are lock-free.
 *
 * ## Memory
 *
 * The entire response body is buffered into pooled segments. Use this wrapper only when body
 * logging is needed and the response size is expected to be reasonable.
 *
 * @param delegate The original response body to wrap.
 * @param segmentHandler Optional handler that receives the full body in fixed-size segments.
 * @param segmentSize The size of each segment in bytes when using segment mode.
 */
class LoggableResponseBody @JvmOverloads constructor(
    private val delegate: ResponseBody,
    private val segmentHandler: BodySegmentHandler? = null,
    private val segmentSize: Int = DEFAULT_SEGMENT_SIZE
) : ResponseBody() {

    /** Guards the one-time buffering operation. Uses [ReentrantLock] to avoid virtual thread pinning. */
    private val lock = ReentrantLock()

    /** The eagerly-buffered body content. `null` until [ensureBuffered] completes. */
    @Volatile
    private var buffer: Buffer? = null

    /** Whether this response body has been closed. */
    @Volatile
    private var closed = false

    /** Returns the delegate's media type. */
    override fun mediaType(): MediaType? = delegate.mediaType()

    /** Returns the delegate's content length. */
    override fun contentLength(): Long = delegate.contentLength()

    /**
     * Returns a fresh [InputStream] over the buffered body content.
     *
     * On the first call, the delegate body is fully read and closed. Subsequent calls
     * return a new stream over the same buffer.
     *
     * The returned stream reads from the buffer's segments without consuming them,
     * enabling repeatable reads. Each call returns an independent stream with its
     * own read position.
     *
     * @return A new non-consuming [InputStream] backed by the buffer's segments.
     * @throws IOException if reading the delegate body fails.
     */
    @Throws(IOException::class)
    override fun byteStream(): InputStream {
        ensureBuffered()
        return buffer!!.readOnlyInputStream()
    }

    /**
     * Returns a [BodySnapshot] of the full response body for logging.
     *
     * The snapshot contains the **entire** body — no truncation. Since the full body is
     * already buffered in pooled segments, the snapshot is a flat byte array copy for
     * safe cross-thread access. Display-level truncation is handled by
     * [BodySnapshot.preview].
     *
     * @return The body snapshot.
     * @throws IOException if reading the delegate body fails.
     */
    @Throws(IOException::class)
    fun snapshot(): BodySnapshot {
        ensureBuffered()
        val buf = buffer!!
        return BodySnapshot(buf.snapshot(), delegate.mediaType())
    }

    /**
     * Closes this response body and releases resources.
     *
     * If the body was never read, the delegate is closed to release the underlying
     * connection. If the body was buffered, the buffer's segments are recycled.
     *
     * **Important**: Any [InputStream] previously returned by [byteStream] must not be
     * used after calling close. The streams reference the buffer's segments which are
     * recycled here.
     */
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            val buf = buffer
            if (buf == null) {
                delegate.close()
            } else {
                buffer = null
                buf.clear()
            }
        }
    }

    /**
     * Ensures the delegate body has been fully read into [buffer].
     *
     * Uses double-checked locking: the first check is lock-free on the volatile [buffer]
     * reference; the second check is inside the lock to handle races. If the drain fails,
     * marks this body as [closed] so subsequent calls get a clear error.
     */
    private fun ensureBuffered() {
        if (buffer != null) return
        lock.withLock {
            if (buffer != null) return
            check(!closed) { "LoggableResponseBody is closed" }
            try {
                delegate.use { buffer = drain(it.byteStream()) }
            } catch (e: Throwable) {
                // Delegate is already closed by use{}. Mark this body as closed
                // so subsequent calls get a clear error instead of "stream closed".
                closed = true
                throw e
            }
        }
    }

    /**
     * Reads all bytes from [source] into a new [Buffer] and emits segments to the handler.
     *
     * @return the buffer containing the full body content.
     */
    private fun drain(source: InputStream): Buffer {
        val buf = Buffer()
        buf.readFrom(source)

        // Emit segments to the handler if configured.
        if (segmentHandler != null) {
            emitBodySegments(buf, segmentHandler, segmentSize)
        }

        return buf
    }

    companion object {
        /** Default segment size for the segment handler: 8 KB. */
        const val DEFAULT_SEGMENT_SIZE: Int = 8 * 1024
    }
}
