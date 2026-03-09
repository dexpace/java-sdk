package org.dexpace.sdk.core.io

/**
 * A [Source] that reads from an upstream [BufferedSource] without consuming its bytes.
 *
 * This is returned by [BufferedSource.peek] and enables lookahead without advancing the
 * upstream's position. The peeked source becomes invalid if the upstream is read or closed
 * past the point that was peeked.
 */
internal class PeekSource(
    private val upstream: BufferedSource
) : Source {

    /** Whether this peek source has been closed. */
    private var closed = false

    /** The number of bytes this peek source has read (i.e., the offset into the upstream buffer). */
    private var pos = 0L

    /**
     * The upstream buffer's head segment at the time this peek source was created or last read.
     * Used with [expectedPos] to detect if the upstream has been consumed underneath us.
     */
    private var expectedSegment: Segment? = upstream.buffer.head

    /** The upstream head segment's read position at the time of last tracking update. */
    private var expectedPos: Int = upstream.buffer.head?.pos ?: -1

    /**
     * Copies up to [byteCount] bytes from the upstream buffer into [sink] without consuming them.
     *
     * Validates that the upstream hasn't been read past this peek source's window,
     * then requests data from the upstream and copies it at the current [pos] offset.
     *
     * @return the number of bytes copied, or -1 if the upstream is exhausted.
     * @throws IllegalStateException if the upstream buffer was consumed since this peek was created.
     */
    override fun read(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "closed" }
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L

        // Validate the upstream hasn't been read past our window.
        val currentHead = upstream.buffer.head
        val currentPos = currentHead?.pos ?: -1
        if (expectedSegment != null &&
            (expectedSegment !== currentHead || expectedPos != currentPos)
        ) {
            throw IllegalStateException("upstream buffer was read; peek source is invalid")
        }

        // Ensure upstream has the bytes we need.
        if (!upstream.request(pos + 1)) return -1L

        // Update expected position tracking after upstream may have filled its buffer.
        if (expectedSegment == null) {
            expectedSegment = upstream.buffer.head
            expectedPos = upstream.buffer.head?.pos ?: -1
        }

        val toCopy = minOf(byteCount, upstream.buffer.size - pos)
        if (toCopy <= 0L) return -1L

        upstream.buffer.copyTo(sink, pos, toCopy)
        pos += toCopy
        return toCopy
    }

    /** Marks this peek source as closed. Does not close the upstream source. */
    override fun close() {
        closed = true
    }
}
