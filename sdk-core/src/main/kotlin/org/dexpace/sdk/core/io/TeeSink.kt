package org.dexpace.sdk.core.io

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * A [BufferedSink] that mirrors every byte written through it into a [Buffer] (the "tap")
 * while still forwarding to a primary sink.
 *
 * Used by `LoggableRequestBody` to capture request bytes for body logging without breaking
 * the streaming write to the transport sink.
 *
 * ## Thread-safety
 *
 * Not thread-safe. A single in-flight request writes through one [TeeSink] from one thread.
 *
 * ## Cancellation
 *
 * Forwards to [primary] without adding its own interrupt handling; the wrapped primary sink
 * is responsible for honoring `Thread.interrupt()` per the SDK's blocking-call contract.
 *
 * ## Performance
 *
 * Typed writes (`writeUtf8`, `writeString`, `write(ByteArray)`) are encoded **once** into a
 * reused internal scratch buffer, then:
 *
 * 1. `scratch.copyTo(tap)` — non-destructive copy. On an Okio-backed [Buffer] this shares
 *    segments by reference count; near-zero byte copying.
 * 2. `primary.write(scratch, scratch.size)` — destructive drain. On Okio this moves segments
 *    by ownership transfer.
 *
 * The result is one encoding step plus two segment-level operations, regardless of payload
 * size. The previous implementation encoded each string twice and allocated a fresh
 * `ByteArray` per `writeAll` chunk.
 */
internal class TeeSink(
    private val primary: BufferedSink,
    private val tap: Buffer,
    private val provider: IoProvider,
) : BufferedSink {

    /** Reusable staging area — keeps every typed write to a single encode + segment-move. */
    private val scratch: Buffer = provider.buffer()

    /**
     * Direct buffer access is unsupported on a `TeeSink`: writes into the buffer would only
     * reach the tap, never the primary sink, silently corrupting the wire body. Use the
     * typed `write*` methods instead — they tee through `drainScratch` and reach both sinks.
     */
    override val buffer: Buffer
        get() = throw UnsupportedOperationException(
            "TeeSink does not expose a backing buffer; use the typed write methods so " +
                "bytes reach both the primary sink and the tap.",
        )

    @Throws(IOException::class)
    override fun write(source: Buffer, byteCount: Long) {
        // Mirror the prefix into tap (non-destructive), then drain into primary (destructive).
        source.copyTo(tap, 0, byteCount)
        primary.write(source, byteCount)
    }

    @Throws(IOException::class)
    override fun flush() {
        primary.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        primary.close()
    }

    @Throws(IOException::class)
    override fun write(source: ByteArray): BufferedSink {
        scratch.write(source)
        drainScratch()
        return this
    }

    @Throws(IOException::class)
    override fun write(source: ByteArray, offset: Int, byteCount: Int): BufferedSink {
        scratch.write(source, offset, byteCount)
        drainScratch()
        return this
    }

    @Throws(IOException::class)
    override fun writeAll(source: Source): Long {
        var total = 0L
        while (true) {
            val read = source.read(scratch, SCRATCH_BYTES)
            // Guard against a Source returning 0 for a nonzero request — without this the
            // loop would spin forever. We treat 0 as the end of the pump.
            if (read <= 0L) break
            drainScratch()
            total += read
        }
        return total
    }

    @Throws(IOException::class)
    override fun writeUtf8(string: String): BufferedSink {
        scratch.writeUtf8(string)
        drainScratch()
        return this
    }

    @Throws(IOException::class)
    override fun writeUtf8(string: String, beginIndex: Int, endIndex: Int): BufferedSink {
        scratch.writeUtf8(string, beginIndex, endIndex)
        drainScratch()
        return this
    }

    @Throws(IOException::class)
    override fun writeString(string: String, charset: Charset): BufferedSink {
        scratch.writeString(string, charset)
        drainScratch()
        return this
    }

    @Throws(IOException::class)
    override fun outputStream(): OutputStream {
        val primaryStream = primary.outputStream()
        val tapStream = tap.outputStream()
        return object : OutputStream() {
            override fun write(b: Int) {
                primaryStream.write(b)
                tapStream.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                primaryStream.write(b, off, len)
                tapStream.write(b, off, len)
            }

            override fun flush() {
                primaryStream.flush()
                tapStream.flush()
            }

            override fun close() {
                try {
                    primaryStream.close()
                } finally {
                    tapStream.close()
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun emit(): BufferedSink {
        primary.emit()
        return this
    }

    /**
     * Empties [scratch]: copies all currently-staged bytes into [tap] (non-destructive on
     * scratch) then drains them into [primary] (destructive on scratch). Scratch is empty
     * on return — including on failure paths, so a primary-side exception doesn't leave
     * stale bytes that would corrupt a subsequent typed write.
     */
    @Throws(IOException::class)
    private fun drainScratch() {
        val staged = scratch.size
        if (staged == 0L) return
        try {
            scratch.copyTo(tap, 0, staged)
            primary.write(scratch, staged)
        } finally {
            // Ensure scratch is empty even if the copy/write threw, otherwise the next
            // typed write would prepend leftover bytes from the failed attempt.
            if (scratch.size > 0L) scratch.clear()
        }
    }

    private companion object {
        /** Per-chunk pump size for [writeAll] — matches Okio's segment size to favor the fast path. */
        const val SCRATCH_BYTES: Long = 8 * 1024
    }
}
