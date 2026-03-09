package org.dexpace.sdk.core.client

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response

interface HttpClient {
    fun execute(request: Request): Response
}
