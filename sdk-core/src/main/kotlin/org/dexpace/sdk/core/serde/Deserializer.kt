package org.dexpace.sdk.core.serde

import java.io.InputStream



interface Deserializer {
    fun <T> deserialize(input: String) : T
    fun <T> deserialize(input: ByteArray) : T
    fun <T> deserialize(inputStream: InputStream) : T
}
