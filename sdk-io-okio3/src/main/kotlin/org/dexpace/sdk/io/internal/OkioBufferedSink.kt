package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.Source
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * Okio-backed implementation of [BufferedSink]. Wraps an [okio.BufferedSink] and forwards
 * every method.
 */
internal class OkioBufferedSink(val delegate: okio.BufferedSink) : BufferedSink {

    // Sinks are not thread-safe per contract — synchronized lazy initialization is overkill.
    override val buffer: Buffer by lazy(LazyThreadSafetyMode.NONE) { OkioBuffer(delegate.buffer) }

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

    override fun write(source: ByteArray): BufferedSink {
        delegate.write(source)
        return this
    }

    override fun write(source: ByteArray, offset: Int, byteCount: Int): BufferedSink {
        delegate.write(source, offset, byteCount)
        return this
    }

    override fun writeAll(source: Source): Long = writeAllInto(delegate, source)

    override fun writeUtf8(string: String): BufferedSink {
        delegate.writeUtf8(string)
        return this
    }

    override fun writeUtf8(string: String, beginIndex: Int, endIndex: Int): BufferedSink {
        delegate.writeUtf8(string, beginIndex, endIndex)
        return this
    }

    override fun writeString(string: String, charset: Charset): BufferedSink {
        delegate.writeString(string, charset)
        return this
    }

    override fun outputStream(): OutputStream = delegate.outputStream()

    override fun emit(): BufferedSink {
        delegate.emit()
        return this
    }
}
