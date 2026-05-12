package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Okio-backed implementation of [BufferedSource]. Wraps an [okio.BufferedSource] and forwards
 * every method.
 */
internal class OkioBufferedSource(val delegate: okio.BufferedSource) : BufferedSource {

    // Sources are not thread-safe per contract — synchronized lazy initialization is overkill.
    override val buffer: Buffer by lazy(LazyThreadSafetyMode.NONE) { OkioBuffer(delegate.buffer) }

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        return when (sink) {
            is OkioBuffer -> delegate.read(sink.delegate, byteCount)
            else -> {
                if (byteCount == 0L) 0L
                else if (delegate.exhausted()) -1L
                else {
                    val available = minOf(byteCount, delegate.buffer.size.coerceAtLeast(1L))
                    val bytes = delegate.readByteArray(available)
                    sink.write(bytes)
                    bytes.size.toLong()
                }
            }
        }
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
}
