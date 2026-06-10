/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry mapping a call's [callKey][CallContext.callKey] to its current
 * [CallContext]. Each promotion in the context chain ([DispatchContext.toRequestContext],
 * [RequestContext.toExchangeContext]) overwrites the entry under the **same** call key so
 * the latest snapshot is visible to downstream observers.
 *
 * The key is per call, not per trace: the trace id is not call-unique (the no-op
 * instrumentation context shares one constant trace id, and an inbound W3C trace shares a
 * trace id across spans), so keying by it would let concurrent calls collide. See
 * [CallContext] for the full rationale.
 *
 * Entries are removed by [CallContext.close] when callers honour the close contract.
 *
 * ## Thread-safety
 *
 * Thread-safe. The backing map is a [ConcurrentHashMap] so concurrent calls with distinct
 * call keys do not need external synchronisation. [put] uses CAS-style `putIfAbsent` so two
 * threads racing to register the same key deterministically reject the second one, and
 * [remove] supports identity-conditional eviction so an earlier link in a promotion chain
 * never evicts the live child that replaced it.
 */
public object ContextStore {
    private val contexts: ConcurrentHashMap<String, CallContext> = ConcurrentHashMap()

    /** Returns the context registered under [callKey], or `null` if none is present. */
    public fun get(callKey: String): CallContext? = contexts[callKey]

    /**
     * Registers [context] under [callKey]; rejects a duplicate key with
     * [IllegalArgumentException]. Uses CAS semantics via `putIfAbsent` so concurrent
     * registrations with the same key deterministically fail the loser. Use [set] when
     * overwrite semantics are intended (e.g. context promotion).
     */
    @Throws(IllegalArgumentException::class)
    public fun put(
        callKey: String,
        context: CallContext,
    ) {
        val previous = contexts.putIfAbsent(callKey, context)
        require(previous == null) { "Call context key already registered: $callKey" }
    }

    /**
     * Unconditionally stores [context] under [callKey], replacing any prior entry. No-throw
     * if no entry exists yet — used by the context promotion chain, where the first
     * promotion installs the entry and later promotions overwrite it.
     */
    public fun set(
        callKey: String,
        context: CallContext,
    ) {
        contexts[callKey] = context
    }

    /**
     * Removes the entry under [callKey]. No-op (does not throw) if no such entry exists, so
     * callers that close a context twice — or close one that was never registered — get
     * well-defined behaviour rather than an exception, which made the close contract awkward
     * to honour from cleanup paths.
     */
    public fun remove(callKey: String) {
        contexts.remove(callKey)
    }

    /**
     * Removes the entry under [callKey] **only if** it currently maps to [expected]
     * (identity-conditional eviction via [ConcurrentHashMap.remove]). No-op (does not
     * throw) when the slot is absent or has already been replaced by a promoted successor,
     * so closing an earlier link in a promotion chain never deletes the live child that
     * took over the slot. Returns `true` when an entry was removed.
     */
    public fun remove(
        callKey: String,
        expected: CallContext,
    ): Boolean = contexts.remove(callKey, expected)
}
