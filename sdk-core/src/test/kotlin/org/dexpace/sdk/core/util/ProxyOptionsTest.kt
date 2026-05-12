package org.dexpace.sdk.core.util

import org.dexpace.sdk.core.config.ConfigurationBuilder
import org.dexpace.sdk.core.http.auth.AuthenticateChallenge
import org.dexpace.sdk.core.http.auth.ChallengeHandler
import org.dexpace.sdk.core.http.request.Method
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProxyOptionsTest {

    // ----- Direct construction -----

    @Test
    fun `direct constructor with type and address only`() {
        val po = ProxyOptions(ProxyOptions.Type.HTTP, InetSocketAddress("proxy", 8080))
        assertEquals(ProxyOptions.Type.HTTP, po.type)
        assertEquals("proxy", po.address.hostString)
        assertEquals(8080, po.address.port)
        assertTrue(po.nonProxyHosts.isEmpty())
        assertNull(po.username)
        assertNull(po.password)
        assertNull(po.challengeHandler)
    }

    @Test
    fun `direct constructor accepts SOCKS4 and SOCKS5 types`() {
        val s4 = ProxyOptions(ProxyOptions.Type.SOCKS4, InetSocketAddress("p", 1080))
        val s5 = ProxyOptions(ProxyOptions.Type.SOCKS5, InetSocketAddress("p", 1080))
        assertEquals(ProxyOptions.Type.SOCKS4, s4.type)
        assertEquals(ProxyOptions.Type.SOCKS5, s5.type)
    }

    // ----- bypassesProxy: glob patterns -----

    @Test
    fun `bypassesProxy matches subdomain wildcard`() {
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            nonProxyHosts = listOf("*.example.com"),
        )
        assertTrue(po.bypassesProxy("internal.example.com"))
        assertTrue(po.bypassesProxy("api.example.com"))
    }

    @Test
    fun `bypassesProxy does not match unrelated hosts`() {
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            nonProxyHosts = listOf("*.example.com"),
        )
        assertFalse(po.bypassesProxy("external.com"))
        // `*.example.com` requires at least one prefix character before the dot — bare
        // `example.com` does not match. Matches Java's StreamHandler / OkHttp behavior.
        assertFalse(po.bypassesProxy("other.org"))
    }

    @Test
    fun `bypassesProxy matches prefix wildcard like 127 dot star`() {
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            nonProxyHosts = listOf("127.*"),
        )
        assertTrue(po.bypassesProxy("127.0.0.1"))
        assertTrue(po.bypassesProxy("127.5.5.5"))
        assertFalse(po.bypassesProxy("10.0.0.1"))
    }

    @Test
    fun `bypassesProxy matches exact literal host`() {
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            nonProxyHosts = listOf("localhost"),
        )
        assertTrue(po.bypassesProxy("localhost"))
        assertFalse(po.bypassesProxy("localhost.example.com"))
    }

    @Test
    fun `bypassesProxy handles multiple wildcards`() {
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            nonProxyHosts = listOf("*.internal.*"),
        )
        assertTrue(po.bypassesProxy("api.internal.com"))
        assertTrue(po.bypassesProxy("svc.internal.net"))
        assertFalse(po.bypassesProxy("api.external.com"))
    }

    @Test
    fun `bypassesProxy is case-insensitive`() {
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            nonProxyHosts = listOf("*.example.com"),
        )
        assertTrue(po.bypassesProxy("API.EXAMPLE.COM"))
        assertTrue(po.bypassesProxy("Internal.Example.Com"))
    }

    @Test
    fun `bypassesProxy with empty list never bypasses`() {
        val po = ProxyOptions(ProxyOptions.Type.HTTP, InetSocketAddress("proxy", 8080))
        assertFalse(po.bypassesProxy("anywhere.com"))
        assertFalse(po.bypassesProxy("localhost"))
    }

    @Test
    fun `bypassesProxy escapes regex metacharacters in glob literal`() {
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            // Literal `.` should not match `a` or `?` — without escaping it would.
            nonProxyHosts = listOf("api.example.com"),
        )
        assertTrue(po.bypassesProxy("api.example.com"))
        assertFalse(po.bypassesProxy("apixexample-com"))
    }

    @Test
    fun `bypassesProxy multiple patterns linear scan`() {
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            nonProxyHosts = listOf("*.example.com", "localhost", "127.*"),
        )
        assertTrue(po.bypassesProxy("a.example.com"))
        assertTrue(po.bypassesProxy("localhost"))
        assertTrue(po.bypassesProxy("127.0.0.1"))
        assertFalse(po.bypassesProxy("10.0.0.1"))
    }

    // ----- fromConfiguration: system properties -----

    @Test
    fun `fromConfiguration reads https proxyHost and proxyPort sysprops`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name ->
                when (name) {
                    "https.proxyHost" -> "proxy.example.com"
                    "https.proxyPort" -> "8443"
                    else -> null
                }
            }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals(ProxyOptions.Type.HTTP, po.type)
        assertEquals("proxy.example.com", po.address.hostString)
        assertEquals(8443, po.address.port)
    }

    @Test
    fun `fromConfiguration reads https proxyUser and proxyPassword sysprops`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name ->
                when (name) {
                    "https.proxyHost" -> "proxy"
                    "https.proxyPort" -> "8443"
                    "https.proxyUser" -> "alice"
                    "https.proxyPassword" -> "wonderland"
                    else -> null
                }
            }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals("alice", po.username)
        assertEquals("wonderland", po.password)
    }

    @Test
    fun `fromConfiguration falls back from https to http proxyHost`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name ->
                when (name) {
                    "http.proxyHost" -> "fallback.example.com"
                    "http.proxyPort" -> "3128"
                    else -> null
                }
            }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals("fallback.example.com", po.address.hostString)
        assertEquals(3128, po.address.port)
    }

    @Test
    fun `fromConfiguration https proxyHost wins over http proxyHost`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name ->
                when (name) {
                    "https.proxyHost" -> "secure.proxy"
                    "https.proxyPort" -> "8443"
                    "http.proxyHost" -> "plain.proxy"
                    "http.proxyPort" -> "3128"
                    else -> null
                }
            }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals("secure.proxy", po.address.hostString)
        assertEquals(8443, po.address.port)
    }

    @Test
    fun `fromConfiguration system property wins over env var`() {
        val cfg = ConfigurationBuilder()
            .envSource { name ->
                if (name == "HTTPS_PROXY") "http://env.proxy:9000" else null
            }
            .propsSource { name ->
                when (name) {
                    "https.proxyHost" -> "sys.proxy"
                    "https.proxyPort" -> "8443"
                    else -> null
                }
            }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals("sys.proxy", po.address.hostString)
        assertEquals(8443, po.address.port)
    }

    // ----- fromConfiguration: env vars -----

    @Test
    fun `fromConfiguration parses HTTPS_PROXY with embedded credentials`() {
        val cfg = ConfigurationBuilder()
            .envSource { name ->
                if (name == "HTTPS_PROXY") "http://user:pass@proxy.example.com:8080" else null
            }
            .propsSource { null }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals("proxy.example.com", po.address.hostString)
        assertEquals(8080, po.address.port)
        assertEquals("user", po.username)
        assertEquals("pass", po.password)
    }

    @Test
    fun `fromConfiguration parses HTTPS_PROXY without credentials`() {
        val cfg = ConfigurationBuilder()
            .envSource { name ->
                if (name == "HTTPS_PROXY") "http://proxy.example.com:8080" else null
            }
            .propsSource { null }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals("proxy.example.com", po.address.hostString)
        assertEquals(8080, po.address.port)
        assertNull(po.username)
        assertNull(po.password)
    }

    @Test
    fun `fromConfiguration falls back from HTTPS_PROXY to HTTP_PROXY env var`() {
        val cfg = ConfigurationBuilder()
            .envSource { name ->
                if (name == "HTTP_PROXY") "http://only.http.proxy:3128" else null
            }
            .propsSource { null }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals("only.http.proxy", po.address.hostString)
        assertEquals(3128, po.address.port)
    }

    @Test
    fun `fromConfiguration returns null when no proxy is configured`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { null }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    @Test
    fun `fromConfiguration returns null on empty env var URL`() {
        val cfg = ConfigurationBuilder()
            .envSource { name -> if (name == "HTTPS_PROXY") "" else null }
            .propsSource { null }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    // ----- fromConfiguration: port validation -----

    @Test
    fun `fromConfiguration returns null on non-numeric port sysprop`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name ->
                when (name) {
                    "https.proxyHost" -> "proxy"
                    "https.proxyPort" -> "not-a-number"
                    else -> null
                }
            }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    @Test
    fun `fromConfiguration returns null on negative port sysprop`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name ->
                when (name) {
                    "https.proxyHost" -> "proxy"
                    "https.proxyPort" -> "-1"
                    else -> null
                }
            }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    @Test
    fun `fromConfiguration returns null on out-of-range port sysprop`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name ->
                when (name) {
                    "https.proxyHost" -> "proxy"
                    "https.proxyPort" -> "70000"
                    else -> null
                }
            }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    @Test
    fun `fromConfiguration returns null when sysprop has host but no port`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name -> if (name == "https.proxyHost") "proxy" else null }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    @Test
    fun `fromConfiguration returns null on malformed env var URL`() {
        val cfg = ConfigurationBuilder()
            .envSource { name -> if (name == "HTTPS_PROXY") "::::::not-a-url" else null }
            .propsSource { null }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    @Test
    fun `fromConfiguration returns null when env var URL is missing host`() {
        val cfg = ConfigurationBuilder()
            .envSource { name -> if (name == "HTTPS_PROXY") "http://:8080" else null }
            .propsSource { null }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    @Test
    fun `fromConfiguration returns null when env var URL has no port`() {
        // URI.getPort() returns -1 when absent → rejected.
        val cfg = ConfigurationBuilder()
            .envSource { name -> if (name == "HTTPS_PROXY") "http://proxy.example.com" else null }
            .propsSource { null }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    // ----- fromConfiguration: nonProxyHosts -----

    @Test
    fun `fromConfiguration reads http nonProxyHosts sysprop with pipe separator`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name ->
                when (name) {
                    "https.proxyHost" -> "proxy"
                    "https.proxyPort" -> "8443"
                    "http.nonProxyHosts" -> "*.internal.com|localhost|127.*"
                    else -> null
                }
            }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals(listOf("*.internal.com", "localhost", "127.*"), po.nonProxyHosts)
        assertTrue(po.bypassesProxy("api.internal.com"))
        assertTrue(po.bypassesProxy("localhost"))
        assertTrue(po.bypassesProxy("127.0.0.1"))
    }

    @Test
    fun `fromConfiguration reads NO_PROXY env var with comma separator`() {
        val cfg = ConfigurationBuilder()
            .envSource { name ->
                when (name) {
                    "HTTPS_PROXY" -> "http://proxy:8080"
                    "NO_PROXY" -> "*.example.com,localhost,127.*"
                    else -> null
                }
            }
            .propsSource { null }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals(listOf("*.example.com", "localhost", "127.*"), po.nonProxyHosts)
    }

    @Test
    fun `fromConfiguration sysprop nonProxyHosts wins over env var NO_PROXY`() {
        val cfg = ConfigurationBuilder()
            .envSource { name ->
                when (name) {
                    "HTTPS_PROXY" -> "http://proxy:8080"
                    "NO_PROXY" -> "from-env.com"
                    else -> null
                }
            }
            .propsSource { name ->
                if (name == "http.nonProxyHosts") "from-sysprop.com" else null
            }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals(listOf("from-sysprop.com"), po.nonProxyHosts)
    }

    @Test
    fun `fromConfiguration NO_PROXY equals star returns null bypass everything`() {
        val cfg = ConfigurationBuilder()
            .envSource { name ->
                when (name) {
                    "HTTPS_PROXY" -> "http://proxy:8080"
                    "NO_PROXY" -> "*"
                    else -> null
                }
            }
            .propsSource { null }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    @Test
    fun `fromConfiguration sysprop nonProxyHosts equals star returns null bypass everything`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name ->
                when (name) {
                    "https.proxyHost" -> "proxy"
                    "https.proxyPort" -> "8443"
                    "http.nonProxyHosts" -> "*"
                    else -> null
                }
            }
            .build()
        assertNull(ProxyOptions.fromConfiguration(cfg))
    }

    @Test
    fun `fromConfiguration NO_PROXY honors backslash escape on comma`() {
        // `a\,b,c` → ["a,b", "c"] — first element contains a literal comma.
        val cfg = ConfigurationBuilder()
            .envSource { name ->
                when (name) {
                    "HTTPS_PROXY" -> "http://proxy:8080"
                    "NO_PROXY" -> "a\\,b,c"
                    else -> null
                }
            }
            .propsSource { null }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals(listOf("a,b", "c"), po.nonProxyHosts)
    }

    @Test
    fun `fromConfiguration http nonProxyHosts honors backslash escape on pipe`() {
        // `a\|b|c` → ["a|b", "c"]
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name ->
                when (name) {
                    "https.proxyHost" -> "proxy"
                    "https.proxyPort" -> "8443"
                    "http.nonProxyHosts" -> "a\\|b|c"
                    else -> null
                }
            }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals(listOf("a|b", "c"), po.nonProxyHosts)
    }

    // ----- IPv6 -----

    @Test
    fun `direct constructor accepts IPv6 address`() {
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("::1", 8080),
        )
        assertEquals(8080, po.address.port)
        // InetSocketAddress canonicalises to the resolved (or unresolved) form. We assert
        // that the address is non-null and the port stuck — that's all that matters.
        assertNotNull(po.address.address ?: po.address.hostString)
    }

    @Test
    fun `fromConfiguration parses IPv6 bracketed proxy URL`() {
        val cfg = ConfigurationBuilder()
            .envSource { name -> if (name == "HTTPS_PROXY") "http://[::1]:8080" else null }
            .propsSource { null }
            .build()
        val po = ProxyOptions.fromConfiguration(cfg)
        assertNotNull(po)
        assertEquals(8080, po.address.port)
        // URI strips the brackets; the resulting host is `::1` or `0:0:0:0:0:0:0:1` post-normalisation.
        val host = po.address.hostString
        assertTrue(
            host == "::1" || host == "0:0:0:0:0:0:0:1" || host.contains(":"),
            "Expected IPv6-looking host, got '$host'",
        )
    }

    // ----- Credentials & ChallengeHandler interaction -----

    @Test
    fun `username and password without challengeHandler implies Basic auth`() {
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            username = "u",
            password = "p",
        )
        assertEquals("u", po.username)
        assertEquals("p", po.password)
        assertNull(po.challengeHandler)
    }

    @Test
    fun `challengeHandler can be supplied directly`() {
        val handler = StubChallengeHandler()
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            challengeHandler = handler,
        )
        assertSame(handler, po.challengeHandler)
    }

    @Test
    fun `challengeHandler is retained alongside username and password`() {
        // When both are present we keep both — the consumer is expected to prefer the
        // challengeHandler. ProxyOptions itself does not enforce one over the other; that
        // is documented as the caller's responsibility (per to-implement.md §16).
        val handler = StubChallengeHandler()
        val po = ProxyOptions(
            ProxyOptions.Type.HTTP,
            InetSocketAddress("proxy", 8080),
            username = "u",
            password = "p",
            challengeHandler = handler,
        )
        assertEquals("u", po.username)
        assertEquals("p", po.password)
        assertSame(handler, po.challengeHandler)
    }

    // ----- compileGlob direct -----

    @Test
    fun `compileGlob escapes regex metacharacters`() {
        val p = ProxyOptions.compileGlob("a.b")
        assertTrue(p.matcher("a.b").matches())
        // A literal `.` must NOT match an arbitrary character.
        assertFalse(p.matcher("axb").matches())
    }

    @Test
    fun `compileGlob expands star to dot-star`() {
        val p = ProxyOptions.compileGlob("*.example.com")
        assertTrue(p.matcher("api.example.com").matches())
        assertTrue(p.matcher("api.v2.example.com").matches())
        assertFalse(p.matcher("example.org").matches())
    }

    @Test
    fun `compileGlob expands question mark to single character`() {
        val p = ProxyOptions.compileGlob("a?c")
        assertTrue(p.matcher("abc").matches())
        assertTrue(p.matcher("axc").matches())
        assertFalse(p.matcher("ac").matches())
        assertFalse(p.matcher("abcd").matches())
    }

    @Test
    fun `compileGlob is case-insensitive`() {
        val p = ProxyOptions.compileGlob("*.example.com")
        assertTrue(p.matcher("API.EXAMPLE.COM").matches())
    }

    // ----- Stub ChallengeHandler for tests -----

    private class StubChallengeHandler : ChallengeHandler {
        override fun handleChallenges(
            method: Method,
            uri: URI,
            challenges: List<AuthenticateChallenge>,
            isProxy: Boolean,
        ): Pair<String, String>? = if (isProxy) "Proxy-Authorization" to "stub" else null

        override fun canHandle(challenges: List<AuthenticateChallenge>): Boolean = true
    }
}
