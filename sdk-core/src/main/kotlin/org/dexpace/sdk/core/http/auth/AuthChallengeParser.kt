/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

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
public object AuthChallengeParser {
    // Initial capacity for the per-challenge auth-param map. Auth challenges typically
    // carry 1-3 params (realm, qop, nonce); 4 covers the common case without resize.
    private const val PARAM_MAP_INITIAL_CAP = 4

    /**
     * Parses [header] and returns the list of challenges it contains. Returns an
     * empty list for blank input or input that contains no recognisable scheme.
     *
     * Both scheme and parameter names are normalised to lower case (US locale);
     * values are stored verbatim with quoting and escaping removed.
     */
    @JvmStatic
    public fun parse(header: String): List<AuthenticateChallenge> {
        if (header.isBlank()) return emptyList()
        val cursor = Cursor(header)
        val out = ArrayList<AuthenticateChallenge>(2)
        // Top-level parse loop: distinct break/continue for `EOF after OWS+comma skip`,
        // `unparseable challenge — recover and continue`. Flattening these would obscure
        // the malformed-input recovery contract.
        @Suppress("LoopWithTooManyJumpStatements")
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
     *
     * Per-challenge parser: each guard branch (no scheme, bare-scheme challenge,
     * malformed first param, malformed continuation param) is a distinct recovery
     * path. Combining would force a sentinel result and obscure the per-mode logging.
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun parseChallenge(cursor: Cursor): AuthenticateChallenge? {
        val rawScheme =
            cursor.readToken() ?: run {
                cursor.recoverToNextChallenge()
                return null
            }
        // Normalise the scheme to lower case so callers can compare with a simple
        // `==` (matches the same treatment we give to parameter names below).
        val scheme = rawScheme.lowercase(Locale.US)
        cursor.skipOws()

        // After the scheme: zero or more auth-params (separated by commas), or a
        // bare token68. If we see EOF or a comma immediately, the challenge has no
        // params — still emit it (some Bearer challenges look like `Bearer`).
        if (!cursor.hasMore() || cursor.peek() == ',') {
            return AuthenticateChallenge(scheme, emptyMap())
        }

        val params = LinkedHashMap<String, String>(PARAM_MAP_INITIAL_CAP)
        val first =
            parseAuthParamOrToken68(cursor) ?: run {
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
                val value =
                    cursor.readTokenOrQuotedString()
                        ?: return AuthenticateChallenge(scheme, params) // malformed — bail
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
     *
     * Five distinct token68/auth-param disambiguation branches each have their own
     * rewind and `return "token68" to …` shape; folding to a single return would
     * mean threading a sentinel value through deeply-nested if's at the cost of clarity.
     */
    @Suppress("ReturnCount")
    private fun parseAuthParamOrToken68(cursor: Cursor): Pair<String, String>? {
        val saved = cursor.position

        fun rewindAsToken68(): Pair<String, String>? {
            cursor.position = saved
            val token68 = cursor.readToken68() ?: return null
            return "token68" to token68
        }

        val name = cursor.readToken() ?: return null
        cursor.skipOws()
        if (!cursor.hasMore() || cursor.peek() != '=') {
            // No `=` after the token — it's a token68.
            return rewindAsToken68()
        }
        cursor.advance() // consume the first `=`

        // Token68 may carry one or more `=` pad chars (e.g. `cmVhbA==`). After
        // consuming the first `=`, if the very next character is another `=`, we
        // are clearly looking at token68 padding rather than the BWS-allowed gap
        // before an auth-param value — rewind and reparse as token68 so the
        // entire `cmVhbA==` is recovered. Without this branch a doubly-padded
        // base64 token would be silently dropped.
        if (cursor.hasMore() && cursor.peek() == '=') {
            return rewindAsToken68()
        }
        cursor.skipOws()

        // We've already consumed the `=`; check whether what follows is a value.
        if (!cursor.hasMore() || cursor.peek() == ',') {
            // looked like `key=` with nothing after — try to treat the whole
            // thing as token68 (rewind and read it as such).
            return rewindAsToken68()
        }
        val value = cursor.readTokenOrQuotedString() ?: return null
        return name.lowercase(Locale.US) to value
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

        fun advance() {
            position++
        }

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
                when (c) {
                    '\\' -> {
                        position++
                        if (position >= len) return null // dangling escape
                        sb.append(src[position])
                        position++
                    }
                    '"' -> {
                        position++ // closing quote
                        return sb.toString()
                    }
                    else -> {
                        sb.append(c)
                        position++
                    }
                }
            }
            // Ran off the end without seeing closing quote.
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
                when (src[position]) {
                    ',' -> return
                    // Reuse the escape-aware reader; on an unterminated string it
                    // consumes through to EOF, exactly where recovery wants to land.
                    '"' -> readQuotedString()
                    else -> position++
                }
            }
        }
    }

    private const val TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~"

    private const val TOKEN68_PUNCTUATION = "-._~+/"

    /** RFC 7230 token char: ALPHA / DIGIT / one of the punctuation set. */
    private fun isTokenChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in TOKEN_PUNCTUATION

    /** RFC 7235 token68 char (excluding the trailing "=" pad, handled separately). */
    private fun isToken68Char(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in TOKEN68_PUNCTUATION
}
