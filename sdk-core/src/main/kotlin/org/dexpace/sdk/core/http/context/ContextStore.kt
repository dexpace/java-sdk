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
 * ## Lifecycle and leaks
 *
 * Eviction is **primarily close-only**: the normal path is that [CallContext.close] removes
 * the entry. Only the **terminal** promotion context (the [ExchangeContext] that ends the
 * chain) should be closed — closing an earlier link uses identity-conditional [remove] so it
 * cannot evict the live successor that replaced it. A caller that fails to close on an
 * exception path drops an entry that **pins the full Request + Response graph** — possibly an
 * unread response body holding a transport connection open.
 *
 * To stop a missed close from leaking unboundedly, the backing map is **bounded** to
 * [MAX_TRACKED_CONTEXTS] entries: after each insert ([set] / [put]) the map is drained back
 * under the cap, mirroring the proven bound in
 * [DigestChallengeHandler][org.dexpace.sdk.core.http.auth.DigestChallengeHandler]'s nonce
 * counters. The bound is a backstop, not a substitute for closing — it caps memory but a
 * leaked entry still holds its graph alive until evicted, and eviction is by arbitrary victim
 * (see [drainToCap]), so a still-live call could lose its registry entry under heavy leak
 * pressure. The store is the only strong reference on the live path, so a [java.lang.ref.WeakReference]
 * cannot be used here: it would let an in-flight context be collected mid-call.
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
        drainToCap()
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
        drainToCap()
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
     * Removes the entry under [callKey] **only if** it currently maps to [expected] **by
     * reference identity**. No-op (does not throw) when the slot is absent or has already been
     * replaced by a promoted successor, so closing an earlier link in a promotion chain never
     * deletes the live child that took over the slot. Returns `true` when an entry was removed.
     *
     * Identity, not value equality: the [CallContext] implementations are data classes, so two
     * distinct instances of the same chain link compare `equals`. [ConcurrentHashMap.remove]
     * with a value argument matches by `equals`, which would let a stale context evict a
     * structurally-equal live sibling; [ConcurrentHashMap.computeIfPresent] with a `===` check
     * compares the actual occupant by reference instead, atomically.
     */
    public fun remove(
        callKey: String,
        expected: CallContext,
    ): Boolean {
        var removed = false
        contexts.computeIfPresent(callKey) { _, current ->
            if (current === expected) {
                removed = true
                null // returning null removes the mapping
            } else {
                current
            }
        }
        return removed
    }

    /**
     * Drains the backing map back under [MAX_TRACKED_CONTEXTS] after an insert. Mirrors
     * [DigestChallengeHandler][org.dexpace.sdk.core.http.auth.DigestChallengeHandler]'s
     * post-insert drain loop: rather than a single check-then-evict-then-insert (a non-atomic
     * race that a burst of concurrent inserts can overshoot and then sit at the cap forever),
     * each insert drains until the map is back under the cap, so the map converges to the
     * bound. `ConcurrentHashMap` has no insertion order, so any single key is an acceptable
     * victim — the first the iterator yields is dropped.
     */
    private fun drainToCap() {
        while (contexts.size > MAX_TRACKED_CONTEXTS) {
            val iterator = contexts.keys.iterator()
            if (!iterator.hasNext()) break
            contexts.remove(iterator.next())
        }
    }

    // Upper bound on call contexts tracked at once. The store is normally drained by
    // close(), so this is a backstop against a missed close on an exception path leaking an
    // entry that pins a full Request + Response graph. 4096 is large enough to never bite a
    // realistic in-flight concurrency level (each entry is one live or recently-finished
    // call), yet small enough to cap worst-case retained memory at a few thousand graphs.
    // Matches the bounded-map style of DigestChallengeHandler.MAX_TRACKED_NONCES.
    private const val MAX_TRACKED_CONTEXTS = 4096
}
