package org.dexpace.sdk.core.instrumentation

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fluent log-event builder returned by [ClientLogger.atError] / [atWarning] / [atInfo] / [atVerbose].
 *
 * Two flavours:
 * - **Enabled** — backed by a real [ClientLogger]; collects fields lazily; flushes through SLF4J's
 *   `LoggingEventBuilder` on [log]. The per-event field map is allocated only on first [field] call.
 * - **NOOP** — the shared [LoggingEvent.NOOP] singleton returned when the requested level is disabled.
 *   Every builder method returns `this` and [log] is silent — zero allocations on the disabled path.
 *
 * `globalContext` is taken from the owning [ClientLogger] and emitted on every log; the map reference
 * is shared, never copied per event.
 *
 * Single-shot. Intended to be built and `log()`'d once. The `consumed` guard makes concurrent misuse
 * a no-op rather than a double-log, but field accumulation (`field()`, `event()`, `cause()`) is NOT
 * thread-safe — these must happen on one thread, then `log()` may be called from any thread.
 */
class LoggingEvent internal constructor(
    private val logger: ClientLogger?,
    private val level: LogLevel?,
    private val enabled: Boolean,
) {
    /** Lazily allocated on the first `field(...)` call so the no-field path costs nothing extra. */
    private var fields: LinkedHashMap<String, Any?>? = null
    private var eventName: String? = null
    private var cause: Throwable? = null

    /**
     * Prevents accidental double-log of the same event. We use `AtomicBoolean` rather than
     * `@Volatile var` so the consumed-once guard is correct even under concurrent misuse.
     */
    private val consumed = AtomicBoolean(false)

    // -- Field setters ---------------------------------------------------------------------------

    /**
     * Attaches a string-valued field to the event. Null values are emitted as the literal
     * `"null"` (see [NULL_PLACEHOLDER]) — silently dropping them would look like a bug in
     * log searches.
     *
     * @param key the field key; must be non-empty.
     * @param value the field value, or `null` to record the explicit absence.
     * @throws IllegalArgumentException if [key] is empty.
     */
    fun field(key: String, value: String?): LoggingEvent {
        if (!enabled) return this
        require(key.isNotEmpty()) { "field key must not be empty" }
        putField(key, value ?: NULL_PLACEHOLDER)
        return this
    }

    // Primitive overloads: dispatched at call-site by Kotlin so callers writing `field("n", 1L)`
    // do not pass through the `Any?` overload (which would box on the Kotlin side regardless of
    // SLF4J's signature). The actual SLF4J `addKeyValue(String, Object)` seam still autoboxes —
    // that's unavoidable without a custom SLF4J SPI.

    /** Attaches a `Long`-valued field. Primitive overload — avoids Kotlin call-site boxing. */
    fun field(key: String, value: Long): LoggingEvent {
        if (!enabled) return this
        require(key.isNotEmpty()) { "field key must not be empty" }
        putField(key, value)
        return this
    }

    /** Attaches an `Int`-valued field. Primitive overload — avoids Kotlin call-site boxing. */
    fun field(key: String, value: Int): LoggingEvent {
        if (!enabled) return this
        require(key.isNotEmpty()) { "field key must not be empty" }
        putField(key, value)
        return this
    }

    /** Attaches a `Boolean`-valued field. Primitive overload — avoids Kotlin call-site boxing. */
    fun field(key: String, value: Boolean): LoggingEvent {
        if (!enabled) return this
        require(key.isNotEmpty()) { "field key must not be empty" }
        putField(key, value)
        return this
    }

    /** Attaches a `Double`-valued field. Primitive overload — avoids Kotlin call-site boxing. */
    fun field(key: String, value: Double): LoggingEvent {
        if (!enabled) return this
        require(key.isNotEmpty()) { "field key must not be empty" }
        putField(key, value)
        return this
    }

    /**
     * Attaches an arbitrarily-typed field. Throwables, arrays, collections, and maps receive
     * special rendering on flush; primitives are passed through unchanged so structured
     * backends keep their type information.
     *
     * @param key the field key; must be non-empty.
     * @throws IllegalArgumentException if [key] is empty.
     */
    fun field(key: String, value: Any?): LoggingEvent {
        if (!enabled) return this
        require(key.isNotEmpty()) { "field key must not be empty" }
        putField(key, value)
        return this
    }

    /**
     * Sets the structured event-name tag emitted under the [EVENT_KEY] key. An empty name
     * clears any previously set value rather than emitting `event=""`.
     */
    fun event(name: String): LoggingEvent {
        if (!enabled) return this
        // Empty event name → do not emit `event=""`; treat as cleared.
        eventName = if (name.isEmpty()) null else name
        return this
    }

    /** Attaches a throwable cause to the event; forwarded to SLF4J's `setCause` on [log]. */
    fun cause(t: Throwable?): LoggingEvent {
        if (!enabled) return this
        cause = t
        return this
    }

    /**
     * Flushes the accumulated fields, event name, and cause to SLF4J at the level captured by
     * the originating `at*()` call. Idempotent — a second invocation on the same instance is
     * a no-op (guarded by [consumed]).
     *
     * @param message the human-readable message; defaults to empty since structured fields
     *   are typically the primary payload.
     */
    @JvmOverloads
    fun log(message: String = "") {
        if (!enabled) return
        if (!consumed.compareAndSet(false, true)) return

        val logger = this.logger ?: return
        val level = this.level ?: return

        val builder = logger.slf4j.atLevel(logger.slf4jLevel(level))

        // Global context first so per-event fields can override on the SLF4J side.
        val gc = logger.globalContext
        if (gc.isNotEmpty()) {
            for ((k, v) in gc) {
                builder.addKeyValue(k, renderForLog(v))
            }
        }

        eventName?.let { builder.addKeyValue(EVENT_KEY, it) }

        fields?.let {
            for ((k, v) in it) {
                builder.addKeyValue(k, renderForLog(v))
            }
        }

        cause?.let { builder.setCause(it) }

        builder.log(message)
    }

    // -- Internals -------------------------------------------------------------------------------

    private fun putField(key: String, value: Any?) {
        val map = fields ?: LinkedHashMap<String, Any?>().also { fields = it }
        map[key] = value
    }

    private fun renderForLog(value: Any?): Any? {
        // The SLF4J seam is `addKeyValue(String, Object)`; we pre-render anything we want
        // to special-case (Throwable, arrays, collections, primitive 'null' marker) so the
        // downstream encoder sees a sensible String. Primitives pass through unchanged so
        // structured backends can keep them typed.
        return try {
            when (value) {
                null -> NULL_PLACEHOLDER
                is Throwable -> renderThrowable(value)
                is String -> truncate(value)
                is Long, is Int, is Boolean, is Double, is Short, is Byte, is Float, is Char -> value
                is BooleanArray -> truncate(value.contentToString())
                is ByteArray -> truncate(value.contentToString())
                is CharArray -> truncate(value.contentToString())
                is ShortArray -> truncate(value.contentToString())
                is IntArray -> truncate(value.contentToString())
                is LongArray -> truncate(value.contentToString())
                is FloatArray -> truncate(value.contentToString())
                is DoubleArray -> truncate(value.contentToString())
                is Array<*> -> truncate(value.contentToString())
                is Collection<*> -> truncate(value.joinToString(prefix = "[", postfix = "]"))
                is Map<*, *> -> truncate(value.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" })
                else -> truncate(value.toString())
            }
        } catch (t: Throwable) {
            "<error: serializer threw: ${t.message}>"
        }
    }

    private fun renderThrowable(t: Throwable): String {
        val name = t::class.simpleName ?: t.javaClass.name
        return "$name: ${t.message}"
    }

    private fun truncate(s: String): String =
        if (s.length <= MAX_FIELD_LEN) s
        else s.substring(0, MAX_FIELD_LEN) + TRUNCATED_SUFFIX

    companion object {
        /** Standard structured key for the categorisation tag set by [LoggingEvent.event]. */
        const val EVENT_KEY: String = "event"

        /**
         * Literal string `"null"` used in place of a `null` field value. Emitting the literal
         * (rather than dropping the field) keeps log searches predictable — a missing field
         * would otherwise be indistinguishable from a bug in the producing code.
         */
        internal const val NULL_PLACEHOLDER: String = "null"

        /**
         * Per-field value cap before truncation (8 KiB). Larger rendered values are cut and
         * suffixed with [TRUNCATED_SUFFIX] to bound log volume.
         */
        internal const val MAX_FIELD_LEN: Int = 8 * 1024

        /** Suffix appended to a value that exceeded [MAX_FIELD_LEN] before truncation. */
        internal const val TRUNCATED_SUFFIX: String = "…[truncated]"

        /**
         * Shared singleton returned for disabled levels. All builder methods return `this`;
         * `log()` is a no-op. Allocates nothing per call site — the whole point of the facade.
         */
        @JvmField
        val NOOP: LoggingEvent = LoggingEvent(logger = null, level = null, enabled = false)

        /**
         * Returns a real enabled event when [logger] has [level] enabled, otherwise [NOOP].
         * Centralising the check here is what makes every `at*()` call site allocation-free
         * on the disabled path.
         */
        internal fun create(logger: ClientLogger, level: LogLevel): LoggingEvent =
            if (logger.canLog(level)) LoggingEvent(logger, level, enabled = true) else NOOP
    }
}
