package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Okio-backed implementation of [BufferedSource]. Wraps an [okio.BufferedSource] and forwards
 * every method.
 *
 * ## Thread-safety
 *
 * Not safe for concurrent use. The atomic flags are only used to make close state visible
 * across threads (e.g. when a slice on one thread detects parent closure on another); the
 * source itself is not intended for concurrent reads.
 *
 * ## Close semantics
 *
 * Two separate flags govern closure:
 * - [selfClosedFlag] — per-instance; guards THIS source's own [delegate.close] call (single-shot).
 * - [parentClosed] — shared with every slice/peek spawned from the ROOT; signals "parent is gone"
 *   to derived views WITHOUT blocking the root from closing its own delegate.
 *
 * The root source flips [parentClosed] on close so that all outstanding slices start throwing.
 * A peek view (constructed via [peek]) shares [parentClosed] but has its own [selfClosedFlag];
 * closing the peek does NOT win the parent's flag and therefore does NOT prevent the root's
 * later [close] from reaching [delegate.close].
 */
internal class OkioBufferedSource private constructor(
    internal val delegate: okio.BufferedSource,
    /**
     * Shared closed-state passed DOWN to slices so they can detect parent closure.
     * Only the ROOT source sets this flag on close; peek views share it read-only.
     */
    private val parentClosed: AtomicBoolean,
    /** Whether THIS specific instance owns the [parentClosed] flag (i.e. it is the root). */
    private val isRoot: Boolean,
) : BufferedSource {

    /** Guards [delegate.close] for THIS instance — never shared. */
    private val selfClosedFlag = AtomicBoolean(false)

    /**
     * Primary constructor: creates a ROOT source with its own fresh [parentClosed] flag.
     * Only the root sets [parentClosed] on close.
     */
    constructor(delegate: okio.BufferedSource) : this(delegate, AtomicBoolean(false), isRoot = true)

    /**
     * Internal constructor for peek views: shares the root's [parentClosed] flag but is NOT
     * the root — it will not flip [parentClosed] on close.
     */
    private constructor(delegate: okio.BufferedSource, parentClosed: AtomicBoolean) :
        this(delegate, parentClosed, isRoot = false)

    override val buffer: Buffer by lazy(LazyThreadSafetyMode.NONE) { OkioBuffer(delegate.buffer) }

    private fun checkOpen() {
        if (selfClosedFlag.get() || parentClosed.get()) throw IOException("source closed")
    }

    @Throws(IOException::class)
    override fun read(sink: Buffer, byteCount: Long): Long {
        checkOpen()
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        if (sink is OkioBuffer) return delegate.read(sink.delegate, byteCount)
        if (byteCount == 0L) return 0L
        if (delegate.exhausted()) return -1L
        // Force Okio to pull at least one segment from the transport so we can move bytes
        // in bulk; without this the staging buffer can sit at 0 and we'd degrade to a
        // one-byte-per-call loop.
        delegate.require(1L)
        val available = minOf(byteCount, delegate.buffer.size)
        val bytes = delegate.readByteArray(available)
        sink.write(bytes)
        return bytes.size.toLong()
    }

    @Throws(IOException::class)
    override fun close() {
        // Single-shot: each instance closes its own delegate exactly once.
        if (selfClosedFlag.compareAndSet(false, true)) {
            delegate.close()
        }
        // The root also signals all outstanding slices that the parent is gone.
        if (isRoot) {
            parentClosed.set(true)
        }
    }

    @Throws(IOException::class)
    override fun exhausted(): Boolean {
        checkOpen()
        return delegate.exhausted()
    }
    @Throws(IOException::class)
    override fun readByte(): Byte {
        checkOpen()
        return delegate.readByte()
    }
    @Throws(IOException::class)
    override fun readByteArray(): ByteArray {
        checkOpen()
        return delegate.readByteArray()
    }
    @Throws(IOException::class)
    override fun readByteArray(byteCount: Long): ByteArray {
        checkOpen()
        return delegate.readByteArray(byteCount)
    }
    @Throws(IOException::class)
    override fun readUtf8(): String {
        checkOpen()
        return delegate.readUtf8()
    }
    @Throws(IOException::class)
    override fun readUtf8(byteCount: Long): String {
        checkOpen()
        return delegate.readUtf8(byteCount)
    }
    @Throws(IOException::class)
    override fun readUtf8Line(): String? {
        checkOpen()
        return delegate.readUtf8Line()
    }
    @Throws(IOException::class)
    override fun readString(charset: Charset): String {
        checkOpen()
        return delegate.readString(charset)
    }
    // Share `parentClosed` with the peek view so closing the ROOT invalidates outstanding
    // peeks. The peek view is not the root — it will not flip parentClosed on its own close.
    // Reading from the peek itself does not advance the parent (Okio's `peek()` contract);
    // close-state propagation is the only thing this wrapper adds.
    override fun peek(): BufferedSource = OkioBufferedSource(delegate.peek(), parentClosed)
    override fun inputStream(): InputStream = delegate.inputStream()
    @Throws(IOException::class)
    override fun skip(byteCount: Long) {
        checkOpen()
        delegate.skip(byteCount)
    }

    override fun slice(offset: Long, byteCount: Long): BufferedSource {
        require(offset >= 0) { "offset must be non-negative (got $offset)" }
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        // peek() shares segments with the parent — no copy. The offset skip is deferred
        // until first read so that `offset > source.size` surfaces as EOF on read, not at
        // construction (per the BufferedSource.slice contract).
        return SlicedOkioBufferedSource(
            peeked = delegate.peek(),
            maxBytes = byteCount,
            pendingSkip = offset,
            parentClosed = parentClosed,
        )
    }
}
