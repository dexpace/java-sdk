package org.dexpace.sdk.core.instrumentation

/**
 * Lifecycle handle for a span that has been activated via [Span.makeCurrent].
 *
 * While the scope is open, the associated span is the "current" span for the executing
 * thread; closing the scope restores the previously active span. Implementations must be
 * safe to `close()` from `try`-with-resources / Kotlin `use { … }` to guarantee cleanup
 * even when the guarded code throws.
 *
 * For log-event correlation, use [makeCurrentWithLoggingContext] instead of
 * [Span.makeCurrent] directly — that wrapper also pushes `trace.id` / `span.id` onto
 * SLF4J MDC for the lifetime of the scope. The plain [Span.makeCurrent] is appropriate
 * when only tracing-system propagation (e.g. OTel context) is needed.
 *
 * **Async propagation:** MDC is per-thread. Values pushed inside the scope do NOT
 * automatically propagate across [java.util.concurrent.CompletableFuture] continuations,
 * coroutine suspensions, or executor handoffs. For coroutines use
 * `kotlinx-coroutines-slf4j`'s `MDCContext` element.
 */
fun interface TracingScope : AutoCloseable {
    override fun close()
}
