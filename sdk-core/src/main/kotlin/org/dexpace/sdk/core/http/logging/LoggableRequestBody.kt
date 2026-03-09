package org.dexpace.sdk.core.http.logging

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.io.Buffer
import java.io.IOException
import java.io.OutputStream

/**
 * A [RequestBody] wrapper that captures written bytes for logging without consuming or
 * altering the write to the actual output stream.
 *
 * Every byte written is simultaneously captured into a segment-based [Buffer] via a
 * [TeeOutputStream]. After [writeTo] completes, the full body is available via [snapshot].
 *
 * When a [BodySegmentHandler] is provided, the captured body is also emitted in fixed-size
 * segments after the write completes — useful for streaming to telemetry or audit logs.
 *
 * ## Stream architecture
 *
 * ```
 * delegate.writeTo(stream)
 *       │
 *       ▼
 * TeeOutputStream ──► primary (real OutputStream to the HTTP transport)
 *       │
 *       ▼ branch
 * Buffer (captures all bytes via outputStream())
 * ```
 *
 * ## Usage
 *
 * ```
 * val loggable = LoggableRequestBody(body)
 * loggable.writeTo(outputStream)
 * logger.debug("Request: {}", loggable.snapshot()?.preview())
 * ```
 *
 * ## Thread safety
 *
 * This class is **not** thread-safe. A single instance should only be written from one
 * thread at a time. The snapshot is published via a `@Volatile` field for safe visibility
 * after [writeTo] completes.
 *
 * @param delegate The original request body to wrap.
 * @param segmentHandler Optional handler that receives the full body in fixed-size segments.
 * @param segmentSize The size of each segment in bytes when using segment mode.
 */
class LoggableRequestBody @JvmOverloads constructor(
    private val delegate: RequestBody,
    private val segmentHandler: BodySegmentHandler? = null,
    private val segmentSize: Int = DEFAULT_SEGMENT_SIZE
) : RequestBody() {

    /** The captured snapshot, published after [writeTo] completes. */
    @Volatile
    private var snapshot: BodySnapshot? = null

    /** Returns the delegate's media type. */
    override fun mediaType(): MediaType? = delegate.mediaType()

    /** Returns the delegate's content length. */
    override fun contentLength(): Long = delegate.contentLength()

    /**
     * Writes the body to [stream] while simultaneously capturing all bytes into a [Buffer]
     * for snapshot and optional segment emission.
     *
     * @param stream the output stream to write to.
     * @throws IOException if the delegate body throws during write.
     */
    @Throws(IOException::class)
    override fun writeTo(stream: OutputStream) {
        val captureBuffer = Buffer()
        try {
            delegate.writeTo(TeeOutputStream(stream, captureBuffer.outputStream()))
        } finally {
            snapshot = BodySnapshot(captureBuffer.snapshot(), delegate.mediaType())

            if (segmentHandler != null) {
                emitBodySegments(captureBuffer, segmentHandler, segmentSize)
            }

            captureBuffer.clear()
        }
    }

    /**
     * Returns the captured body snapshot, or `null` if [writeTo] has not been called yet.
     *
     * The snapshot contains the **full** body — no truncation.
     */
    fun snapshot(): BodySnapshot? = snapshot

    companion object {
        /** Default segment size for the segment handler: 8 KB. */
        const val DEFAULT_SEGMENT_SIZE: Int = 8 * 1024
    }
}

// ---------------------------------------------------------------------------
// Internal stream utility
// ---------------------------------------------------------------------------

/**
 * An [OutputStream] that duplicates every write to two underlying streams simultaneously.
 *
 * The [primary] stream is the "real" destination (e.g., the HTTP transport's output stream).
 * The [branch] stream receives an identical copy of every byte for observation purposes.
 *
 * ## Lifecycle
 *
 * Closing this stream flushes both delegates but does **not** close either. The primary
 * stream is owned by the HTTP transport; the branch stream is owned by the logging
 * infrastructure. Each owner is responsible for closing its own stream.
 *
 * @param primary The main output stream — receives all writes.
 * @param branch The observation stream — receives a copy of all writes.
 */
internal class TeeOutputStream(
    private val primary: OutputStream,
    private val branch: OutputStream
) : OutputStream() {

    /** Writes a single byte to both [primary] and [branch]. */
    @Throws(IOException::class)
    override fun write(b: Int) {
        primary.write(b)
        branch.write(b)
    }

    /** Writes [len] bytes from [b] starting at [off] to both [primary] and [branch]. */
    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        primary.write(b, off, len)
        branch.write(b, off, len)
    }

    /** Flushes both [primary] and [branch]. */
    @Throws(IOException::class)
    override fun flush() {
        primary.flush()
        branch.flush()
    }

    /** Flushes both streams without closing either. Ownership remains with the callers. */
    override fun close() {
        flush()
    }
}
