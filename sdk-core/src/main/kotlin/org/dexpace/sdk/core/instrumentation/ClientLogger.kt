package org.dexpace.sdk.core.instrumentation

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Structured-logging facade with a zero-allocation disabled-level fast path.
 *
 * Typical usage:
 * ```
 * logger.atVerbose()
 *     .event("http.request")
 *     .field("method", "GET")
 *     .field("status", 200)
 *     .cause(t)
 *     .log("done")
 * ```
 *
 * When the underlying SLF4J level is disabled, every `at*` entry returns
 * [LoggingEvent.NOOP] — a shared singleton whose builder methods all return
 * `this` and whose `log()` is a no-op. No `Map`, `StringBuilder`, or chained
 * event instances are allocated in that path.
 *
 * `globalContext` is attached to every event automatically; the map reference is
 * shared, not copied.
 *
 * ## MDC key allow-list
 *
 * [mdcKeys] controls which SLF4J MDC keys are folded into the structured event. The
 * default is `setOf("trace.id", "span.id")` — only these two keys reach the event as
 * structured fields. Pass `null` to fold every MDC key (backwards-compatible behaviour
 * prior to this allow-list). Pass a custom `Set<String>` to include additional keys
 * (e.g. `setOf("trace.id", "span.id", "x-app-tenant")`).
 *
 * **Behavioural change:** the previous default was to fold all MDC keys. The new default
 * allows only `trace.id` and `span.id` to prevent arbitrary application MDC leaking into
 * SDK-owned log events.
 *
 * ## SLF4J usage
 *
 * `ClientLogger` uses `LoggerFactory.getLogger` directly rather than delegating to a
 * `kotlin-logging`-style wrapper. This is a deliberate deviation from styleguide §6.1:
 * `ClientLogger` is itself a custom SLF4J facade that provides the same lazy /
 * zero-allocation semantics on the disabled-level path (via [LoggingEvent.NOOP]) plus
 * an SDK-specific structured-field API tailored to the SDK's instrumentation needs.
 * Wrapping it in another logging abstraction would add allocation and indirection with
 * no benefit.
 */
class ClientLogger private constructor(
    internal val slf4j: Logger,
    internal val globalContext: Map<String, Any?>,
    internal val mdcKeys: Set<String>? = DEFAULT_MDC_KEYS,
) {
    /**
     * Creates a logger by SLF4J logger name.
     *
     * @param name the SLF4J logger name; must be non-empty.
     * @param globalContext key-value pairs attached to every event emitted through this logger.
     *   The map reference is retained (not copied); callers should pass an immutable map.
     * @param mdcKeys MDC keys to fold into each event. Default = `setOf("trace.id", "span.id")`.
     *   Pass `null` for unfiltered fold (backwards-compat with pre-allow-list behaviour).
     */
    @JvmOverloads
    constructor(
        name: String,
        globalContext: Map<String, Any?> = emptyMap(),
        mdcKeys: Set<String>? = DEFAULT_MDC_KEYS,
    ) : this(LoggerFactory.getLogger(requireNonEmpty(name)), globalContext, mdcKeys)

    /** Secondary constructor for the common `ClientLogger(MyClass::class)` Kotlin form. */
    @JvmOverloads
    constructor(
        klass: kotlin.reflect.KClass<*>,
        globalContext: Map<String, Any?> = emptyMap(),
        mdcKeys: Set<String>? = DEFAULT_MDC_KEYS,
    ) : this(klass.java.name, globalContext, mdcKeys)

    /** Secondary constructor for the common `new ClientLogger(MyClass.class)` Java form. */
    @JvmOverloads
    constructor(
        klass: Class<*>,
        globalContext: Map<String, Any?> = emptyMap(),
        mdcKeys: Set<String>? = DEFAULT_MDC_KEYS,
    ) : this(klass.name, globalContext, mdcKeys)

    /** Test-only seam: inject a pre-built [Logger] (e.g. a fake) without going through `LoggerFactory`. */
    companion object {
        internal fun forTesting(
            slf4j: Logger,
            globalContext: Map<String, Any?> = emptyMap(),
            mdcKeys: Set<String>? = DEFAULT_MDC_KEYS,
        ): ClientLogger = ClientLogger(slf4j, globalContext, mdcKeys)

        /**
         * Default MDC key allow-list: only `trace.id` and `span.id` are folded into
         * structured events. Prevents arbitrary application MDC from leaking into SDK events.
         * Pass `null` to [ClientLogger] for unfiltered fold (backwards-compat behaviour).
         */
        @JvmField
        val DEFAULT_MDC_KEYS: Set<String> = setOf("trace.id", "span.id")
    }

    /**
     * Returns a [LoggingEvent] at [LogLevel.ERROR]. Returns [LoggingEvent.NOOP] (shared singleton,
     * zero allocation) when the underlying SLF4J `ERROR` level is disabled.
     */
    fun atError(): LoggingEvent = LoggingEvent.create(this, LogLevel.ERROR)

    /**
     * Returns a [LoggingEvent] at [LogLevel.WARNING]. Returns [LoggingEvent.NOOP] (shared singleton,
     * zero allocation) when the underlying SLF4J `WARN` level is disabled.
     */
    fun atWarning(): LoggingEvent = LoggingEvent.create(this, LogLevel.WARNING)

    /**
     * Returns a [LoggingEvent] at [LogLevel.INFO]. Returns [LoggingEvent.NOOP] (shared singleton,
     * zero allocation) when the underlying SLF4J `INFO` level is disabled.
     */
    fun atInfo(): LoggingEvent = LoggingEvent.create(this, LogLevel.INFO)

    /**
     * Returns a [LoggingEvent] at [LogLevel.VERBOSE]. Returns [LoggingEvent.NOOP] (shared singleton,
     * zero allocation) when the underlying SLF4J `DEBUG` level is disabled.
     */
    fun atVerbose(): LoggingEvent = LoggingEvent.create(this, LogLevel.VERBOSE)

    /**
     * Returns `true` when the underlying SLF4J logger has [level] enabled. Useful for guarding
     * field computation that is expensive enough to warrant skipping when the event would be
     * discarded anyway.
     *
     * Equivalent to SLF4J `Logger.isEnabledForLevel(Level)`; named `canLog` to read naturally
     * at call sites such as `if (logger.canLog(LogLevel.VERBOSE)) { … }`.
     */
    fun canLog(level: LogLevel): Boolean = slf4j.isEnabledForLevel(toSlf4j(level))

    internal fun slf4jLevel(level: LogLevel): Level = toSlf4j(level)

    private fun toSlf4j(level: LogLevel): Level = when (level) {
        LogLevel.ERROR -> Level.ERROR
        LogLevel.WARNING -> Level.WARN
        LogLevel.INFO -> Level.INFO
        // SLF4J has no VERBOSE; map to DEBUG (the closest convention).
        LogLevel.VERBOSE -> Level.DEBUG
    }
}

/**
 * Validates that [name] is non-empty and returns it. Extracted from the companion object
 * because the companion should hold only constants and factories, not private helper logic.
 */
private fun requireNonEmpty(name: String): String {
    require(name.isNotEmpty()) { "name must not be empty" }
    return name
}
