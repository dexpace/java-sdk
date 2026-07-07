/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp

import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.net.http.HttpClient
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that [JdkHttpTransport.Builder] applies a sensible connect-timeout default
 * and allows callers to override or clear it.
 *
 * The underlying [java.net.http.HttpClient] is extracted via reflection (the same technique
 * used in [JdkHttpTransportCloseTest]) because the transport intentionally does not expose
 * the internal JDK client.
 */
class JdkHttpTransportDefaultConnectTimeoutTest {
    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `builder applies 10s connect timeout by default`() {
        val transport = JdkHttpTransport.builder().build()
        val timeout = extractClient(transport).connectTimeout()

        assertTrue(timeout.isPresent, "connect timeout should be set by default")
        assertEquals(Duration.ofSeconds(10), timeout.get())
    }

    @Test
    fun `builder honours explicit connect timeout override`() {
        val transport = JdkHttpTransport.builder().connectTimeout(Duration.ofSeconds(5)).build()
        val timeout = extractClient(transport).connectTimeout()

        assertTrue(timeout.isPresent)
        assertEquals(Duration.ofSeconds(5), timeout.get())
    }

    @Test
    fun `builder accepts null connect timeout to disable it`() {
        val transport = JdkHttpTransport.builder().connectTimeout(null).build()
        val timeout = extractClient(transport).connectTimeout()

        assertFalse(timeout.isPresent, "null connect timeout should result in no JDK connect timeout")
    }

    private fun extractClient(transport: JdkHttpTransport): HttpClient {
        val field = JdkHttpTransport::class.java.getDeclaredField("client")
        field.isAccessible = true
        return field.get(transport) as HttpClient
    }
}
