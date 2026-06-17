/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.auth.KeyCredential
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CallOptionsTest {
    private companion object {
        private val RETRYABLE: CallOption<Boolean> = CallOption("retryable")
        private val LABEL: CallOption<String> = CallOption("label")
    }

    @Test
    fun `NONE overrides nothing`() {
        val none = CallOptions.NONE
        assertTrue(none.isEmpty())
        assertEquals(CallTimeout.NONE, none.timeout)
        assertEquals(ResponseValidation.INHERIT, none.responseValidation)
        assertNull(none.credentialOverride)
        assertTrue(none.attributes.isEmpty())
    }

    @Test
    fun `builder sets the well-known fields`() {
        val credential = KeyCredential("secret")
        val options =
            CallOptions.builder()
                .timeout(CallTimeout.ofCall(Duration.ofSeconds(5)))
                .responseValidation(ResponseValidation.DISABLED)
                .credentialOverride(credential)
                .build()

        assertEquals(Duration.ofSeconds(5), options.timeout.call)
        assertEquals(ResponseValidation.DISABLED, options.responseValidation)
        assertSame(credential, options.credentialOverride)
        assertFalse(options.isEmpty())
    }

    @Test
    fun `typed attributes round-trip with the key's value type`() {
        val options =
            CallOptions.builder()
                .attribute(RETRYABLE, true)
                .attribute(LABEL, "list-users")
                .build()

        val retryable: Boolean? = options.get(RETRYABLE)
        val label: String? = options.get(LABEL)
        assertEquals(true, retryable)
        assertEquals("list-users", label)
        assertTrue(options.contains(RETRYABLE))
        assertEquals("fallback", options.getOrDefault(CallOption("absent"), "fallback"))
    }

    @Test
    fun `get returns null for an absent key`() {
        assertNull(CallOptions.NONE.get(RETRYABLE))
        assertFalse(CallOptions.NONE.contains(RETRYABLE))
    }

    @Test
    fun `distinct keys with the same name do not collide`() {
        val keyA: CallOption<String> = CallOption("dup")
        val keyB: CallOption<String> = CallOption("dup")
        val options = CallOptions.builder().attribute(keyA, "a").attribute(keyB, "b").build()
        assertEquals("a", options.get(keyA))
        assertEquals("b", options.get(keyB))
    }

    @Test
    fun `removeAttribute drops a previously-set key`() {
        val options =
            CallOptions.builder()
                .attribute(LABEL, "x")
                .removeAttribute(LABEL)
                .build()
        assertNull(options.get(LABEL))
    }

    @Test
    fun `built attributes are an immutable snapshot`() {
        val builder = CallOptions.builder().attribute(LABEL, "first")
        val built = builder.build()
        // Mutating the builder after build does not leak into the built instance.
        builder.attribute(LABEL, "second")
        assertEquals("first", built.get(LABEL))
    }

    @Test
    fun `newBuilder round-trips every field`() {
        val original =
            CallOptions.builder()
                .timeout(CallTimeout.ofCall(Duration.ofSeconds(2)))
                .responseValidation(ResponseValidation.ENABLED)
                .credentialOverride(KeyCredential("k"))
                .attribute(LABEL, "op")
                .build()
        assertEquals(original, original.newBuilder().build())
    }

    @Test
    fun `applyDefaults merges each field independently`() {
        val callCredential = KeyCredential("call")
        val receiver =
            CallOptions.builder()
                .timeout(CallTimeout.builder().connect(Duration.ofSeconds(1)).build())
                .credentialOverride(callCredential)
                .attribute(LABEL, "call-label")
                .build()
        val defaults =
            CallOptions.builder()
                .timeout(CallTimeout.builder().read(Duration.ofSeconds(9)).build())
                .responseValidation(ResponseValidation.ENABLED)
                .credentialOverride(KeyCredential("client"))
                .attribute(RETRYABLE, true)
                .build()

        val merged = receiver.applyDefaults(defaults)

        // Timeout merges per phase: receiver connect wins, default read fills in.
        assertEquals(Duration.ofSeconds(1), merged.timeout.connect)
        assertEquals(Duration.ofSeconds(9), merged.timeout.read)
        // Response validation: receiver is INHERIT, so the default's ENABLED shows through.
        assertEquals(ResponseValidation.ENABLED, merged.responseValidation)
        // Credential: receiver's wins.
        assertSame(callCredential, merged.credentialOverride)
        // Attributes union; receiver wins on collisions, default fills the rest.
        assertEquals("call-label", merged.get(LABEL))
        assertEquals(true, merged.get(RETRYABLE))
    }

    @Test
    fun `applyDefaults attribute collision is won by the receiver`() {
        val receiver = CallOptions.builder().attribute(LABEL, "call").build()
        val defaults = CallOptions.builder().attribute(LABEL, "client").build()
        assertEquals("call", receiver.applyDefaults(defaults).get(LABEL))
    }

    @Test
    fun `NONE is the identity element of applyDefaults`() {
        val x =
            CallOptions.builder()
                .timeout(CallTimeout.ofCall(Duration.ofSeconds(3)))
                .responseValidation(ResponseValidation.DISABLED)
                .attribute(LABEL, "op")
                .build()
        assertSame(x, x.applyDefaults(CallOptions.NONE))
        assertEquals(x, CallOptions.NONE.applyDefaults(x))
    }

    @Test
    fun `credential override falls through to defaults when absent`() {
        val clientCredential = KeyCredential("client")
        val receiver = CallOptions.NONE
        val defaults = CallOptions.builder().credentialOverride(clientCredential).build()
        assertSame(clientCredential, receiver.applyDefaults(defaults).credentialOverride)
    }
}
