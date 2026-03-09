package org.dexpace.sdk.core.serde

import java.io.InputStream
import java.io.OutputStream

interface Serializer {
    fun serialize(input: Any) : String
    fun serializeToByteArray(input: Any)

    fun serialize(input: Any, outputStream: OutputStream)
    fun serialize(input: Any, buffer: ByteArray)
}
