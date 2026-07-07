/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.example

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.dexpace.sdk.core.http.pipeline.HttpPipeline
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.transport.okhttp.OkHttpTransport

/*
 * Minimal end-to-end sample: a plain-HTTP GET through the simplest possible pipeline.
 *
 * No TLS, no auth, no serde — just HttpPipeline.of(transport) and Request.get(url).
 * A MockWebServer provides a deterministic local endpoint so the sample runs with no network.
 * sdk-io-okio3 on the classpath registers itself automatically; no explicit installProvider() call
 * is needed.
 *
 * Run via `./gradlew :sdk-example:run` (will run ExampleApp.main instead; call runSimpleGet()
 * directly to exercise this path) or via the smoke test SimpleGetAppTest.
 */

/**
 * Fires a single GET request against an embedded [MockWebServer] and prints the response body.
 * Extracted from [main] so the smoke test can call it directly.
 */
public fun runSimpleGet() {
    MockWebServer().use { server ->
        server.enqueue(MockResponse.Builder().body("Hello, dexpace!").build())
        server.start()

        OkHttpTransport.builder().build().use { transport ->
            val pipeline = HttpPipeline.of(transport)
            pipeline.send(Request.get(server.url("/hello").toString())).use { response ->
                println(response.body?.string())
            }
        }
    }
}

/** Entry point for running this minimal sample standalone. */
public fun main() {
    runSimpleGet()
}
