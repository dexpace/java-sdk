package org.dexpace.sdk.core.http.context

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry mapping a call's trace id to its current [CallContext]. Each
 * promotion in the context chain ([DispatchContext.toRequestContext],
 * [RequestContext.toExchangeContext]) overwrites the entry so the latest snapshot is
 * visible to downstream observers keyed by trace id.
 *
 * Entries are removed by [CallContext.close] when callers honour the close contract.
 *
 * ## Thread-safety
 *
 * Thread-safe. The backing map is a [ConcurrentHashMap] so concurrent calls with distinct
 * trace ids do not need external synchronisation. [put] uses CAS-style `putIfAbsent` so two
 * threads racing to register the same trace id deterministically reject the second one.
 */
public object ContextStore {
    private val contexts: ConcurrentHashMap<String, CallContext> = ConcurrentHashMap()

    /** Returns the context registered under [runId], or `null` if none is present. */
    public fun get(runId: String): CallContext? = contexts[runId]

    /**
     * Registers [context] under [runId]; rejects duplicate ids with [IllegalArgumentException].
     * Uses CAS semantics via `putIfAbsent` so concurrent registrations with the same id
     * deterministically fail the loser. Use [set] when overwrite semantics are intended
     * (e.g. context promotion).
     */
    @Throws(IllegalArgumentException::class)
    public fun put(
        runId: String,
        context: CallContext,
    ) {
        val previous = contexts.putIfAbsent(runId, context)
        require(previous == null) { "Pipeline run id duplicated: $runId" }
    }

    /**
     * Unconditionally stores [context] under [runId], replacing any prior entry. No-throw
     * if no entry exists yet — used by the context promotion chain, where the first
     * promotion installs the entry and later promotions overwrite it.
     */
    public fun set(
        runId: String,
        context: CallContext,
    ) {
        contexts[runId] = context
    }

    /**
     * Removes the entry under [traceId]. No-op if no such entry exists — callers that close
     * a context twice (or close one that was never registered) get well-defined behaviour
     * rather than a thrown exception, which made the close contract awkward to honour from
     * cleanup paths.
     */
    public fun remove(traceId: String) {
        contexts.remove(traceId)
    }
}
