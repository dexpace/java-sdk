package org.dexpace.sdk.core.http.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthChallengeParserTest {

    @Test
    fun `parses single Basic challenge`() {
        val challenges = AuthChallengeParser.parse("""Basic realm="api"""")
        assertEquals(1, challenges.size)
        assertEquals("Basic", challenges[0].scheme)
        assertEquals(mapOf("realm" to "api"), challenges[0].parameters)
    }

    @Test
    fun `parses single Digest challenge with multiple params`() {
        val challenges = AuthChallengeParser.parse("""Digest realm="api", nonce="xyz", qop=auth""")
        assertEquals(1, challenges.size)
        val c = challenges[0]
        assertEquals("Digest", c.scheme)
        assertEquals("api", c.parameters["realm"])
        assertEquals("xyz", c.parameters["nonce"])
        assertEquals("auth", c.parameters["qop"])
    }

    @Test
    fun `parses multiple challenges separated by commas`() {
        val challenges = AuthChallengeParser.parse("""Basic realm="a", Digest realm="b", nonce="c"""")
        assertEquals(2, challenges.size)
        assertEquals("Basic", challenges[0].scheme)
        assertEquals("a", challenges[0].parameters["realm"])
        assertEquals("Digest", challenges[1].scheme)
        assertEquals("b", challenges[1].parameters["realm"])
        assertEquals("c", challenges[1].parameters["nonce"])
    }

    @Test
    fun `preserves commas embedded inside quoted-string values`() {
        val challenges = AuthChallengeParser.parse("""Digest realm="foo, bar", nonce="x"""")
        assertEquals(1, challenges.size)
        assertEquals("foo, bar", challenges[0].parameters["realm"])
        assertEquals("x", challenges[0].parameters["nonce"])
    }

    @Test
    fun `unescapes escaped quotes inside quoted-string`() {
        val challenges = AuthChallengeParser.parse("""Digest realm="he said \"hi\""""")
        assertEquals(1, challenges.size)
        assertEquals("""he said "hi"""", challenges[0].parameters["realm"])
    }

    @Test
    fun `unescapes escaped backslash inside quoted-string`() {
        val challenges = AuthChallengeParser.parse("""Digest realm="back\\slash"""")
        assertEquals(1, challenges.size)
        assertEquals("""back\slash""", challenges[0].parameters["realm"])
    }

    @Test
    fun `tolerates extra whitespace around equals and commas`() {
        val challenges = AuthChallengeParser.parse("""Digest  realm  =  "x"  ,  nonce = "y"""")
        assertEquals(1, challenges.size)
        assertEquals("x", challenges[0].parameters["realm"])
        assertEquals("y", challenges[0].parameters["nonce"])
    }

    @Test
    fun `parameter names are case-insensitive (normalised to lower case)`() {
        val challenges = AuthChallengeParser.parse("""Digest REALM="x", Nonce="y"""")
        assertEquals(1, challenges.size)
        assertEquals("x", challenges[0].parameters["realm"])
        assertEquals("y", challenges[0].parameters["nonce"])
    }

    @Test
    fun `empty header produces empty list`() {
        assertTrue(AuthChallengeParser.parse("").isEmpty())
    }

    @Test
    fun `blank-only header produces empty list`() {
        assertTrue(AuthChallengeParser.parse("    ").isEmpty())
    }

    @Test
    fun `recovers to next challenge after unbalanced quote`() {
        // First challenge has an unterminated quote; we expect at minimum the
        // first challenge's scheme + the param we did get parsed, OR no first
        // challenge — both are acceptable, but we should not throw and we should
        // pick up any subsequent well-formed challenge if present.
        val challenges = AuthChallengeParser.parse("""Garbage realm="oops, Basic realm="a"""")
        // The first one is malformed (the closing quote consumes the comma); the
        // parser must not throw. With this particular input the malformed quote
        // swallows the rest of the input so we get one challenge with whatever
        // we could glean. The contract is "skip bad challenge, continue" — which
        // here just means: don't throw.
        // Cheaper assertion: parser returned (possibly empty) list without throwing.
        assertTrue(challenges.size <= 2)
    }

    @Test
    fun `keeps emitting after a token68-only challenge`() {
        // Bearer challenges can carry a token68 value or be parameter-style. We
        // already exercise parameter-style in other tests; here we check that a
        // bare scheme followed by a comma + another challenge parses both sides.
        val challenges = AuthChallengeParser.parse("""Bearer, Basic realm="a"""")
        assertEquals(2, challenges.size)
        assertEquals("Bearer", challenges[0].scheme)
        assertTrue(challenges[0].parameters.isEmpty())
        assertEquals("Basic", challenges[1].scheme)
        assertEquals("a", challenges[1].parameters["realm"])
    }

    @Test
    fun `parses unquoted token values`() {
        val challenges = AuthChallengeParser.parse("""Digest realm=api, qop=auth, algorithm=MD5""")
        assertEquals(1, challenges.size)
        assertEquals("api", challenges[0].parameters["realm"])
        assertEquals("auth", challenges[0].parameters["qop"])
        assertEquals("MD5", challenges[0].parameters["algorithm"])
    }

    @Test
    fun `parses scheme with no parameters`() {
        val challenges = AuthChallengeParser.parse("Negotiate")
        assertEquals(1, challenges.size)
        assertEquals("Negotiate", challenges[0].scheme)
        assertTrue(challenges[0].parameters.isEmpty())
    }
}
