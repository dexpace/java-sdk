/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

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

    // Guard-clause read: each early `return` covers a distinct EOF/short-read condition
    // (zero requested, offset unresolved, slice exhausted, non-Okio sink empty). Collapsing
    // would obscure the contract these guards encode.
    @Suppress("ReturnCount")
    @Throws(IOException::class)
    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        checkOpen()
        if (byteCount == 0L) return 0L
        if (!realizeOffset()) return -1L
        if (atEnd()) return -1L
        val cap = minOf(byteCount, remaining)
        val transferred =
            if (sink is OkioBuffer) {
                peeked.read(sink.delegate, cap)
            } else {
                // Pull a chunk from the upstream source into the peek view's buffer before
                // consulting its size; without this the buffer can be empty and each call would
                // read only a single byte (the old coerceAtLeast(1L) approach).
                peeked.request(minOf(cap, SEGMENT_SIZE))
                val available = minOf(cap, peeked.buffer.size)
                if (available == 0L) return -1L
                val bytes = peeked.readByteArray(available)
                sink.write(bytes)
                bytes.size.toLong()
            }
        if (transferred > 0L) remaining -= transferred
        return transferred
    }

    @Throws(IOException::class)
    override fun exhausted(): Boolean {
        checkOpen()
        return !realizeOffset() || atEnd()
    }

    @Throws(IOException::class)
    override fun readByte(): Byte {
        checkOpen()
        if (!realizeOffset() || remaining == 0L) throw EOFException()
        val byte = peeked.readByte()
        remaining -= 1
        return byte
    }

    @Throws(IOException::class)
    override fun readByteArray(): ByteArray {
        checkOpen()
        if (!realizeOffset() || atEnd()) return EMPTY_BYTES
        val bytes = readUpTo(remaining)
        remaining -= bytes.size
        return bytes
    }

    @Throws(IOException::class)
    override fun readByteArray(byteCount: Long): ByteArray {
        require(byteCount >= 0) { "byteCount must be non-negative (got $byteCount)" }
        require(byteCount <= Buffer.MAX_BYTE_ARRAY_SIZE) {
            "byteCount $byteCount exceeds MAX_BYTE_ARRAY_SIZE; stream via read(Buffer, Long) or inputStream() instead"
        }
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

    @Throws(IOException::class)
    override fun readUtf8(): String {
        checkOpen()
        if (!realizeOffset() || atEnd()) return ""
        val bytes = readUpTo(remaining)
        remaining -= bytes.size
        return String(bytes, Charsets.UTF_8)
    }

    @Throws(IOException::class)
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

    @Throws(IOException::class)
    override fun readUtf8Line(): String? {
        checkOpen()
        if (!realizeOffset() || atEnd()) return null
        // Hand-roll the line search so we don't over-read the peek view: scan byte-by-byte
        // within `remaining` until `\n`, accumulating into a small okio.Buffer. Delegating to
        // peek.readUtf8Line() could read past the slice window.
        //
        // To honor `\r\n`, defer writing a `\r` until we know the next byte isn't `\n`.
        val accumulator = okio.Buffer()
        var foundTerminator = false
        var pendingCr = false
        val newline = '\n'.code.toByte()
        val carriageReturn = '\r'.code.toByte()
        while (remaining > 0L && !peeked.exhausted()) {
            val byte = peeked.readByte()
            remaining -= 1
            if (byte == newline) {
                // pendingCr (if any) is intentionally dropped — it was the leading half of `\r\n`.
                foundTerminator = true
                break
            }
            // Any non-`\n` flushes a pending `\r` first; the prior byte was a literal CR.
            if (pendingCr) accumulator.writeByte(carriageReturn.toInt())
            pendingCr = (byte == carriageReturn)
            if (!pendingCr) accumulator.writeByte(byte.toInt())
        }
        // A trailing `\r` that never met a `\n` is still part of the line.
        if (pendingCr && !foundTerminator) accumulator.writeByte(carriageReturn.toInt())
        if (!foundTerminator && accumulator.size == 0L) return null
        return accumulator.readUtf8()
    }

    @Throws(IOException::class)
    override fun readString(charset: Charset): String {
        checkOpen()
        if (!realizeOffset() || atEnd()) return ""
        val bytes = readUpTo(remaining)
        remaining -= bytes.size
        return String(bytes, charset)
    }

    @Throws(IOException::class)
    override fun peek(): BufferedSource {
        checkOpen()
        return SlicedOkioBufferedSource(peeked.peek(), remaining, pendingSkip, parentClosed)
    }

    @Throws(IOException::class)
    override fun inputStream(): InputStream {
        checkOpen()
        return SliceInputStream(this)
    }

    @Throws(IOException::class)
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

    @Throws(IOException::class)
    override fun slice(
        offset: Long,
        byteCount: Long,
    ): BufferedSource {
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
        private fun checkOpen() {
            if (slice.closed) throw IOException("slice closed")
            if (slice.parentClosed.get()) throw IOException("Parent source closed")
        }

        override fun read(): Int {
            checkOpen()
            if (!slice.realizeOffset() || slice.atEnd()) return -1
            val byte = slice.peeked.readByte().toInt() and UNSIGNED_BYTE_MASK
            slice.remaining -= 1
            return byte
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            checkOpen()
            if (len == 0) return 0
            if (!slice.realizeOffset() || slice.atEnd()) return -1
            val want = minOf(len.toLong(), slice.remaining)
            val staging = okio.Buffer()
            if (slice.peeked.read(staging, want) <= 0L) return -1
            val bytes = staging.readByteArray()
            System.arraycopy(bytes, 0, b, off, bytes.size)
            slice.remaining -= bytes.size
            return bytes.size
        }

        override fun close() {
            slice.close()
        }
    }

    private companion object {
        private val EMPTY_BYTES = ByteArray(0)
        private const val SEGMENT_SIZE: Long = 8192L

        // Mask for converting a signed byte to an unsigned int (0..255), per the
        // `java.io.InputStream.read()` contract.
        private const val UNSIGNED_BYTE_MASK = 0xFF
    }
}
