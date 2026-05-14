package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.Source
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Okio-backed implementation of [BufferedSink]. Wraps an [okio.BufferedSink] and forwards
 * every method.
 *
 * ## Thread-safety
 *
 * Not safe for concurrent use. Lazy [buffer] init uses [LazyThreadSafetyMode.NONE] to match.
 * [closedFlag] is atomic only so that a `close()` on one thread is observable elsewhere; the
 * sink itself is single-threaded.
 */
internal class OkioBufferedSink(internal val delegate: okio.BufferedSink) : BufferedSink {

    /**
     * Tracks whether [close] has been called. Every mutating operation checks this and throws
     * [IOException] if it has, mirroring the `OkioBufferedSource` closed-flag pattern.
     */
    private val closedFlag = AtomicBoolean(false)

    override val buffer: Buffer by lazy(LazyThreadSafetyMode.NONE) { OkioBuffer(delegate.buffer) }

    /** Throws [IOException] if this sink has already been closed. */
    private fun checkOpen() {
        if (closedFlag.get()) throw IOException("sink closed")
    }

    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        checkOpen()
        when (source) {
            is OkioBuffer -> delegate.write(source.delegate, byteCount)
            else -> delegate.write(source.readByteArray(byteCount))
        }
    }

    override fun flush() {
        checkOpen()
        delegate.flush()
    }

    override fun close() {
        if (closedFlag.compareAndSet(false, true)) {
            delegate.close()
        }
    }

    override fun write(source: ByteArray): BufferedSink {
        checkOpen()
        delegate.write(source)
        return this
    }

    override fun write(source: ByteArray, offset: Int, byteCount: Int): BufferedSink {
        checkOpen()
        delegate.write(source, offset, byteCount)
        return this
    }

    override fun writeAll(source: Source): Long {
        checkOpen()
        return writeAllInto(delegate, source)
    }

    override fun writeUtf8(string: String): BufferedSink {
        checkOpen()
        delegate.writeUtf8(string)
        return this
    }

    override fun writeUtf8(string: String, beginIndex: Int, endIndex: Int): BufferedSink {
        checkOpen()
        delegate.writeUtf8(string, beginIndex, endIndex)
        return this
    }

    override fun writeString(string: String, charset: Charset): BufferedSink {
        checkOpen()
        delegate.writeString(string, charset)
        return this
    }

    override fun outputStream(): OutputStream {
        checkOpen()
        return delegate.outputStream()
    }

    override fun emit(): BufferedSink {
        checkOpen()
        delegate.emit()
        return this
    }
}
