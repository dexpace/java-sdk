/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.function.Supplier

/**
 * Covers WU-5 acceptance criteria for [IdempotencyKeyStep]: method-gated injection,
 * caller override, custom strategy, custom header, custom method set.
 *
 * The shared `DispatchContext.default()` value is passed unchanged to every `execute()` call
 * because the step is context-agnostic; the tests assert behaviour purely on the in/out
 * [Request] pair.
 */
class IdempotencyKeyStepTest {
    // region -- helpers --

    private val context: DispatchContext = DispatchContext.default()

    private fun request(
        method: Method,
        headerName: String? = null,
        headerValue: String? = null,
    ): Request {
        val builder =
            Request.builder()
                .url("https://api.example.com/resource")
                .method(method)
        if (headerName != null && headerValue != null) {
            builder.addHeader(headerName, headerValue)
        }
        return builder.build()
    }

    /** RFC 4122 8-4-4-4-12 hex form, accepting both lower and upper case. */
    private val uuidRegex: Regex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private fun assertUuid(value: String?) {
        assertNotNull(value, "Expected an Idempotency-Key header to be present")
        assertTrue(uuidRegex.matches(value!!), "Expected UUID-shaped header value but was: $value")
        // Cross-check by round-tripping through java.util.UUID — throws on malformed input.
        UUID.fromString(value)
    }

    // endregion

    // region -- 1..3: methods in default set get a fresh key --

    @Test
    @DisplayName("POST without header gets a UUID-shaped Idempotency-Key")
    fun postWithoutHeaderGetsUuid() {
        val step = IdempotencyKeyStep.default()
        val out = step.execute(request(Method.POST), context)
        assertUuid(out.headers.get("Idempotency-Key"))
    }

    @Test
    @DisplayName("PUT without header gets a UUID-shaped Idempotency-Key")
    fun putWithoutHeaderGetsUuid() {
        val step = IdempotencyKeyStep.default()
        val out = step.execute(request(Method.PUT), context)
        assertUuid(out.headers.get("Idempotency-Key"))
    }

    @Test
    @DisplayName("PATCH without header gets a UUID-shaped Idempotency-Key")
    fun patchWithoutHeaderGetsUuid() {
        val step = IdempotencyKeyStep.default()
        val out = step.execute(request(Method.PATCH), context)
        assertUuid(out.headers.get("Idempotency-Key"))
    }

    // endregion

    // region -- 4..5: methods outside the default set pass through --

    @Test
    @DisplayName("GET pass-through: no Idempotency-Key added, request returned as-is")
    fun getPassesThroughUnchanged() {
        val step = IdempotencyKeyStep.default()
        val input = request(Method.GET)
        val out = step.execute(input, context)
        assertSame(input, out, "GET request should be returned unchanged")
        assertEquals(null, out.headers.get("Idempotency-Key"))
    }

    @Test
    @DisplayName("DELETE pass-through under default method set: no Idempotency-Key added")
    fun deletePassesThroughByDefault() {
        val step = IdempotencyKeyStep.default()
        val input = request(Method.DELETE)
        val out = step.execute(input, context)
        assertSame(input, out, "DELETE request should be returned unchanged under default set")
        assertEquals(null, out.headers.get("Idempotency-Key"))
    }

    // endregion

    // region -- 6..7: respectExisting behaviour --

    @Test
    @DisplayName("respectExisting=true preserves an existing Idempotency-Key")
    fun respectExistingTruePreservesCallerHeader() {
        val callerKey = "caller-supplied-key-xyz"
        val step = IdempotencyKeyStep.default()
        val input = request(Method.POST, "Idempotency-Key", callerKey)

        val out = step.execute(input, context)

        assertSame(input, out, "Caller header set + respectExisting=true should bypass mutation entirely")
        assertEquals(callerKey, out.headers.get("Idempotency-Key"))
    }

    @Test
    @DisplayName("respectExisting=false replaces an existing Idempotency-Key")
    fun respectExistingFalseReplacesCallerHeader() {
        val callerKey = "caller-supplied-key-xyz"
        val step =
            IdempotencyKeyStep
                .builder()
                .respectExisting(false)
                .build()

        val input = request(Method.POST, "Idempotency-Key", callerKey)
        val out = step.execute(input, context)

        val outKey = out.headers.get("Idempotency-Key")
        assertUuid(outKey)
        assertNotEquals(callerKey, outKey, "Existing header should have been overwritten")
        // Headers contract: set() collapses to one value; ensure we did not accidentally add.
        assertEquals(1, out.headers.values("Idempotency-Key").size)
    }

    // endregion

    // region -- 8..10: custom strategy / header / method-set --

    @Test
    @DisplayName("Custom keyStrategy supplies the exact value it returns")
    fun customKeyStrategyReturnsFixedValue() {
        val step =
            IdempotencyKeyStep
                .builder()
                .keyStrategy { "fixed-key-123" }
                .build()

        val out = step.execute(request(Method.POST), context)
        assertEquals("fixed-key-123", out.headers.get("Idempotency-Key"))
    }

    @Test
    @DisplayName("Java Supplier strategy is adapted and used identically to Kotlin lambda")
    fun javaSupplierStrategyIsAdapted() {
        val supplier: Supplier<String> = Supplier { "from-java-supplier" }
        val step =
            IdempotencyKeyStep
                .builder()
                .keyStrategy(supplier)
                .build()

        val out = step.execute(request(Method.POST), context)
        assertEquals("from-java-supplier", out.headers.get("Idempotency-Key"))
    }

    @Test
    @DisplayName("Custom header name is written instead of the default")
    fun customHeaderNameIsUsed() {
        val step =
            IdempotencyKeyStep
                .builder()
                .header("X-Request-Id")
                .keyStrategy { "req-id-42" }
                .build()

        val out = step.execute(request(Method.POST), context)
        assertEquals("req-id-42", out.headers.get("X-Request-Id"))
        assertEquals(null, out.headers.get("Idempotency-Key"), "Default header must not be written when overridden")
    }

    @Test
    @DisplayName("Custom method set including DELETE injects on DELETE")
    fun customMethodSetIncludesDelete() {
        val step =
            IdempotencyKeyStep
                .builder()
                .methods(setOf(Method.POST, Method.PUT, Method.PATCH, Method.DELETE))
                .build()

        val out = step.execute(request(Method.DELETE), context)
        assertUuid(out.headers.get("Idempotency-Key"))
    }

    // endregion

    // region -- supplementary invariants --

    @Test
    @DisplayName("Default strategy produces a distinct key per invocation")
    fun defaultStrategyProducesDistinctKeys() {
        val step = IdempotencyKeyStep.default()
        val a = step.execute(request(Method.POST), context).headers.get("Idempotency-Key")
        val b = step.execute(request(Method.POST), context).headers.get("Idempotency-Key")
        assertNotEquals(a, b, "Two invocations should yield different UUIDs")
    }

    @Test
    @DisplayName("Method comparison is case-insensitive header check (header case folding sanity)")
    fun existingHeaderLookupIsCaseInsensitive() {
        // Headers are normalised to lower case for lookup, so the step must see a header set
        // under a differently-cased name. Verifies we use Headers.contains() not raw string match.
        val step = IdempotencyKeyStep.default()
        val input = request(Method.POST, "idempotency-key", "lower-case-key")

        val out = step.execute(input, context)
        assertSame(input, out, "Lower-cased existing header should still trigger respectExisting")
        assertEquals("lower-case-key", out.headers.get("Idempotency-Key"))
    }

    // endregion
}
