/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CallTimeoutTest {
    @Test
    fun `NONE inherits every phase`() {
        val none = CallTimeout.NONE
        assertTrue(none.isEmpty())
        assertNull(none.connect)
        assertNull(none.write)
        assertNull(none.read)
        assertNull(none.call)
    }

    @Test
    fun `builder sets individual phases`() {
        val timeout =
            CallTimeout.builder()
                .connect(Duration.ofSeconds(1))
                .write(Duration.ofSeconds(2))
                .read(Duration.ofSeconds(3))
                .call(Duration.ofSeconds(4))
                .build()

        assertEquals(Duration.ofSeconds(1), timeout.connect)
        assertEquals(Duration.ofSeconds(2), timeout.write)
        assertEquals(Duration.ofSeconds(3), timeout.read)
        assertEquals(Duration.ofSeconds(4), timeout.call)
        assertFalse(timeout.isEmpty())
    }

    @Test
    fun `ofCall sets only the call phase`() {
        val timeout = CallTimeout.ofCall(Duration.ofSeconds(5))
        assertEquals(Duration.ofSeconds(5), timeout.call)
        assertNull(timeout.connect)
        assertNull(timeout.read)
        assertNull(timeout.write)
    }

    @Test
    fun `newBuilder round-trips`() {
        val original = CallTimeout.builder().read(Duration.ofSeconds(7)).build()
        val rebuilt = original.newBuilder().build()
        assertEquals(original, rebuilt)
    }

    @Test
    fun `applyDefaults takes receiver value when set`() {
        val receiver = CallTimeout.builder().connect(Duration.ofSeconds(1)).build()
        val defaults = CallTimeout.builder().connect(Duration.ofSeconds(9)).read(Duration.ofSeconds(2)).build()

        val merged = receiver.applyDefaults(defaults)

        // Receiver's connect wins; read falls through from defaults.
        assertEquals(Duration.ofSeconds(1), merged.connect)
        assertEquals(Duration.ofSeconds(2), merged.read)
    }

    @Test
    fun `applyDefaults coalesces unset phases from defaults`() {
        val receiver = CallTimeout.NONE
        val defaults =
            CallTimeout.builder()
                .connect(Duration.ofSeconds(1))
                .call(Duration.ofSeconds(8))
                .build()

        val merged = receiver.applyDefaults(defaults)
        assertEquals(defaults, merged)
    }

    @Test
    fun `applyDefaults returns this when nothing changes`() {
        val full =
            CallTimeout.builder()
                .connect(Duration.ofSeconds(1))
                .write(Duration.ofSeconds(2))
                .read(Duration.ofSeconds(3))
                .call(Duration.ofSeconds(4))
                .build()

        // A fully-specified overlay ignores any defaults; the merge is identity.
        assertSame(full, full.applyDefaults(CallTimeout.ofCall(Duration.ofSeconds(99))))
        assertSame(full, full.applyDefaults(CallTimeout.NONE))
    }

    @Test
    fun `applyDefaults is associative as a first-non-null overlay`() {
        val a = CallTimeout.builder().connect(Duration.ofSeconds(1)).build()
        val b = CallTimeout.builder().connect(Duration.ofSeconds(2)).read(Duration.ofSeconds(2)).build()
        val c = CallTimeout.builder().read(Duration.ofSeconds(3)).call(Duration.ofSeconds(3)).build()

        val merged = a.applyDefaults(b).applyDefaults(c)

        assertEquals(Duration.ofSeconds(1), merged.connect)
        assertEquals(Duration.ofSeconds(2), merged.read)
        assertEquals(Duration.ofSeconds(3), merged.call)
        assertNull(merged.write)
    }

    @Test
    fun `negative phase is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CallTimeout.builder().read(Duration.ofSeconds(-1)).build()
        }
    }

    @Test
    fun `zero phase is allowed`() {
        val timeout = CallTimeout.builder().call(Duration.ZERO).build()
        assertEquals(Duration.ZERO, timeout.call)
    }
}
