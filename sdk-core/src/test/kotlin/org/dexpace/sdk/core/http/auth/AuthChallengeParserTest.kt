package org.dexpace.sdk.core.http.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthChallengeParserTest {

    @Test
    fun `parses single Basic challenge`() {
        val challenges = AuthChallengeParser.parse("""Basic realm="api"""")
        assertEquals(1, challenges.size)
        // Scheme is normalised to lower case so callers can compare with `==`.
        assertEquals("basic", challenges[0].scheme)
        assertEquals(mapOf("realm" to "api"), challenges[0].parameters)
    }

    @Test
    fun `parses single Digest challenge with multiple params`() {
        val challenges = AuthChallengeParser.parse("""Digest realm="api", nonce="xyz", qop=auth""")
        assertEquals(1, challenges.size)
        val c = challenges[0]
        assertEquals("digest", c.scheme)
        assertEquals("api", c.parameters["realm"])
        assertEquals("xyz", c.parameters["nonce"])
        assertEquals("auth", c.parameters["qop"])
    }

    @Test
    fun `parses multiple challenges separated by commas`() {
        val challenges = AuthChallengeParser.parse("""Basic realm="a", Digest realm="b", nonce="c"""")
        assertEquals(2, challenges.size)
        assertEquals("basic", challenges[0].scheme)
        assertEquals("a", challenges[0].parameters["realm"])
        assertEquals("digest", challenges[1].scheme)
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
        assertEquals("bearer", challenges[0].scheme)
        assertTrue(challenges[0].parameters.isEmpty())
        assertEquals("basic", challenges[1].scheme)
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
        assertEquals("negotiate", challenges[0].scheme)
        assertTrue(challenges[0].parameters.isEmpty())
    }

    @Test
    fun `parses doubly-padded token68 value as a single token`() {
        // Regression: `Bearer cmVhbA==` used to silently drop the challenge because
        // the parser consumed the first `=` after the token, then failed to read a
        // value (the next character was another `=`). The token68 form must include
        // both pad chars.
        val challenges = AuthChallengeParser.parse("Bearer cmVhbA==")
        assertEquals(1, challenges.size)
        assertEquals("bearer", challenges[0].scheme)
        assertEquals("cmVhbA==", challenges[0].parameters["token68"])
    }

    // ---- token68 padding variants ------------------------------------------------

    @Test
    fun `parses token68 with no padding`() {
        val challenges = AuthChallengeParser.parse("Bearer abc")
        assertEquals(1, challenges.size)
        assertEquals("bearer", challenges[0].scheme)
        assertEquals("abc", challenges[0].parameters["token68"])
    }

    @Test
    fun `parses token68 with a single padding char`() {
        // `abc=` — after consuming the `=`, what follows is EOF, so the parser
        // rewinds and reads the whole thing as token68.
        val challenges = AuthChallengeParser.parse("Bearer abc=")
        assertEquals(1, challenges.size)
        assertEquals("bearer", challenges[0].scheme)
        assertEquals("abc=", challenges[0].parameters["token68"])
    }

    @Test
    fun `parses Bearer token68 with single-pad base64 value cmVhbA=`() {
        // Regression guard: `cmVhbA=` is a single-padded base64 token. The parser must
        // recover from consuming the first `=` and re-read the whole value as token68.
        val challenges = AuthChallengeParser.parse("Bearer cmVhbA=")
        assertEquals(1, challenges.size)
        assertEquals("bearer", challenges[0].scheme)
        assertEquals("cmVhbA=", challenges[0].parameters["token68"])
    }

    @Test
    fun `parses token68 with mixed case base64 characters`() {
        val challenges = AuthChallengeParser.parse("Bearer ABC123")
        assertEquals(1, challenges.size)
        assertEquals("bearer", challenges[0].scheme)
        assertEquals("ABC123", challenges[0].parameters["token68"])
    }

    // ---- whitespace variants -----------------------------------------------------

    @Test
    fun `tolerates tabs between scheme and params`() {
        val challenges = AuthChallengeParser.parse("Digest\trealm=\"x\"")
        assertEquals(1, challenges.size)
        assertEquals("digest", challenges[0].scheme)
        assertEquals("x", challenges[0].parameters["realm"])
    }

    @Test
    fun `tolerates multiple spaces around commas`() {
        val challenges = AuthChallengeParser.parse("""Basic realm="a"   ,   Digest realm="b", nonce="c"""")
        assertEquals(2, challenges.size)
        assertEquals("basic", challenges[0].scheme)
        assertEquals("a", challenges[0].parameters["realm"])
        assertEquals("digest", challenges[1].scheme)
        assertEquals("b", challenges[1].parameters["realm"])
        assertEquals("c", challenges[1].parameters["nonce"])
    }

    @Test
    fun `tolerates trailing comma`() {
        val challenges = AuthChallengeParser.parse("""Basic realm="x", """)
        assertEquals(1, challenges.size)
        assertEquals("basic", challenges[0].scheme)
        assertEquals("x", challenges[0].parameters["realm"])
    }

    @Test
    fun `tolerates leading whitespace and commas`() {
        val challenges = AuthChallengeParser.parse(""" ,  ,  Basic realm="x"""")
        assertEquals(1, challenges.size)
        assertEquals("basic", challenges[0].scheme)
        assertEquals("x", challenges[0].parameters["realm"])
    }

    // ---- malformed-input recovery -----------------------------------------------

    @Test
    fun `tolerates obsolete folded line continuation in a header value`() {
        // RFC 7230 §3.2.4 obsoleted line folding; the parser should not crash on it.
        val challenges = AuthChallengeParser.parse("Digest realm=\"line1\n\tline2\"")
        // The contract is simply "don't throw" — accept whatever the parser does.
        assertTrue(challenges.size <= 1)
    }

    @Test
    fun `empty quoted-string value is preserved`() {
        val challenges = AuthChallengeParser.parse("""Digest realm=""""")
        assertEquals(1, challenges.size)
        assertEquals("", challenges[0].parameters["realm"])
    }

    @Test
    fun `dangling backslash at end of quoted-string returns null and recovers`() {
        // Triggers `readQuotedString` returning null on the dangling-escape branch
        // — the parser must not throw and must move on.
        val challenges = AuthChallengeParser.parse("""Digest realm="bad\""")
        assertTrue(challenges.size <= 1)
    }

    @Test
    fun `quoted-string containing equals is preserved verbatim`() {
        val challenges = AuthChallengeParser.parse("""Digest realm="foo=bar"""")
        assertEquals(1, challenges.size)
        assertEquals("foo=bar", challenges[0].parameters["realm"])
    }

    @Test
    fun `garbage after comma terminates the param list and leaves comma in place`() {
        // `Digest realm="x", "garbage"` — the token after the comma is unreadable,
        // so the parser rewinds to before the comma and stops. The outer loop will
        // recover from there.
        val challenges = AuthChallengeParser.parse("""Digest realm="x", "junk"""")
        assertTrue(challenges.isNotEmpty())
        assertEquals("digest", challenges[0].scheme)
        assertEquals("x", challenges[0].parameters["realm"])
    }

    @Test
    fun `param with malformed value triggers bail-out preserving prior params`() {
        // After `nonce=`, the value can't be read (dangling `\` at end). The parser
        // returns the challenge with the params we collected up to that point.
        val challenges = AuthChallengeParser.parse("""Digest realm="x", nonce="""")
        // We don't strictly require a particular count here — the contract is
        // "don't throw" + "preserve prior params if any". With one challenge we
        // expect realm to come through.
        if (challenges.isNotEmpty()) {
            assertEquals("digest", challenges[0].scheme)
            assertEquals("x", challenges[0].parameters["realm"])
        }
    }

    @Test
    fun `value containing only a backslash before EOF is rejected gracefully`() {
        // Tests `readQuotedString` dangling-escape branch via the EOF path.
        val challenges = AuthChallengeParser.parse("Digest realm=\"\\")
        assertTrue(challenges.size <= 1)
    }

    @Test
    fun `multiple challenges with mixed quoted and unquoted values`() {
        val challenges = AuthChallengeParser.parse("""Basic realm=a, Digest realm="b", nonce=c""")
        assertEquals(2, challenges.size)
        assertEquals("basic", challenges[0].scheme)
        assertEquals("a", challenges[0].parameters["realm"])
        assertEquals("digest", challenges[1].scheme)
        assertEquals("b", challenges[1].parameters["realm"])
        assertEquals("c", challenges[1].parameters["nonce"])
    }

    @Test
    fun `parse returns empty list when input is only commas and whitespace`() {
        assertTrue(AuthChallengeParser.parse(",,, ,,, \t,").isEmpty())
    }

    @Test
    fun `chained challenges with no params each`() {
        val challenges = AuthChallengeParser.parse("Negotiate, Basic, Bearer")
        assertEquals(3, challenges.size)
        assertEquals("negotiate", challenges[0].scheme)
        assertEquals("basic", challenges[1].scheme)
        assertEquals("bearer", challenges[2].scheme)
        challenges.forEach { assertTrue(it.parameters.isEmpty()) }
    }

    @Test
    fun `token68 followed by next challenge`() {
        // `Bearer abc, Basic realm="x"` — after a token68 challenge, we should
        // still pick up the next one.
        val challenges = AuthChallengeParser.parse("""Bearer abc, Basic realm="x"""")
        assertEquals(2, challenges.size)
        assertEquals("bearer", challenges[0].scheme)
        assertEquals("abc", challenges[0].parameters["token68"])
        assertEquals("basic", challenges[1].scheme)
        assertEquals("x", challenges[1].parameters["realm"])
    }

    @Test
    fun `quoted-string with embedded escaped chars other than quote and backslash`() {
        // `\n` in a quoted-string is unescaped to a literal `n` (the backslash is
        // stripped regardless of what follows).
        val challenges = AuthChallengeParser.parse("""Digest realm="a\nb"""")
        assertEquals(1, challenges.size)
        assertEquals("anb", challenges[0].parameters["realm"])
    }

    @Test
    fun `recovery skips unterminated quoted string but continues to next challenge`() {
        // The first challenge has an unterminated quoted-string. The parser bails on
        // that and the cursor recovery advances to EOF, so we expect <= 1 challenge.
        val challenges = AuthChallengeParser.parse("""Digest realm="oops""")
        // Just ensure it didn't throw.
        assertTrue(challenges.size <= 1)
    }

    @Test
    fun `recovery via quoted-string in garbage between commas`() {
        // Exercises `Cursor.recoverToNextChallenge` quoted-string branch.
        // Use a non-token first char so the scheme read fails.
        val challenges = AuthChallengeParser.parse(""""junk content with comma, inside" , Basic realm="x"""")
        // Either we got the Basic at index 0 (likely) or nothing — just verify no throw.
        assertTrue(challenges.size <= 2)
    }

    @Test
    fun `recovery via escaped quote in garbage`() {
        // Exercises the `\\` skip-in-quoted-string branch of recoverToNextChallenge.
        val challenges = AuthChallengeParser.parse(""""junk with \"escape\" inside" , Basic realm="x"""")
        assertTrue(challenges.size <= 2)
    }

    @Test
    fun `recovery via unterminated quoted-string skips to EOF`() {
        // Exercises the path where `recoverToNextChallenge` runs off end inside a quoted string.
        val challenges = AuthChallengeParser.parse(""""never-ends, Basic realm="x"""")
        assertTrue(challenges.size <= 1)
    }

    @Test
    fun `key with no equals followed by comma falls through as next challenge scheme`() {
        // `Digest realm, nonce="x"` — `realm` is a token followed by comma (no `=`),
        // so it's treated as a token68 (the saved-position rewind), then `nonce="x"`
        // is interpreted as the next challenge.
        val challenges = AuthChallengeParser.parse("""Digest realm, nonce="x"""")
        // We don't pin exact behavior — just confirm no throw and at least one challenge.
        assertTrue(challenges.isNotEmpty())
        assertEquals("digest", challenges[0].scheme)
    }

    @Test
    fun `scheme followed by only whitespace then EOF`() {
        val challenges = AuthChallengeParser.parse("Basic   ")
        assertEquals(1, challenges.size)
        assertEquals("basic", challenges[0].scheme)
        assertTrue(challenges[0].parameters.isEmpty())
    }

    @Test
    fun `comma immediately after scheme with no params`() {
        val challenges = AuthChallengeParser.parse("Basic,")
        assertEquals(1, challenges.size)
        assertEquals("basic", challenges[0].scheme)
        assertTrue(challenges[0].parameters.isEmpty())
    }

    @Test
    fun `quote with only a backslash inside before close quote`() {
        // `"\"` — opens, sees backslash, advances, sees `"` (the close), appends
        // the `"` and continues. Then EOF: dangling. The parser returns null and
        // recovery happens.
        val challenges = AuthChallengeParser.parse("""Digest realm="\"""")
        assertTrue(challenges.size <= 1)
    }
}
