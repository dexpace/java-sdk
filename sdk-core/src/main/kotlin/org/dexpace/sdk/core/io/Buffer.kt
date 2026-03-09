package org.dexpace.sdk.core.io

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * An in-memory byte buffer backed by a circular doubly-linked list of [Segment]s.
 *
 * Buffer is the central type of the I/O system. It implements both [BufferedSource] and
 * [BufferedSink], acting as an infinitely growable queue of bytes: writes append to the
 * tail segment and reads consume from the head segment.
 *
 * ## Efficiency
 *
 * - **Fast allocation**: Segments are obtained from [SegmentPool], avoiding fresh 8 KiB arrays.
 * - **Fast resize**: Capacity changes by linking/unlinking segments, not by copying arrays.
 * - **Fast move**: [write(Buffer, Long)][write] transfers segment ownership between buffers —
 *   no bytes are copied when entire segments can be moved.
 * - **Fast copy**: [copy] and [snapshot] create shared segments that reference the same byte
 *   arrays as the original, avoiding data duplication.
 *
 * ## Thread safety
 *
 * Buffer is **not** thread-safe. External synchronization is required for concurrent access.
 *
 * @see Source
 * @see Sink
 */
@Suppress("unused", "duplicates")
class Buffer : BufferedSource, BufferedSink, Cloneable {

    /** The head of the circular doubly-linked segment list, or `null` if the buffer is empty. */
    internal var head: Segment? = null

    /** The total number of readable bytes in this buffer. */
    var size: Long = 0L
        internal set

    /** Returns this buffer. Buffer is its own buffer since it implements both source and sink. */
    override val buffer: Buffer get() = this

    // ========================================================================
    // BufferedSink — write operations
    // ========================================================================

    /** Writes the low-order byte of [b] to the tail segment. */
    override fun writeByte(b: Int): Buffer {
        val tail = writableSegment(1)
        tail.data[tail.limit++] = b.toByte()
        size += 1
        return this
    }

    /** Writes two bytes in big-endian order from the low 16 bits of [s]. */
    override fun writeShort(s: Int): Buffer {
        val tail = writableSegment(2)
        val data = tail.data
        var limit = tail.limit
        data[limit++] = (s ushr 8 and 0xFF).toByte()
        data[limit++] = (s and 0xFF).toByte()
        tail.limit = limit
        size += 2
        return this
    }

    /** Writes two bytes in little-endian order from the low 16 bits of [s]. */
    override fun writeShortLe(s: Int): Buffer {
        return writeShort(Integer.reverseBytes(s shl 16))
    }

    /** Writes four bytes in big-endian order from [i]. */
    override fun writeInt(i: Int): Buffer {
        val tail = writableSegment(4)
        val data = tail.data
        var limit = tail.limit
        data[limit++] = (i ushr 24 and 0xFF).toByte()
        data[limit++] = (i ushr 16 and 0xFF).toByte()
        data[limit++] = (i ushr 8 and 0xFF).toByte()
        data[limit++] = (i and 0xFF).toByte()
        tail.limit = limit
        size += 4
        return this
    }

    /** Writes four bytes in little-endian order from [i]. */
    override fun writeIntLe(i: Int): Buffer {
        return writeInt(Integer.reverseBytes(i))
    }

    /** Writes eight bytes in big-endian order from [v]. */
    override fun writeLong(v: Long): Buffer {
        val tail = writableSegment(8)
        val data = tail.data
        var limit = tail.limit
        data[limit++] = (v ushr 56 and 0xFF).toByte()
        data[limit++] = (v ushr 48 and 0xFF).toByte()
        data[limit++] = (v ushr 40 and 0xFF).toByte()
        data[limit++] = (v ushr 32 and 0xFF).toByte()
        data[limit++] = (v ushr 24 and 0xFF).toByte()
        data[limit++] = (v ushr 16 and 0xFF).toByte()
        data[limit++] = (v ushr 8 and 0xFF).toByte()
        data[limit++] = (v and 0xFF).toByte()
        tail.limit = limit
        size += 8
        return this
    }

    /** Writes eight bytes in little-endian order from [v]. */
    override fun writeLongLe(v: Long): Buffer {
        return writeLong(java.lang.Long.reverseBytes(v))
    }

    /** Writes all bytes from [source] into this buffer. */
    override fun write(source: ByteArray): Buffer {
        return write(source, 0, source.size)
    }

    /**
     * Writes [byteCount] bytes from [source] starting at [offset] into this buffer.
     *
     * Bytes are copied into tail segments, allocating new segments as needed.
     */
    override fun write(source: ByteArray, offset: Int, byteCount: Int): Buffer {
        checkOffsetAndCount(source.size.toLong(), offset.toLong(), byteCount.toLong())
        var currentOffset = offset
        val limit = offset + byteCount
        while (currentOffset < limit) {
            val tail = writableSegment(1)
            val toCopy = minOf(limit - currentOffset, Segment.SIZE - tail.limit)
            source.copyInto(tail.data, tail.limit, currentOffset, currentOffset + toCopy)
            currentOffset += toCopy
            tail.limit += toCopy
        }
        size += byteCount
        return this
    }

    /**
     * Pulls exactly [byteCount] bytes from [source] into this buffer.
     *
     * @throws EOFException if [source] is exhausted before [byteCount] bytes are read.
     */
    override fun write(source: Source, byteCount: Long): Buffer {
        var remaining = byteCount
        while (remaining > 0L) {
            val read = source.read(this, remaining)
            if (read == -1L) throw EOFException("source exhausted before reading $byteCount bytes")
            remaining -= read
        }
        return this
    }

    /**
     * Drains all bytes from [source] into this buffer.
     *
     * @return the total number of bytes read.
     */
    override fun writeAll(source: Source): Long {
        var totalBytesRead = 0L
        while (true) {
            val readCount = source.read(this, Segment.SIZE.toLong())
            if (readCount == -1L) break
            totalBytesRead += readCount
        }
        return totalBytesRead
    }

    /** Encodes [string] as UTF-8 and writes the bytes to this buffer. */
    override fun writeUtf8(string: String): Buffer {
        return writeUtf8(string, 0, string.length)
    }

    /** Encodes the substring `string[beginIndex, endIndex)` as UTF-8 and writes it. */
    override fun writeUtf8(string: String, beginIndex: Int, endIndex: Int): Buffer {
        val bytes = string.substring(beginIndex, endIndex).toByteArray(Charsets.UTF_8)
        return write(bytes)
    }

    /** Encodes [string] with [charset] and writes the bytes to this buffer. */
    override fun writeString(string: String, charset: Charset): Buffer {
        return write(string.toByteArray(charset))
    }

    /**
     * Writes [byteCount] bytes from [source] buffer into this buffer.
     *
     * When possible, entire segments are moved from [source] to this buffer without
     * copying bytes. This is the key performance optimization. For partial segments,
     * [Segment.split] creates a prefix that is detached from [source] and linked into
     * this buffer.
     *
     * @throws IllegalArgumentException if [source] is the same buffer as this (self-write).
     */
    override fun write(source: Buffer, byteCount: Long) {
        require(source !== this) { "source == this" }
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        require(byteCount <= source.size) { "byteCount ($byteCount) > source.size (${source.size})" }
        if (byteCount == 0L) return

        var remaining = byteCount

        while (remaining > 0L) {
            val sourceHead = source.head!!
            val sourceCount = sourceHead.count.toLong()

            if (sourceCount <= remaining) {
                // Move the entire head segment from source to this buffer.
                source.head = sourceHead.pop()
                if (head == null) {
                    // This buffer is empty — adopt the segment as head.
                    head = sourceHead
                    sourceHead.next = sourceHead
                    sourceHead.prev = sourceHead
                } else {
                    // Append to tail.
                    val tail = head!!.prev!!
                    tail.push(sourceHead)
                }
                remaining -= sourceCount
                source.size -= sourceCount
                size += sourceCount
            } else {
                // Partial segment: split off the needed bytes and move.
                val prefix = sourceHead.split(remaining.toInt())
                // Remove prefix from source's circular list before adopting it.
                prefix.pop()
                source.size -= remaining
                // Move the prefix segment to this buffer.
                if (head == null) {
                    head = prefix
                    prefix.next = prefix
                    prefix.prev = prefix
                } else {
                    val tail = head!!.prev!!
                    tail.push(prefix)
                }
                size += remaining
                remaining = 0
            }
        }

        // Compact the tail of this buffer with its predecessor if underutilized.
        val tail = head?.prev
        if (tail != null && tail !== head && tail.count < Segment.SHARE_MINIMUM) {
            tail.compact()
        }
    }

    // ========================================================================
    // BufferedSource — read operations
    // ========================================================================

    /**
     * Removes and returns a single byte from the head segment.
     *
     * @throws EOFException if the buffer is empty.
     */
    override fun readByte(): Byte {
        if (size == 0L) throw EOFException("buffer is empty")
        val segment = head!!
        val b = segment.data[segment.pos++]
        size -= 1
        if (segment.pos == segment.limit) {
            head = segment.pop()
            SegmentPool.recycle(segment)
        }
        return b
    }

    /**
     * Reads two bytes from the head and returns them as a big-endian short.
     *
     * Optimized for the common case where both bytes are in the same segment.
     * Falls back to two [readByte] calls when the short spans a segment boundary.
     *
     * @throws EOFException if fewer than 2 bytes are available.
     */
    override fun readShort(): Short {
        if (size < 2) throw EOFException("size < 2: $size")
        val segment = head!!
        var pos = segment.pos
        val limit = segment.limit

        // If both bytes are in the same segment.
        if (limit - pos >= 2) {
            val data = segment.data
            val s = (data[pos++].toInt() and 0xFF shl 8 or (data[pos++].toInt() and 0xFF))
            size -= 2
            segment.pos = pos
            if (pos == limit) {
                head = segment.pop()
                SegmentPool.recycle(segment)
            }
            return s.toShort()
        }

        return ((readByte().toInt() and 0xFF shl 8) or (readByte().toInt() and 0xFF)).toShort()
    }

    /** Reads two bytes and returns them as a little-endian short. */
    override fun readShortLe(): Short {
        return java.lang.Short.reverseBytes(readShort())
    }

    /**
     * Reads four bytes from the head and returns them as a big-endian int.
     *
     * Optimized for the common case where all four bytes are in the same segment.
     * Falls back to four [readByte] calls when the int spans a segment boundary.
     *
     * @throws EOFException if fewer than 4 bytes are available.
     */
    override fun readInt(): Int {
        if (size < 4) throw EOFException("size < 4: $size")
        val segment = head!!
        var pos = segment.pos
        val limit = segment.limit

        if (limit - pos >= 4) {
            val data = segment.data
            val i = (data[pos++].toInt() and 0xFF shl 24) or
                (data[pos++].toInt() and 0xFF shl 16) or
                (data[pos++].toInt() and 0xFF shl 8) or
                (data[pos++].toInt() and 0xFF)
            size -= 4
            segment.pos = pos
            if (pos == limit) {
                head = segment.pop()
                SegmentPool.recycle(segment)
            }
            return i
        }

        return (readByte().toInt() and 0xFF shl 24) or
            (readByte().toInt() and 0xFF shl 16) or
            (readByte().toInt() and 0xFF shl 8) or
            (readByte().toInt() and 0xFF)
    }

    /** Reads four bytes and returns them as a little-endian int. */
    override fun readIntLe(): Int {
        return Integer.reverseBytes(readInt())
    }

    /**
     * Reads eight bytes from the head and returns them as a big-endian long.
     *
     * Optimized for the common case where all eight bytes are in the same segment.
     * Falls back to eight [readByte] calls when the long spans a segment boundary.
     *
     * @throws EOFException if fewer than 8 bytes are available.
     */
    override fun readLong(): Long {
        if (size < 8) throw EOFException("size < 8: $size")
        val segment = head!!
        var pos = segment.pos
        val limit = segment.limit

        if (limit - pos >= 8) {
            val data = segment.data
            val v = (data[pos++].toLong() and 0xFF shl 56) or
                (data[pos++].toLong() and 0xFF shl 48) or
                (data[pos++].toLong() and 0xFF shl 40) or
                (data[pos++].toLong() and 0xFF shl 32) or
                (data[pos++].toLong() and 0xFF shl 24) or
                (data[pos++].toLong() and 0xFF shl 16) or
                (data[pos++].toLong() and 0xFF shl 8) or
                (data[pos++].toLong() and 0xFF)
            size -= 8
            segment.pos = pos
            if (pos == limit) {
                head = segment.pop()
                SegmentPool.recycle(segment)
            }
            return v
        }

        return (readByte().toLong() and 0xFF shl 56) or
            (readByte().toLong() and 0xFF shl 48) or
            (readByte().toLong() and 0xFF shl 40) or
            (readByte().toLong() and 0xFF shl 32) or
            (readByte().toLong() and 0xFF shl 24) or
            (readByte().toLong() and 0xFF shl 16) or
            (readByte().toLong() and 0xFF shl 8) or
            (readByte().toLong() and 0xFF)
    }

    /** Reads eight bytes and returns them as a little-endian long. */
    override fun readLongLe(): Long {
        return java.lang.Long.reverseBytes(readLong())
    }

    /** Reads and returns all remaining bytes as a byte array. Consumes the entire buffer. */
    override fun readByteArray(): ByteArray {
        return readByteArray(size)
    }

    /**
     * Reads and returns [byteCount] bytes as a byte array.
     *
     * @throws EOFException if fewer than [byteCount] bytes are available.
     */
    override fun readByteArray(byteCount: Long): ByteArray {
        require(byteCount >= 0 && byteCount <= Int.MAX_VALUE) { "byteCount: $byteCount" }
        if (byteCount > size) throw EOFException("size ($size) < byteCount ($byteCount)")

        val result = ByteArray(byteCount.toInt())
        readFully(result)
        return result
    }

    /**
     * Reads up to `sink.size` bytes into [sink].
     *
     * @return the number of bytes read, or -1 if the buffer is empty.
     */
    override fun read(sink: ByteArray): Int {
        return read(sink, 0, sink.size)
    }

    /**
     * Reads up to [byteCount] bytes into [sink] starting at [offset].
     *
     * Only reads from the current head segment — callers should loop for multi-segment reads.
     *
     * @return the number of bytes read, or -1 if the buffer is empty.
     */
    override fun read(sink: ByteArray, offset: Int, byteCount: Int): Int {
        checkOffsetAndCount(sink.size.toLong(), offset.toLong(), byteCount.toLong())
        if (size == 0L) return -1
        val toRead = minOf(byteCount.toLong(), size).toInt()
        val segment = head!!
        val toCopy = minOf(toRead, segment.count)
        segment.data.copyInto(sink, offset, segment.pos, segment.pos + toCopy)
        segment.pos += toCopy
        size -= toCopy
        if (segment.pos == segment.limit) {
            head = segment.pop()
            SegmentPool.recycle(segment)
        }
        return toCopy
    }

    /**
     * Reads exactly `sink.size` bytes into [sink].
     *
     * @throws EOFException if the buffer is exhausted before filling [sink].
     */
    fun readFully(sink: ByteArray) {
        var offset = 0
        while (offset < sink.size) {
            val read = read(sink, offset, sink.size - offset)
            if (read == -1) throw EOFException("buffer exhausted before reading ${sink.size} bytes")
            offset += read
        }
    }

    /** Reads all remaining bytes and decodes them as UTF-8. Consumes the entire buffer. */
    override fun readUtf8(): String {
        return readString(Charsets.UTF_8)
    }

    /** Reads [byteCount] bytes and decodes them as UTF-8. */
    override fun readUtf8(byteCount: Long): String {
        return readString(byteCount, Charsets.UTF_8)
    }

    /**
     * Reads a line of UTF-8 text, consuming the newline (`\n` or `\r\n`).
     *
     * If no newline is found, returns the remaining content (or `null` if empty).
     */
    override fun readUtf8Line(): String? {
        val newline = indexOf('\n'.code.toByte())
        return if (newline != -1L) {
            readUtf8Line(newline)
        } else if (size != 0L) {
            readUtf8(size)
        } else {
            null
        }
    }

    /**
     * Reads a line of UTF-8 text, consuming the newline.
     *
     * @throws EOFException if no newline is found.
     */
    override fun readUtf8LineStrict(): String {
        val newline = indexOf('\n'.code.toByte())
        if (newline == -1L) throw EOFException("\\n not found: size=$size")
        return readUtf8Line(newline)
    }

    /**
     * Reads a UTF-8 line ending at the newline at position [newline].
     *
     * Handles both `\n` and `\r\n` line endings. Shared between [readUtf8Line] and
     * [RealBufferedSource.readUtf8Line].
     *
     * @param newline the buffer position of the `\n` byte.
     * @return the line content without the line terminator.
     */
    internal fun readUtf8Line(newline: Long): String {
        return if (newline > 0 && getByte(newline - 1) == '\r'.code.toByte()) {
            val result = readUtf8(newline - 1)
            skip(2) // skip \r\n
            result
        } else {
            val result = readUtf8(newline)
            skip(1) // skip \n
            result
        }
    }

    /** Reads all remaining bytes and decodes them with [charset]. Consumes the entire buffer. */
    override fun readString(charset: Charset): String {
        return readString(size, charset)
    }

    /**
     * Reads [byteCount] bytes and decodes them with [charset].
     *
     * Optimized for the common case where all requested bytes are in a single segment,
     * avoiding intermediate byte array allocation.
     *
     * @throws EOFException if fewer than [byteCount] bytes are available.
     */
    override fun readString(byteCount: Long, charset: Charset): String {
        require(byteCount >= 0 && byteCount <= Int.MAX_VALUE) { "byteCount: $byteCount" }
        if (byteCount > size) throw EOFException("size ($size) < byteCount ($byteCount)")
        if (byteCount == 0L) return ""

        val s = head!!
        if (s.pos + byteCount.toInt() > s.limit) {
            return String(readByteArray(byteCount), charset)
        }

        val result = String(s.data, s.pos, byteCount.toInt(), charset)
        s.pos += byteCount.toInt()
        size -= byteCount
        if (s.pos == s.limit) {
            head = s.pop()
            SegmentPool.recycle(s)
        }
        return result
    }

    /** Drains all bytes from this buffer into [sink]. Returns the number of bytes written. */
    override fun readAll(sink: Sink): Long {
        val result = size
        if (result > 0L) {
            sink.write(this, result)
        }
        return result
    }

    /**
     * Discards [byteCount] bytes from the head of this buffer.
     *
     * @throws EOFException if the buffer contains fewer than [byteCount] bytes.
     */
    override fun skip(byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val segment = head ?: throw EOFException("buffer exhausted before skipping $byteCount bytes")
            val toSkip = minOf(remaining, segment.count.toLong()).toInt()
            size -= toSkip
            segment.pos += toSkip
            remaining -= toSkip
            if (segment.pos == segment.limit) {
                head = segment.pop()
                SegmentPool.recycle(segment)
            }
        }
    }

    /** Returns the index of the first occurrence of [b], or -1 if not found. */
    override fun indexOf(b: Byte): Long = indexOf(b, 0)

    /**
     * Returns the index of the first occurrence of [b] at or after [fromIndex], or -1 if not found.
     *
     * Uses [seek] to jump to the starting segment, then scans forward linearly.
     */
    override fun indexOf(b: Byte, fromIndex: Long): Long {
        val actualFromIndex = maxOf(fromIndex, 0L)
        if (actualFromIndex >= size) return -1L
        head ?: return -1L

        val (startSegment, startOffset) = seek(actualFromIndex)
        var s = startSegment
        var scanOffset = startOffset

        // Scan forward from the start segment.
        while (true) {
            val data = s.data
            val limit = s.limit
            var pos = s.pos + (maxOf(actualFromIndex, scanOffset) - scanOffset).toInt()
            while (pos < limit) {
                if (data[pos] == b) {
                    return scanOffset + (pos - s.pos)
                }
                pos++
            }
            scanOffset += s.count
            if (scanOffset >= size) return -1L
            s = s.next!!
        }
    }

    /** Returns a non-consuming [BufferedSource] that reads from this buffer without advancing it. */
    override fun peek(): BufferedSource {
        return PeekSource(this).buffered()
    }

    // ========================================================================
    // Source / Sink interface implementations
    // ========================================================================

    /**
     * Moves bytes from this buffer into [sink].
     *
     * This implements the [Source] contract: removes bytes from this buffer and appends
     * them to [sink].
     */
    override fun read(sink: Buffer, byteCount: Long): Long {
        if (size == 0L) return -1L
        val toRead = minOf(byteCount, size)
        sink.write(this, toRead)
        return toRead
    }

    /** No-op. Buffer has no downstream to flush to. */
    override fun flush() {
        // No-op: Buffer has no downstream to flush to.
    }

    /** No-op. Buffer has no downstream to emit to. Returns this buffer for chaining. */
    override fun emit(): Buffer {
        // No-op: Buffer has no downstream.
        return this
    }

    /** No-op. Buffer has no downstream to emit to. Returns this buffer for chaining. */
    override fun emitCompleteSegments(): Buffer {
        // No-op: Buffer has no downstream.
        return this
    }

    /** Clears the buffer, recycling all segments. Equivalent to [clear]. */
    override fun close() {
        clear()
    }

    // ========================================================================
    // BufferedSource convenience
    // ========================================================================

    /** Returns `true` if this buffer contains no bytes. */
    override fun exhausted(): Boolean = size == 0L

    /**
     * Throws [EOFException] if this buffer contains fewer than [byteCount] bytes.
     *
     * Since Buffer is not backed by an upstream source, this simply checks [size].
     */
    override fun require(byteCount: Long) {
        if (size < byteCount) throw EOFException("required $byteCount but only $size available")
    }

    /** Returns `true` if this buffer contains at least [byteCount] bytes. */
    override fun request(byteCount: Long): Boolean = size >= byteCount

    // ========================================================================
    // Buffer-specific operations
    // ========================================================================

    /** Returns the byte at [index] without consuming it. Uses [seek] for segment navigation. */
    fun getByte(index: Long): Byte {
        require(index in 0..size) { "index ($index) out of range [0, $size)" }
        val (s, offset) = seek(index)
        return s.data[s.pos + (index - offset).toInt()]
    }

    /** Clears the buffer, recycling all segments back to [SegmentPool]. */
    fun clear() {
        skip(size)
    }

    /**
     * Returns a copy of this buffer that shares segment data with the original.
     *
     * The copy is a snapshot: modifications to either buffer after this call do not
     * affect the other. The underlying byte arrays are shared (not copied) for efficiency.
     */
    fun copy(): Buffer {
        val result = Buffer()
        if (size == 0L) return result

        val headCopy = head!!.sharedCopy()
        result.head = headCopy
        headCopy.prev = headCopy
        headCopy.next = headCopy

        var s = head!!.next!!
        while (s !== head) {
            headCopy.prev!!.push(s.sharedCopy())
            s = s.next!!
        }

        result.size = size
        return result
    }

    /**
     * Returns a snapshot of the first [byteCount] bytes as a byte array.
     *
     * Unlike [readByteArray], this does **not** consume the bytes. Defaults to the
     * entire buffer content (capped at [Int.MAX_VALUE]).
     */
    fun snapshot(byteCount: Int = size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()): ByteArray {
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        require(byteCount.toLong() <= size) { "byteCount ($byteCount) > size ($size)" }
        if (byteCount == 0) return ByteArray(0)

        val result = ByteArray(byteCount)
        var offset = 0
        var s = head!!
        while (offset < byteCount) {
            val toCopy = minOf(s.count, byteCount - offset)
            s.data.copyInto(result, offset, s.pos, s.pos + toCopy)
            offset += toCopy
            s = s.next!!
        }
        return result
    }

    /**
     * Copies [byteCount] bytes from this buffer starting at [offset] into [out]
     * using shared segments (zero-copy).
     *
     * Does not consume bytes from this buffer.
     */
    fun copyTo(out: Buffer, offset: Long = 0, byteCount: Long = size - offset) {
        require(offset >= 0) { "offset < 0: $offset" }
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        require(offset + byteCount <= size) { "offset + byteCount > size" }
        if (byteCount == 0L) return

        out.size += byteCount

        // Navigate to the segment containing the offset.
        var s = head!!
        var segmentOffset = 0L
        while (segmentOffset + s.count <= offset) {
            segmentOffset += s.count
            s = s.next!!
        }

        // Copy segments.
        var remaining = byteCount
        while (remaining > 0L) {
            val copy = s.sharedCopy()
            val posAdjust = (offset + (byteCount - remaining) - segmentOffset).toInt()
            copy.pos += posAdjust
            // Clamp limit: don't read more than remaining bytes from this segment.
            val maxLimit = copy.pos + minOf(remaining, (copy.limit - copy.pos).toLong()).toInt()
            copy.limit = minOf(maxLimit, copy.limit)
            if (out.head == null) {
                out.head = copy
                copy.prev = copy
                copy.next = copy
            } else {
                out.head!!.prev!!.push(copy)
            }
            remaining -= copy.count
            segmentOffset += s.count
            s = s.next!!
        }
    }

    /**
     * Returns the number of bytes in complete (full) segments.
     *
     * This is useful for [RealBufferedSink.emitCompleteSegments]: it tells us how many
     * bytes can be flushed without splitting a partial segment.
     */
    fun completeSegmentByteCount(): Long {
        var result = size
        if (result == 0L) return 0L
        // The tail segment may be incomplete.
        val tail = head!!.prev!!
        if (tail.limit < Segment.SIZE && tail.owner) {
            result -= tail.count
        }
        return result
    }

    /**
     * Iterates over this buffer's segments, calling [action] for each one.
     *
     * Each invocation receives the segment's byte array, the start position, and
     * the byte count. The buffer is not modified.
     */
    fun forEach(action: (data: ByteArray, pos: Int, count: Int) -> Unit) {
        val h = head ?: return
        var s = h
        do {
            action(s.data, s.pos, s.count)
            s = s.next!!
        } while (s !== h)
    }

    // ========================================================================
    // java.io bridge
    // ========================================================================

    /**
     * Returns a consuming [InputStream] that reads from this buffer.
     *
     * Each read advances the buffer's head, removing bytes. Closing the stream
     * does **not** clear the buffer — the caller may still need the remaining content.
     */
    override fun inputStream(): InputStream {
        return object : InputStream() {
            /** Reads a single byte, or returns -1 if the buffer is empty. */
            override fun read(): Int {
                if (size == 0L) return -1
                return this@Buffer.readByte().toInt() and 0xFF
            }

            /** Reads up to [byteCount] bytes into [sink] at [offset]. */
            override fun read(sink: ByteArray, offset: Int, byteCount: Int): Int {
                return this@Buffer.read(sink, offset, byteCount)
            }

            /** Returns the number of bytes currently readable without blocking. */
            override fun available(): Int {
                return minOf(size, Int.MAX_VALUE.toLong()).toInt()
            }

            /** No-op. Does not clear the buffer — the caller may still need it. */
            override fun close() {
                // Don't clear the buffer — the caller may still need it.
            }

            override fun toString(): String = "${this@Buffer}.inputStream()"
        }
    }

    /**
     * Returns an [OutputStream] that writes into this buffer.
     *
     * Each write appends bytes to the buffer's tail. Closing the stream does **not**
     * clear the buffer — the caller may still need to read the written content.
     */
    override fun outputStream(): OutputStream {
        return object : OutputStream() {
            /** Writes a single byte to the buffer. */
            override fun write(b: Int) {
                this@Buffer.writeByte(b)
            }

            /** Writes [byteCount] bytes from [data] at [offset] to the buffer. */
            override fun write(data: ByteArray, offset: Int, byteCount: Int) {
                this@Buffer.write(data, offset, byteCount)
            }

            /** No-op. Buffer has no downstream to flush to. */
            override fun flush() {
                // No-op: Buffer has no downstream.
            }

            /** No-op. Does not clear the buffer — the caller may still need it. */
            override fun close() {
                // Don't clear the buffer — the caller may still need it.
            }

            override fun toString(): String = "${this@Buffer}.outputStream()"
        }
    }

    /**
     * Returns a read-only [InputStream] that reads from this buffer without consuming bytes.
     *
     * Each call returns an independent stream with its own read position. The buffer must
     * not be structurally modified (clear, write, read, skip) while any read-only stream
     * is in use.
     *
     * This is ideal for scenarios like response body logging where the buffer is populated
     * once and then read repeatedly.
     */
    fun readOnlyInputStream(): InputStream {
        return object : InputStream() {
            /** The independent read cursor for this stream instance. */
            private var position = 0L

            /** Reads a single byte at [position], or returns -1 if at end. */
            override fun read(): Int {
                if (position >= size) return -1
                val b = getByte(position).toInt() and 0xFF
                position++
                return b
            }

            /**
             * Reads up to [len] bytes into [sink] at [offset] without consuming the buffer.
             *
             * Uses [copyToByteArray] for efficient bulk reads across segment boundaries.
             */
            override fun read(sink: ByteArray, offset: Int, len: Int): Int {
                if (offset < 0 || len < 0 || offset + len > sink.size) {
                    throw IndexOutOfBoundsException("offset=$offset len=$len sink.size=${sink.size}")
                }
                if (len == 0) return 0
                val remaining = size - position
                if (remaining <= 0L) return -1
                val toRead = minOf(len.toLong(), remaining).toInt()

                copyToByteArray(sink, offset, position, toRead)
                position += toRead
                return toRead
            }

            /** Returns the number of bytes remaining from [position] to end of buffer. */
            override fun available(): Int {
                return maxOf(0L, minOf(size - position, Int.MAX_VALUE.toLong())).toInt()
            }

            /** No-op. This stream does not own the buffer. */
            override fun close() {
                // No-op: does not own the buffer.
            }

            override fun toString(): String = "${this@Buffer}.readOnlyInputStream()"
        }
    }

    /**
     * Copies [byteCount] bytes from this buffer starting at [bufferOffset] into [dest]
     * at [destOffset], without consuming any bytes.
     *
     * Uses [seek] to jump to the starting segment, then copies forward across
     * segment boundaries.
     */
    private fun copyToByteArray(dest: ByteArray, destOffset: Int, bufferOffset: Long, byteCount: Int) {
        var remaining = byteCount
        var sinkOffset = destOffset
        var bufPos = bufferOffset

        // Navigate to the segment containing bufPos.
        val (startSegment, segStart) = seek(bufPos)
        var s = startSegment
        var currentSegStart = segStart

        while (remaining > 0) {
            val posInSegment = (bufPos - currentSegStart).toInt()
            val availableInSegment = s.count - posInSegment
            val toCopy = minOf(remaining, availableInSegment)
            s.data.copyInto(dest, sinkOffset, s.pos + posInSegment, s.pos + posInSegment + toCopy)
            sinkOffset += toCopy
            bufPos += toCopy
            remaining -= toCopy
            if (remaining > 0) {
                currentSegStart += s.count
                s = s.next!!
            }
        }
    }

    /**
     * Reads all bytes from [input] and writes them to this buffer.
     *
     * Reads directly into tail segments for efficiency, recycling empty segments on EOF.
     *
     * @return the number of bytes read.
     */
    @Throws(IOException::class)
    fun readFrom(input: InputStream): Long {
        var total = 0L
        while (true) {
            val tail = writableSegment(1)
            val maxRead = Segment.SIZE - tail.limit
            val bytesRead = input.read(tail.data, tail.limit, maxRead)
            if (bytesRead == -1) {
                if (tail.pos == tail.limit) {
                    // The segment is empty — remove and recycle it.
                    head = tail.pop()
                    SegmentPool.recycle(tail)
                }
                return total
            }
            tail.limit += bytesRead
            size += bytesRead
            total += bytesRead
        }
    }

    /**
     * Reads exactly [byteCount] bytes from [input] into this buffer.
     *
     * @throws EOFException if the stream is exhausted before [byteCount] bytes are read.
     */
    @Throws(IOException::class)
    fun readFrom(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val tail = writableSegment(1)
            val maxRead = minOf(remaining, (Segment.SIZE - tail.limit).toLong()).toInt()
            val bytesRead = input.read(tail.data, tail.limit, maxRead)
            if (bytesRead == -1) throw EOFException("expected $byteCount but got ${byteCount - remaining}")
            tail.limit += bytesRead
            size += bytesRead
            remaining -= bytesRead
        }
    }

    /**
     * Writes all bytes from this buffer to [out]. Consumes the entire buffer.
     *
     * @return the number of bytes written.
     */
    @Throws(IOException::class)
    fun writeTo(out: OutputStream): Long {
        return writeTo(out, size)
    }

    /**
     * Writes [byteCount] bytes from this buffer to [out].
     * Consumes those bytes from this buffer, recycling empty segments.
     *
     * @return [byteCount].
     */
    @Throws(IOException::class)
    fun writeTo(out: OutputStream, byteCount: Long): Long {
        var remaining = byteCount
        while (remaining > 0L) {
            val s = head ?: throw EOFException("buffer exhausted")
            val toCopy = minOf(remaining, s.count.toLong()).toInt()
            out.write(s.data, s.pos, toCopy)
            s.pos += toCopy
            remaining -= toCopy
            size -= toCopy
            if (s.pos == s.limit) {
                head = s.pop()
                SegmentPool.recycle(s)
            }
        }
        return byteCount
    }

    // ========================================================================
    // Internal
    // ========================================================================

    /**
     * Finds the segment and base offset that contains the byte at [position].
     *
     * Chooses to scan from head or tail depending on which is closer, matching
     * okio's seek algorithm. This makes random access O(segments/2) on average.
     *
     * @return a pair of (segment, offset-of-segment-start-in-buffer).
     */
    private fun seek(position: Long): Pair<Segment, Long> {
        var s = head!!
        if (size - position < position) {
            // Scan backward from head (wrapping to tail via prev).
            var offset = size
            while (offset > position) {
                s = s.prev!!
                offset -= s.count
            }
            return s to offset
        } else {
            // Scan forward from head.
            var offset = 0L
            while (offset + s.count <= position) {
                offset += s.count
                s = s.next!!
            }
            return s to offset
        }
    }

    /**
     * Returns a tail segment with at least [minimumCapacity] bytes of writable space.
     *
     * If the buffer is empty, allocates a new segment from [SegmentPool] and sets it as head.
     * If the current tail has insufficient space or is not the owner, appends a new segment.
     * Otherwise, returns the existing tail.
     */
    internal fun writableSegment(minimumCapacity: Int): Segment {
        require(minimumCapacity in 1..Segment.SIZE) { "unexpected capacity: $minimumCapacity" }

        if (head == null) {
            val result = SegmentPool.take()
            head = result
            result.prev = result
            result.next = result
            return result
        }

        val tail = head!!.prev!!
        if (tail.limit + minimumCapacity > Segment.SIZE || !tail.owner) {
            val result = SegmentPool.take()
            tail.push(result)
            return result
        }

        return tail
    }

    /** Returns a shallow copy of this buffer via [copy]. */
    override fun clone(): Buffer = copy()

    /** Returns a string representation showing the buffer's byte count. */
    override fun toString(): String {
        return "Buffer(size=$size)"
    }

    /**
     * Content-based equality. Two buffers are equal if they contain the same bytes in
     * the same order, regardless of segment boundaries.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Buffer) return false
        if (size != other.size) return false
        if (size == 0L) return true

        var sa = head!!
        var sb = other.head!!
        var posA = sa.pos
        var posB = sb.pos
        var remaining = size

        while (remaining > 0L) {
            val countA = sa.limit - posA
            val countB = sb.limit - posB
            val toCompare = minOf(countA.toLong(), countB.toLong(), remaining).toInt()

            for (i in 0 until toCompare) {
                if (sa.data[posA + i] != sb.data[posB + i]) return false
            }

            posA += toCompare
            posB += toCompare
            remaining -= toCompare

            if (posA == sa.limit) {
                sa = sa.next!!
                posA = sa.pos
            }
            if (posB == sb.limit) {
                sb = sb.next!!
                posB = sb.pos
            }
        }

        return true
    }

    /**
     * Content-based hash code. Computed by iterating all bytes across all segments
     * with a standard polynomial hash (factor 31).
     */
    override fun hashCode(): Int {
        var h = head ?: return 0
        var result = 1
        do {
            for (i in h.pos until h.limit) {
                result = 31 * result + h.data[i]
            }
            h = h.next!!
        } while (h !== head)
        return result
    }
}

/**
 * Validates that [offset] and [byteCount] are within the bounds of a container with [size] bytes.
 *
 * @throws ArrayIndexOutOfBoundsException if the range is out of bounds or any argument is negative.
 */
internal fun checkOffsetAndCount(size: Long, offset: Long, byteCount: Long) {
    if (offset or byteCount < 0 || offset > size || size - offset < byteCount) {
        throw ArrayIndexOutOfBoundsException("size=$size offset=$offset byteCount=$byteCount")
    }
}
