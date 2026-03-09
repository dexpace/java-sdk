package org.dexpace.sdk.core.http.logging

/**
 * A fixed-size chunk of HTTP body content emitted during streaming observation.
 *
 * Segments are emitted in order as bytes flow through the body, allowing the observer
 * to see the **entire** body without holding it all in memory at once. Each segment
 * carries enough metadata to reconstruct ordering and detect the final chunk.
 *
 * @property data The bytes in this segment. Length equals the configured segment size
 *           except for the final segment, which may be shorter.
 * @property offset The byte offset of this segment's first byte within the full body.
 * @property index The zero-based segment index (0, 1, 2, ...).
 * @property isLast Whether this is the final segment.
 */
class BodySegment(
    val data: ByteArray,
    val offset: Long,
    val index: Int,
    val isLast: Boolean
) {
    /**
     * The number of bytes in this segment.
     */
    val size: Int get() = data.size
}

/**
 * Callback for receiving body content in fixed-size segments as it flows through.
 *
 * Use this alongside [BodySnapshot] when you need to stream the body to an external system
 * (telemetry, audit logs) with bounded memory per segment.
 *
 * Example — logging each segment to a file:
 * ```
 * val handler = BodySegmentHandler { segment ->
 *     fileWriter.write(segment.data)
 *     if (segment.isLast) fileWriter.flush()
 * }
 * ```
 *
 * Example — forwarding to an observability system:
 * ```
 * val handler = BodySegmentHandler { segment ->
 *     telemetry.recordBodyChunk(traceId, segment.index, segment.data)
 * }
 * ```
 */
fun interface BodySegmentHandler {
    /**
     * Called once per segment, in order, as body bytes flow through.
     *
     * Implementations should be fast and non-blocking. Throwing from this method
     * does **not** interrupt the body write/read — it is caught and suppressed to
     * protect the primary data flow.
     *
     * @param segment The current body segment.
     */
    fun onSegment(segment: BodySegment)
}

/**
 * Emits the contents of [buffer] to [handler] in chunks of up to [segmentSize] bytes.
 *
 * Iterates the buffer's internal segments and re-chunks them into [BodySegment]s.
 * Chunks are bounded by both [segmentSize] and the buffer's internal segment boundaries
 * (8 KiB), so emitted segments may be smaller than [segmentSize] at segment transitions.
 * When [segmentSize] is equal to or smaller than [Segment.SIZE][org.dexpace.sdk.core.io.Segment.SIZE],
 * chunks are exact.
 *
 * Handler exceptions are caught and suppressed to protect the primary data flow.
 */
internal fun emitBodySegments(
    buffer: org.dexpace.sdk.core.io.Buffer,
    handler: BodySegmentHandler,
    segmentSize: Int
) {
    var offset = 0L
    var index = 0
    val totalSize = buffer.size

    buffer.forEach { data, pos, count ->
        var segPos = pos
        var remaining = count
        while (remaining > 0) {
            val chunkSize = minOf(remaining, segmentSize)
            val chunkData = data.copyOfRange(segPos, segPos + chunkSize)
            val isLast = (offset + chunkSize >= totalSize)
            try {
                handler.onSegment(BodySegment(chunkData, offset, index, isLast))
            } catch (_: Exception) {
                // Suppress handler errors to protect the primary data flow.
            }
            offset += chunkSize
            index++
            segPos += chunkSize
            remaining -= chunkSize
        }
    }

    if (totalSize == 0L) {
        try {
            handler.onSegment(BodySegment(ByteArray(0), 0L, 0, isLast = true))
        } catch (_: Exception) {
            // Suppress.
        }
    }
}
