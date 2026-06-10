/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TristateTest {
    // ----- A-7: stable toString for the sentinel objects -----

    @Test
    fun `Absent toString is stable and identity-free`() {
        assertEquals("Absent", Tristate.Absent.toString())
        // No identity hash leaking into the rendering.
        assertFalse(Tristate.Absent.toString().contains("@"))
    }

    @Test
    fun `Null toString is stable and identity-free`() {
        assertEquals("Null", Tristate.Null.toString())
        assertFalse(Tristate.Null.toString().contains("@"))
    }

    @Test
    fun `Present toString renders the data-class form`() {
        assertEquals("Present(value=hi)", Tristate.Present("hi").toString())
    }

    // ----- A-3: present / Present are bounded to T : Any, so Present(null) is unreachable -----

    @Test
    fun `present wraps a non-null value as Present`() {
        val t = Tristate.present("v")
        assertTrue(t.isPresent)
        assertEquals(Tristate.Present("v"), t)
    }

    @Test
    fun `ofNullable routes null to Null and non-null to Present`() {
        assertSame(Tristate.Null, Tristate.ofNullable<String>(null))
        assertEquals(Tristate.Present("x"), Tristate.ofNullable("x"))
    }

    @Test
    fun `Present is never equal to Null`() {
        // The illegal fourth state Present(null) cannot be constructed (T : Any bound), so a
        // Present is always distinguishable from the explicit-null sentinel.
        val present: Tristate<String> = Tristate.present("anything")
        assertFalse(present == Tristate.Null)
        assertFalse(present == Tristate.Absent)
    }

    // ----- Core sum-type behaviour -----

    @Test
    fun `factories return the expected variants`() {
        assertSame(Tristate.Absent, Tristate.absent<String>())
        assertSame(Tristate.Null, Tristate.nullValue<String>())
        assertTrue(Tristate.present(1).isPresent)
    }

    @Test
    fun `getOrNull yields value for Present and null otherwise`() {
        assertEquals("v", Tristate.present("v").getOrNull())
        assertEquals(null, Tristate.Null.getOrNull())
        assertEquals(null, Tristate.Absent.getOrNull())
    }

    @Test
    fun `fold dispatches to the matching branch`() {
        fun <T> label(t: Tristate<T>): String =
            t.fold(onAbsent = { "absent" }, onNull = { "null" }, onPresent = { "present:$it" })
        assertEquals("absent", label(Tristate.Absent))
        assertEquals("null", label(Tristate.Null))
        assertEquals("present:7", label(Tristate.present(7)))
    }
}
