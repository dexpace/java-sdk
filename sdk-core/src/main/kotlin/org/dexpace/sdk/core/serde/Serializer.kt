package org.dexpace.sdk.core.serde

import java.io.InputStream

interface Serializer {
    fun serializeToByteArray(input: Any): ByteArray

    fun serialize(input: Any) : String
    fun serialize(input: Any, output: ByteArray)
    fun serialize(input: Any, inputStream: InputStream)
}
