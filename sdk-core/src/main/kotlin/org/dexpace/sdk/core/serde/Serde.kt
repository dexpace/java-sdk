package org.dexpace.sdk.core.serde

import org.dexpace.sdk.core.org.dexpace.sdk.core.serde.Deserializer

interface Serde {
    val serializer: Serializer
    val deserializer: Deserializer
}
