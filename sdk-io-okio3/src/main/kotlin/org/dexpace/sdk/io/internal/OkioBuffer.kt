package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Source
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Okio-backed implementation of [Buffer]. Wraps an [okio.Buffer] and forwards every method.
 *
 * Buffers are cheap — every call to `IoProvider.buffer()` returns a fresh instance. When a
 * fast path is available (operations between two [OkioBuffer] or [OkioBufferedSource]
 * instances) the implementation uses Okio's native methods to avoid intermediate copies.
 *
 * ## Thread-safety
 *
 * Not safe for concurrent use; [closedFlag] is the only atomic, and it exists solely so a
 * `close()` on one thread is observable by a slice reading on another.
 *
 * ## Post-close read/write behavior
 *
 * After [close], reads and writes continue to work because Okio's in-memory [okio.Buffer] is
 * not closeable in any meaningful way — its [okio.Buffer.close] is effectively a no-op that
 * only clears the buffer. The [closedFlag] exists solely to invalidate outstanding slices
 * spawned via [slice]; it does NOT guard the read/write methods of this instance.
 */
internal class OkioBuffer(internal val delegate: okio.Buffer = okio.Buffer()) : Buffer {
    /**
     * Shared by every [slice] spawned from this buffer so that closing the buffer invalidates
     * outstanding slices. [okio.Buffer.close] is a no-op for in-memory buffers, but the slice
     * contract still requires the invariant — track closure ourselves.
     */
    private val closedFlag = AtomicBoolean(false)

    override val size: Long get() = delegate.size

    override fun snapshot(): ByteArray {
        val byteCount = delegate.size
        check(byteCount <= Buffer.MAX_BYTE_ARRAY_SIZE) {
            "Buffer is too large to materialize as a single ByteArray (size=$byteCount bytes, " +
                "max=${Buffer.MAX_BYTE_ARRAY_SIZE}). Stream via inputStream() or copyTo() instead."
        }
        return delegate.snapshot().toByteArray()
    }

    override fun clear() {
        delegate.clear()
    }

    override fun copyTo(
        out: Buffer,
        offset: Long,
        byteCount: Long,
    ): Buffer {
        when (out) {
            is OkioBuffer -> delegate.copyTo(out.delegate, offset, byteCount)
            else -> {
                val tmp = okio.Buffer()
                delegate.copyTo(tmp, offset, byteCount)
                out.write(tmp.readByteArray())
            }
        }
        return this
    }

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        if (sink is OkioBuffer) return delegate.read(sink.delegate, byteCount)
        if (byteCount == 0L) return 0L
        if (delegate.exhausted()) return -1L
        val available = minOf(byteCount, delegate.size)
        sink.write(delegate.readByteArray(available))
        return available
    }

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        when (source) {
            is OkioBuffer -> delegate.write(source.delegate, byteCount)
            else -> delegate.write(source.readByteArray(byteCount))
        }
    }

    override fun flush() {
        delegate.flush()
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

    override fun slice(
        offset: Long,
        byteCount: Long,
    ): BufferedSource {
        require(offset >= 0) { "offset must be non-negative (got $offset)" }
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        return SlicedOkioBufferedSource(
            peeked = delegate.peek(),
            maxBytes = byteCount,
            pendingSkip = offset,
            parentClosed = closedFlag,
        )
    }

    override fun write(source: ByteArray): BufferedSink {
        delegate.write(source)
        return this
    }

    override fun write(
        source: ByteArray,
        offset: Int,
        byteCount: Int,
    ): BufferedSink {
        delegate.write(source, offset, byteCount)
        return this
    }

    override fun writeAll(source: Source): Long = writeAllInto(delegate, source)

    override fun writeUtf8(string: String): BufferedSink {
        delegate.writeUtf8(string)
        return this
    }

    override fun writeUtf8(
        string: String,
        beginIndex: Int,
        endIndex: Int,
    ): BufferedSink {
        delegate.writeUtf8(string, beginIndex, endIndex)
        return this
    }

    override fun writeString(
        string: String,
        charset: Charset,
    ): BufferedSink {
        delegate.writeString(string, charset)
        return this
    }

    override fun outputStream(): OutputStream = delegate.outputStream()

    override fun emit(): BufferedSink {
        delegate.emit()
        return this
    }
}
