/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.sse

import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerSentEventExtensionsTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun source(text: String): BufferedSource = Io.provider.source(text.toByteArray(Charsets.UTF_8))

    // ----- readServerSentEvents() sequence behavior -----

    @Test
    fun `valid stream of two events yields the expected ServerSentEvent sequence`() {
        val src = source("data: first\n\ndata: second\n\n")
        val events = src.readServerSentEvents().toList()
        assertEquals(2, events.size)
        assertEquals(listOf("first"), events[0].data)
        assertEquals(listOf("second"), events[1].data)
    }

    @Test
    fun `empty stream yields an empty sequence`() {
        val src = source("")
        val events = src.readServerSentEvents().toList()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `iteration is single-pass — sequence from generateSequence is constrained once`() {
        // readServerSentEvents() wraps a single ServerSentEventReader via generateSequence,
        // which returns a ConstrainedOnceSequence. A second call to .iterator() (i.e. a second
        // .toList()) on the same sequence throws IllegalStateException.
        val src = source("data: a\n\ndata: b\n\n")
        val seq = src.readServerSentEvents()

        // First traversal consumes the sequence.
        val firstPass = seq.toList()
        assertEquals(2, firstPass.size)

        // Second traversal on the same sequence object throws — it is a one-shot sequence.
        assertFailsWith<IllegalStateException> { seq.toList() }
    }

    @Test
    fun `exception in stream propagates from the next element pull`() {
        // Build a BufferedSource that throws IOException after the first readByte call
        // after the event boundary, simulating a mid-stream connection drop.
        val goodPart = "data: good\n\n".toByteArray(Charsets.UTF_8)
        val backing = Io.provider.buffer().also { it.write(goodPart) }
        // Wrap in a source that injects an IOException when exhausted before the read.
        val failingSource =
            object : BufferedSource by backing {
                override fun exhausted(): Boolean {
                    // After the good bytes are consumed the buffer is empty — report not-exhausted
                    // so the reader attempts another readByte, then throw from readByte.
                    return false
                }

                override fun readByte(): Byte {
                    if (backing.size == 0L) throw IOException("simulated connection drop")
                    return backing.readByte()
                }

                override fun peek(): BufferedSource {
                    // Return a peek over the original backing for BOM detection.
                    return backing.peek()
                }
            }

        val iter = failingSource.readServerSentEvents().iterator()

        // The good event is emitted first.
        assertTrue(iter.hasNext())
        val first = iter.next()
        assertEquals(listOf("good"), first.data)

        // Advancing further triggers the IOException.
        assertFailsWith<IOException> { iter.hasNext() }
    }
}
