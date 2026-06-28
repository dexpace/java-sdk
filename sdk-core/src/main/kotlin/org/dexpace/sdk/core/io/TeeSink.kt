/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

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
 *
 * ## Tap cap
 *
 * [tapLimit] bounds how many bytes are mirrored into [tap]. Once that many bytes have been
 * tapped, further writes stop copying into [tap] entirely while still forwarding the **full**
 * payload to [primary] — the wire body is never truncated. The default [tapLimit] is
 * [Long.MAX_VALUE] (unbounded mirroring). `LoggableRequestBody` caps this so a multi-GB upload
 * mirrors only a bounded preview into the tap while streaming zero-copy to the transport.
 */
internal class TeeSink(
    private val primary: BufferedSink,
    private val tap: Buffer,
    private val provider: IoProvider,
    private val tapLimit: Long = Long.MAX_VALUE,
) : BufferedSink {
    /** Reusable staging area — keeps every typed write to a single encode + segment-move. */
    private val scratch: Buffer = provider.buffer()

    /** Bytes mirrored into [tap] so far; mirroring stops once this reaches [tapLimit]. */
    private var mirrored: Long = 0L

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
    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        // Mirror up to the remaining tap budget (non-destructive), then drain the FULL count into
        // primary (destructive) — the wire body is never truncated.
        mirrorPrefix(source, byteCount)
        primary.write(source, byteCount)
    }

    /**
     * Copies the leading bytes of [source] into [tap] up to the remaining [tapLimit] budget,
     * advancing [mirrored]. Once the budget is exhausted this is a no-op. [source] itself is
     * left intact for the subsequent destructive drain into [primary].
     */
    @Throws(IOException::class)
    private fun mirrorPrefix(
        source: Buffer,
        byteCount: Long,
    ) {
        val copy = tapAllowance(byteCount)
        if (copy == 0L) return
        source.copyTo(tap, 0, copy)
        mirrored += copy
    }

    /**
     * Computes how many of [requested] bytes may still be mirrored into [tap]: the smaller of
     * [requested] and the remaining [tapLimit] budget, clamped to never go negative. The actual
     * copy and [mirrored] advancement stay at each call site.
     */
    private fun tapAllowance(requested: Long): Long = minOf(requested, (tapLimit - mirrored).coerceAtLeast(0L))

    @Throws(IOException::class)
    override fun flush() {
        primary.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        primary.close()
    }

    /**
     * Stages one typed write into [scratch] then tees it into the tap and drains it into the
     * primary in a single pass, returning `this` for chaining. [encode] receives [scratch]
     * explicitly as its `it` argument so the staged write always targets the staging [Buffer] —
     * never one of this `TeeSink`'s own `write*` overrides, which a `Buffer.()` receiver lambda
     * could silently rebind to (and self-recurse) if a `Buffer` overload were ever added.
     */
    private inline fun staged(encode: (Buffer) -> Unit): BufferedSink {
        encode(scratch)
        drainScratch()
        return this
    }

    @Throws(IOException::class)
    override fun write(source: ByteArray): BufferedSink = staged { it.write(source) }

    @Throws(IOException::class)
    override fun write(
        source: ByteArray,
        offset: Int,
        byteCount: Int,
    ): BufferedSink = staged { it.write(source, offset, byteCount) }

    @Throws(IOException::class)
    override fun writeAll(source: Source): Long {
        var total = 0L
        while (true) {
            val read = source.read(scratch, SCRATCH_BYTES)
            when {
                read == -1L -> break // EOF — normal termination
                read == 0L -> throw IOException(
                    "Source returned 0 for byteCount=$SCRATCH_BYTES which violates the Source.read contract",
                )
                else -> {
                    drainScratch()
                    total += read
                }
            }
        }
        return total
    }

    @Throws(IOException::class)
    override fun writeUtf8(string: String): BufferedSink = staged { it.writeUtf8(string) }

    @Throws(IOException::class)
    override fun writeUtf8(
        string: String,
        beginIndex: Int,
        endIndex: Int,
    ): BufferedSink = staged { it.writeUtf8(string, beginIndex, endIndex) }

    @Throws(IOException::class)
    override fun writeString(
        string: String,
        charset: Charset,
    ): BufferedSink = staged { it.writeString(string, charset) }

    @Throws(IOException::class)
    override fun outputStream(): OutputStream {
        val primaryStream = primary.outputStream()
        val tapStream = tap.outputStream()
        return object : OutputStream() {
            override fun write(b: Int) {
                // Mirror into the tap FIRST (within the budget), then forward the byte to the
                // primary — same ordering as the typed-write path's `drainScratch`. If the primary
                // side throws mid-write, the attempted byte is still captured in the tap snapshot.
                if (mirrored < tapLimit) {
                    tapStream.write(b)
                    mirrored += 1L
                }
                primaryStream.write(b)
            }

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                // Tap first (within the budget), primary second (see single-byte overload): a
                // primary-side failure leaves the failing chunk captured in the tap. The FULL
                // chunk is always forwarded to the primary so the wire body is never truncated.
                val copy = tapAllowance(len.toLong())
                if (copy > 0L) {
                    tapStream.write(b, off, copy.toInt())
                    mirrored += copy
                }
                primaryStream.write(b, off, len)
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
            mirrorPrefix(scratch, staged)
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
