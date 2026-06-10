/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import java.net.URI
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the [RestrictedHeaders] drop-list against the JDK's actual disallowed-header set so a
 * user-set restricted header is dropped before it can reach `HttpRequest.Builder.header(...)`
 * (where it would throw `IllegalArgumentException` and escape `execute`).
 */
class RestrictedHeadersTest {
    @Test
    fun `the jdk disallowed headers are all restricted`() {
        // Names empirically rejected by HttpRequest.Builder.header on JDK 11+.
        for (name in listOf("connection", "content-length", "expect", "host", "upgrade")) {
            assertTrue(RestrictedHeaders.isRestricted(name), "$name must be restricted")
        }
    }

    @Test
    fun `restriction is case insensitive`() {
        assertTrue(RestrictedHeaders.isRestricted("Expect"))
        assertTrue(RestrictedHeaders.isRestricted("UPGRADE"))
        assertTrue(RestrictedHeaders.isRestricted("Content-Length"))
        assertTrue(RestrictedHeaders.isRestricted("CONNECTION"))
    }

    @Test
    fun `ordinary headers are not restricted`() {
        assertFalse(RestrictedHeaders.isRestricted("Authorization"))
        assertFalse(RestrictedHeaders.isRestricted("X-Custom"))
        assertFalse(RestrictedHeaders.isRestricted("Accept"))
    }

    @Test
    fun `every restricted header is actually rejected by the jdk builder`() {
        // Guards against the drop-list drifting away from the JDK's real reject set: each name
        // we claim to restrict must, in fact, be rejected by HttpRequest.Builder.header.
        val builder = HttpRequest.newBuilder(URI.create("http://example.test/"))
        for (name in listOf("Connection", "Content-Length", "Expect", "Host", "Upgrade")) {
            assertTrue(RestrictedHeaders.isRestricted(name), "$name must be in the drop-list")
            assertFails("JDK should reject $name on header()") {
                builder.header(name, "value")
            }
        }
    }
}
