/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

import org.slf4j.MDC
import org.slf4j.spi.LoggingEventBuilder
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
public class LoggingEvent internal constructor(
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
    public fun field(
        key: String,
        value: String?,
    ): LoggingEvent {
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
    public fun field(
        key: String,
        value: Long,
    ): LoggingEvent {
        if (!enabled) return this
        require(key.isNotEmpty()) { "field key must not be empty" }
        putField(key, value)
        return this
    }

    /** Attaches an `Int`-valued field. Primitive overload — avoids Kotlin call-site boxing. */
    public fun field(
        key: String,
        value: Int,
    ): LoggingEvent {
        if (!enabled) return this
        require(key.isNotEmpty()) { "field key must not be empty" }
        putField(key, value)
        return this
    }

    /** Attaches a `Boolean`-valued field. Primitive overload — avoids Kotlin call-site boxing. */
    public fun field(
        key: String,
        value: Boolean,
    ): LoggingEvent {
        if (!enabled) return this
        require(key.isNotEmpty()) { "field key must not be empty" }
        putField(key, value)
        return this
    }

    /** Attaches a `Double`-valued field. Primitive overload — avoids Kotlin call-site boxing. */
    public fun field(
        key: String,
        value: Double,
    ): LoggingEvent {
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
    public fun field(
        key: String,
        value: Any?,
    ): LoggingEvent {
        if (!enabled) return this
        require(key.isNotEmpty()) { "field key must not be empty" }
        putField(key, value)
        return this
    }

    /**
     * Sets the structured event-name tag emitted under the [EVENT_KEY] key. An empty name
     * clears any previously set value rather than emitting `event=""`.
     *
     * The name tag is authoritative for the [EVENT_KEY] (`"event"`) key: when a non-empty name
     * is set, any `"event"` key arriving from the logger's global context, the folded MDC, or a
     * per-event [field] is suppressed so the event carries the `"event"` key exactly once. When
     * no name is set (or it was cleared), a user-supplied `"event"` key passes through unchanged.
     *
     * A per-event `field("event", …)` whose value is dropped this way is reported once at DEBUG so
     * the override is visible while debugging; ambient `"event"` keys from the global context or MDC
     * defer to the tag silently.
     */
    public fun event(name: String): LoggingEvent {
        if (!enabled) return this
        // Empty event name → do not emit `event=""`; treat as cleared.
        eventName = if (name.isEmpty()) null else name
        return this
    }

    /** Attaches a throwable cause to the event; forwarded to SLF4J's `setCause` on [log]. */
    public fun cause(t: Throwable?): LoggingEvent {
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
    public fun log(message: String = "") {
        if (!enabled) return
        if (!consumed.compareAndSet(false, true)) return

        val logger = this.logger ?: return
        val level = this.level ?: return

        val builder = logger.slf4j.atLevel(logger.slf4jLevel(level))

        // When `event(name)` set a non-empty name, the dedicated [EVENT_KEY] tag is authoritative:
        // any `"event"` key arriving from the global context, MDC, or a per-event `field(...)` is
        // skipped so the name tag is the single `event` entry. SLF4J's addKeyValue APPENDS rather
        // than replaces, so emitting both would put two KeyValuePairs named `event` on the event —
        // invalid duplicate-key JSON in JSON appenders. `null` means no name tag is emitted, so the
        // user's `"event"` key (if any) passes through normally.
        val eventNameTag = eventName
        val reservedEventKey = if (eventNameTag != null) EVENT_KEY else null
        if (eventNameTag != null) warnOnDroppedEventField(logger, eventNameTag)

        val gc = logger.globalContext
        emitGlobalContext(builder, gc, reservedEventKey)
        emitMdc(builder, gc, logger.mdcKeys, reservedEventKey)
        eventNameTag?.let { builder.addKeyValue(EVENT_KEY, it) }
        emitFields(builder, reservedEventKey)

        cause?.let { builder.setCause(it) }

        builder.log(message)
    }

    // -- Internals -------------------------------------------------------------------------------

    /**
     * Emits the logger's global context. A global-context key that is also a per-event field is
     * skipped here and emitted once below from `fields`, letting the per-event value win. SLF4J's
     * addKeyValue APPENDS rather than replaces, so emitting both would put two KeyValuePairs with
     * the same name on the event — invalid duplicate-key JSON in JSON appenders. The reserved
     * [EVENT_KEY] is skipped when an event-name tag is set so the tag stays authoritative.
     */
    private fun emitGlobalContext(
        builder: LoggingEventBuilder,
        gc: Map<String, Any?>,
        reservedEventKey: String?,
    ) {
        if (gc.isEmpty()) return
        val perEventKeys = fields
        for ((k, v) in gc) {
            if (k == reservedEventKey || perEventKeys?.containsKey(k) == true) continue
            builder.addKeyValue(k, renderForLog(v))
        }
    }

    /**
     * Folds SLF4J MDC into the structured event so trace.id / span.id set by an enclosing
     * TracingScope reaches log backends as structured fields. Only keys in [allowedMdcKeys] are
     * folded (default: "trace.id", "span.id"); a `null` allow-list folds everything
     * (backwards-compat). A key already emitted by this event — via a per-event `field(...)`, the
     * global context ([gc]), or the authoritative event-name tag — is skipped so JSON appenders
     * never see a duplicate-key entry. All lookups are O(1).
     */
    private fun emitMdc(
        builder: LoggingEventBuilder,
        gc: Map<String, Any?>,
        allowedMdcKeys: Set<String>?,
        reservedEventKey: String?,
    ) {
        val mdcMap = MDC.getCopyOfContextMap() ?: return
        val perEventKeys = fields
        for ((k, v) in mdcMap) {
            if (v == null || k == reservedEventKey) continue
            val alreadyEmitted = perEventKeys?.containsKey(k) == true || gc.containsKey(k)
            if (!alreadyEmitted && (allowedMdcKeys == null || allowedMdcKeys.contains(k))) {
                builder.addKeyValue(k, v)
            }
        }
    }

    /**
     * Emits the per-event fields. The reserved [EVENT_KEY] is skipped when an event-name tag is set
     * so the tag — already emitted by [log] — remains the single `event` entry.
     */
    private fun emitFields(
        builder: LoggingEventBuilder,
        reservedEventKey: String?,
    ) {
        val map = fields ?: return
        for ((k, v) in map) {
            if (k == reservedEventKey) continue
            builder.addKeyValue(k, renderForLog(v))
        }
    }

    /**
     * Surfaces, at DEBUG, the one case where the authoritative event-name tag silently swallows a
     * caller value: a per-event `field(EVENT_KEY, …)` whose key collides with the name tag. That
     * field is dropped by [emitFields], so the caller's value never reaches the backend; logging it
     * here makes the misuse visible when DEBUG is on (idiomatic SLF4J parameterised logging formats
     * the message only then). An ambient `EVENT_KEY` from the global context or MDC is expected to
     * defer to the tag and is *not* flagged — only the explicit `field(...)` collision is.
     */
    private fun warnOnDroppedEventField(
        logger: ClientLogger,
        eventNameTag: String,
    ) {
        if (fields?.containsKey(EVENT_KEY) != true) return
        logger.slf4j.debug(
            "LoggingEvent: dropped field \"{}\" because event(\"{}\") owns that key; " +
                "rename the field to keep its value.",
            EVENT_KEY,
            eventNameTag,
        )
    }

    private fun putField(
        key: String,
        value: Any?,
    ) {
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
                is Map<*, *> ->
                    truncate(
                        value.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" },
                    )
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
        if (s.length <= MAX_FIELD_LEN) {
            s
        } else {
            s.substring(0, MAX_FIELD_LEN) + TRUNCATED_SUFFIX
        }

    public companion object {
        /** Standard structured key for the categorisation tag set by [LoggingEvent.event]. */
        public const val EVENT_KEY: String = "event"

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
        public val NOOP: LoggingEvent = LoggingEvent(logger = null, level = null, enabled = false)

        /**
         * Returns a real enabled event when [logger] has [level] enabled, otherwise [NOOP].
         * Centralising the check here is what makes every `at*()` call site allocation-free
         * on the disabled path.
         */
        internal fun create(
            logger: ClientLogger,
            level: LogLevel,
        ): LoggingEvent = if (logger.canLog(level)) LoggingEvent(logger, level, enabled = true) else NOOP
    }
}
