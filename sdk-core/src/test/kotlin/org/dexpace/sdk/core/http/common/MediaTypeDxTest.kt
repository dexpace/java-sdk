/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.common

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaTypeDxTest {
    @Test
    fun `APPLICATION_JSON companion val has correct fullType`() {
        assertEquals("application/json", MediaType.APPLICATION_JSON.fullType)
    }

    @Test
    fun `APPLICATION_JSON companion val equals CommonMediaTypes APPLICATION_JSON`() {
        assertEquals(CommonMediaTypes.APPLICATION_JSON, MediaType.APPLICATION_JSON)
    }

    @Test
    fun `TEXT_PLAIN companion val has correct fullType`() {
        assertEquals("text/plain", MediaType.TEXT_PLAIN.fullType)
    }

    @Test
    fun `TEXT_PLAIN companion val equals CommonMediaTypes TEXT_PLAIN`() {
        assertEquals(CommonMediaTypes.TEXT_PLAIN, MediaType.TEXT_PLAIN)
    }

    @Test
    fun `APPLICATION_OCTET_STREAM companion val has correct fullType`() {
        assertEquals("application/octet-stream", MediaType.APPLICATION_OCTET_STREAM.fullType)
    }

    @Test
    fun `APPLICATION_OCTET_STREAM companion val equals CommonMediaTypes APPLICATION_OCTET_STREAM`() {
        assertEquals(CommonMediaTypes.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_OCTET_STREAM)
    }

    @Test
    fun `APPLICATION_FORM_URLENCODED companion val has correct fullType`() {
        assertEquals("application/x-www-form-urlencoded", MediaType.APPLICATION_FORM_URLENCODED.fullType)
    }

    @Test
    fun `APPLICATION_FORM_URLENCODED companion val equals CommonMediaTypes APPLICATION_FORM_URLENCODED`() {
        assertEquals(CommonMediaTypes.APPLICATION_FORM_URLENCODED, MediaType.APPLICATION_FORM_URLENCODED)
    }

    @Test
    fun `TEXT_HTML companion val has correct fullType`() {
        assertEquals("text/html", MediaType.TEXT_HTML.fullType)
    }

    @Test
    fun `TEXT_HTML companion val equals CommonMediaTypes TEXT_HTML`() {
        assertEquals(CommonMediaTypes.TEXT_HTML, MediaType.TEXT_HTML)
    }

    @Test
    fun `APPLICATION_XML companion val has correct fullType`() {
        assertEquals("application/xml", MediaType.APPLICATION_XML.fullType)
    }

    @Test
    fun `APPLICATION_XML companion val equals CommonMediaTypes APPLICATION_XML`() {
        assertEquals(CommonMediaTypes.APPLICATION_XML, MediaType.APPLICATION_XML)
    }
}
