/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

/**
 * Validates an HTTP header name at the transport-agnostic model layer. Shared by the
 * String-keyed [Headers.Builder] API and the typed [HttpHeaderName.fromString] entry point so a
 * malformed name cannot slip through either one — the two were previously inconsistent (only the
 * String API validated, and only against the raw input).
 *
 * The check runs on the **trimmed** name, matching the trimming both call sites apply before
 * storing or interning. `String.trim()` strips only the surrounding *whitespace* code points —
 * space, tab, CR, LF, and the C0 information separators (`0x1C`–`0x1F`) — so a leading or trailing
 * one of those is removed before it could reach the wire and is harmless. Every other control byte
 * survives the trim: a surrounding NUL or DEL, like any *interior* control character, is rejected.
 * What is rejected:
 *
 *  - **A blank name.** A field-name must be a non-empty RFC 7230 `token`; an empty or
 *    all-whitespace name has no canonical form.
 *  - **Any interior control character** — the C0 control range and DEL (code points `0x00`–`0x1F`
 *    and `0x7F`), which covers CR, LF, and NUL. An embedded `\r`/`\n` is the same
 *    request/header-splitting vector guarded against for header values: once the name is
 *    serialised an attacker could inject a new header or a second request. A NUL or other control
 *    character is illegal in a field-name, and the two reference transports handle it differently at
 *    their raw API (OkHttp's `addHeader` throws unchecked, the JDK builder drops it); their adapters
 *    now catch and drop uniformly, but a splitting vector should never get that far. Validating here
 *    rejects it loudly at construction — fast, uniform, and transport-independent.
 *
 * Policy: the control-character set is intentionally narrower than RFC 7230's full `tchar`
 * allow-list — restricting names to `tchar` would reject some non-ASCII names that certain
 * transports accept, whereas the control-character set is illegal everywhere and covers the
 * splitting/injection surface. This mirrors the conservative CR/LF-only stance taken for values.
 */
@JvmSynthetic
internal fun requireValidHeaderName(rawName: String) {
    val trimmed = rawName.trim()
    require(trimmed.isNotEmpty()) { "Header name must not be blank." }
    trimmed.forEach { ch ->
        require(ch.code > LAST_C0_CONTROL && ch.code != DEL_CONTROL) {
            "Header name '${escapeControlCharacters(rawName)}' must not contain control characters " +
                "(carriage return, line feed, NUL, or other C0/DEL bytes); " +
                "such characters enable request/header splitting."
        }
    }
}

/**
 * Renders [name] for an error message with every control character replaced by its `\uXXXX`
 * escape, so a raw CR/LF/NUL from the rejected name never lands verbatim in a log line while the
 * printable portion still identifies the offending header.
 */
private fun escapeControlCharacters(name: String): String =
    buildString {
        name.forEach { ch ->
            if (ch.code <= LAST_C0_CONTROL || ch.code == DEL_CONTROL) {
                append("\\u")
                append(ch.code.toString(HEX_RADIX).padStart(ESCAPE_HEX_WIDTH, '0'))
            } else {
                append(ch)
            }
        }
    }

/** Highest code point in the C0 control range (US, `0x1F`); everything at or below is illegal in a name. */
private const val LAST_C0_CONTROL: Int = 0x1F

/** The DEL control character (`0x7F`), the lone control code above the C0 range. */
private const val DEL_CONTROL: Int = 0x7F

/** Radix for rendering a control character's code point as the hex digits of a `\uXXXX` escape. */
private const val HEX_RADIX: Int = 16

/** Zero-padded width of a `\uXXXX` escape's hex digits. */
private const val ESCAPE_HEX_WIDTH: Int = 4
