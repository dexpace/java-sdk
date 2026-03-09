package org.dexpace.sdk.core.http.context

object ContextStore {
    private val contexts: MutableMap<String, CallContext> = mutableMapOf()

    fun get(runId: String): CallContext? = contexts[runId]

    @Throws(IllegalArgumentException::class)
    fun put(runId: String, context: CallContext) {
        require(!contexts.containsKey(runId)) { "Pipeline run id duplicated: $runId" }
        contexts[runId] = context
    }

    fun set(runId: String, context: CallContext) {
        contexts[runId] = context
    }

    @Throws(IllegalArgumentException::class)
    fun remove(traceId: String) {
        require(contexts.containsKey(traceId)) { "Pipeline run id not found: $traceId" }
        contexts.remove(traceId)
    }
}
