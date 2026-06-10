/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SerializerTest {
    // Minimal Serializer that encodes via toString().toByteArray(); enough to exercise the
    // buffer-overload contract (returns bytes written, honours offset, throws on overflow).
    private class StringSerializer : Serializer {
        override fun serialize(input: Any): String = input.toString()

        override fun serializeToByteArray(input: Any): ByteArray = input.toString().toByteArray()

        override fun serialize(
            input: Any,
            outputStream: OutputStream,
        ) {
            outputStream.write(serializeToByteArray(input))
        }

        override fun serialize(
            input: Any,
            buffer: ByteArray,
            offset: Int,
        ): Int {
            if (offset < 0 || offset > buffer.size) {
                throw IndexOutOfBoundsException("offset $offset / size ${buffer.size}")
            }
            val encoded = serializeToByteArray(input)
            if (encoded.size > buffer.size - offset) {
                throw IndexOutOfBoundsException("payload ${encoded.size} > remaining ${buffer.size - offset}")
            }
            System.arraycopy(encoded, 0, buffer, offset, encoded.size)
            return encoded.size
        }
    }

    @Test
    fun `buffer overload returns bytes written and uses offset default of zero`() {
        val s = StringSerializer()
        val buffer = ByteArray(16)
        // Default offset (0) supplied via the interface default when called with two args.
        val written = s.serialize("abc", buffer)
        assertEquals(3, written)
        assertEquals("abc", String(buffer, 0, written))
    }

    @Test
    fun `buffer overload writes at the requested offset`() {
        val s = StringSerializer()
        val buffer = ByteArray(16)
        val written = s.serialize("xy", buffer, 4)
        assertEquals(2, written)
        assertEquals("xy", String(buffer, 4, written))
        assertTrue(buffer.copyOfRange(0, 4).all { it == 0.toByte() })
    }

    @Test
    fun `buffer overload throws on overflow`() {
        val s = StringSerializer()
        assertFailsWith<IndexOutOfBoundsException> {
            s.serialize("too-long-payload", ByteArray(4))
        }
    }
}
