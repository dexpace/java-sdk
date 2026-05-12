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
 * Not safe for concurrent use. [closedFlag] is atomic only so that a `close()` on one thread
 * is observable by a slice reading on another; the source itself is single-threaded.
 */
internal class OkioBufferedSource private constructor(
    val delegate: okio.BufferedSource,
    /**
     * Shared closed-state. The root source allocates a fresh flag; peek/slice views inherit
     * the root's flag so closing the parent invalidates outstanding views. [okio.BufferedSource]
     * has no public closed-flag, and Okio's `peek()` keeps reading from shared segments after
     * the parent closes — which is not what we want here.
     */
    internal val closedFlag: AtomicBoolean,
) : BufferedSource {

    /** Primary constructor: a root source allocates its own [closedFlag]. */
    constructor(delegate: okio.BufferedSource) : this(delegate, AtomicBoolean(false))

    override val buffer: Buffer by lazy(LazyThreadSafetyMode.NONE) { OkioBuffer(delegate.buffer) }

    private fun checkOpen() {
        if (closedFlag.get()) throw IOException("source closed")
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
        // Idempotent: only set the shared flag the first time, but always allow Okio's own
        // double-close (it tolerates it). Peek/slice views share `closedFlag` — closing any
        // one of them invalidates every view spawned from the same root.
        if (closedFlag.compareAndSet(false, true)) {
            delegate.close()
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
    // Share `closedFlag` with the peek view so closing the parent invalidates outstanding
    // peeks. Reading from the peek itself does not advance the parent (Okio's `peek()`
    // contract); close-state propagation is the only thing this wrapper adds.
    override fun peek(): BufferedSource = OkioBufferedSource(delegate.peek(), closedFlag)
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
            parentClosed = closedFlag,
        )
    }
}
