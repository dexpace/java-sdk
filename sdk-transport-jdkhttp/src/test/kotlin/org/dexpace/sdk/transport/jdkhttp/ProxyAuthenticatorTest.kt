/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp

import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage for the proxy [Authenticator] installed by [JdkHttpTransport.Builder] when
 * [org.dexpace.sdk.core.util.ProxyOptions] carries credentials.
 *
 * The JDK invokes the installed authenticator for both proxy (407) and origin-server (401)
 * challenges. The authenticator must answer **only** proxy challenges whose host/port match
 * the configured proxy; for an origin 401 it must return `null` so the proxy credentials never
 * leak to the origin host (S-6).
 *
 * Each case is driven through the public static
 * `Authenticator.requestPasswordAuthentication(Authenticator, ...)` helper, which populates the
 * protected requestor fields and dispatches to `getPasswordAuthentication()` exactly as the JDK
 * client does at challenge time.
 */
class ProxyAuthenticatorTest {
    private val proxyAddress = InetSocketAddress("proxy.example", 3128)
    private val authenticator: Authenticator =
        JdkHttpTransport.ProxyAuthenticator(proxyAddress, "proxy-user", "proxy-pass")

    @Test
    fun `proxy challenge from the configured proxy is answered with credentials`() {
        val auth = challenge(host = "proxy.example", port = 3128, type = Authenticator.RequestorType.PROXY)
        requireNotNull(auth) { "matching proxy challenge must be answered" }
        assertEquals("proxy-user", auth.userName)
        assertEquals("proxy-pass", String(auth.password))
    }

    @Test
    fun `origin server 401 challenge is not answered with proxy credentials`() {
        val auth = challenge(host = "api.origin.example", port = 443, type = Authenticator.RequestorType.SERVER)
        assertNull(auth, "an origin-server (401) challenge must never receive proxy credentials")
    }

    @Test
    fun `proxy challenge from a different host is not answered`() {
        val auth = challenge(host = "other-proxy.example", port = 3128, type = Authenticator.RequestorType.PROXY)
        assertNull(auth, "a proxy challenge from a non-configured host must not receive credentials")
    }

    @Test
    fun `proxy challenge on a different port is not answered`() {
        val auth = challenge(host = "proxy.example", port = 9999, type = Authenticator.RequestorType.PROXY)
        assertNull(auth, "a proxy challenge on a non-configured port must not receive credentials")
    }

    /**
     * Pins the documented proxy-auth contract: the credentials this authenticator returns are
     * the raw username/password, with no scheme negotiation of its own. Whether a challenge is
     * actually satisfied is decided by the `java.net.http` client's built-in handling of a
     * registered `Authenticator`, which covers the **Basic** scheme only — it does not drive
     * Digest proxy auth through this hook. The authenticator therefore returns the same
     * credentials whether the proxy advertises `Basic` or `Digest`; a Digest-only proxy is not
     * authenticated end-to-end, matching the [JdkHttpTransport.Builder] KDoc.
     */
    @Test
    fun `proxy challenge credentials carry no scheme of their own`() {
        val proxy = Authenticator.RequestorType.PROXY
        val basic = challenge(host = "proxy.example", port = 3128, type = proxy, scheme = "Basic")
        val digest = challenge(host = "proxy.example", port = 3128, type = proxy, scheme = "Digest")
        requireNotNull(basic) { "Basic proxy challenge must be answered" }
        requireNotNull(digest) { "the authenticator does not inspect the scheme string" }
        assertEquals(basic.userName, digest.userName)
        assertEquals(String(basic.password), String(digest.password))
    }

    private fun challenge(
        host: String,
        port: Int,
        type: Authenticator.RequestorType,
        scheme: String = "Basic",
    ): PasswordAuthentication? =
        Authenticator.requestPasswordAuthentication(
            authenticator,
            host,
            InetAddress.getLoopbackAddress(),
            port,
            "http",
            "challenge",
            scheme,
            URL("http://$host:$port/"),
            type,
        )
}
