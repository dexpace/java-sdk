/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

/**
 * Validates an HTTP header name at the transport-agnostic model layer and returns its trimmed
 * form. Shared by the String-keyed [Headers.Builder] API and the typed [HttpHeaderName.fromString]
 * entry point so a malformed name cannot slip through either one — the two were previously
 * inconsistent (only the String API validated, and only against the raw input). The trimmed name is
 * returned so callers reuse it instead of trimming a second time.
 *
 * The check runs on the **trimmed** name. `String.trim()` removes only surrounding *whitespace* —
 * the full Unicode class `Char.isWhitespace` recognises (`Character.isWhitespace ||
 * Character.isSpaceChar`: ASCII space and tab, the C0 line/separator controls, and the Unicode
 * space separators such as NBSP) — so leading or trailing whitespace is stripped before it could
 * reach the wire and is harmless. A surrounding control byte that is *not* whitespace — NUL, DEL,
 * and the other non-whitespace C0 codes — survives the trim and is rejected, exactly like an
 * *interior* control character. What is rejected:
 *
 *  - **A blank name.** A field-name must be a non-empty RFC 7230 `token`; an empty or
 *    all-whitespace name has no canonical form.
 *  - **Any interior control character** — the C0 control range and DEL (code points `0x00`–`0x1F`
 *    and `0x7F`), which covers CR, LF, and NUL. An embedded `\r`/`\n` is the same
 *    request/header-splitting vector guarded against for header values: once the name is
 *    serialised an attacker could inject a new header or a second request. A NUL or other control
 *    character is illegal in a field-name; validating here rejects it loudly at construction —
 *    fast, uniform, and transport-independent.
 *  - **Any non-ASCII byte** (code point `>= 0x80`). RFC 7230 field-names are ASCII `token`s, and
 *    every shipped reference transport (OkHttp, the JDK `HttpClient`) rejects a non-ASCII name at
 *    its own model layer, so a name no transport can put on the wire is refused here at
 *    construction rather than accepted by the model and then silently dropped mid-dispatch.
 *
 * Policy: the accepted set is printable ASCII (`0x20`–`0x7E`) — still wider than RFC 7230's
 * `tchar`, so a name a transport's stricter grammar rejects (e.g. an interior space) is left for
 * the transport to drop rather than pre-judged here. Control characters and non-ASCII bytes, which
 * no transport can encode, are rejected outright. This mirrors the stance taken for values in
 * [requireValidHeaderValues].
 *
 * @return the trimmed, validated name
 * @throws IllegalArgumentException if the trimmed name is blank, or contains a control character or
 *   a non-ASCII byte
 */
@JvmSynthetic
internal fun requireValidHeaderName(rawName: String): String {
    val trimmed = rawName.trim()
    require(trimmed.isNotEmpty()) { "Header name must not be blank." }
    trimmed.forEach { ch ->
        require(!isProhibitedInName(ch.code)) {
            "Header name '${escapeControlCharacters(rawName)}' must be printable ASCII: it must not " +
                "contain control characters (carriage return, line feed, NUL, or other C0/DEL bytes) " +
                "or non-ASCII bytes; control characters enable request/header splitting, and no " +
                "reference transport can encode a non-ASCII name."
        }
    }
    return trimmed
}

/**
 * Validates the [values] of a header [name] at the transport-agnostic model layer, applying the
 * same control-character policy as [requireValidHeaderName] with **one deliberate exception**:
 * horizontal tab (`0x09`) is permitted. Unlike a field-name `token`, an RFC 7230 field-value may
 * carry HTAB as whitespace between field-content, and the two reference transports accept it (it is
 * the one control byte OkHttp's value rule allows), so rejecting it would refuse a legitimate value.
 *
 * Every other C0 control (`0x00`–`0x1F`, which covers CR, LF, and NUL) and DEL (`0x7F`) is
 * rejected. A bare CR/LF is the request/header-splitting vector — once a value is serialised an
 * attacker could inject a new header or a second request — and the remaining control bytes are
 * illegal in a field-value on every transport. The earlier policy here rejected only CR/LF; the
 * broader control-character set closes the same splitting/injection surface the name check does
 * while staying narrower than the strict field-value grammar.
 *
 * Non-ASCII bytes (code point `>= 0x80`) are rejected as well, matching OkHttp's field-value
 * grammar (HTAB plus printable ASCII `0x20`–`0x7E`). The shipped transports are not unanimous here
 * — the JDK `HttpClient` will serialise obs-text (`0x80`+) on the wire — so the model deliberately
 * takes the *most restrictive* shipped transport's stance: a value it accepts is sendable by every
 * shipped transport, and a value only some can encode is refused at construction rather than
 * silently dropped by the stricter one mid-dispatch. This governs **outbound, caller-set** values;
 * a header parsed from an already-received response takes the lenient
 * [requireValidInboundHeaderValue] path (via `Headers.Builder.addUnsafeNonAscii`), which preserves
 * the obs-text a server may legitimately send. [name] only labels the error message; the value
 * itself is never echoed, so a secret or oversized value is not leaked into a log line.
 *
 * @throws IllegalArgumentException if any value contains a prohibited control character or a
 *   non-ASCII byte
 */
@JvmSynthetic
internal fun requireValidHeaderValues(
    name: String,
    values: List<String>,
) {
    values.forEach { value ->
        value.forEach { ch ->
            require(!isProhibitedInValue(ch.code)) {
                "Header value for '$name' must be ASCII: it must not contain control characters " +
                    "(carriage return, line feed, NUL, or other C0/DEL bytes, except horizontal tab) " +
                    "or non-ASCII bytes; control characters enable request/header splitting, and " +
                    "OkHttp's field-value grammar rejects a non-ASCII value."
            }
        }
    }
}

/**
 * Validates the [value] of an *inbound* (response) header [name] leniently. Like OkHttp's
 * `addUnsafeNonAscii`/`addLenient` read path, the ASCII restriction is dropped so **non-ASCII bytes
 * are permitted**; unlike that path — which does no value validation at all — control characters are
 * still rejected here as defence-in-depth against a misbehaving transport (a stray CR/LF/NUL has no
 * place in a parsed field-value). RFC 7230 explicitly allows obs-text (`0x80`+) in field-content,
 * and a server legitimately puts Latin-1 bytes in values such as a `Content-Disposition` filename;
 * applying the stricter outbound grammar of [requireValidHeaderValues] to a response would silently
 * strip those headers. [name] only labels the error message; the value itself is never echoed.
 *
 * @throws IllegalArgumentException if [value] contains a prohibited control character
 */
@JvmSynthetic
internal fun requireValidInboundHeaderValue(
    name: String,
    value: String,
) {
    value.forEach { ch ->
        require(!isProhibitedInInboundValue(ch.code)) {
            "Inbound header value for '$name' must not contain control characters (carriage " +
                "return, line feed, NUL, or other C0/DEL bytes, except horizontal tab)."
        }
    }
}

/**
 * Whether [code] is prohibited in a header name — the full C0 range, DEL, and every non-ASCII byte
 * (`>= 0x80`). `code >= DEL_CONTROL` covers DEL (`0x7F`) and all of `0x80`+ in one comparison.
 */
private fun isProhibitedInName(code: Int): Boolean = code <= LAST_C0_CONTROL || code >= DEL_CONTROL

/**
 * Whether [code] is prohibited in an outbound header value — the same set as for a name, minus
 * horizontal tab (`0x09`), which RFC 7230 permits as field-value whitespace. Shared with [MediaType]
 * so a media type's rendered form and the header value it becomes agree on the accepted byte set.
 */
@JvmSynthetic
internal fun isProhibitedInValue(code: Int): Boolean =
    (code <= LAST_C0_CONTROL && code != HORIZONTAL_TAB) || code >= DEL_CONTROL

/**
 * Whether [code] is prohibited in an *inbound* header value: control characters (`0x00`–`0x1F`
 * except HTAB, plus DEL `0x7F`) are still rejected, but non-ASCII bytes (`0x80`+) are permitted —
 * the obs-text a server may legitimately send. See [requireValidInboundHeaderValue].
 */
private fun isProhibitedInInboundValue(code: Int): Boolean =
    (code <= LAST_C0_CONTROL && code != HORIZONTAL_TAB) || code == DEL_CONTROL

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

/** Horizontal tab (`0x09`) — the one C0 control RFC 7230 permits in a field-value (but not a name). */
private const val HORIZONTAL_TAB: Int = 0x09

/** Highest code point in the C0 control range (US, `0x1F`); everything at or below is illegal in a name. */
private const val LAST_C0_CONTROL: Int = 0x1F

/**
 * The DEL control character (`0x7F`). Used as the low bound of the rejected high range: for a name
 * and an outbound value, DEL and every byte above it (the non-ASCII bytes, `0x80`+) are prohibited
 * (`code >= DEL_CONTROL`); an inbound value rejects only DEL itself (`code == DEL_CONTROL`),
 * permitting obs-text.
 */
private const val DEL_CONTROL: Int = 0x7F

/** Radix for rendering a control character's code point as the hex digits of a `\uXXXX` escape. */
private const val HEX_RADIX: Int = 16

/** Zero-padded width of a `\uXXXX` escape's hex digits. */
private const val ESCAPE_HEX_WIDTH: Int = 4
