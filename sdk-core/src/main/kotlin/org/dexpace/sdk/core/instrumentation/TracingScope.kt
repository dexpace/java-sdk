package org.dexpace.sdk.core.instrumentation

/**
 * Represents a controlled execution scope for a specific trace or span in a distributed tracing system.
 *
 * The `TracingScope` interface is typically used to manage the lifecycle of a span that has been made current.
 * When a span is made current, it becomes the active context for tracing operations within the execution scope.
 * The `TracingScope` ensures that the current trace context is properly set and cleaned up when the scope ends.
 *
 * Implementations of this interface should encapsulate logic to manage the activation and deactivation
 * of tracing contexts, ensuring that the proper span is active during its execution.
 */
fun interface TracingScope : AutoCloseable {
    override fun close()
}
