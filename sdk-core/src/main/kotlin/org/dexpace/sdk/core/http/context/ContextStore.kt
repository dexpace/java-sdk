package org.dexpace.sdk.core.http.context

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
 * Not thread-safe — the backing map is plain `mutableMapOf`. Concurrent calls from
 * different threads with distinct trace ids require external synchronisation today; the
 * store is intended for per-call single-threaded use.
 */
object ContextStore {
    private val contexts: MutableMap<String, CallContext> = mutableMapOf()

    /** Returns the context registered under [runId], or `null` if none is present. */
    fun get(runId: String): CallContext? = contexts[runId]

    /**
     * Registers [context] under [runId]; rejects duplicate ids with [IllegalArgumentException].
     * Use [set] when overwrite semantics are intended (e.g. context promotion).
     */
    @Throws(IllegalArgumentException::class)
    fun put(runId: String, context: CallContext) {
        require(!contexts.containsKey(runId)) { "Pipeline run id duplicated: $runId" }
        contexts[runId] = context
    }

    /** Unconditionally stores [context] under [runId], replacing any prior entry. */
    fun set(runId: String, context: CallContext) {
        contexts[runId] = context
    }

    /**
     * Removes the entry under [traceId]; throws [IllegalArgumentException] if no such
     * entry exists. The strict check surfaces bugs where a context is closed twice or
     * was never registered.
     */
    @Throws(IllegalArgumentException::class)
    fun remove(traceId: String) {
        require(contexts.containsKey(traceId)) { "Pipeline run id not found: $traceId" }
        contexts.remove(traceId)
    }
}
