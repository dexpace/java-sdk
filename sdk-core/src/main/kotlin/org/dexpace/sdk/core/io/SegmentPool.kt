package org.dexpace.sdk.core.io

import java.util.concurrent.atomic.AtomicReference

/**
 * A lock-free pool that recycles [Segment] instances to reduce allocation pressure.
 *
 * Segments are expensive to allocate (8 KiB each) and are created/destroyed frequently
 * during buffer operations. This pool maintains a thread-safe stack of recently released
 * segments for reuse.
 *
 * ## Thread safety
 *
 * The pool uses a per-bucket lock-free stack with atomic compare-and-swap. When contention
 * is detected (another thread holds the lock sentinel), the operation falls back gracefully:
 * [take] allocates a fresh segment, and [recycle] silently drops the segment. This avoids
 * blocking at the cost of occasional extra allocations.
 *
 * ## Capacity
 *
 * Each hash bucket holds at most [MAX_SIZE] bytes (64 KiB = 8 segments). Segments returned
 * to a full pool are silently discarded.
 */
internal object SegmentPool {

    /** Maximum pooled bytes per hash bucket. */
    const val MAX_SIZE: Int = 64 * 1024

    /** Number of hash buckets — power-of-two derived from available processors for bitmask indexing. */
    private val HASH_BUCKET_COUNT: Int = Integer.highestOneBit(
        (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(1)
    )

    /** Sentinel value indicating a bucket is locked by another thread. */
    private val LOCK = Segment()

    /** Per-thread segment stacks. Each bucket is an atomic stack head used for lock-free push/pop. */
    private val hashBuckets: Array<AtomicReference<Segment?>> = Array(HASH_BUCKET_COUNT) {
        AtomicReference<Segment?>(null)
    }

    /**
     * Returns a recycled segment if available, or allocates a new one.
     *
     * The returned segment has `pos = 0`, `limit = 0`, `next = null`, `prev = null`,
     * `shared = false`, `owner = true`.
     */
    fun take(): Segment {
        val bucket = hashBuckets[bucketIndex()]
        val first = bucket.getAndSet(LOCK)

        if (first === LOCK) {
            // Another thread holds this bucket — just allocate.
            return Segment()
        }

        if (first == null) {
            // Pool is empty — unlock the bucket and allocate a fresh segment.
            bucket.set(null)
            return Segment()
        }

        try {
            // Pop the head from the stack.
            val next = first.next
            first.next = null
            first.limit = 0 // Reset — limit doubles as pool byte count
            first.shared = false
            first.owner = true

            // Restore the rest of the stack (excluding the popped head).
            bucket.set(next)
            return first
        } catch (e: Throwable) {
            // Restore whatever was there to avoid permanently locking the bucket.
            bucket.set(first)
            throw e
        }
    }

    /**
     * Returns [segment] to the pool for future reuse.
     *
     * The segment must not be part of any buffer's linked list (i.e., `next` and `prev`
     * must be `null`). Shared segments cannot be recycled.
     */
    fun recycle(segment: Segment) {
        require(segment.next == null && segment.prev == null) { "segment still in a list" }
        if (segment.shared) return // Shared arrays may be in use elsewhere.

        val bucket = hashBuckets[bucketIndex()]
        val first = bucket.getAndSet(LOCK)

        if (first === LOCK) {
            // Contention — silently drop.
            return
        }

        try {
            val firstLimit = first?.limit ?: 0
            if (firstLimit >= MAX_SIZE) {
                // Pool is full — restore and drop.
                bucket.set(first)
                return
            }

            // Push onto the stack.
            segment.next = first
            segment.pos = 0
            segment.limit = firstLimit + Segment.SIZE
            segment.shared = false
            segment.owner = true

            bucket.set(segment)
        } catch (e: Throwable) {
            bucket.set(first)
            throw e
        }
    }

    /** The total number of pooled bytes in the current thread's bucket. */
    val byteCount: Int
        get() {
            val bucket = hashBuckets[bucketIndex()]
            val first = bucket.get()
            return if (first === LOCK || first == null) 0 else first.limit
        }

    /** Returns the hash bucket index for the current thread using a bitmask on the thread ID. */
    @Suppress("DEPRECATION") // Thread.id deprecated in JDK 19+; threadId() not available on Java 8
    private fun bucketIndex(): Int {
        return Thread.currentThread().id.toInt() and (HASH_BUCKET_COUNT - 1)
    }
}
