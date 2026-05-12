package org.dexpace.sdk.core.http.auth

import java.util.Locale

/**
 * Parses RFC 7235 `WWW-Authenticate` / `Proxy-Authenticate` header values.
 *
 * Grammar (simplified from RFC 7235 §2.1):
 * ```
 * challenge   = auth-scheme [ 1*SP ( token68 / #auth-param ) ]
 * auth-param  = token BWS "=" BWS ( token / quoted-string )
 * token68     = 1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *"="
 * ```
 *
 * Multiple challenges may appear in one header separated by commas. Quoted-string
 * values may contain commas and `=` characters, which must NOT be treated as
 * delimiters. Escape sequences (`\"`, `\\`) inside quoted strings are unescaped on
 * read; surrounding quotes are stripped from the stored value.
 *
 * Malformed challenges are skipped — the parser does not throw on bad input. This
 * matches the [edge-case guardrails][to-implement.md] for Rank 12.
 */
object AuthChallengeParser {

    /**
     * Parses [header] and returns the list of challenges it contains. Returns an
     * empty list for blank input or input that contains no recognisable scheme.
     *
     * Parameter names are normalised to lower case (US locale); values are stored
     * verbatim with quoting and escaping removed.
     */
    @JvmStatic
    fun parse(header: String): List<AuthenticateChallenge> {
        if (header.isEmpty()) return emptyList()
        val cursor = Cursor(header)
        val out = ArrayList<AuthenticateChallenge>(2)
        while (cursor.hasMore()) {
            cursor.skipOwsAndCommas()
            if (!cursor.hasMore()) break
            val challenge = parseChallenge(cursor) ?: continue
            out.add(challenge)
        }
        return out
    }

    /**
     * Parses one challenge starting at the cursor. Advances the cursor past the
     * challenge (and any trailing whitespace/commas). Returns null and recovers to
     * the next top-level comma on malformed input.
     */
    private fun parseChallenge(cursor: Cursor): AuthenticateChallenge? {
        val scheme = cursor.readToken() ?: run {
            cursor.recoverToNextChallenge()
            return null
        }
        cursor.skipOws()

        // After the scheme: zero or more auth-params (separated by commas), or a
        // bare token68. If we see EOF or a comma immediately, the challenge has no
        // params — still emit it (some Bearer challenges look like `Bearer`).
        if (!cursor.hasMore() || cursor.peek() == ',') {
            return AuthenticateChallenge(scheme, emptyMap())
        }

        val params = LinkedHashMap<String, String>(4)
        val first = parseAuthParamOrToken68(cursor)
        if (first == null) {
            cursor.recoverToNextChallenge()
            return null
        }
        params[first.first] = first.second

        // Subsequent params; stop when we hit something that doesn't look like a
        // param (likely the next challenge's scheme).
        while (true) {
            cursor.skipOws()
            if (!cursor.hasMore() || cursor.peek() != ',') break
            // Save position before consuming the comma — if what follows is the
            // next scheme rather than a param of THIS challenge, we need to leave
            // the comma in place for the outer loop.
            val saved = cursor.position
            cursor.advance() // consume comma
            cursor.skipOws()
            if (!cursor.hasMore()) break

            val nextToken = cursor.readToken()
            if (nextToken == null) {
                // garbage after comma — break out
                cursor.position = saved
                break
            }
            cursor.skipOws()
            if (cursor.hasMore() && cursor.peek() == '=') {
                cursor.advance()
                cursor.skipOws()
                val value = cursor.readTokenOrQuotedString()
                if (value == null) {
                    // malformed — bail out of this challenge but leave cursor here.
                    return AuthenticateChallenge(scheme, params)
                }
                params[nextToken.lowercase(Locale.US)] = value
            } else {
                // The token we just consumed is actually the next challenge's
                // scheme — rewind so the outer loop can pick it up.
                cursor.position = saved
                break
            }
        }
        return AuthenticateChallenge(scheme, params)
    }

    /**
     * Parses either an `auth-param` (token "=" token-or-quoted-string) or, failing
     * that, treats the input as a `token68` value and stores it under the synthetic
     * key `"token68"`. Returns null on unrecoverable malformation.
     */
    private fun parseAuthParamOrToken68(cursor: Cursor): Pair<String, String>? {
        val saved = cursor.position
        val name = cursor.readToken() ?: return null
        cursor.skipOws()
        if (cursor.hasMore() && cursor.peek() == '=') {
            cursor.advance()
            cursor.skipOws()
            // Could be token68 (ends in `=` padding) or a regular value. We've
            // already consumed the `=`; check whether what follows is a value.
            if (!cursor.hasMore() || cursor.peek() == ',') {
                // looked like `key=` with nothing after — try to treat the whole
                // thing as token68 (rewind and read it as such).
                cursor.position = saved
                val token68 = cursor.readToken68() ?: return null
                return "token68" to token68
            }
            val value = cursor.readTokenOrQuotedString()
                ?: return null
            return name.lowercase(Locale.US) to value
        }
        // No `=` after the token — it's a token68.
        cursor.position = saved
        val token68 = cursor.readToken68() ?: return null
        return "token68" to token68
    }

    // ---- internal cursor ---------------------------------------------------------

    /**
     * Mutable position over a header string. Methods that "read" advance the
     * position past whatever they consume; methods that "peek" do not.
     */
    private class Cursor(private val src: String) {
        var position: Int = 0
        private val len: Int = src.length

        fun hasMore(): Boolean = position < len
        fun peek(): Char = src[position]
        fun advance() { position++ }

        fun skipOws() {
            while (position < len) {
                val c = src[position]
                if (c == ' ' || c == '\t') position++ else break
            }
        }

        fun skipOwsAndCommas() {
            while (position < len) {
                val c = src[position]
                if (c == ' ' || c == '\t' || c == ',') position++ else break
            }
        }

        /**
         * Reads a token per RFC 7230 §3.2.6. Returns null if no valid token char is
         * present at the current position.
         */
        fun readToken(): String? {
            val start = position
            while (position < len && isTokenChar(src[position])) position++
            return if (position == start) null else src.substring(start, position)
        }

        /**
         * Reads either a quoted string (with unescaping) or a token. Returns the
         * value with quotes stripped and escapes resolved.
         */
        fun readTokenOrQuotedString(): String? {
            if (!hasMore()) return null
            return if (peek() == '"') readQuotedString() else readToken()
        }

        /**
         * Reads a quoted-string per RFC 7230 §3.2.6. Handles `\"`, `\\`, and any
         * other escaped char by stripping the backslash. Returns null if the string
         * is unterminated and advances the cursor to end-of-input in that case
         * (caller will recover).
         */
        fun readQuotedString(): String? {
            if (!hasMore() || peek() != '"') return null
            position++ // opening quote
            val sb = StringBuilder()
            while (position < len) {
                val c = src[position]
                when {
                    c == '\\' -> {
                        position++
                        if (position >= len) return null // dangling escape
                        sb.append(src[position])
                        position++
                    }
                    c == '"' -> {
                        position++ // closing quote
                        return sb.toString()
                    }
                    else -> {
                        sb.append(c)
                        position++
                    }
                }
            }
            // ran off the end without seeing closing quote
            return null
        }

        /** Reads a token68 (RFC 7235): 1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *"=". */
        fun readToken68(): String? {
            val start = position
            while (position < len && isToken68Char(src[position])) position++
            while (position < len && src[position] == '=') position++
            return if (position == start) null else src.substring(start, position)
        }

        /** Advances to the next top-level comma (skipping over quoted strings). */
        fun recoverToNextChallenge() {
            while (position < len) {
                val c = src[position]
                when (c) {
                    ',' -> return
                    '"' -> {
                        // skip the quoted string — but if it's unterminated, just
                        // jump to EOF.
                        position++
                        while (position < len && src[position] != '"') {
                            if (src[position] == '\\' && position + 1 < len) position++
                            position++
                        }
                        if (position < len) position++ // closing quote
                    }
                    else -> position++
                }
            }
        }
    }

    /** RFC 7230 token char: ALPHA / DIGIT / one of the punctuation set. */
    private fun isTokenChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
            c == '!' || c == '#' || c == '$' || c == '%' || c == '&' ||
            c == '\'' || c == '*' || c == '+' || c == '-' || c == '.' ||
            c == '^' || c == '_' || c == '`' || c == '|' || c == '~'

    /** RFC 7235 token68 char (excluding the trailing "=" pad, handled separately). */
    private fun isToken68Char(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
            c == '-' || c == '.' || c == '_' || c == '~' || c == '+' || c == '/'
}
