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
 */
class ClientLogger private constructor(
    internal val slf4j: Logger,
    internal val globalContext: Map<String, Any?>,
) {
    /**
     * Creates a logger by SLF4J logger name.
     *
     * @param name the SLF4J logger name; must be non-empty.
     * @param globalContext key-value pairs attached to every event emitted through this logger.
     *   The map reference is retained (not copied); callers should pass an immutable map.
     */
    @JvmOverloads
    constructor(
        name: String,
        globalContext: Map<String, Any?> = emptyMap(),
    ) : this(LoggerFactory.getLogger(requireNonEmpty(name)), globalContext)

    /** Secondary constructor for the common `ClientLogger(MyClass::class)` Kotlin form. */
    @JvmOverloads
    constructor(klass: kotlin.reflect.KClass<*>, globalContext: Map<String, Any?> = emptyMap()) :
        this(klass.java.name, globalContext)

    /** Secondary constructor for the common `new ClientLogger(MyClass.class)` Java form. */
    @JvmOverloads
    constructor(klass: Class<*>, globalContext: Map<String, Any?> = emptyMap()) :
        this(klass.name, globalContext)

    /** Test-only seam: inject a pre-built [Logger] (e.g. a fake) without going through `LoggerFactory`. */
    internal companion object {
        internal fun forTesting(slf4j: Logger, globalContext: Map<String, Any?> = emptyMap()): ClientLogger =
            ClientLogger(slf4j, globalContext)

        private fun requireNonEmpty(name: String): String {
            require(name.isNotEmpty()) { "name must not be empty" }
            return name
        }
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
