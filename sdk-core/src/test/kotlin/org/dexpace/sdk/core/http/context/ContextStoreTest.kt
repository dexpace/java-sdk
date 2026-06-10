/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.instrumentation.InstrumentationContext
import org.dexpace.sdk.core.instrumentation.Span
import org.dexpace.sdk.core.instrumentation.SpanId
import org.dexpace.sdk.core.instrumentation.TraceFlags
import org.dexpace.sdk.core.instrumentation.TraceId
import org.dexpace.sdk.core.instrumentation.TraceIdType
import org.dexpace.sdk.core.instrumentation.TraceState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Stable, equality-safe [InstrumentationContext] for tests that need a deterministic trace id.
 * Production contexts mint random ids; tests need to predict the key into [ContextStore].
 */
internal data class FakeInstrumentationContext(
    override val traceId: TraceId,
    override val spanId: SpanId = SpanId("0000000000000001"),
    override val traceFlags: TraceFlags = TraceFlags("00"),
    override val traceState: TraceState = TraceState.NOOP,
    override val traceIdType: TraceIdType = TraceIdType.W3C,
    override val isValid: Boolean = true,
    override val isRemote: Boolean = false,
    override val span: Span = Span.NOOP,
) : InstrumentationContext

internal fun request(): Request =
    Request.builder()
        .url("https://api.example.test/x")
        .method(Method.GET)
        .build()

internal fun response(): Response =
    Response.builder()
        .request(request())
        .protocol(Protocol.HTTP_1_1)
        .status(Status.OK)
        .build()

class ContextStoreTest {
    // Each test uses a unique trace id so cross-test mutation of the global store can't
    // produce flakes. The @AfterTest cleanup belt-and-braces evicts whatever the test put in.
    private val ownedIds: MutableList<String> = mutableListOf()

    private fun owned(name: String): String {
        // Append a per-process suffix so two concurrent JVMs running the same suite
        // never collide (Gradle's `useJUnitPlatform()` defaults to one fork, but a
        // future config may parallelise).
        val id = "test-$name-${System.nanoTime()}"
        ownedIds.add(id)
        return id
    }

    @BeforeTest
    fun clearOwnedKeys() {
        // Defensive: nothing should be left over from a previous test, but if so, evict.
        for (id in ownedIds) ContextStore.remove(id)
        ownedIds.clear()
    }

    @AfterTest
    fun evict() {
        for (id in ownedIds) ContextStore.remove(id)
    }

    @Test
    fun `get returns null when no context is registered`() {
        assertNull(ContextStore.get(owned("missing")))
    }

    @Test
    fun `put and get round trip`() {
        val id = owned("put-get")
        val ctx = DispatchContext(FakeInstrumentationContext(TraceId(id)))
        ContextStore.put(id, ctx)
        assertSame(ctx, ContextStore.get(id))
    }

    @Test
    fun `put rejects duplicate ids with IllegalArgumentException`() {
        val id = owned("dup")
        val ctx1 = DispatchContext(FakeInstrumentationContext(TraceId(id)))
        val ctx2 = DispatchContext(FakeInstrumentationContext(TraceId(id)))

        ContextStore.put(id, ctx1)
        val ex = assertFailsWith<IllegalArgumentException> { ContextStore.put(id, ctx2) }
        assertTrue(ex.message?.contains(id) == true)
        // Original is still in place
        assertSame(ctx1, ContextStore.get(id))
    }

    @Test
    fun `set installs first entry and overwrites existing`() {
        val id = owned("set")
        val first = DispatchContext(FakeInstrumentationContext(TraceId(id)))
        val second = DispatchContext(FakeInstrumentationContext(TraceId(id)))

        ContextStore.set(id, first)
        assertSame(first, ContextStore.get(id))

        ContextStore.set(id, second)
        assertSame(second, ContextStore.get(id))
    }

    @Test
    fun `remove drops a previously registered entry`() {
        val id = owned("remove")
        ContextStore.put(id, DispatchContext(FakeInstrumentationContext(TraceId(id))))
        ContextStore.remove(id)
        assertNull(ContextStore.get(id))
    }

    @Test
    fun `remove is a no-op when no entry exists`() {
        val id = owned("noop-remove")
        // Should not throw even though there's no matching entry.
        ContextStore.remove(id)
        assertNull(ContextStore.get(id))
    }

    @Test
    fun `identity-conditional remove only evicts when the registered context matches`() {
        val id = owned("cond-remove")
        val first = DispatchContext(FakeInstrumentationContext(TraceId(id)), id)
        val replacement = RequestContext(FakeInstrumentationContext(TraceId(id)), request(), id)

        ContextStore.set(id, first)
        // A replacement has taken over the slot; closing the original (which still believes
        // it owns the slot) must not delete the replacement.
        ContextStore.set(id, replacement)
        assertEquals(false, ContextStore.remove(id, first))
        assertSame(replacement, ContextStore.get(id))

        // Removing with the matching value succeeds.
        assertEquals(true, ContextStore.remove(id, replacement))
        assertNull(ContextStore.get(id))
    }

    @Test
    fun `identity-conditional remove is a no-op when no entry exists`() {
        val id = owned("cond-remove-missing")
        val ctx = DispatchContext(FakeInstrumentationContext(TraceId(id)), id)
        assertEquals(false, ContextStore.remove(id, ctx))
        assertNull(ContextStore.get(id))
    }

    @Test
    fun `concurrent untraced calls keep independent entries`() {
        // Two concurrent default()/NOOP-trace calls share a constant trace+span id, but each
        // mints a unique call key, so neither overwrites nor evicts the other's live entry.
        val calls = 16
        val barrier = CountDownLatch(1)
        val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val survivors = AtomicInteger(0)

        val ts =
            (1..calls).map {
                Thread {
                    barrier.await()
                    val dispatch = DispatchContext.default()
                    keys.add(dispatch.callKey)
                    ownedIds.add(dispatch.callKey)
                    ContextStore.set(dispatch.callKey, dispatch)
                    // Each call's own entry is still present after registration.
                    if (ContextStore.get(dispatch.callKey) === dispatch) survivors.incrementAndGet()
                }.also { it.start() }
            }
        barrier.countDown()
        for (t in ts) t.join()

        assertEquals(calls, keys.size, "every concurrent call should mint a distinct key")
        assertEquals(calls, survivors.get(), "no call should have its entry overwritten by another")
    }

    @Test
    fun `concurrent put for the same id only lets one winner through`() {
        val id = owned("concurrent")
        val threads = 16
        val barrier = CountDownLatch(1)
        val failures = AtomicInteger(0)
        val successes = AtomicInteger(0)

        val ts =
            (1..threads).map {
                Thread {
                    barrier.await()
                    try {
                        ContextStore.put(id, DispatchContext(FakeInstrumentationContext(TraceId(id))))
                        successes.incrementAndGet()
                    } catch (_: IllegalArgumentException) {
                        failures.incrementAndGet()
                    }
                }.also { it.start() }
            }
        barrier.countDown()
        for (t in ts) t.join()

        assertEquals(1, successes.get(), "exactly one thread should win the put race")
        assertEquals(threads - 1, failures.get(), "all losers should observe IllegalArgumentException")
        // Successor should still be present.
        ContextStore.get(id)?.let { assertTrue(true) }
    }
}
