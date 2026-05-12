package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Length-bounded, non-consuming view over an [okio.BufferedSource] obtained from
 * [okio.BufferedSource.peek]. Caps every read at [maxBytes] bytes total. The [pendingSkip] is
 * applied on first read so that an `offset > parent.size` surfaces as EOF on read instead of
 * at construction — matching the lazy-detection contract of [BufferedSource.slice].
 *
 * [parentClosed] is a flag owned by the root source; when the parent closes, every slice
 * derived from it (transitively) starts throwing [IllegalStateException] on read. okio's
 * `peek()` shares segments with the parent's buffer, so the peek view itself does not notice
 * parent closure — we have to detect it ourselves.
 *
 * Closing this view does NOT close [peeked] nor the original parent — the peek view shares
 * segments with the parent, so eagerly closing it would not free anything and could surprise
 * sibling slices. Reads after [close] throw [IllegalStateException].
 *
 * ## Thread-safety
 *
 * Not safe for concurrent use. Sibling slices may run on different threads, but each
 * individual slice is single-threaded; [parentClosed] is atomic only to make a parent's
 * `close()` visible across threads.
 */
internal class SlicedOkioBufferedSource(
    private val peeked: okio.BufferedSource,
    maxBytes: Long,
    pendingSkip: Long = 0L,
    private val parentClosed: AtomicBoolean,
) : BufferedSource {

    private var remaining: Long = maxBytes
    private var pendingSkip: Long = pendingSkip
    private var closed: Boolean = false

    override val buffer: Buffer by lazy(LazyThreadSafetyMode.NONE) { OkioBuffer(peeked.buffer) }

    /** Throws [IllegalStateException] if this slice has been closed or its parent has been closed. */
    private fun checkOpen() {
        check(!closed) { "slice closed" }
        check(!parentClosed.get()) { "Parent source closed" }
    }

    /**
     * Consumes [pendingSkip] from the peek view. Returns `true` if the offset was reached and
     * the slice has bytes remaining to read; `false` if the underlying source ran out before
     * the offset was satisfied (caller treats this as EOF).
     */
    @Throws(IOException::class)
    private fun realizeOffset(): Boolean {
        if (pendingSkip > 0L) {
            // Drain instead of `skip()` so we don't throw eagerly — `skip(n)` requires `n`
            // bytes and would throw EOFException, but the slice contract says EOF on read.
            val sink = okio.Buffer()
            while (pendingSkip > 0L && !peeked.exhausted()) {
                val got = peeked.read(sink, pendingSkip)
                if (got <= 0L) break
                pendingSkip -= got
                sink.clear()
            }
            if (pendingSkip > 0L) {
                pendingSkip = 0L
                remaining = 0L
                return false
            }
        }
        return remaining > 0L
    }

    /** True when the slice's byte budget is spent or the underlying peek view is exhausted. */
    @Throws(IOException::class)
    private fun atEnd(): Boolean = remaining == 0L || peeked.exhausted()

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        checkOpen()
        if (byteCount == 0L) return 0L
        if (!realizeOffset()) return -1L
        if (atEnd()) return -1L
        val cap = minOf(byteCount, remaining)
        val n = when (sink) {
            is OkioBuffer -> peeked.read(sink.delegate, cap)
            else -> {
                val available = minOf(cap, peeked.buffer.size.coerceAtLeast(1L))
                val bytes = peeked.readByteArray(available)
                sink.write(bytes)
                bytes.size.toLong()
            }
        }
        if (n > 0L) remaining -= n
        return n
    }

    override fun exhausted(): Boolean {
        checkOpen()
        if (!realizeOffset()) return true
        return atEnd()
    }

    override fun readByte(): Byte {
        checkOpen()
        if (!realizeOffset()) throw EOFException()
        if (remaining == 0L) throw EOFException()
        val b = peeked.readByte()
        remaining -= 1
        return b
    }

    override fun readByteArray(): ByteArray {
        checkOpen()
        if (!realizeOffset()) return EMPTY_BYTES
        if (atEnd()) return EMPTY_BYTES
        val bytes = readUpTo(remaining)
        remaining -= bytes.size
        return bytes
    }

    override fun readByteArray(byteCount: Long): ByteArray {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        checkOpen()
        if (!realizeOffset()) {
            if (byteCount == 0L) return EMPTY_BYTES
            throw EOFException()
        }
        if (byteCount > remaining) throw EOFException()
        val bytes = peeked.readByteArray(byteCount)
        remaining -= bytes.size
        return bytes
    }

    override fun readUtf8(): String {
        checkOpen()
        if (!realizeOffset()) return ""
        if (atEnd()) return ""
        val bytes = readUpTo(remaining)
        remaining -= bytes.size
        return String(bytes, Charsets.UTF_8)
    }

    override fun readUtf8(byteCount: Long): String {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        checkOpen()
        if (!realizeOffset()) {
            if (byteCount == 0L) return ""
            throw EOFException()
        }
        if (byteCount > remaining) throw EOFException()
        val s = peeked.readUtf8(byteCount)
        remaining -= byteCount
        return s
    }

    override fun readUtf8Line(): String? {
        checkOpen()
        if (!realizeOffset()) return null
        if (atEnd()) return null
        // Hand-roll the line search so we don't over-read the peek view: scan byte-by-byte
        // within `remaining` until `\n`, accumulating into a small okio.Buffer. Without this,
        // delegating to peek.readUtf8Line() could read past the slice window.
        val sink = okio.Buffer()
        var foundTerminator = false
        var trailingCr = false
        while (remaining > 0L && !peeked.exhausted()) {
            val b = peeked.readByte()
            remaining -= 1
            if (b == '\n'.code.toByte()) {
                foundTerminator = true
                // If the previous byte we wrote was '\r', drop it from the sink so the
                // returned line excludes the CR.
                if (trailingCr) {
                    val withCr = sink.readByteArray()
                    sink.clear()
                    sink.write(withCr, 0, withCr.size - 1)
                }
                break
            }
            trailingCr = (b == '\r'.code.toByte())
            sink.writeByte(b.toInt())
        }
        if (!foundTerminator && sink.size == 0L) return null
        return sink.readUtf8()
    }

    override fun readString(charset: Charset): String {
        checkOpen()
        if (!realizeOffset()) return ""
        if (atEnd()) return ""
        val bytes = readUpTo(remaining)
        remaining -= bytes.size
        return String(bytes, charset)
    }

    override fun peek(): BufferedSource {
        checkOpen()
        return SlicedOkioBufferedSource(peeked.peek(), remaining, pendingSkip, parentClosed)
    }

    override fun inputStream(): InputStream {
        checkOpen()
        return SliceInputStream(this)
    }

    override fun skip(byteCount: Long) {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        checkOpen()
        if (!realizeOffset()) {
            if (byteCount == 0L) return
            throw EOFException()
        }
        if (byteCount > remaining) throw EOFException()
        peeked.skip(byteCount)
        remaining -= byteCount
    }

    override fun slice(offset: Long, byteCount: Long): BufferedSource {
        require(offset >= 0) { "offset must be non-negative (got $offset)" }
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        checkOpen()
        // The inner slice composes our pending offset with its own, and is bounded by
        // whatever bytes are still within our window after the offset.
        val combinedSkip = pendingSkip + offset
        val available = (remaining - offset).coerceAtLeast(0L)
        val cap = minOf(byteCount, available)
        return SlicedOkioBufferedSource(peeked.peek(), cap, combinedSkip, parentClosed)
    }

    override fun close() {
        closed = true
    }

    /**
     * Reads up to [max] bytes from [peeked] without throwing on EOF — returns whatever is
     * actually available, possibly an empty array.
     */
    private fun readUpTo(max: Long): ByteArray {
        if (max <= 0L) return EMPTY_BYTES
        val limit = Buffer.MAX_BYTE_ARRAY_SIZE.toLong()
        check(max <= limit) {
            "Requested read of $max bytes exceeds maximum ByteArray size ($limit). " +
                "Stream via inputStream() or read(Buffer, Long) instead."
        }
        val sink = okio.Buffer()
        var collected = 0L
        while (collected < max && !peeked.exhausted()) {
            val want = max - collected
            val got = peeked.read(sink, want)
            if (got <= 0L) break
            collected += got
        }
        return sink.readByteArray()
    }

    /** Bridges [BufferedSource.inputStream] for slices without exposing the peek view. */
    private class SliceInputStream(private val slice: SlicedOkioBufferedSource) : InputStream() {
        override fun read(): Int {
            if (slice.closed) throw IOException("slice closed")
            if (slice.parentClosed.get()) throw IOException("Parent source closed")
            if (!slice.realizeOffset()) return -1
            if (slice.remaining == 0L || slice.peeked.exhausted()) return -1
            val b = slice.peeked.readByte().toInt() and 0xFF
            slice.remaining -= 1
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (slice.closed) throw IOException("slice closed")
            if (slice.parentClosed.get()) throw IOException("Parent source closed")
            if (len == 0) return 0
            if (!slice.realizeOffset()) return -1
            if (slice.remaining == 0L || slice.peeked.exhausted()) return -1
            val want = minOf(len.toLong(), slice.remaining)
            val tmp = okio.Buffer()
            val n = slice.peeked.read(tmp, want)
            if (n <= 0L) return -1
            val bytes = tmp.readByteArray()
            System.arraycopy(bytes, 0, b, off, bytes.size)
            slice.remaining -= bytes.size
            return bytes.size
        }

        override fun close() {
            slice.close()
        }
    }

    private companion object {
        val EMPTY_BYTES = ByteArray(0)
    }
}
