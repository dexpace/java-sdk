package org.dexpace.sdk.core.instrumentation

import java.util.concurrent.ThreadLocalRandom

fun generateW3CTraceId(): TraceId =
    TraceId(
        "%016x%016x".format(
            ThreadLocalRandom.current().nextLong(),
            ThreadLocalRandom.current().nextLong().takeIf { it != 0L } ?: 1L)
    )

fun generateDatadogTraceId(): TraceId =
    TraceId(
        (ThreadLocalRandom.current().nextLong().takeIf { it != 0L } ?: 1L).toULong().toString()
    )

fun generateNoopTraceId(): TraceId = TraceId.NOOP

enum class TraceIdType(val generate: () -> TraceId) {
    DATADOG(::generateDatadogTraceId),
    W3C(::generateW3CTraceId),
    NOOP(::generateNoopTraceId)
}
