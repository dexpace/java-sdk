/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.serde.Deserializer
import org.dexpace.sdk.core.serde.Serde
import org.dexpace.sdk.core.serde.Serializer
import org.dexpace.sdk.io.OkioIoProvider
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RequestBodyDxTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private val jsonPayload = """{"k":1}"""

    private val fakeSerde: Serde =
        object : Serde {
            override val serializer: Serializer =
                object : Serializer {
                    override fun serialize(input: Any): String = jsonPayload

                    override fun serializeToByteArray(input: Any): ByteArray = jsonPayload.toByteArray(Charsets.UTF_8)

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
                        val bytes = serializeToByteArray(input)
                        bytes.copyInto(buffer, offset)
                        return bytes.size
                    }
                }
            override val deserializer: Deserializer =
                object : Deserializer {
                    override fun <T> deserialize(
                        input: String,
                        type: Class<T>,
                    ): T = throw UnsupportedOperationException()

                    override fun <T> deserialize(
                        input: ByteArray,
                        type: Class<T>,
                    ): T = throw UnsupportedOperationException()

                    override fun <T> deserialize(
                        inputStream: InputStream,
                        type: Class<T>,
                    ): T = throw UnsupportedOperationException()
                }
        }

    private fun drain(body: RequestBody): ByteArray {
        val buf = Io.provider.buffer()
        body.writeTo(buf)
        return buf.snapshot()
    }

    @Test
    fun `create with serde produces body with application json mediaType by default`() {
        val body = RequestBody.create(Any(), fakeSerde)
        assertEquals(CommonMediaTypes.APPLICATION_JSON, body.mediaType())
    }

    @Test
    fun `create with serde serializes value to expected bytes`() {
        val body = RequestBody.create(Any(), fakeSerde)
        assertContentEquals(jsonPayload.toByteArray(Charsets.UTF_8), drain(body))
    }

    @Test
    fun `create with serde and explicit mediaType uses that mediaType`() {
        val body = RequestBody.create(Any(), fakeSerde, CommonMediaTypes.TEXT_PLAIN)
        assertEquals(CommonMediaTypes.TEXT_PLAIN, body.mediaType())
    }

    @Test
    fun `create with serde and explicit mediaType still serializes via serde`() {
        val body = RequestBody.create(Any(), fakeSerde, CommonMediaTypes.TEXT_PLAIN)
        assertContentEquals(jsonPayload.toByteArray(Charsets.UTF_8), drain(body))
    }

    @Test
    fun `Serde default contentType returns APPLICATION_JSON`() {
        assertEquals(CommonMediaTypes.APPLICATION_JSON, fakeSerde.contentType())
    }
}
