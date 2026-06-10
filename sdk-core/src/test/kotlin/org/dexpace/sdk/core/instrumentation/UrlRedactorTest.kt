/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UrlRedactorTest {
    @Test
    fun `URL with no query and no userinfo returns identity`() {
        val url = URL("https://api.example.com/path")
        assertEquals("https://api.example.com/path", UrlRedactor.redact(url))
    }

    @Test
    fun `userinfo is always redacted regardless of allow-list`() {
        val url = URL("https://user:secret@api.example.com/path")
        val out = UrlRedactor.redact(url)
        assertTrue(out.contains("***:***@"), "expected userinfo redaction in: $out")
        assertTrue(!out.contains("secret"), "raw password leaked: $out")
        assertTrue(!out.contains("user@"), "raw username leaked: $out")
    }

    @Test
    fun `allow-listed query param is preserved`() {
        val url = URL("https://api.example.com/x?api-version=2024-01")
        assertEquals("https://api.example.com/x?api-version=2024-01", UrlRedactor.redact(url))
    }

    @Test
    fun `disallowed query param is redacted`() {
        val url = URL("https://api.example.com/x?token=abc123")
        val out = UrlRedactor.redact(url)
        assertTrue(out.contains("token="), "key should be kept: $out")
        assertTrue(out.contains("***"), "value should be redacted: $out")
        assertTrue(!out.contains("abc123"), "raw token leaked: $out")
    }

    @Test
    fun `mixed allowed and disallowed params are independently redacted`() {
        val url = URL("https://api.example.com/x?api-version=2024-01&token=abc")
        val out = UrlRedactor.redact(url)
        assertTrue(out.contains("api-version=2024-01"))
        assertTrue(out.contains("token="))
        assertTrue(!out.contains("abc"), "token value leaked: $out")
    }

    @Test
    fun `empty query string is preserved`() {
        val url = URL("https://api.example.com/x?")
        val out = UrlRedactor.redact(url)
        assertTrue(out.endsWith("?"), "expected preserved '?' in: $out")
    }

    @Test
    fun `fragment without equals sign is preserved verbatim`() {
        val url = URL("https://api.example.com/x?api-version=1#section")
        val out = UrlRedactor.redact(url)
        assertTrue(out.endsWith("#section"), "expected preserved fragment in: $out")
    }

    @Test
    fun `fragment with disallowed query-param-shaped token is redacted`() {
        val url = URL("https://api.example.com/x#token=secret")
        val out = UrlRedactor.redact(url)
        assertTrue(out.contains("#"), "fragment marker should be present: $out")
        assertTrue(out.contains("token="), "fragment key should be kept: $out")
        assertTrue(out.contains("***"), "fragment value should be redacted: $out")
        assertTrue(!out.contains("secret"), "raw fragment value leaked: $out")
    }

    @Test
    fun `allow-list match is case-insensitive on the param name`() {
        val url = URL("https://api.example.com/x?Api-Version=2024-01")
        assertEquals(
            "https://api.example.com/x?Api-Version=2024-01",
            UrlRedactor.redact(url, setOf("api-version")),
        )
    }

    @Test
    fun `empty allow-list redacts every value`() {
        val url = URL("https://api.example.com/x?api-version=2024-01&token=abc")
        val out = UrlRedactor.redact(url, emptySet())
        assertTrue(!out.contains("2024-01"), "expected api-version value redacted: $out")
        assertTrue(!out.contains("abc"), "expected token value redacted: $out")
    }

    @Test
    fun `multi-value same-key params behave atomically per key`() {
        val url = URL("https://api.example.com/x?token=a&token=b")
        val out = UrlRedactor.redact(url)
        assertTrue(!out.contains("=a"), "first token value leaked: $out")
        assertTrue(!out.contains("=b"), "second token value leaked: $out")
    }

    @Test
    fun `key without value is preserved without crashing`() {
        val url = URL("https://api.example.com/x?flag&token=abc")
        val out = UrlRedactor.redact(url)
        assertTrue(out.contains("flag"), "flag key dropped: $out")
        assertTrue(!out.contains("abc"), "token value leaked: $out")
    }

    @Test
    fun `port is preserved`() {
        val url = URL("https://api.example.com:8443/x?api-version=1")
        assertEquals("https://api.example.com:8443/x?api-version=1", UrlRedactor.redact(url))
    }

    @Test
    fun `userinfo with query both get redacted`() {
        val url = URL("https://u:p@api.example.com/x?token=abc")
        val out = UrlRedactor.redact(url)
        assertTrue(out.contains("***:***@"))
        assertTrue(!out.contains("abc"))
    }

    @Test
    fun `non-https scheme still works`() {
        val url = URL("http://api.example.com/x?token=abc")
        val out = UrlRedactor.redact(url)
        assertTrue(out.startsWith("http://"))
        assertTrue(!out.contains("abc"))
    }

    @Test
    fun `question mark only in fragment does not insert a spurious query separator`() {
        // http://h/p#a?b=c has url.query == null — the only '?' lives inside the fragment.
        // The redacted output must NOT gain a stray '?' before the '#' (which the old
        // whole-string raw.indexOf('?') scan produced, corrupting it to http://h/p?#a?b=c).
        val url = URL("http://h/p#a?b=c")
        val out = UrlRedactor.redact(url)
        // No query component exists, so there must be no '?' before the fragment marker.
        val hashIndex = out.indexOf('#')
        assertTrue(hashIndex >= 0, "fragment marker should be present: $out")
        assertTrue(
            !out.substring(0, hashIndex).contains('?'),
            "no spurious '?' must appear before the fragment: $out",
        )
        // Path is preserved and the fragment is carried through (its 'b' value is redacted
        // because it is a key=value token under the allow-list).
        assertTrue(out.startsWith("http://h/p#"), "path/fragment boundary corrupted: $out")
    }

    @Test
    fun `fragment-only question mark with userinfo still redacts without stray separator`() {
        // Combine the fragment-only '?' case with userinfo to ensure the rebuild path (not the
        // identity fast path) also avoids the spurious query separator.
        val url = URL("http://user:pass@h/p#a?b=c")
        val out = UrlRedactor.redact(url)
        assertTrue(out.contains("***:***@"), "userinfo should be redacted: $out")
        assertTrue(!out.contains("pass"), "raw password leaked: $out")
        val hashIndex = out.indexOf('#')
        assertTrue(hashIndex >= 0, "fragment marker should be present: $out")
        assertTrue(
            !out.substring(0, hashIndex).contains('?'),
            "no spurious '?' must appear before the fragment: $out",
        )
    }

    @Test
    fun `fragment with trailing ampersand drops the trailing separator`() {
        // A fragment like "token=secret&" has a trailing '&' that produces an empty final
        // pair. appendRedactedQuery intentionally drops this empty pair — the output contains
        // the redacted key=value without a trailing '&'. This is the documented behaviour:
        // a trailing '&' in a fragment or query is almost always malformed input.
        val url = URL("https://api.example.com/x#token=secret&")
        val out = UrlRedactor.redact(url)
        assertTrue(out.contains("#"), "fragment marker should be present: $out")
        assertTrue(out.contains("token="), "fragment key should be kept: $out")
        assertTrue(out.contains("***"), "fragment value should be redacted: $out")
        assertTrue(!out.contains("secret"), "raw value leaked: $out")
        // The trailing '&' is intentionally dropped — the output must NOT end with '&'.
        assertTrue(!out.endsWith("&"), "trailing '&' must be dropped; got: $out")
    }
}
