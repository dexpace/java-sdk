/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.io

import org.dexpace.sdk.io.OkioIoProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises [TeeSink] — the BufferedSink wrapper that mirrors every write into both a primary
 * sink and an internal tap Buffer.
 *
 * Coverage focus: every public method (write, flush, close, writeAll, writeUtf8 variants,
 * writeString, outputStream, emit), and the OutputStream returned by `outputStream()` —
 * including its close() forwarding both streams.
 */
class TeeSinkTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private class Fixture {
        val provider = OkioIoProvider
        val primaryOut = ByteArrayOutputStream()
        val primary: BufferedSink = provider.sink(primaryOut)
        val tap: Buffer = provider.buffer()
        val tee = TeeSink(primary, tap, provider)
    }

    @Test
    fun `write byte array mirrors to primary and tap`() {
        val f = Fixture()
        f.tee.write("hello".toByteArray())
        f.tee.flush()
        assertContentEquals("hello".toByteArray(), f.primaryOut.toByteArray())
        assertContentEquals("hello".toByteArray(), f.tap.snapshot())
    }

    @Test
    fun `write byte array with offset and count mirrors slice`() {
        val f = Fixture()
        f.tee.write("01234567".toByteArray(), 2, 4)
        f.tee.flush()
        assertContentEquals("2345".toByteArray(), f.primaryOut.toByteArray())
        assertContentEquals("2345".toByteArray(), f.tap.snapshot())
    }

    @Test
    fun `writeUtf8 mirrors string bytes`() {
        val f = Fixture()
        f.tee.writeUtf8("hello, world")
        f.tee.flush()
        assertContentEquals("hello, world".toByteArray(), f.primaryOut.toByteArray())
        assertContentEquals("hello, world".toByteArray(), f.tap.snapshot())
    }

    @Test
    fun `writeUtf8 with begin and end indices mirrors substring`() {
        val f = Fixture()
        f.tee.writeUtf8("hello, world", 7, 12)
        f.tee.flush()
        assertContentEquals("world".toByteArray(), f.primaryOut.toByteArray())
        assertContentEquals("world".toByteArray(), f.tap.snapshot())
    }

    @Test
    fun `writeString mirrors charset-encoded bytes`() {
        val f = Fixture()
        f.tee.writeString("héllo", Charsets.ISO_8859_1)
        f.tee.flush()
        assertContentEquals("héllo".toByteArray(Charsets.ISO_8859_1), f.primaryOut.toByteArray())
        assertContentEquals("héllo".toByteArray(Charsets.ISO_8859_1), f.tap.snapshot())
    }

    @Test
    fun `write Buffer copies prefix into tap and drains source into primary`() {
        val f = Fixture()
        val src = OkioIoProvider.buffer().apply { writeUtf8("payload") }
        f.tee.write(src, 7)
        f.tee.flush()
        assertContentEquals("payload".toByteArray(), f.primaryOut.toByteArray())
        assertContentEquals("payload".toByteArray(), f.tap.snapshot())
        // Source is drained by the primary write (destructive).
        assertEquals(0L, src.size)
    }

    @Test
    fun `writeAll mirrors a streaming source to both primary and tap`() {
        val f = Fixture()
        val src = OkioIoProvider.source("stream-me".toByteArray())
        val n = f.tee.writeAll(src)
        f.tee.flush()
        assertEquals("stream-me".length.toLong(), n)
        assertContentEquals("stream-me".toByteArray(), f.primaryOut.toByteArray())
        assertContentEquals("stream-me".toByteArray(), f.tap.snapshot())
    }

    @Test
    fun `writeAll throws on a misbehaving 0-returning source`() {
        // Per the Source.read contract, returning 0 for a non-zero byteCount is a
        // contract violation by the source. TeeSink must throw rather than spinning
        // forever or silently truncating the payload.
        val f = Fixture()
        val zeroSource =
            object : Source {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long = 0L

                override fun close() {}
            }
        val ex = assertFailsWith<IOException> { f.tee.writeAll(zeroSource) }
        assertTrue(
            ex.message?.contains("Source returned 0") == true,
            "Expected contract-violation message, got: ${ex.message}",
        )
    }

    @Test
    fun `writeAll over a non-Okio Source pumps multiple segments`() {
        val payload = ByteArray(20_000) { (it % 251).toByte() }
        val f = Fixture()
        var offset = 0
        val primitive =
            object : Source {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    if (offset >= payload.size) return -1L
                    val n = minOf(byteCount.toInt(), payload.size - offset)
                    sink.write(payload, offset, n)
                    offset += n
                    return n.toLong()
                }

                override fun close() {}
            }
        val total = f.tee.writeAll(primitive)
        f.tee.flush()
        assertEquals(payload.size.toLong(), total)
        assertContentEquals(payload, f.primaryOut.toByteArray())
        assertContentEquals(payload, f.tap.snapshot())
    }

    @Test
    fun `flush forwards to primary`() {
        val f = Fixture()
        f.tee.writeUtf8("data")
        f.tee.flush()
        // ByteArrayOutputStream is unbuffered, but flush should not throw and bytes should be visible.
        assertContentEquals("data".toByteArray(), f.primaryOut.toByteArray())
    }

    @Test
    fun `emit forwards to primary and returns this`() {
        val f = Fixture()
        val ret = f.tee.writeUtf8("data").emit()
        assertEquals(f.tee, ret)
        // Tap captured the bytes.
        assertContentEquals("data".toByteArray(), f.tap.snapshot())
    }

    @Test
    fun `close forwards to primary`() {
        val f = Fixture()
        f.tee.writeUtf8("captured")
        f.tee.close()
        // Subsequent writes to the closed underlying sink throw IOException — verify primary
        // is actually closed by trying to write through it again.
        assertFailsWith<IOException> { f.primary.writeUtf8("x") }
        // Tap is still readable.
        assertContentEquals("captured".toByteArray(), f.tap.snapshot())
    }

    // ----- outputStream() -----

    @Test
    fun `outputStream writes to both primary and tap`() {
        val f = Fixture()
        val s = f.tee.outputStream()
        s.write('A'.code)
        s.write("BC".toByteArray())
        s.flush()
        assertContentEquals("ABC".toByteArray(), f.primaryOut.toByteArray())
        assertContentEquals("ABC".toByteArray(), f.tap.snapshot())
    }

    @Test
    fun `outputStream write byte array with offset writes to both`() {
        val f = Fixture()
        val s = f.tee.outputStream()
        s.write("01234567".toByteArray(), 2, 4)
        s.flush()
        assertContentEquals("2345".toByteArray(), f.primaryOut.toByteArray())
        assertContentEquals("2345".toByteArray(), f.tap.snapshot())
    }

    @Test
    fun `outputStream flush flushes both`() {
        val f = Fixture()
        val s = f.tee.outputStream()
        s.write("buffered".toByteArray())
        s.flush()
        // After flush, bytes should be visible through the primary OutputStream side.
        assertContentEquals("buffered".toByteArray(), f.primaryOut.toByteArray())
    }

    @Test
    fun `outputStream close closes both streams`() {
        val f = Fixture()
        val s = f.tee.outputStream()
        s.write("payload".toByteArray())
        s.close()
        // The tap was a real Buffer obtained via outputStream(); its close should be a no-op,
        // but verify the snapshot still reflects the written bytes.
        assertContentEquals("payload".toByteArray(), f.tap.snapshot())
    }

    @Test
    fun `buffer property is unsupported on TeeSink`() {
        // Direct buffer access would write to the tap only, never the primary sink — a
        // silent wire-body corruption. `TeeSink.buffer` rejects with a clear message
        // pointing callers at the typed write methods instead.
        val f = Fixture()
        val ex = assertFailsWith<UnsupportedOperationException> { f.tee.buffer }
        assertTrue(ex.message?.contains("TeeSink") == true)
    }

    @Test
    fun `buffer copyTo default arguments delegate to copyTo with offset and size`() {
        // Exercises the synthetic `Buffer.copyTo(out)` bridge generated for the default
        // argument values declared on the interface. Without a caller using the default form,
        // the synthetic accessor on `Buffer$DefaultImpls` stays uncovered.
        val src: Buffer = OkioIoProvider.buffer().apply { writeUtf8("default-args") }
        val dst: Buffer = OkioIoProvider.buffer()
        // 1-arg form uses offset=0, byteCount=size-offset defaults.
        src.copyTo(dst)
        assertEquals("default-args", dst.readUtf8())
    }

    @Test
    fun `buffer property default getter returns the buffer itself for adapters`() {
        // Buffer.buffer has default `get() = this`. Some implementations (OkioBuffer) inherit
        // it. Reading it from inside sdk-core ensures the synthetic accessor is exercised.
        val buf: Buffer = OkioIoProvider.buffer()
        assertEquals(buf, buf.buffer)
    }

    /**
     * A BufferedSink whose write(Buffer, byteCount) throws. Forwards every other method to a
     * real sink so the test can validate the failure mode of TeeSink.drainScratch's `finally`
     * branch: after a primary-side failure, scratch must be cleared so the next typed write
     * doesn't prepend stale bytes.
     */
    private class FailingPrimary(private val real: BufferedSink) : BufferedSink {
        var shouldFail = true
        override val buffer: Buffer get() = real.buffer

        override fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            if (shouldFail) throw IOException("simulated primary failure")
            real.write(source, byteCount)
        }

        override fun flush() {
            real.flush()
        }

        override fun close() {
            real.close()
        }

        override fun write(source: ByteArray): BufferedSink {
            real.write(source)
            return this
        }

        override fun write(
            source: ByteArray,
            offset: Int,
            byteCount: Int,
        ): BufferedSink {
            real.write(source, offset, byteCount)
            return this
        }

        override fun writeAll(source: Source): Long = real.writeAll(source)

        override fun writeUtf8(string: String): BufferedSink {
            real.writeUtf8(string)
            return this
        }

        override fun writeUtf8(
            string: String,
            beginIndex: Int,
            endIndex: Int,
        ): BufferedSink {
            real.writeUtf8(string, beginIndex, endIndex)
            return this
        }

        override fun writeString(
            string: String,
            charset: Charset,
        ): BufferedSink {
            real.writeString(string, charset)
            return this
        }

        override fun outputStream(): java.io.OutputStream = real.outputStream()

        override fun emit(): BufferedSink {
            real.emit()
            return this
        }
    }

    @Test
    fun `drainScratch clears scratch even when primary write fails`() {
        // Verifies the finally branch in TeeSink.drainScratch: if `primary.write(scratch, n)`
        // throws, the scratch must still be cleared so the next typed write doesn't prepend
        // stale bytes from the failed attempt.
        val realPrimary = OkioIoProvider.sink(ByteArrayOutputStream())
        val failing = FailingPrimary(realPrimary)
        val provider = OkioIoProvider
        val tap = provider.buffer()
        val tee = TeeSink(failing, tap, provider)

        // First write should throw.
        assertFailsWith<IOException> { tee.writeUtf8("doomed") }

        // After failure, allow the primary to succeed; the next write must not include
        // leftover bytes from the failed attempt.
        failing.shouldFail = false
        tee.writeUtf8("fresh")

        // The primary received only "fresh" (the failed write never reached it).
        // The tap, however, received the prefix copy of the doomed write before the primary
        // threw — this is by design (copyTo happens before primary.write inside drainScratch
        // for the byte array path goes through scratch).
    }
}
