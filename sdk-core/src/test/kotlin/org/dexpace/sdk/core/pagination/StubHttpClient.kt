/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response

/**
 * Test support: a stub HttpClient driven by canned responses keyed by request URL.
 *
 * The fake records every request it receives so tests can assert on call counts and the
 * exact URL sequence the paginator drove. Responses are looked up by `request.url.toString()`,
 * matching the way RequestRebuilder produces URLs.
 */
internal class StubHttpClient : HttpClient {
    private val responders: MutableMap<String, (Request) -> Response> = LinkedHashMap()
    private val urls: MutableList<String> = ArrayList()

    fun on(
        url: String,
        responseBuilder: (Request) -> Response,
    ): StubHttpClient {
        responders[url] = responseBuilder
        return this
    }

    /** All URLs received, in call order. */
    val receivedUrls: List<String> get() = urls.toList()

    /** Number of HTTP calls executed. */
    val callCount: Int get() = urls.size

    override fun execute(request: Request): Response {
        val url = request.url.toString()
        urls.add(url)
        val responder =
            responders[url]
                ?: error("StubHttpClient: no canned response for URL: $url\nKnown: ${responders.keys}")
        return responder(request)
    }
}
