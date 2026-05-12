package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Source
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * Okio-backed implementation of [Buffer]. Wraps an [okio.Buffer] and forwards every method.
 *
 * Buffers are cheap — every call to `IoProvider.buffer()` returns a fresh instance. When a
 * fast path is available (operations between two [OkioBuffer] or [OkioBufferedSource]
 * instances) the implementation uses Okio's native methods to avoid intermediate copies.
 */
internal class OkioBuffer(val delegate: okio.Buffer = okio.Buffer()) : Buffer {

    override val size: Long get() = delegate.size

    override fun snapshot(): ByteArray {
        val n = delegate.size
        check(n <= Buffer.MAX_BYTE_ARRAY_SIZE) {
            "Buffer is too large to materialize as a single ByteArray (size=$n bytes, " +
                "max=${Buffer.MAX_BYTE_ARRAY_SIZE}). Stream via inputStream() or copyTo() instead."
        }
        return delegate.snapshot().toByteArray()
    }

    override fun clear() {
        delegate.clear()
    }

    override fun copyTo(out: Buffer, offset: Long, byteCount: Long): Buffer {
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

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        return when (sink) {
            is OkioBuffer -> delegate.read(sink.delegate, byteCount)
            else -> {
                if (delegate.exhausted()) -1L
                else {
                    val available = minOf(byteCount, delegate.size)
                    sink.write(delegate.readByteArray(available))
                    available
                }
            }
        }
    }

    override fun write(source: Buffer, byteCount: Long) {
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

    override fun write(source: ByteArray): org.dexpace.sdk.core.io.BufferedSink {
        delegate.write(source)
        return this
    }

    override fun write(source: ByteArray, offset: Int, byteCount: Int): org.dexpace.sdk.core.io.BufferedSink {
        delegate.write(source, offset, byteCount)
        return this
    }

    override fun writeAll(source: Source): Long = writeAllInto(delegate, source)

    override fun writeUtf8(string: String): org.dexpace.sdk.core.io.BufferedSink {
        delegate.writeUtf8(string)
        return this
    }

    override fun writeUtf8(string: String, beginIndex: Int, endIndex: Int): org.dexpace.sdk.core.io.BufferedSink {
        delegate.writeUtf8(string, beginIndex, endIndex)
        return this
    }

    override fun writeString(string: String, charset: Charset): org.dexpace.sdk.core.io.BufferedSink {
        delegate.writeString(string, charset)
        return this
    }

    override fun outputStream(): OutputStream = delegate.outputStream()

    override fun emit(): org.dexpace.sdk.core.io.BufferedSink {
        delegate.emit()
        return this
    }
}
