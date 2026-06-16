/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.common.MediaType
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BodyPreviewTest {
    @Test
    fun `empty bytes render to empty string`() {
        assertEquals("", BodyPreview.render(ByteArray(0), MediaType.parse("text/plain")))
    }

    @Test
    fun `text body declared as ISO-8859-1 is decoded with that charset`() {
        // 0xE9 is 'é' in ISO-8859-1. Decoded as UTF-8 it would be the U+FFFD replacement char.
        val bytes = "café".toByteArray(StandardCharsets.ISO_8859_1)
        val preview = BodyPreview.render(bytes, MediaType.parse("text/plain;charset=ISO-8859-1"))
        assertEquals("café", preview)
        assertFalse(preview.contains('�'), "latin-1 body must not be decoded as mojibake")
    }

    @Test
    fun `text body declared as UTF-8 is decoded with UTF-8`() {
        val bytes = "naïve — text".toByteArray(StandardCharsets.UTF_8)
        assertEquals("naïve — text", BodyPreview.render(bytes, MediaType.parse("text/plain;charset=utf-8")))
    }

    @Test
    fun `text body with no declared charset falls back to UTF-8`() {
        val bytes = "résumé".toByteArray(StandardCharsets.UTF_8)
        // No charset parameter on the media type → documented default is UTF-8.
        assertEquals("résumé", BodyPreview.render(bytes, MediaType.parse("text/plain")))
        // A null media type also falls back to UTF-8.
        assertEquals("résumé", BodyPreview.render(bytes, null))
    }

    @Test
    fun `unknown declared charset falls back to UTF-8`() {
        val bytes = "hello".toByteArray(StandardCharsets.UTF_8)
        // MediaType.charset returns null for an unrecognised charset name; preview falls back.
        assertEquals("hello", BodyPreview.render(bytes, MediaType.parse("text/plain;charset=not-a-real-charset")))
    }

    @Test
    fun `binary body with NUL byte is summarised as size only and not decoded`() {
        // gzip magic header (0x1f 0x8b 0x08) followed by a NUL byte — a representative binary body.
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03)
        val preview = BodyPreview.render(bytes, MediaType.parse("application/gzip"))
        assertEquals("[binary ${bytes.size} bytes]", preview)
        assertFalse(preview.contains('�'), "binary body must not be rendered as replacement chars")
    }

    @Test
    fun `binary body declared with a text charset is still detected as binary`() {
        // Even if a server mislabels a gzip stream as text, the NUL byte keeps it from being decoded.
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 0x00, 0x42, 0x00, 0x13)
        val preview = BodyPreview.render(bytes, MediaType.parse("text/plain;charset=utf-8"))
        assertEquals("[binary ${bytes.size} bytes]", preview)
    }

    @Test
    fun `text body declared as UTF-16 is decoded despite NUL padding`() {
        // UTF-16BE pads ASCII with NUL bytes ('A' = 0x00 0x41), which the NUL heuristic would
        // otherwise treat as binary. An explicitly declared wide charset must still decode.
        val bytes = "café".toByteArray(StandardCharsets.UTF_16BE)
        val preview = BodyPreview.render(bytes, MediaType.parse("text/plain;charset=UTF-16BE"))
        assertEquals("café", preview)
        assertFalse(preview.contains("[binary"), "declared UTF-16 text must not be summarised as binary")
    }

    @Test
    fun `text body declared as UTF-16LE is decoded despite NUL padding`() {
        val bytes = "hello".toByteArray(StandardCharsets.UTF_16LE)
        assertEquals("hello", BodyPreview.render(bytes, MediaType.parse("text/plain;charset=UTF-16LE")))
    }

    @Test
    fun `isProbablyText accepts plain ASCII and latin-1 high bytes`() {
        assertTrue(BodyPreview.isProbablyText("plain ascii text".toByteArray(StandardCharsets.US_ASCII)))
        assertTrue(BodyPreview.isProbablyText("café".toByteArray(StandardCharsets.ISO_8859_1)))
        // Common text whitespace control bytes do not flip the verdict.
        assertTrue(BodyPreview.isProbablyText("line1\r\n\tline2".toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `isProbablyText rejects a NUL byte and a control-byte-heavy body`() {
        assertFalse(BodyPreview.isProbablyText(byteArrayOf(0x41, 0x00, 0x42)))
        // A run of non-whitespace C0 control bytes exceeds the ratio cutoff.
        assertFalse(BodyPreview.isProbablyText(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)))
    }
}
