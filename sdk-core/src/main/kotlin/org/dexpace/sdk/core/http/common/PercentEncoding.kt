/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Shared percent-encoding for a single URL **component** — a query parameter name/value, or a
 * path segment — using RFC 3986 semantics for spaces and `+`.
 *
 * This is deliberately *not* `application/x-www-form-urlencoded`: a space is `%20` (not `+`),
 * and a literal `+` is preserved as `%2B` rather than being read back as a space. Both
 * directions go through the JDK's [URLEncoder] / [URLDecoder] (so multibyte UTF-8 and malformed
 * input are handled by battle-tested code), with the `+`/space convention corrected on each side:
 *
 * - **encode**: `URLEncoder` emits `+` for a space, so the `+`s it produces are exactly the
 *   spaces — rewrite them to `%20`. Any literal `+` in the input is already `%2B` by then.
 * - **decode**: `URLDecoder` would turn `+` into a space, so escape literal `+` to `%2B`
 *   before decoding, leaving real spaces (`%20`) to decode normally.
 *
 * The encoder percent-encodes everything except RFC 3986 unreserved characters, which makes it
 * safe for both query components and path segments: a `/` becomes `%2F`, so a path-parameter
 * value cannot inject extra path segments. A query string assembled as a form *body* would need
 * the form scheme instead; that is a separate concern (a future form-body type).
 */
internal object PercentEncoding {
    private const val UTF_8: String = "UTF-8"

    /** Percent-encodes a single URL component (space → `%20`, literal `+` → `%2B`, `/` → `%2F`). */
    internal fun encodeComponent(component: String): String = URLEncoder.encode(component, UTF_8).replace("+", "%20")

    /**
     * Decodes a single URL component. A literal `+` stays a `+` (RFC 3986, not a space).
     * Malformed percent-encoding falls back to the raw text rather than throwing, so a legacy
     * URL a transport already accepted cannot break parsing.
     */
    internal fun decodeComponent(component: String): String =
        try {
            URLDecoder.decode(component.replace("+", "%2B"), UTF_8)
        } catch (ignored: IllegalArgumentException) {
            component
        }
}
