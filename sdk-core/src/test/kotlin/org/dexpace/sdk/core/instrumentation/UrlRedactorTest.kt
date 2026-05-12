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
    fun `fragment is preserved verbatim`() {
        val url = URL("https://api.example.com/x?api-version=1#section")
        val out = UrlRedactor.redact(url)
        assertTrue(out.endsWith("#section"), "expected preserved fragment in: $out")
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
}
