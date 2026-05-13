package org.dexpace.sdk.core.http.auth

import org.dexpace.sdk.core.http.request.Method
import java.net.URI
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DigestChallengeHandlerTest {

    private val uri = URI.create("https://api.example.com/dir/index.html")

    // ---- canHandle --------------------------------------------------------------

    @Test
    fun `canHandle accepts MD5 plus qop auth`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "MD5"),
        )
        assertTrue(handler.canHandle(listOf(challenge)))
    }

    @Test
    fun `canHandle accepts SHA-256 plus qop auth`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "SHA-256"),
        )
        assertTrue(handler.canHandle(listOf(challenge)))
    }

    @Test
    fun `canHandle rejects unsupported algorithm SHA-512-256`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "SHA-512-256"),
        )
        assertFalse(handler.canHandle(listOf(challenge)))
    }

    @Test
    fun `canHandle rejects qop=auth-int only`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth-int", "algorithm" to "MD5"),
        )
        assertFalse(handler.canHandle(listOf(challenge)))
    }

    @Test
    fun `canHandle accepts qop=auth-int,auth (both offered)`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth-int,auth", "algorithm" to "MD5"),
        )
        assertTrue(handler.canHandle(listOf(challenge)))
    }

    @Test
    fun `canHandle is case-insensitive on scheme`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "MD5"),
        )
        assertTrue(handler.canHandle(listOf(challenge)))
    }

    @Test
    fun `canHandle treats missing algorithm as MD5 default per RFC`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        assertTrue(handler.canHandle(listOf(challenge)))
    }

    @Test
    fun `canHandle false when realm is missing`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("nonce" to "n", "qop" to "auth"),
        )
        assertFalse(handler.canHandle(listOf(challenge)))
    }

    @Test
    fun `canHandle false when nonce is missing`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "qop" to "auth"),
        )
        assertFalse(handler.canHandle(listOf(challenge)))
    }

    // ---- handleChallenges header structure --------------------------------------

    @Test
    fun `handleChallenges emits Authorization header by default`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val result = handler.handleChallenges(Method.GET, uri, listOf(challenge))
        assertNotNull(result)
        assertEquals("Authorization", result.first)
        assertTrue(result.second.startsWith("Digest "))
    }

    @Test
    fun `handleChallenges emits Proxy-Authorization when isProxy=true`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val result = handler.handleChallenges(Method.GET, uri, listOf(challenge), isProxy = true)
        assertNotNull(result)
        assertEquals("Proxy-Authorization", result.first)
    }

    @Test
    fun `handleChallenges header contains all required fields`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf(
                "realm" to "r",
                "nonce" to "n",
                "qop" to "auth",
                "algorithm" to "MD5",
                "opaque" to "op-val",
            ),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        assertEquals("u", parsed["username"])
        assertEquals("r", parsed["realm"])
        assertEquals("n", parsed["nonce"])
        assertTrue(parsed.containsKey("uri"))
        assertEquals("auth", parsed["qop"])
        assertEquals("00000001", parsed["nc"])
        assertNotNull(parsed["cnonce"])
        assertNotNull(parsed["response"])
        assertEquals("MD5", parsed["algorithm"])
        assertEquals("op-val", parsed["opaque"])
    }

    @Test
    fun `nc increments on each call`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val first = parseDigestHeader(handler.handleChallenges(Method.GET, uri, listOf(challenge))!!.second)
        val second = parseDigestHeader(handler.handleChallenges(Method.GET, uri, listOf(challenge))!!.second)
        val third = parseDigestHeader(handler.handleChallenges(Method.GET, uri, listOf(challenge))!!.second)
        assertEquals("00000001", first["nc"])
        assertEquals("00000002", second["nc"])
        assertEquals("00000003", third["nc"])
    }

    @Test
    fun `cnonce differs between calls`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val first = parseDigestHeader(handler.handleChallenges(Method.GET, uri, listOf(challenge))!!.second)
        val second = parseDigestHeader(handler.handleChallenges(Method.GET, uri, listOf(challenge))!!.second)
        assertNotEquals(first["cnonce"], second["cnonce"])
    }

    @Test
    fun `cnonce is 16 hex chars`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val parsed = parseDigestHeader(handler.handleChallenges(Method.GET, uri, listOf(challenge))!!.second)
        val cnonce = parsed["cnonce"]
        assertNotNull(cnonce)
        assertEquals(16, cnonce.length, "cnonce length")
        assertTrue(cnonce.all { it in '0'..'9' || it in 'a'..'f' }, "cnonce must be lower-case hex")
    }

    // ---- algorithm-specific behaviour ------------------------------------------

    @Test
    fun `missing algorithm defaults to MD5 in emitted header`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        assertEquals("MD5", parsed["algorithm"])
    }

    @Test
    fun `prefers SHA-256 over MD5 when both offered`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenges = listOf(
            AuthenticateChallenge(
                "Digest",
                mapOf("realm" to "r", "nonce" to "n1", "qop" to "auth", "algorithm" to "MD5"),
            ),
            AuthenticateChallenge(
                "Digest",
                mapOf("realm" to "r", "nonce" to "n2", "qop" to "auth", "algorithm" to "SHA-256"),
            ),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, challenges)!!
        val parsed = parseDigestHeader(value)
        assertEquals("SHA-256", parsed["algorithm"])
        // Should also have used the SHA-256 challenge's nonce.
        assertEquals("n2", parsed["nonce"])
    }

    @Test
    fun `respects custom algorithm preference order`() {
        val handler = DigestChallengeHandler(
            "u",
            "p",
            preferredAlgorithms = listOf(DigestAlgorithm.MD5, DigestAlgorithm.SHA_256),
        )
        val challenges = listOf(
            AuthenticateChallenge(
                "Digest",
                mapOf("realm" to "r", "nonce" to "n1", "qop" to "auth", "algorithm" to "MD5"),
            ),
            AuthenticateChallenge(
                "Digest",
                mapOf("realm" to "r", "nonce" to "n2", "qop" to "auth", "algorithm" to "SHA-256"),
            ),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, challenges)!!
        val parsed = parseDigestHeader(value)
        assertEquals("MD5", parsed["algorithm"])
    }

    @Test
    fun `MD5-sess HA1 incorporates nonce and cnonce`() {
        // Compute response twice with the handler — same MD5-sess challenge. The
        // emitted response must equal what we get when we recompute HA1 using the
        // -sess derivation rule with the emitted cnonce.
        val handler = DigestChallengeHandler(
            "u",
            "p",
            preferredAlgorithms = listOf(DigestAlgorithm.MD5_SESS),
        )
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "the-nonce", "qop" to "auth", "algorithm" to "MD5-sess"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        assertEquals("MD5-sess", parsed["algorithm"])
        val cnonce = parsed["cnonce"]!!
        val nc = parsed["nc"]!!
        val emittedResponse = parsed["response"]!!

        val ha1Base = md5Hex("u:r:p")
        val ha1Sess = md5Hex("$ha1Base:the-nonce:$cnonce")
        val ha2 = md5Hex("GET:${parsed["uri"]}")
        val expected = md5Hex("$ha1Sess:the-nonce:$nc:$cnonce:auth:$ha2")
        assertEquals(expected, emittedResponse)
    }

    @Test
    fun `SHA-256 response matches independent computation`() {
        val handler = DigestChallengeHandler(
            "u",
            "p",
            preferredAlgorithms = listOf(DigestAlgorithm.SHA_256),
        )
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "the-nonce", "qop" to "auth", "algorithm" to "SHA-256"),
        )
        val (_, value) = handler.handleChallenges(Method.POST, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        val cnonce = parsed["cnonce"]!!
        val nc = parsed["nc"]!!

        val ha1 = sha256Hex("u:r:p")
        val ha2 = sha256Hex("POST:${parsed["uri"]}")
        val expected = sha256Hex("$ha1:the-nonce:$nc:$cnonce:auth:$ha2")
        assertEquals(expected, parsed["response"])
    }

    // ---- RFC 2617 §3.5 / RFC 7616 spec-style test vector ------------------------

    @Test
    fun `RFC 2617 section 3-5 canonical test vector — MD5`() {
        // The canonical RFC 2617 example. We can't directly inject cnonce/nc into
        // the handler, so we verify in two parts:
        //   1) The algorithmic constants of the test vector — HA1, HA2, and
        //      response for cnonce=0a4f113b, nc=00000001 — match the spec value.
        //   2) The handler, given the same challenge and credentials, computes
        //      a response that we can independently re-verify from the emitted
        //      cnonce/nc.
        val username = "Mufasa"
        val password = "Circle Of Life"
        val realm = "testrealm@host.com"
        val nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093"
        val expectedResponse = "6629fae49393a05397450978507c4ef1"

        // Part 1 — verify spec constants.
        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("GET:/dir/index.html")
        val responseForGiven = md5Hex("$ha1:$nonce:00000001:0a4f113b:auth:$ha2")
        assertEquals(expectedResponse, responseForGiven, "RFC 2617 §3.5 constants do not match")

        // Part 2 — handler-emitted header is internally consistent.
        val handler = DigestChallengeHandler(username, password)
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf(
                "realm" to realm,
                "nonce" to nonce,
                "qop" to "auth",
                "algorithm" to "MD5",
                "opaque" to "5ccc069c403ebaf9f0171e9517f40e41",
            ),
        )
        val (_, value) = handler.handleChallenges(
            Method.GET,
            URI.create("http://www.example.com/dir/index.html"),
            listOf(challenge),
        )!!
        val parsed = parseDigestHeader(value)
        assertEquals(username, parsed["username"])
        assertEquals(realm, parsed["realm"])
        assertEquals(nonce, parsed["nonce"])
        assertEquals("/dir/index.html", parsed["uri"])
        assertEquals("auth", parsed["qop"])
        assertEquals("00000001", parsed["nc"])
        assertEquals("MD5", parsed["algorithm"])
        assertEquals("5ccc069c403ebaf9f0171e9517f40e41", parsed["opaque"])

        // Re-derive the response from the emitted cnonce/nc and confirm.
        val emittedCnonce = parsed["cnonce"]!!
        val emittedNc = parsed["nc"]!!
        val expectedFromEmitted = md5Hex("$ha1:$nonce:$emittedNc:$emittedCnonce:auth:$ha2")
        assertEquals(expectedFromEmitted, parsed["response"])
    }

    // ---- error paths ------------------------------------------------------------

    @Test
    fun `empty username rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { DigestChallengeHandler("", "p") }
    }

    @Test
    fun `empty password rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { DigestChallengeHandler("u", "") }
    }

    @Test
    fun `empty preferredAlgorithms rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            DigestChallengeHandler("u", "p", emptyList())
        }
    }

    @Test
    fun `handleChallenges returns null when no satisfiable challenge`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "SHA-512-256"),
        )
        assertNull(handler.handleChallenges(Method.GET, uri, listOf(challenge)))
    }

    // ---- qop list parsing -------------------------------------------------------

    @Test
    fun `qop with auth and auth-int picks auth`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth, auth-int", "algorithm" to "MD5"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        assertEquals("auth", parsed["qop"])
    }

    @Test
    fun `qop with whitespace-separated tokens works`() {
        // Splits on comma and trims; verify tab/space handling.
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth-int,   auth", "algorithm" to "MD5"),
        )
        assertTrue(handler.canHandle(listOf(challenge)))
    }

    @Test
    fun `legacy challenge with no qop produces no qop nc or cnonce on the header`() {
        // RFC 2069: no qop in challenge → handler emits a header without qop/nc/cnonce.
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        assertNull(parsed["qop"])
        assertNull(parsed["nc"])
        assertNull(parsed["cnonce"])
        // response should still be present (computed via legacy formula).
        assertNotNull(parsed["response"])
    }

    @Test
    fun `legacy challenge with no qop computes RFC 2069 response`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "the-nonce"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        val ha1 = md5Hex("u:r:p")
        val ha2 = md5Hex("GET:${parsed["uri"]}")
        val expected = md5Hex("$ha1:the-nonce:$ha2")
        assertEquals(expected, parsed["response"])
    }

    // ---- charset handling -------------------------------------------------------

    @Test
    fun `charset UTF-8 produces UTF-8 byte encoding for HA1`() {
        val handler = DigestChallengeHandler("ué", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "MD5", "charset" to "UTF-8"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        // HA1 should be MD5("ué:r:p") with UTF-8 encoding (two bytes for é).
        val ha1 = md5HexUtf8("ué:r:p")
        val ha2 = md5HexUtf8("GET:${parsed["uri"]}")
        val expected = md5HexUtf8("$ha1:n:${parsed["nc"]}:${parsed["cnonce"]}:auth:$ha2")
        assertEquals(expected, parsed["response"])
    }

    @Test
    fun `charset utf-8 lower-case is also treated as UTF-8`() {
        // Case-insensitive matching of the `charset` parameter.
        val handler = DigestChallengeHandler("ué", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "MD5", "charset" to "utf-8"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        val ha1 = md5HexUtf8("ué:r:p")
        val ha2 = md5HexUtf8("GET:${parsed["uri"]}")
        val expected = md5HexUtf8("$ha1:n:${parsed["nc"]}:${parsed["cnonce"]}:auth:$ha2")
        assertEquals(expected, parsed["response"])
    }

    @Test
    fun `charset ISO-8859-1 produces Latin-1 byte encoding`() {
        // Latin-1 is the default — passing the parameter explicitly should behave the same way.
        val handler = DigestChallengeHandler("ué", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "MD5", "charset" to "ISO-8859-1"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        val ha1 = md5Hex("ué:r:p") // helper uses ISO-8859-1 already
        val ha2 = md5Hex("GET:${parsed["uri"]}")
        val expected = md5Hex("$ha1:n:${parsed["nc"]}:${parsed["cnonce"]}:auth:$ha2")
        assertEquals(expected, parsed["response"])
    }

    @Test
    fun `no charset parameter defaults to ISO-8859-1`() {
        val handler = DigestChallengeHandler("ué", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "algorithm" to "MD5"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        val ha1 = md5Hex("ué:r:p")
        val ha2 = md5Hex("GET:${parsed["uri"]}")
        val expected = md5Hex("$ha1:n:${parsed["nc"]}:${parsed["cnonce"]}:auth:$ha2")
        assertEquals(expected, parsed["response"])
    }

    // ---- digest-uri rendering ---------------------------------------------------

    @Test
    fun `digest URI includes the query string when present`() {
        val handler = DigestChallengeHandler("u", "p")
        val uriWithQuery = URI.create("https://api.example.com/dir/index.html?a=1&b=2")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uriWithQuery, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        assertEquals("/dir/index.html?a=1&b=2", parsed["uri"])
    }

    @Test
    fun `digest URI falls back to slash when path is empty`() {
        val handler = DigestChallengeHandler("u", "p")
        val uriRoot = URI.create("https://api.example.com")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uriRoot, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        assertEquals("/", parsed["uri"])
    }

    // ---- opaque -----------------------------------------------------------------

    @Test
    fun `opaque parameter passes through unchanged when present`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth", "opaque" to "the-opaque-value"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        assertEquals("the-opaque-value", parsed["opaque"])
    }

    @Test
    fun `opaque is omitted from the header when absent in the challenge`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        assertFalse(parsed.containsKey("opaque"))
    }

    // ---- nc formatting -----------------------------------------------------------

    @Test
    fun `nc is 8-char zero-padded lower-case hex`() {
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        val nc = parsed["nc"]
        assertNotNull(nc)
        assertEquals(8, nc.length, "nc must be 8 hex chars")
        assertTrue(nc.all { it in '0'..'9' || it in 'a'..'f' }, "nc must be lower-case hex")
    }

    @Test
    fun `concurrent nc increments produce monotonic, non-repeating sequence`() {
        // Verify thread safety of the AtomicLong-backed nc.
        val handler = DigestChallengeHandler("u", "p")
        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val threadCount = 8
        val iterationsPerThread = 50
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(threadCount)
        val all = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        try {
            repeat(threadCount) {
                pool.submit {
                    try {
                        start.await()
                        repeat(iterationsPerThread) {
                            val header = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!.second
                            val parsed = parseDigestHeader(header)
                            all.add(Integer.parseInt(parsed["nc"]!!, 16))
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS), "threads timed out")
        } finally {
            pool.shutdownNow()
        }
        // Every increment must be unique (1..N).
        val collected = all.toList()
        assertEquals(threadCount * iterationsPerThread, collected.size)
        assertEquals(collected.toSet().size, collected.size, "no duplicate nc values across threads")
        // Highest value should be threadCount*iterationsPerThread.
        assertEquals(threadCount * iterationsPerThread, collected.max())
    }

    // ---- AtomicLong overflow safety (H4) ----------------------------------------

    @Test
    fun `nc counter past Int MAX_VALUE stays positive and well-formed`() {
        // Verify that using AtomicLong prevents the counter from wrapping to negative
        // after surpassing Int.MAX_VALUE. We seed the counter via reflection so the test
        // completes in microseconds rather than performing 2^31 actual calls.
        val handler = DigestChallengeHandler("u", "p")
        val nonceCountField = DigestChallengeHandler::class.java
            .getDeclaredField("nonceCount")
        nonceCountField.isAccessible = true
        val atomicLong = nonceCountField.get(handler) as java.util.concurrent.atomic.AtomicLong
        // Seed to just below Int.MAX_VALUE so the next increment crosses the boundary.
        atomicLong.set(Int.MAX_VALUE.toLong())

        val challenge = AuthenticateChallenge(
            "Digest",
            mapOf("realm" to "r", "nonce" to "n", "qop" to "auth"),
        )
        val (_, value) = handler.handleChallenges(Method.GET, uri, listOf(challenge))!!
        val parsed = parseDigestHeader(value)
        val ncStr = parsed["nc"]!!

        // nc must be 8 hex chars.
        assertEquals(8, ncStr.length, "nc must be 8 hex chars after crossing Int.MAX_VALUE")
        assertTrue(ncStr.all { it in '0'..'9' || it in 'a'..'f' }, "nc must be lower-case hex")

        // The value after Int.MAX_VALUE (0x7fffffff) incremented by 1 is 0x80000000,
        // which is positive as a Long (unlike Int, which would overflow to negative).
        val ncValue = java.lang.Long.parseUnsignedLong(ncStr, 16)
        assertTrue(ncValue > Int.MAX_VALUE.toLong(), "nc must be greater than Int.MAX_VALUE when AtomicLong is used")
    }

    private fun md5HexUtf8(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    // ---- helpers ----------------------------------------------------------------

    /**
     * Minimal parser for an emitted Digest authorization header — splits into
     * `key=value` pairs, stripping surrounding quotes. We deliberately do NOT
     * reuse [AuthChallengeParser] here because we want a tiny independent
     * implementation that doesn't share bugs with the code under test.
     */
    private fun parseDigestHeader(header: String): Map<String, String> {
        require(header.startsWith("Digest ")) { "not a Digest header: $header" }
        val body = header.substring("Digest ".length)
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < body.length) {
            // skip whitespace and commas
            while (i < body.length && (body[i] == ' ' || body[i] == ',')) i++
            if (i >= body.length) break
            // read key
            val keyStart = i
            while (i < body.length && body[i] != '=') i++
            val key = body.substring(keyStart, i).trim()
            if (i >= body.length) break
            i++ // skip =
            // read value (quoted or unquoted)
            val value = if (i < body.length && body[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < body.length && body[i] != '"') {
                    if (body[i] == '\\' && i + 1 < body.length) {
                        sb.append(body[i + 1])
                        i += 2
                    } else {
                        sb.append(body[i])
                        i++
                    }
                }
                if (i < body.length) i++ // skip closing quote
                sb.toString()
            } else {
                val start = i
                while (i < body.length && body[i] != ',') i++
                body.substring(start, i).trim()
            }
            out[key] = value
        }
        return out
    }

    private fun md5Hex(s: String): String = hex(MessageDigest.getInstance("MD5"), s)
    private fun sha256Hex(s: String): String = hex(MessageDigest.getInstance("SHA-256"), s)

    private fun hex(md: MessageDigest, s: String): String {
        val bytes = md.digest(s.toByteArray(Charsets.ISO_8859_1))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }
}
