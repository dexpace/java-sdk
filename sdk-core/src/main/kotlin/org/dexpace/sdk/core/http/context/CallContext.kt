package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.instrumentation.InstrumentationContext

/**
 * Marker for a single in-flight SDK operation. Every context flavor —
 * [DispatchContext], [RequestContext], [ExchangeContext] — extends this and carries an
 * [InstrumentationContext] used to correlate logs, metrics, and spans across the
 * dispatch / request / exchange phases of a call.
 *
 * Implements [AutoCloseable] so users can `use { ... }` the context; the default [close]
 * evicts the entry from [ContextStore] keyed by the trace id, ensuring the store does not
 * accumulate stale entries when callers honour the close contract.
 *
 * ## Thread-safety
 *
 * Implementations are immutable data classes — instances are safe to share. The shared
 * [ContextStore] is thread-safe (concurrent-map backed), so contexts for different trace
 * ids can be promoted / removed concurrently without external synchronisation.
 */
public interface CallContext : AutoCloseable {
    /** Distributed-tracing context for this call; identifies the trace, span, and sampling flags. */
    public val instrumentationContext: InstrumentationContext

    /** Removes this context from [ContextStore] keyed by the call's trace id. */
    override fun close() {
        ContextStore.remove(instrumentationContext.traceId.value)
    }
}
