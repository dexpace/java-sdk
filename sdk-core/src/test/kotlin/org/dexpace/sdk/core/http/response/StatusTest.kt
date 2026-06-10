/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StatusTest {
    @Test
    fun `fromCode returns the matching status for a known code`() {
        assertEquals(Status.OK, Status.fromCode(200))
        assertEquals(Status.NOT_FOUND, Status.fromCode(404))
        assertEquals(Status.INTERNAL_SERVER_ERROR, Status.fromCode(500))
        assertEquals(Status.IM_A_TEAPOT, Status.fromCode(418))
        assertEquals(Status.THIS_IS_FINE, Status.fromCode(218))
    }

    @Test
    fun `fromCode returns canonical singletons for known codes`() {
        // Known codes resolve to the exact canonical constant, not a fresh instance.
        assertSame(Status.OK, Status.fromCode(200))
        assertSame(Status.GONE, Status.fromCode(410))
    }

    @Test
    fun `fromCode returns a total Status with null statusName for an unknown code`() {
        // 999 is not in the canonical set: fromCode no longer throws, it surfaces the code.
        val unknown = Status.fromCode(999)
        assertEquals(999, unknown.code)
        assertNull(unknown.statusName)
    }

    @Test
    fun `fromCode surfaces real vendor codes without throwing`() {
        // nginx 499 and Cloudflare 520-526/530 are real wire values the SDK must not reject.
        for (vendorCode in intArrayOf(499, 520, 521, 522, 523, 524, 525, 526, 530)) {
            val status = Status.fromCode(vendorCode)
            assertEquals(vendorCode, status.code)
            assertNull(status.statusName, "vendor code $vendorCode must not be treated as canonical")
            assertFalse(status.isSuccess)
        }
    }

    @Test
    fun `fromCodeOrNull returns the matching status for a known code`() {
        assertEquals(Status.OK, Status.fromCodeOrNull(200))
        assertEquals(Status.GONE, Status.fromCodeOrNull(410))
    }

    @Test
    fun `fromCodeOrNull returns null for an unknown code`() {
        assertNull(Status.fromCodeOrNull(999))
        assertNull(Status.fromCodeOrNull(-1))
        assertNull(Status.fromCodeOrNull(0))
    }

    @Test
    fun `isSuccess is true for every 2xx status`() {
        for (s in Status.canonicalStatuses) {
            if (s.code in 200..299) {
                assertTrue(s.isSuccess, "$s should be considered successful")
            }
        }
        // Spot-checks
        assertTrue(Status.OK.isSuccess)
        assertTrue(Status.CREATED.isSuccess)
        assertTrue(Status.NO_CONTENT.isSuccess)
        assertTrue(Status.PARTIAL_CONTENT.isSuccess)
    }

    @Test
    fun `isSuccess is false for non-2xx statuses`() {
        assertFalse(Status.CONTINUE.isSuccess)
        assertFalse(Status.SWITCHING_PROTOCOLS.isSuccess)
        assertFalse(Status.MOVED_PERMANENTLY.isSuccess)
        assertFalse(Status.BAD_REQUEST.isSuccess)
        assertFalse(Status.NOT_FOUND.isSuccess)
        assertFalse(Status.INTERNAL_SERVER_ERROR.isSuccess)
        // Non-standard 218 is in the 2xx range and IS reported as success — guard rail
        // for that boundary.
        assertTrue(Status.THIS_IS_FINE.isSuccess)
    }

    @Test
    fun `isSuccess honours the 2xx range for unknown codes too`() {
        assertTrue(Status.fromCode(299).isSuccess)
        assertFalse(Status.fromCode(300).isSuccess)
        assertFalse(Status.fromCode(199).isSuccess)
    }

    @Test
    fun `every canonical Status code is uniquely recoverable via fromCode`() {
        for (s in Status.canonicalStatuses) {
            assertEquals(s, Status.fromCode(s.code), "round trip failed for $s")
        }
    }

    // ---- value semantics -------------------------------------------------------

    @Test
    fun `equality and hashCode are driven by code`() {
        assertEquals(Status.OK, Status.fromCode(200))
        assertEquals(Status.OK.hashCode(), Status.fromCode(200).hashCode())

        // Two unknown statuses with the same code are equal.
        assertEquals(Status.fromCode(599), Status.fromCode(599))
        assertEquals(Status.fromCode(599).hashCode(), Status.fromCode(599).hashCode())

        assertNotEquals(Status.OK, Status.NOT_FOUND)
        assertNotEquals<Any?>(Status.OK, null)
        assertNotEquals<Any?>(Status.OK, 200)
    }

    @Test
    fun `toString names canonical codes and falls back for unknown codes`() {
        assertEquals("OK(200)", Status.OK.toString())
        assertEquals("NOT_FOUND(404)", Status.NOT_FOUND.toString())
        assertEquals("HTTP 520", Status.fromCode(520).toString())
        assertEquals("HTTP 499", Status.fromCode(499).toString())
    }

    @Test
    fun `canonical constants expose a statusName`() {
        assertEquals("OK", Status.OK.statusName)
        assertEquals("INTERNAL_SERVER_ERROR", Status.INTERNAL_SERVER_ERROR.statusName)
    }

    // ---- LOOKUP map coverage ---------------------------------------------------

    @Test
    fun `fromCode returns correct entries for representative codes`() {
        assertEquals(Status.OK, Status.fromCode(200))
        assertEquals(Status.NOT_FOUND, Status.fromCode(404))
        assertEquals(Status.INTERNAL_SERVER_ERROR, Status.fromCode(500))
        assertEquals(Status.CONTINUE, Status.fromCode(100))
    }

    @Test
    fun `fromCodeOrNull returns correct entries for representative codes`() {
        assertEquals(Status.OK, Status.fromCodeOrNull(200))
        assertEquals(Status.NOT_FOUND, Status.fromCodeOrNull(404))
        assertEquals(Status.INTERNAL_SERVER_ERROR, Status.fromCodeOrNull(500))
        assertEquals(Status.CONTINUE, Status.fromCodeOrNull(100))
    }

    @Test
    fun `fromCodeOrNull returns null for non-existent codes`() {
        assertNull(Status.fromCodeOrNull(1))
        assertNull(Status.fromCodeOrNull(99))
        assertNull(Status.fromCodeOrNull(600))
        assertNull(Status.fromCodeOrNull(Integer.MAX_VALUE))
    }

    @Test
    fun `fromCode is total for non-existent codes`() {
        val s1 = Status.fromCode(1)
        assertEquals(1, s1.code)
        assertNull(s1.statusName)

        val s2 = Status.fromCode(600)
        assertEquals(600, s2.code)
        assertNull(s2.statusName)

        val sMax = Status.fromCode(Integer.MAX_VALUE)
        assertEquals(Integer.MAX_VALUE, sMax.code)
        assertNull(sMax.statusName)
    }
}
