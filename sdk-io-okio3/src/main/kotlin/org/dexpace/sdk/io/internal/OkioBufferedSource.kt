package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
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
internal class OkioBufferedSource(val delegate: okio.BufferedSource) : BufferedSource {

    /**
     * Shared by every [slice] spawned from this source so that closing the parent invalidates
     * outstanding slices. [okio.BufferedSource] has no public closed-flag, and Okio's `peek()`
     * returns a view that keeps reading from shared segments even after the parent closes —
     * which is not what slices want. Track closure here and consult it on every slice read.
     */
    internal val closedFlag = AtomicBoolean(false)

    override val buffer: Buffer by lazy(LazyThreadSafetyMode.NONE) { OkioBuffer(delegate.buffer) }

    override fun read(sink: Buffer, byteCount: Long): Long {
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

    override fun close() {
        closedFlag.set(true)
        delegate.close()
    }

    override fun exhausted(): Boolean = delegate.exhausted()
    override fun readByte(): Byte = delegate.readByte()
    override fun readByteArray(): ByteArray = delegate.readByteArray()
    override fun readByteArray(byteCount: Long): ByteArray = delegate.readByteArray(byteCount)
    override fun readUtf8(): String = delegate.readUtf8()
    override fun readUtf8(byteCount: Long): String = delegate.readUtf8(byteCount)
    override fun readUtf8Line(): String? = delegate.readUtf8Line()
    override fun readString(charset: Charset): String = delegate.readString(charset)
    override fun peek(): BufferedSource = OkioBufferedSource(delegate.peek())
    override fun inputStream(): InputStream = delegate.inputStream()
    override fun skip(byteCount: Long) {
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
