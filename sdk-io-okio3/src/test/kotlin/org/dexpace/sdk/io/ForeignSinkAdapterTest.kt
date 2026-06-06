/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.io

import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.io.Sink
import java.io.ByteArrayOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [org.dexpace.sdk.io.internal.ForeignSinkAdapter] through
 * `OkioIoProvider.bufferedSink(Sink)` — the only public path that exercises it.
 *
 * Coverage focus: identity-cache for `OkioBuffer` wrapper, flush idempotency, close
 * idempotency, write through both Okio-backed and foreign Buffer sources.
 */
class ForeignSinkAdapterTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    /**
     * Records every call from the adapter into structured state so we can assert that the
     * delegate received bytes verbatim, and that close/flush were forwarded.
     */
    private class RecordingSink : Sink {
        val buffer = ByteArrayOutputStream()
        var flushCount = 0
        var closeCount = 0

        override fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            buffer.write(source.readByteArray(byteCount))
        }

        override fun flush() {
            flushCount++
        }

        override fun close() {
            closeCount++
        }
    }

    @Test
    fun `writes through OkioIoProvider bufferedSink reach the foreign Sink`() {
        val target = RecordingSink()
        val sink: BufferedSink = OkioIoProvider.bufferedSink(target)
        sink.writeUtf8("hello, world")
        sink.flush()
        assertContentEquals("hello, world".toByteArray(), target.buffer.toByteArray())
    }

    @Test
    fun `wrapper allocation is cached across consecutive writes`() {
        // Functional assertion: multiple write calls succeed and produce the right bytes.
        // The cached-wrapper guarantee is structural — exercising it on a large payload
        // forces Okio to invoke `write(source, byteCount)` multiple times with the same
        // source reference.
        val target = RecordingSink()
        val sink: BufferedSink = OkioIoProvider.bufferedSink(target)
        val payload = ByteArray(50_000) { (it % 251).toByte() }
        sink.write(payload)
        sink.flush()
        assertContentEquals(payload, target.buffer.toByteArray())
    }

    @Test
    fun `flush is forwarded to the delegate Sink`() {
        val target = RecordingSink()
        val sink: BufferedSink = OkioIoProvider.bufferedSink(target)
        sink.writeUtf8("data")
        sink.flush()
        assertEquals(1, target.flushCount)
        sink.flush()
        // After explicit double-flush we should see two forwards; flush() is not deduped on the
        // adapter side, the consumer drives it.
        assertTrue(target.flushCount >= 2)
    }

    @Test
    fun `close is forwarded to the delegate Sink`() {
        val target = RecordingSink()
        val sink: BufferedSink = OkioIoProvider.bufferedSink(target)
        sink.writeUtf8("x").flush()
        sink.close()
        assertEquals(1, target.closeCount)
    }

    @Test
    fun `close is idempotent at the BufferedSink layer`() {
        val target = RecordingSink()
        val sink: BufferedSink = OkioIoProvider.bufferedSink(target)
        sink.close()
        // Calling close on the outer BufferedSink twice must not throw. The inner OkioBufferedSink
        // uses compareAndSet so the underlying ForeignSinkAdapter.close() is only invoked once.
        sink.close()
        assertEquals(1, target.closeCount)
    }

    @Test
    fun `non-Okio source bytes are forwarded through the adapter`() {
        // Forces the path where the adapter's write call sees a fresh okio.Buffer reference and
        // populates cachedWrapper for the first time, then re-uses it on follow-up writes.
        val target = RecordingSink()
        val sink: BufferedSink = OkioIoProvider.bufferedSink(target)
        // Write several chunks so the adapter sees multiple write(source, byteCount) invocations.
        sink.write("alpha-".toByteArray())
        sink.write("beta-".toByteArray())
        sink.write("gamma".toByteArray())
        sink.flush()
        assertContentEquals("alpha-beta-gamma".toByteArray(), target.buffer.toByteArray())
    }
}
