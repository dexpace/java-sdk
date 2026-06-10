/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DeserializerTest {
    data class Dto(val name: String)

    // Records the Class<T> token each overload was handed, and echoes a fixed Dto so the reified
    // extension's type-token forwarding can be asserted without a real codec.
    private class RecordingDeserializer : Deserializer {
        var lastType: Class<*>? = null
            private set

        @Suppress("UNCHECKED_CAST")
        override fun <T> deserialize(
            input: String,
            type: Class<T>,
        ): T {
            lastType = type
            return Dto(input) as T
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> deserialize(
            input: ByteArray,
            type: Class<T>,
        ): T {
            lastType = type
            return Dto(String(input)) as T
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> deserialize(
            inputStream: InputStream,
            type: Class<T>,
        ): T {
            lastType = type
            return Dto(inputStream.readBytes().toString(Charsets.UTF_8)) as T
        }
    }

    @Test
    fun `reified string extension forwards T class java token`() {
        val d = RecordingDeserializer()
        val out: Dto = d.deserialize("alice")
        assertEquals(Dto("alice"), out)
        assertSame(Dto::class.java, d.lastType)
    }

    @Test
    fun `reified byte-array extension forwards T class java token`() {
        val d = RecordingDeserializer()
        val out: Dto = d.deserialize("bob".toByteArray())
        assertEquals(Dto("bob"), out)
        assertSame(Dto::class.java, d.lastType)
    }

    @Test
    fun `reified input-stream extension forwards T class java token`() {
        val d = RecordingDeserializer()
        val out: Dto = d.deserialize(ByteArrayInputStream("carol".toByteArray()))
        assertEquals(Dto("carol"), out)
        assertSame(Dto::class.java, d.lastType)
    }

    @Test
    fun `explicit Class token overload returns the requested type`() {
        val d = RecordingDeserializer()
        val out = d.deserialize("dave", Dto::class.java)
        assertEquals(Dto("dave"), out)
    }
}
