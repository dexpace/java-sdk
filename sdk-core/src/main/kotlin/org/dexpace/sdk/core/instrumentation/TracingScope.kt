package org.dexpace.sdk.core.instrumentation

/**
 * Lifecycle handle for a span that has been activated via [Span.makeCurrent].
 *
 * While the scope is open, the associated span is the "current" span for the executing
 * thread; closing the scope restores the previously active span. Implementations must be
 * safe to `close()` from `try`-with-resources / Kotlin `use { … }` to guarantee cleanup
 * even when the guarded code throws.
 */
fun interface TracingScope : AutoCloseable {
    override fun close()
}
