package org.dexpace.sdk.core.http.request

import java.util.UUID

open class Request {
    val requestId: UUID = UUID.randomUUID()


}