package org.dexpace.sdk.core.http.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DigestAlgorithmTest {
    @Test
    fun `enum exposes all four supported algorithms`() {
        val all = DigestAlgorithm.entries.toSet()
        assertEquals(
            setOf(
                DigestAlgorithm.MD5,
                DigestAlgorithm.MD5_SESS,
                DigestAlgorithm.SHA_256,
                DigestAlgorithm.SHA_256_SESS,
            ),
            all,
        )
        assertEquals(4, DigestAlgorithm.entries.size)
    }

    @Test
    fun `values array round-trips with entries`() {
        assertEquals(DigestAlgorithm.entries, DigestAlgorithm.values().toList())
    }

    @Test
    fun `valueOf reverses name for each entry`() {
        for (entry in DigestAlgorithm.entries) {
            assertSame(entry, DigestAlgorithm.valueOf(entry.name))
        }
    }

    @Test
    fun `javaName matches the JCA algorithm name`() {
        assertEquals("MD5", DigestAlgorithm.MD5.javaName)
        assertEquals("MD5", DigestAlgorithm.MD5_SESS.javaName)
        assertEquals("SHA-256", DigestAlgorithm.SHA_256.javaName)
        assertEquals("SHA-256", DigestAlgorithm.SHA_256_SESS.javaName)
    }

    @Test
    fun `headerName matches the RFC 7616 spelling`() {
        assertEquals("MD5", DigestAlgorithm.MD5.headerName)
        assertEquals("MD5-sess", DigestAlgorithm.MD5_SESS.headerName)
        assertEquals("SHA-256", DigestAlgorithm.SHA_256.headerName)
        assertEquals("SHA-256-sess", DigestAlgorithm.SHA_256_SESS.headerName)
    }

    @Test
    fun `sessionVariant flag is set for sess variants only`() {
        assertFalse(DigestAlgorithm.MD5.sessionVariant)
        assertTrue(DigestAlgorithm.MD5_SESS.sessionVariant)
        assertFalse(DigestAlgorithm.SHA_256.sessionVariant)
        assertTrue(DigestAlgorithm.SHA_256_SESS.sessionVariant)
    }

    @Test
    fun `fromString accepts canonical spelling for each algorithm`() {
        assertSame(DigestAlgorithm.MD5, DigestAlgorithm.fromString("MD5"))
        assertSame(DigestAlgorithm.MD5_SESS, DigestAlgorithm.fromString("MD5-sess"))
        assertSame(DigestAlgorithm.SHA_256, DigestAlgorithm.fromString("SHA-256"))
        assertSame(DigestAlgorithm.SHA_256_SESS, DigestAlgorithm.fromString("SHA-256-sess"))
    }

    @Test
    fun `fromString is case-insensitive`() {
        assertSame(DigestAlgorithm.MD5, DigestAlgorithm.fromString("md5"))
        assertSame(DigestAlgorithm.MD5_SESS, DigestAlgorithm.fromString("md5-SESS"))
        assertSame(DigestAlgorithm.SHA_256, DigestAlgorithm.fromString("sha-256"))
        assertSame(DigestAlgorithm.SHA_256_SESS, DigestAlgorithm.fromString("sha-256-sess"))
    }

    @Test
    fun `fromString returns null for unsupported algorithm SHA-512-256`() {
        assertNull(DigestAlgorithm.fromString("SHA-512-256"))
    }

    @Test
    fun `fromString returns null for unknown garbage input`() {
        assertNull(DigestAlgorithm.fromString("not-an-algorithm"))
        assertNull(DigestAlgorithm.fromString(""))
    }
}
