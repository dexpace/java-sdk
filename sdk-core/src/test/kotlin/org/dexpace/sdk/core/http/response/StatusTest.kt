package org.dexpace.sdk.core.http.response

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `fromCode throws IllegalArgumentException for an unknown code`() {
        val ex = assertFailsWith<IllegalArgumentException> { Status.fromCode(999) }
        assertEquals("Invalid status code: 999", ex.message)
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
        for (s in Status.entries) {
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
    fun `every Status code is uniquely recoverable via fromCode`() {
        for (s in Status.entries) {
            assertEquals(s, Status.fromCode(s.code), "round trip failed for $s")
        }
    }
}
