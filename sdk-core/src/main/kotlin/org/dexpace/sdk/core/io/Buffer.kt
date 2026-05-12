package org.dexpace.sdk.core.io

import java.io.IOException

/**
 * An in-memory queue of bytes that is simultaneously a [BufferedSource] and a [BufferedSink].
 *
 * Bytes written through the sink end of the buffer come out the source end in FIFO order.
 * Buffers are the canonical staging area for body-logging snapshots and for adapter-internal
 * staging between transport streams and typed read/write calls.
 *
 * Instances are obtained from [IoProvider.buffer] — implementations live in adapter modules.
 *
 * ## Thread-safety
 *
 * Buffers are not thread-safe; serialize external access if shared. Adapters may use lock-free
 * structures internally, but the public surface assumes single-threaded use.
 */
interface Buffer : BufferedSource, BufferedSink {
    /** The number of bytes currently held. */
    val size: Long

    /**
     * Returns an immutable byte-array copy of the buffer's contents. Buffer is unchanged.
     *
     * Throws [IllegalStateException] when [size] exceeds [MAX_BYTE_ARRAY_SIZE]. For larger
     * buffers, callers should stream via [BufferedSource.inputStream] or [copyTo] instead.
     */
    fun snapshot(): ByteArray

    /** Discards every byte. */
    fun clear()

    /**
     * Copies [byteCount] bytes starting at [offset] into [out]. This buffer is unchanged.
     *
     * Defaults to copying from [offset] to the end of the buffer (`byteCount = size - offset`).
     *
     * @throws IndexOutOfBoundsException if [offset] is negative, [byteCount] is negative, or
     *         `offset + byteCount` exceeds [size].
     */
    @Throws(IOException::class)
    fun copyTo(out: Buffer, offset: Long = 0, byteCount: Long = size - offset): Buffer

    override val buffer: Buffer get() = this

    companion object {
        /**
         * The maximum number of bytes that can be safely returned as a single [ByteArray] on
         * the JVM. Hotspot reserves a few words for the array header; this matches the
         * effective limit used by `ByteArrayOutputStream` and similar JDK collections.
         */
        const val MAX_BYTE_ARRAY_SIZE: Int = Int.MAX_VALUE - 8
    }
}
