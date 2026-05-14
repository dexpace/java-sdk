package org.dexpace.sdk.core.instrumentation

import java.util.concurrent.ThreadLocalRandom

/**
 * Trace-id encoding flavours supported by the SDK.
 *
 * Different tracing backends expect different on-the-wire trace-id formats; [generate] is the
 * factory used by [InstrumentationContext] to mint a fresh id of the chosen variant.
 *
 * - [DATADOG] — 64-bit unsigned integer rendered as a decimal string (Datadog wire format).
 * - [W3C] — 128-bit value rendered as a 32-character lowercase hex string per the W3C Trace
 *   Context spec.
 * - [NOOP] — placeholder used by no-op contexts; always returns [TraceId.NOOP].
 */
public enum class TraceIdType(public val generate: () -> TraceId) {
    DATADOG(::generateDatadogTraceId),
    W3C(::generateW3CTraceId),
    NOOP(::generateNoopTraceId),
}

/**
 * Generates a fresh W3C-format trace id: 32 lowercase hex characters representing 128 random
 * bits. The low 64-bit half is coerced away from zero to avoid producing the invalid all-zero
 * trace id reserved by the spec.
 */
internal fun generateW3CTraceId(): TraceId {
    val random = ThreadLocalRandom.current()
    val low = random.nextLong().takeIf { it != 0L } ?: 1L
    return TraceId("%016x%016x".format(random.nextLong(), low))
}

/**
 * Generates a fresh Datadog-format trace id: a 64-bit unsigned integer rendered as decimal.
 * Zero is replaced with `1` so the result is always a valid non-zero id.
 */
internal fun generateDatadogTraceId(): TraceId {
    val nonZero = ThreadLocalRandom.current().nextLong().takeIf { it != 0L } ?: 1L
    return TraceId(nonZero.toULong().toString())
}

/** Returns the shared [TraceId.NOOP] sentinel — used when tracing is disabled. */
internal fun generateNoopTraceId(): TraceId = TraceId.NOOP
