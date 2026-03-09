package org.dexpace.sdk.core.io

/**
 * A chunk of bytes in a circular doubly-linked list.
 *
 * Each segment holds an 8 KiB byte array with read ([pos]) and write ([limit]) cursors.
 * Segments form the backbone of [Buffer]'s storage: writes advance [limit] on the tail
 * segment, and reads advance [pos] on the head segment.
 *
 * ## Sharing
 *
 * A segment's byte array can be shared between multiple segments via [sharedCopy].
 * When shared, the [shared] flag is set on the original and the copy receives
 * `shared = true, owner = false`. Only the owner may extend [limit]; shared
 * segments are effectively read-only. This enables zero-copy snapshots and buffer
 * copies.
 *
 * ## Lifecycle
 *
 * Segments are obtained from [SegmentPool.take] and returned via [SegmentPool.recycle].
 * Shared segments cannot be recycled — their byte array may still be in use elsewhere.
 *
 * @see Buffer
 * @see SegmentPool
 */
internal class Segment {

    /** The underlying 8 KiB byte array holding this segment's content. */
    val data: ByteArray

    /** Read position (inclusive). */
    var pos: Int = 0

    /** Write position (exclusive). Also used as byte count when in the pool. */
    var limit: Int = 0

    /** True if other segments or byte strings share this segment's byte array. */
    var shared: Boolean = false

    /** True if this segment owns the byte array and can extend [limit]. */
    var owner: Boolean = false

    /** Next segment in the circular list. */
    var next: Segment? = null

    /** Previous segment in the circular list. */
    var prev: Segment? = null

    /** Creates a new segment with a fresh byte array. */
    constructor() {
        this.data = ByteArray(SIZE)
        this.owner = true
        this.shared = false
    }

    /** Creates a segment sharing or copying the given [data] array. */
    internal constructor(data: ByteArray, pos: Int, limit: Int, shared: Boolean, owner: Boolean) {
        this.data = data
        this.pos = pos
        this.limit = limit
        this.shared = shared
        this.owner = owner
    }

    /** The number of readable bytes in this segment. */
    val count: Int get() = limit - pos

    /** The number of bytes that can still be written to this segment (if owner). */
    val availableForWrite: Int get() = if (owner) SIZE - limit else 0

    /**
     * Creates a new segment that shares this segment's byte array.
     *
     * Both this segment and the returned copy will have `shared = true`.
     * The copy has `owner = false` and cannot extend its [limit].
     */
    fun sharedCopy(): Segment {
        shared = true
        return Segment(data, pos, limit, shared = true, owner = false)
    }

    /**
     * Creates a new segment with a deep copy of this segment's data.
     *
     * The returned segment owns its byte array and can be modified freely.
     */
    fun unsharedCopy(): Segment {
        return Segment(data.copyOf(), pos, limit, shared = false, owner = true)
    }

    /**
     * Removes this segment from the circular list and returns the successor, or `null`
     * if this was the last segment.
     */
    fun pop(): Segment? {
        val result = if (next !== this) next else null
        prev?.next = next
        next?.prev = prev
        next = null
        prev = null
        return result
    }

    /**
     * Inserts [segment] immediately after this segment in the circular list.
     *
     * @return the pushed segment.
     */
    fun push(segment: Segment): Segment {
        segment.prev = this
        segment.next = next
        next?.prev = segment
        next = segment
        return segment
    }

    /**
     * Splits this segment into two at [byteCount] from [pos].
     *
     * Returns the new prefix segment (containing `[pos, pos + byteCount)`), which is
     * inserted before this segment in the list. This segment's [pos] is advanced by
     * [byteCount].
     *
     * For efficiency, when [byteCount] >= [SHARE_MINIMUM], the prefix shares the byte
     * array with this segment (avoiding a copy). Otherwise, bytes are copied into a new
     * segment.
     */
    fun split(byteCount: Int): Segment {
        require(byteCount in 1..count) { "byteCount out of range: $byteCount" }

        val prefix: Segment
        if (byteCount >= SHARE_MINIMUM) {
            prefix = sharedCopy()
            prefix.limit = prefix.pos + byteCount
            pos += byteCount
        } else {
            prefix = SegmentPool.take()
            data.copyInto(prefix.data, destinationOffset = 0, startIndex = pos, endIndex = pos + byteCount)
            prefix.limit = byteCount
            pos += byteCount
        }

        prev!!.push(prefix)
        return prefix
    }

    /**
     * Compacts this segment with its predecessor if both are underutilized.
     *
     * If the predecessor has enough space to absorb this segment's bytes, data is moved
     * to the predecessor, and this segment is removed from the list and recycled.
     */
    fun compact() {
        val predecessor = prev ?: return
        if (!predecessor.owner) return
        val availableInPrev = SIZE - predecessor.limit + predecessor.pos
        if (count > availableInPrev) return
        writeTo(predecessor, count)
        pop()
        SegmentPool.recycle(this)
    }

    /**
     * Moves [byteCount] bytes from this segment into [sink].
     */
    fun writeTo(sink: Segment, byteCount: Int) {
        check(sink.owner) { "only the owner can write" }

        if (sink.limit + byteCount > SIZE) {
            // Shift sink data to the left to make room
            if (sink.shared) throw IllegalArgumentException("cannot write to shared segment")
            if (sink.limit + byteCount - sink.pos > SIZE) throw IllegalArgumentException("not enough space")
            sink.data.copyInto(sink.data, destinationOffset = 0, startIndex = sink.pos, endIndex = sink.limit)
            sink.limit -= sink.pos
            sink.pos = 0
        }

        data.copyInto(sink.data, destinationOffset = sink.limit, startIndex = pos, endIndex = pos + byteCount)
        sink.limit += byteCount
        pos += byteCount
    }

    companion object {
        /** Standard segment size: 8 KiB. */
        const val SIZE: Int = 8192

        /** Minimum byte count to justify sharing vs. copying. */
        const val SHARE_MINIMUM: Int = 1024
    }
}
