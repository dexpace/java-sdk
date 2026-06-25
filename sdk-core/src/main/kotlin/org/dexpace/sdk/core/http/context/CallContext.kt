/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.instrumentation.InstrumentationContext

/**
 * Marker for a single in-flight SDK operation. Every context flavor —
 * [DispatchContext], [RequestContext], [ExchangeContext] — extends this and carries an
 * [InstrumentationContext] used to correlate logs, metrics, and spans across the
 * dispatch / request / exchange phases of a call.
 *
 * Each call is registered in [ContextStore] under its [callKey]. The key is **per call**,
 * not per trace: the trace id alone is not unique — [org.dexpace.sdk.core.instrumentation.NoopInstrumentationContext]
 * shares one constant trace id across every untraced call, and an inbound W3C trace
 * legitimately shares a single trace id across many spans. Keying by the trace id would
 * let concurrent calls overwrite and evict each other's live entries. [DispatchContext]
 * generates a unique [callKey] once at the head of the chain, and each promotion carries that
 * same key forward so the whole chain shares one stable, call-unique store slot.
 *
 * Implements [AutoCloseable] so users can `use { ... }` the context; the default [close]
 * evicts the chain's entry from [ContextStore] **conditionally on identity** — it only
 * removes the slot when the closing context is still the registered one. This means an
 * earlier link in the promotion chain whose live child has already replaced it in the
 * store does not evict that child.
 *
 * ## Promotion-chain ownership
 *
 * Only the **terminal** context of a promotion chain should be closed. Intermediate
 * contexts ([DispatchContext] once promoted to [RequestContext], a [RequestContext] once
 * promoted to [ExchangeContext]) hand ownership of the store slot to their successor; their
 * own [close] is a no-op precisely because the slot no longer points at them. Do not close
 * an intermediate context expecting it to clear the chain — close the last one you promoted.
 *
 * ## Thread-safety
 *
 * Implementations are immutable data classes — instances are safe to share. The shared
 * [ContextStore] is thread-safe (concurrent-map backed), so contexts with distinct call
 * keys can be promoted / removed concurrently without external synchronisation.
 */
public interface CallContext : AutoCloseable {
    /** Distributed-tracing context for this call; identifies the trace, span, and sampling flags. */
    public val instrumentationContext: InstrumentationContext

    /**
     * Per-call key under which this context (and the rest of its promotion chain) is
     * registered in [ContextStore]. Unique per call — never derived from the trace id
     * alone, which is not call-unique. See the type KDoc for why.
     */
    public val callKey: String

    /**
     * Removes this context's chain from [ContextStore], but only if this context is still
     * the registered occupant of the slot. Eviction is conditional on identity
     * ([ContextStore.remove] with the expected value), so closing an earlier link whose
     * live child has already taken over the slot does not delete that child.
     */
    override fun close() {
        ContextStore.remove(callKey, this)
    }
}
