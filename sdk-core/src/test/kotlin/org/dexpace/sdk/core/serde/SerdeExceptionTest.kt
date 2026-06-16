/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SerdeExceptionTest {
    @Test
    fun `SerdeException is a RuntimeException so callers need no checked-exception plumbing`() {
        // Reflective check (not a compile-time-foldable `is`) that the supertype is RuntimeException.
        assertTrue(RuntimeException::class.java.isAssignableFrom(SerdeException::class.java))
    }

    @Test
    fun `SerdeException carries message and chained cause`() {
        val root = IllegalStateException("root")
        val ex = SerdeException("wrapped", root)
        assertEquals("wrapped", ex.message)
        assertSame(root, ex.cause)
    }

    @Test
    fun `SerdeException message-only constructor leaves cause null`() {
        val ex = SerdeException("just a message")
        assertEquals("just a message", ex.message)
        assertNull(ex.cause)
    }

    @Test
    fun `SerializationException is a SerdeException`() {
        val ex = SerializationException("write failed", RuntimeException("inner"))
        assertTrue(SerdeException::class.java.isAssignableFrom(SerializationException::class.java))
        assertEquals("write failed", ex.message)
    }

    @Test
    fun `DeserializationException is a SerdeException`() {
        val ex = DeserializationException("read failed", RuntimeException("inner"))
        assertTrue(SerdeException::class.java.isAssignableFrom(DeserializationException::class.java))
        assertEquals("read failed", ex.message)
    }

    @Test
    fun `SerdeException is open so adapters and codegen can subclass it`() {
        // A bespoke subclass compiles only if SerdeException is `open`; this also documents the
        // extension point for adapter-specific error types.
        class CustomSerdeException(message: String) : SerdeException(message)
        assertTrue(SerdeException::class.java.isAssignableFrom(CustomSerdeException::class.java))
    }
}
