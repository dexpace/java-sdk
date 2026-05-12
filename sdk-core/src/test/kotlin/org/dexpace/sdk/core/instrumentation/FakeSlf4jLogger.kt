package org.dexpace.sdk.core.instrumentation

import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.KeyValuePair
import org.slf4j.event.Level
import org.slf4j.spi.LoggingEventBuilder
import org.slf4j.spi.NOPLoggingEventBuilder

/**
 * Test double for `org.slf4j.Logger`. Records every event flushed through
 * `atLevel(...).log(...)` so tests can assert on key-value pairs, message, and cause.
 *
 * Returns the real [NOPLoggingEventBuilder] singleton for disabled levels — the same
 * shape SLF4J uses in production — so `ClientLogger`'s no-allocation path is exercised
 * exactly as users will see it.
 */
internal class FakeSlf4jLogger(
    private val loggerName: String = "fake",
    threshold: Level = Level.TRACE,
) : Logger {

    data class Recorded(
        val level: Level,
        val message: String?,
        val keyValues: List<KeyValuePair>,
        val cause: Throwable?,
    )

    val records: MutableList<Recorded> = mutableListOf()

    private var threshold: Level = threshold
    private var disabled: Boolean = false

    fun setThreshold(level: Level) {
        threshold = level
        disabled = false
    }

    /** Force every level off. SLF4J's `Level` enum has no OFF value, so we gate manually. */
    fun disableAll() {
        disabled = true
    }

    override fun getName(): String = loggerName

    override fun isEnabledForLevel(level: Level): Boolean {
        if (disabled) return false
        return level.toInt() >= threshold.toInt()
    }

    override fun atLevel(level: Level): LoggingEventBuilder =
        if (isEnabledForLevel(level)) RecordingBuilder(level) else NOPLoggingEventBuilder.singleton()

    // -- Required Logger surface (unused by ClientLogger; provided as no-ops) -------------------

    override fun isTraceEnabled(): Boolean = isEnabledForLevel(Level.TRACE)
    override fun isTraceEnabled(marker: Marker?): Boolean = isTraceEnabled
    override fun isDebugEnabled(): Boolean = isEnabledForLevel(Level.DEBUG)
    override fun isDebugEnabled(marker: Marker?): Boolean = isDebugEnabled
    override fun isInfoEnabled(): Boolean = isEnabledForLevel(Level.INFO)
    override fun isInfoEnabled(marker: Marker?): Boolean = isInfoEnabled
    override fun isWarnEnabled(): Boolean = isEnabledForLevel(Level.WARN)
    override fun isWarnEnabled(marker: Marker?): Boolean = isWarnEnabled
    override fun isErrorEnabled(): Boolean = isEnabledForLevel(Level.ERROR)
    override fun isErrorEnabled(marker: Marker?): Boolean = isErrorEnabled

    override fun trace(msg: String?) {}
    override fun trace(format: String?, arg: Any?) {}
    override fun trace(format: String?, arg1: Any?, arg2: Any?) {}
    override fun trace(format: String?, vararg arguments: Any?) {}
    override fun trace(msg: String?, t: Throwable?) {}
    override fun trace(marker: Marker?, msg: String?) {}
    override fun trace(marker: Marker?, format: String?, arg: Any?) {}
    override fun trace(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun trace(marker: Marker?, format: String?, vararg argArray: Any?) {}
    override fun trace(marker: Marker?, msg: String?, t: Throwable?) {}

    override fun debug(msg: String?) {}
    override fun debug(format: String?, arg: Any?) {}
    override fun debug(format: String?, arg1: Any?, arg2: Any?) {}
    override fun debug(format: String?, vararg arguments: Any?) {}
    override fun debug(msg: String?, t: Throwable?) {}
    override fun debug(marker: Marker?, msg: String?) {}
    override fun debug(marker: Marker?, format: String?, arg: Any?) {}
    override fun debug(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun debug(marker: Marker?, format: String?, vararg arguments: Any?) {}
    override fun debug(marker: Marker?, msg: String?, t: Throwable?) {}

    override fun info(msg: String?) {}
    override fun info(format: String?, arg: Any?) {}
    override fun info(format: String?, arg1: Any?, arg2: Any?) {}
    override fun info(format: String?, vararg arguments: Any?) {}
    override fun info(msg: String?, t: Throwable?) {}
    override fun info(marker: Marker?, msg: String?) {}
    override fun info(marker: Marker?, format: String?, arg: Any?) {}
    override fun info(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun info(marker: Marker?, format: String?, vararg arguments: Any?) {}
    override fun info(marker: Marker?, msg: String?, t: Throwable?) {}

    override fun warn(msg: String?) {}
    override fun warn(format: String?, arg: Any?) {}
    override fun warn(format: String?, vararg arguments: Any?) {}
    override fun warn(format: String?, arg1: Any?, arg2: Any?) {}
    override fun warn(msg: String?, t: Throwable?) {}
    override fun warn(marker: Marker?, msg: String?) {}
    override fun warn(marker: Marker?, format: String?, arg: Any?) {}
    override fun warn(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun warn(marker: Marker?, format: String?, vararg arguments: Any?) {}
    override fun warn(marker: Marker?, msg: String?, t: Throwable?) {}

    override fun error(msg: String?) {}
    override fun error(format: String?, arg: Any?) {}
    override fun error(format: String?, arg1: Any?, arg2: Any?) {}
    override fun error(format: String?, vararg arguments: Any?) {}
    override fun error(msg: String?, t: Throwable?) {}
    override fun error(marker: Marker?, msg: String?) {}
    override fun error(marker: Marker?, format: String?, arg: Any?) {}
    override fun error(marker: Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun error(marker: Marker?, format: String?, vararg arguments: Any?) {}
    override fun error(marker: Marker?, msg: String?, t: Throwable?) {}

    private inner class RecordingBuilder(private val level: Level) : LoggingEventBuilder {
        private val kvs = mutableListOf<KeyValuePair>()
        private var cause: Throwable? = null
        private var message: String? = null

        override fun setCause(t: Throwable?): LoggingEventBuilder { cause = t; return this }
        override fun addMarker(marker: Marker?): LoggingEventBuilder = this
        override fun addArgument(p: Any?): LoggingEventBuilder = this
        override fun addArgument(p: java.util.function.Supplier<*>?): LoggingEventBuilder = this
        override fun addKeyValue(key: String, value: Any?): LoggingEventBuilder {
            kvs.add(KeyValuePair(key, value))
            return this
        }
        override fun addKeyValue(key: String, supplier: java.util.function.Supplier<Any>?): LoggingEventBuilder {
            kvs.add(KeyValuePair(key, supplier?.get()))
            return this
        }
        override fun setMessage(msg: String?): LoggingEventBuilder { message = msg; return this }
        override fun setMessage(supplier: java.util.function.Supplier<String>?): LoggingEventBuilder {
            message = supplier?.get()
            return this
        }

        override fun log() {
            records.add(Recorded(level, message, kvs.toList(), cause))
        }
        override fun log(msg: String?) { message = msg; log() }
        override fun log(msg: String?, arg: Any?) { message = msg; log() }
        override fun log(msg: String?, arg1: Any?, arg2: Any?) { message = msg; log() }
        override fun log(msg: String?, vararg args: Any?) { message = msg; log() }
        override fun log(supplier: java.util.function.Supplier<String>?) { message = supplier?.get(); log() }
    }
}
