/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

import org.slf4j.MDC
import org.slf4j.event.Level
import org.slf4j.spi.MDCAdapter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoggingEventTest {
    // Saved before every test and restored after so that MDC tests installing
    // BasicMDCAdapter do not bleed the non-nop adapter into the rest of the suite.
    private var savedMdcAdapter: MDCAdapter? = null

    @BeforeTest
    fun saveMdcAdapter() {
        savedMdcAdapter = MDC.getMDCAdapter()
    }

    @AfterTest
    fun restoreMdcAdapter() {
        MDC.clear()
        val adapter = savedMdcAdapter
        if (adapter != null) {
            val field = MDC::class.java.getDeclaredField("MDC_ADAPTER")
            field.isAccessible = true
            field.set(null, adapter)
        }
    }

    private fun enabledLogger(globalContext: Map<String, Any?> = emptyMap()): Pair<ClientLogger, FakeSlf4jLogger> {
        val fake = FakeSlf4jLogger(threshold = Level.TRACE)
        return ClientLogger.forTesting(fake, globalContext) to fake
    }

    private fun List<org.slf4j.event.KeyValuePair>.toMap(): Map<String, Any?> = associate { it.key to it.value }

    // -- Fluent chain ---------------------------------------------------------------------------

    @Test
    fun `fluent chain emits structured fields, event name, cause, message`() {
        val (logger, fake) = enabledLogger()
        val ex = RuntimeException("boom")

        logger.atInfo()
            .event("http.request")
            .field("method", "GET")
            .field("status", 200)
            .cause(ex)
            .log("done")

        assertEquals(1, fake.records.size)
        val rec = fake.records.single()
        assertEquals(Level.INFO, rec.level)
        assertEquals("done", rec.message)
        assertSame(ex, rec.cause)

        val kv = rec.keyValues.toMap()
        assertEquals("http.request", kv[LoggingEvent.EVENT_KEY])
        assertEquals("GET", kv["method"])
        assertEquals(200, kv["status"])
    }

    @Test
    fun `log with no message defaults to empty string`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("k", "v").log()
        assertEquals("", fake.records.single().message)
    }

    // -- Primitive overloads --------------------------------------------------------------------

    @Test
    fun `primitive overloads chosen by Kotlin dispatch produce typed values`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo()
            .field("l", 7L)
            .field("i", 3)
            .field("b", true)
            .field("d", 1.5)
            .log()

        val kv = fake.records.single().keyValues.toMap()
        // SLF4J's addKeyValue(String, Object) autoboxes, but the recorded value preserves
        // the original primitive type since we don't render-string primitives.
        assertEquals(7L, kv["l"])
        assertEquals(3, kv["i"])
        assertEquals(true, kv["b"])
        assertEquals(1.5, kv["d"])
    }

    // -- NOOP behaviour -------------------------------------------------------------------------

    @Test
    fun `NOOP returns itself for every builder method`() {
        val noop = LoggingEvent.NOOP
        assertSame(noop, noop.field("k", "v"))
        assertSame(noop, noop.field("k", 1L))
        assertSame(noop, noop.field("k", 1))
        assertSame(noop, noop.field("k", true))
        assertSame(noop, noop.field("k", 1.0))
        assertSame(noop, noop.field("k", null as Any?))
        assertSame(noop, noop.event("e"))
        assertSame(noop, noop.cause(RuntimeException()))
        // log() must be silent — nothing to assert beyond no throw.
        noop.log()
        noop.log("hello")
    }

    @Test
    fun `disabled level uses the NOOP singleton and emits no log lines`() {
        val fake = FakeSlf4jLogger(threshold = Level.ERROR) // VERBOSE -> DEBUG, disabled
        val logger = ClientLogger.forTesting(fake)

        // Run many iterations; with NOOP the chain must not produce events.
        repeat(1000) {
            val ev = logger.atVerbose()
            assertSame(LoggingEvent.NOOP, ev)
            ev.event("e").field("k", "v").field("n", 42L).cause(RuntimeException()).log("ignored")
        }

        assertTrue(fake.records.isEmpty())
    }

    // -- Double-log guard -----------------------------------------------------------------------

    @Test
    fun `second log call on same enabled event is a no-op`() {
        val (logger, fake) = enabledLogger()
        val ev = logger.atInfo().field("k", "v")
        ev.log("first")
        ev.log("second")
        assertEquals(1, fake.records.size)
        assertEquals("first", fake.records.single().message)
    }

    @Test
    fun `single-thread accumulation then concurrent second log is a no-op`() {
        // Build and accumulate on the current thread; then race two threads calling log().
        // Only one should produce a record — the consumed AtomicBoolean ensures the second
        // caller loses the CAS and exits silently.
        val (logger, fake) = enabledLogger()
        val ev = logger.atInfo().field("k", "v").event("e")

        val barrier = java.util.concurrent.CyclicBarrier(2)
        val t1 =
            Thread {
                barrier.await()
                ev.log("from-t1")
            }
        val t2 =
            Thread {
                barrier.await()
                ev.log("from-t2")
            }
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        assertEquals(1, fake.records.size)
    }

    // -- Field rendering edge cases ------------------------------------------------------------

    @Test
    fun `null field value is recorded as the literal string null`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("k", null as String?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("null", kv["k"])
    }

    @Test
    fun `null field value via Any overload is recorded as literal null`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("k", null as Any?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("null", kv["k"])
    }

    @Test
    fun `throwable as a field value renders as ClassName colon message`() {
        val (logger, fake) = enabledLogger()
        val ex = IllegalArgumentException("bad input")
        logger.atInfo().field("err", ex as Any?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("IllegalArgumentException: bad input", kv["err"])
    }

    @Test
    fun `array field renders via contentToString`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo()
            .field("ints", intArrayOf(1, 2, 3) as Any?)
            .field("strs", arrayOf("a", "b") as Any?)
            .log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("[1, 2, 3]", kv["ints"])
        assertEquals("[a, b]", kv["strs"])
    }

    @Test
    fun `collection field renders via joinToString`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("xs", listOf("a", "b", "c") as Any?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("[a, b, c]", kv["xs"])
    }

    @Test
    fun `truncation kicks in above 8 KiB`() {
        val (logger, fake) = enabledLogger()
        val big = "x".repeat(9000)
        logger.atInfo().field("blob", big).log()
        val kv = fake.records.single().keyValues.toMap()
        val rendered = kv["blob"] as String
        assertEquals(LoggingEvent.MAX_FIELD_LEN + LoggingEvent.TRUNCATED_SUFFIX.length, rendered.length)
        assertContains(rendered, LoggingEvent.TRUNCATED_SUFFIX)
    }

    @Test
    fun `field below truncation cap is not modified`() {
        val (logger, fake) = enabledLogger()
        val small = "x".repeat(LoggingEvent.MAX_FIELD_LEN)
        logger.atInfo().field("blob", small).log()
        assertEquals(small, fake.records.single().keyValues.toMap()["blob"])
    }

    @Test
    fun `serializer throwing is caught and reported as placeholder`() {
        val (logger, fake) = enabledLogger()
        val bad =
            object {
                override fun toString(): String = error("nope")
            }
        logger.atInfo().field("k", bad as Any?).log()
        val rendered = fake.records.single().keyValues.toMap()["k"] as String
        assertContains(rendered, "<error: serializer threw")
    }

    @Test
    fun `empty key is rejected`() {
        val (logger, _) = enabledLogger()
        assertFailsWith<IllegalArgumentException> {
            logger.atInfo().field("", "v")
        }
    }

    @Test
    fun `empty event name clears the field`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().event("e").event("").log()
        val kv = fake.records.single().keyValues.toMap()
        assertNull(kv[LoggingEvent.EVENT_KEY])
    }

    @Test
    fun `event name last-write-wins`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().event("first").event("second").log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("second", kv[LoggingEvent.EVENT_KEY])
    }

    // -- globalContext semantics ---------------------------------------------------------------

    @Test
    fun `globalContext is included on every event`() {
        val (logger, fake) = enabledLogger(mapOf("region" to "us-east", "build" to 42))
        logger.atInfo().field("path", "/v1/items").log()

        val kv = fake.records.single().keyValues.toMap()
        assertEquals("us-east", kv["region"])
        assertEquals(42, kv["build"])
        assertEquals("/v1/items", kv["path"])
    }

    @Test
    fun `globalContext map is shared, not copied per event`() {
        val ctx = mapOf("a" to 1)
        val fake = FakeSlf4jLogger(threshold = Level.TRACE)
        val logger = ClientLogger.forTesting(fake, ctx)

        // Same reference is held; mutating user-side post-construction is not supported,
        // but we ensure the logger does not eagerly clone.
        assertSame(ctx, logger.globalContext)
    }

    // -- log() returns nothing useful -----------------------------------------------------------

    @Test
    fun `cause null is accepted and emits no cause`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().cause(null).log()
        assertNull(fake.records.single().cause)
    }

    @Test
    fun `field with empty map renders default toString`() {
        // Specifically verify Map rendering path completes without throwing.
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("m", mapOf("k" to "v") as Any?).log()
        val rendered = fake.records.single().keyValues.toMap()["m"] as String
        assertEquals("{k=v}", rendered)
    }

    @Test
    fun `NOOP isEnabled state is false and second log on enabled event is silent`() {
        val (logger, fake) = enabledLogger()
        val ev = logger.atInfo()
        assertNotNull(ev)
        ev.log("once")
        ev.field("late", "x").log("twice")
        assertEquals(1, fake.records.size)
    }

    // -- renderForLog: primitive-typed array branches -------------------------------------------

    @Test
    fun `boolean array renders via contentToString`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("bools", booleanArrayOf(true, false, true) as Any?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("[true, false, true]", kv["bools"])
    }

    @Test
    fun `byte array renders via contentToString`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("bytes", byteArrayOf(1, 2, 3) as Any?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("[1, 2, 3]", kv["bytes"])
    }

    @Test
    fun `char array renders via contentToString`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("chars", charArrayOf('a', 'b', 'c') as Any?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("[a, b, c]", kv["chars"])
    }

    @Test
    fun `short array renders via contentToString`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("shorts", shortArrayOf(1, 2, 3) as Any?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("[1, 2, 3]", kv["shorts"])
    }

    @Test
    fun `long array renders via contentToString`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("longs", longArrayOf(10L, 20L, 30L) as Any?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("[10, 20, 30]", kv["longs"])
    }

    @Test
    fun `float array renders via contentToString`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("floats", floatArrayOf(1.5f, 2.5f) as Any?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("[1.5, 2.5]", kv["floats"])
    }

    @Test
    fun `double array renders via contentToString`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("doubles", doubleArrayOf(1.25, 2.5) as Any?).log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("[1.25, 2.5]", kv["doubles"])
    }

    // -- renderForLog: primitive value branches (passed through Any overload) -------------------

    @Test
    fun `Short primitive passes through unchanged`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("v", 7.toShort() as Any?).log()
        assertEquals(7.toShort(), fake.records.single().keyValues.toMap()["v"])
    }

    @Test
    fun `Byte primitive passes through unchanged`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("v", 3.toByte() as Any?).log()
        assertEquals(3.toByte(), fake.records.single().keyValues.toMap()["v"])
    }

    @Test
    fun `Float primitive passes through unchanged`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("v", 2.5f as Any?).log()
        assertEquals(2.5f, fake.records.single().keyValues.toMap()["v"])
    }

    @Test
    fun `Char primitive passes through unchanged`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("v", 'Z' as Any?).log()
        assertEquals('Z', fake.records.single().keyValues.toMap()["v"])
    }

    @Test
    fun `Long primitive via Any overload passes through unchanged`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("v", 42L as Any?).log()
        assertEquals(42L, fake.records.single().keyValues.toMap()["v"])
    }

    @Test
    fun `Int primitive via Any overload passes through unchanged`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("v", 42 as Any?).log()
        assertEquals(42, fake.records.single().keyValues.toMap()["v"])
    }

    @Test
    fun `Boolean primitive via Any overload passes through unchanged`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("v", true as Any?).log()
        assertEquals(true, fake.records.single().keyValues.toMap()["v"])
    }

    @Test
    fun `Double primitive via Any overload passes through unchanged`() {
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("v", 3.14 as Any?).log()
        assertEquals(3.14, fake.records.single().keyValues.toMap()["v"])
    }

    // -- renderForLog: serializer-exception path (Throwable whose message throws) ---------------

    @Test
    fun `serializer exception path renders the catch block placeholder`() {
        // A custom object whose toString() throws is caught by the outer try/catch in
        // renderForLog and emitted as `<error: serializer threw: <message>>`.
        val bad =
            object {
                override fun toString(): String = error("explode")
            }
        val (logger, fake) = enabledLogger()
        logger.atInfo().field("v", bad as Any?).log()
        val rendered = fake.records.single().keyValues.toMap()["v"] as String
        assertContains(rendered, "<error: serializer threw")
        assertContains(rendered, "explode")
    }

    @Test
    fun `arbitrary non-primitive object falls through to toString`() {
        val (logger, fake) = enabledLogger()
        val obj: Any =
            object {
                override fun toString(): String = "custom-rendered"
            }
        logger.atInfo().field("v", obj).log()
        assertEquals("custom-rendered", fake.records.single().keyValues.toMap()["v"])
    }

    // -- empty-key rejection on each primitive overload (require branch) ------------------------

    @Test
    fun `empty key rejected on Long field overload`() {
        val (logger, _) = enabledLogger()
        assertFailsWith<IllegalArgumentException> { logger.atInfo().field("", 1L) }
    }

    @Test
    fun `empty key rejected on Int field overload`() {
        val (logger, _) = enabledLogger()
        assertFailsWith<IllegalArgumentException> { logger.atInfo().field("", 1) }
    }

    @Test
    fun `empty key rejected on Boolean field overload`() {
        val (logger, _) = enabledLogger()
        assertFailsWith<IllegalArgumentException> { logger.atInfo().field("", true) }
    }

    @Test
    fun `empty key rejected on Double field overload`() {
        val (logger, _) = enabledLogger()
        assertFailsWith<IllegalArgumentException> { logger.atInfo().field("", 1.5) }
    }

    @Test
    fun `empty key rejected on Any field overload`() {
        val (logger, _) = enabledLogger()
        assertFailsWith<IllegalArgumentException> { logger.atInfo().field("", Any()) }
    }

    // -- SLF4J MDC folding -------------------------------------------------------------------
    //
    // These tests require a functional MDCAdapter. The class-level @BeforeTest / @AfterTest
    // above save and restore the original adapter (plus clear MDC) around every test,
    // so these two install BasicMDCAdapter explicitly and the teardown puts the nop adapter
    // back — preventing state from bleeding into the rest of the suite.

    @Test
    fun `log folds MDC entries into the structured event`() {
        installBasicMdcAdapter()
        MDC.put("trace.id", "abc123")
        MDC.put("span.id", "def456")
        val (logger, fake) = enabledLogger()
        logger.atInfo().event("test.event").log("hello")
        val rec = fake.records.single()
        val kv = rec.keyValues.toMap()
        assertEquals("abc123", kv["trace.id"])
        assertEquals("def456", kv["span.id"])
        // Guard against Fix 2 regressing: there must be exactly one trace.id entry,
        // not two (which would happen if the MDC entry were also emitted as a per-event
        // field carrying the same key, producing a duplicate KeyValuePair).
        val traceIdEntries = rec.keyValues.count { it.key == "trace.id" }
        assertEquals(1, traceIdEntries, "expected exactly one trace.id entry; duplicate would mean Fix 2 regressed")
    }

    @Test
    fun `per-event fields override MDC entries with the same key`() {
        installBasicMdcAdapter()
        MDC.put("trace.id", "mdc-trace")
        val (logger, fake) = enabledLogger()
        logger.atInfo()
            .event("test.event")
            .field("trace.id", "event-trace")
            .log("hello")
        val rec = fake.records.single()
        val kv = rec.keyValues.toMap()
        assertEquals("event-trace", kv["trace.id"])
        val traceIdEntries = rec.keyValues.count { it.key == "trace.id" }
        assertEquals(1, traceIdEntries, "expected exactly one trace.id entry; duplicate would mean Fix 2 regressed")
    }

    @Test
    fun `key set in both globalContext and MDC is emitted exactly once`() {
        // A key carried by both the logger's globalContext (emitted unconditionally) and the
        // allow-listed MDC must NOT be folded twice. Two KeyValuePair entries with the same
        // key serialise to invalid duplicate-key JSON in JSON appenders.
        installBasicMdcAdapter()
        MDC.put("trace.id", "mdc-trace")
        val (logger, fake) = enabledLogger(mapOf("trace.id" to "global-trace"))
        logger.atInfo().event("test.event").log("hello")

        val rec = fake.records.single()
        val traceIdEntries = rec.keyValues.filter { it.key == "trace.id" }
        assertEquals(
            1,
            traceIdEntries.size,
            "expected exactly one trace.id entry; a second from MDC would be a duplicate KeyValuePair",
        )
        // globalContext is emitted first and wins — the MDC value must not be folded over it.
        assertEquals("global-trace", traceIdEntries.single().value)
    }

    @Test
    fun `per-event field overrides globalContext key and is emitted exactly once`() {
        // A key carried by both the logger's globalContext and a per-event field() must be
        // emitted once, with the per-event value winning. Two KeyValuePair entries with the
        // same key serialise to invalid duplicate-key JSON in JSON appenders.
        val (logger, fake) = enabledLogger(mapOf("region" to "global"))
        logger.atInfo().field("region", "event").log()

        val rec = fake.records.single()
        val regionEntries = rec.keyValues.filter { it.key == "region" }
        assertEquals(
            1,
            regionEntries.size,
            "expected exactly one region entry; a second from globalContext would be a duplicate KeyValuePair",
        )
        // The per-event field wins over the globalContext value.
        assertEquals("event", regionEntries.single().value)
    }

    @Test
    fun `event name and a colliding field both named event are emitted exactly once`() {
        // The event-name tag and a per-event field() named "event" collide on the reserved
        // EVENT_KEY. They must produce a single "event" entry — two KeyValuePair entries with
        // the same key serialise to invalid duplicate-key JSON in JSON appenders.
        val (logger, fake) = enabledLogger()
        logger.atInfo().event("request.start").field(LoggingEvent.EVENT_KEY, "override").log()

        val rec = fake.records.single()
        val eventEntries = rec.keyValues.filter { it.key == LoggingEvent.EVENT_KEY }
        assertEquals(
            1,
            eventEntries.size,
            "expected exactly one event entry; a second from field() would be a duplicate KeyValuePair",
        )
        // The dedicated event-name tag wins over a colliding field.
        assertEquals("request.start", eventEntries.single().value)
    }

    @Test
    fun `event name wins over a colliding globalContext event key, emitted once`() {
        // A logger whose globalContext carries an "event" key plus a set event-name tag must
        // emit the "event" key once, with the name tag winning.
        val (logger, fake) = enabledLogger(mapOf(LoggingEvent.EVENT_KEY to "from-context"))
        logger.atInfo().event("request.start").log()

        val rec = fake.records.single()
        val eventEntries = rec.keyValues.filter { it.key == LoggingEvent.EVENT_KEY }
        assertEquals(
            1,
            eventEntries.size,
            "expected exactly one event entry; a second from globalContext would be a duplicate KeyValuePair",
        )
        assertEquals("request.start", eventEntries.single().value)
    }

    @Test
    fun `event name wins over a colliding MDC event key, emitted once`() {
        installBasicMdcAdapter()
        MDC.put(LoggingEvent.EVENT_KEY, "from-mdc")
        // Null allow-list folds all MDC keys, so "event" would otherwise be folded.
        val fake = FakeSlf4jLogger(threshold = Level.TRACE)
        val logger = ClientLogger.forTesting(fake, mdcKeys = null)
        logger.atInfo().event("request.start").log()

        val rec = fake.records.single()
        val eventEntries = rec.keyValues.filter { it.key == LoggingEvent.EVENT_KEY }
        assertEquals(
            1,
            eventEntries.size,
            "expected exactly one event entry; a second from MDC would be a duplicate KeyValuePair",
        )
        assertEquals("request.start", eventEntries.single().value)
    }

    @Test
    fun `user event key passes through unchanged when no event name is set`() {
        // Guard against over-suppression: with no event-name tag, a user "event" field is a
        // normal field and must be emitted.
        val (logger, fake) = enabledLogger()
        logger.atInfo().field(LoggingEvent.EVENT_KEY, "user-value").log()

        val kv = fake.records.single().keyValues.toMap()
        assertEquals("user-value", kv[LoggingEvent.EVENT_KEY])
    }

    @Test
    fun `dropping a colliding event field is surfaced at DEBUG`() {
        // The field() value is silently swallowed by the authoritative name tag; a DEBUG
        // diagnostic must surface that so the misuse is visible when debugging.
        val (logger, fake) = enabledLogger()
        logger.atInfo().event("request.start").field(LoggingEvent.EVENT_KEY, "override").log()

        val message = fake.plainMessages.single { it.level == Level.DEBUG }.message!!
        assertContains(message, LoggingEvent.EVENT_KEY)
        assertContains(message, "request.start")
        // The structured event still carries the name-tag value exactly once (unchanged behaviour).
        val eventEntries = fake.records.single().keyValues.filter { it.key == LoggingEvent.EVENT_KEY }
        assertEquals("request.start", eventEntries.single().value)
    }

    @Test
    fun `no DEBUG diagnostic when the level is disabled`() {
        // The diagnostic must cost nothing visible when DEBUG is off — SLF4J's parameterised
        // logging skips formatting, and nothing is recorded.
        val fake = FakeSlf4jLogger(threshold = Level.INFO)
        val logger = ClientLogger.forTesting(fake)
        logger.atInfo().event("request.start").field(LoggingEvent.EVENT_KEY, "override").log()

        assertTrue(fake.plainMessages.isEmpty())
    }

    @Test
    fun `no DEBUG diagnostic without a colliding field`() {
        // Only the explicit field() collision is flagged: a plain event(name), or an ambient
        // globalContext "event" key, must not emit the diagnostic.
        val (loggerNoField, fakeNoField) = enabledLogger()
        loggerNoField.atInfo().event("request.start").log()
        assertTrue(fakeNoField.plainMessages.isEmpty())

        val (loggerCtx, fakeCtx) = enabledLogger(mapOf(LoggingEvent.EVENT_KEY to "from-context"))
        loggerCtx.atInfo().event("request.start").log()
        assertTrue(fakeCtx.plainMessages.isEmpty())
    }

    @Test
    fun `no DEBUG diagnostic when a user event field has no name tag`() {
        // With no name tag set the user field is legitimately emitted, not dropped — so no warning.
        val (logger, fake) = enabledLogger()
        logger.atInfo().field(LoggingEvent.EVENT_KEY, "user-value").log()

        assertTrue(fake.plainMessages.isEmpty())
    }

    @Test
    fun `dropped colliding event field is reported at most once per logger`() {
        // The misuse is a static call-site error: repeating it on the same logger (e.g. in a hot
        // loop) must not flood DEBUG. The diagnostic fires once per logger; every event is still
        // emitted with the name tag intact.
        val (logger, fake) = enabledLogger()
        repeat(3) {
            logger.atInfo().event("request.start").field(LoggingEvent.EVENT_KEY, "override").log()
        }

        assertEquals(
            1,
            fake.plainMessages.count { it.level == Level.DEBUG },
            "expected the dropped-field diagnostic at most once per logger",
        )
        assertEquals(3, fake.records.size, "every structured event is still emitted")
        fake.records.forEach { rec ->
            val eventEntries = rec.keyValues.filter { it.key == LoggingEvent.EVENT_KEY }
            assertEquals("request.start", eventEntries.single().value)
        }
    }

    @Test
    fun `MDC keys absent from globalContext are still folded`() {
        // Guard the collision fix against over-skipping: an MDC key NOT present in
        // globalContext must continue to be folded into the event.
        installBasicMdcAdapter()
        MDC.put("trace.id", "mdc-trace")
        MDC.put("span.id", "mdc-span")
        val (logger, fake) = enabledLogger(mapOf("trace.id" to "global-trace"))
        logger.atInfo().event("test.event").log("hello")

        val kv = fake.records.single().keyValues.toMap()
        // trace.id is supplied by globalContext (collision → global wins, MDC skipped).
        assertEquals("global-trace", kv["trace.id"])
        // span.id is only in MDC and must still be folded.
        assertEquals("mdc-span", kv["span.id"])
    }

    // -- renderThrowable simpleName-null fallback --------------------------------------------

    @Test
    fun `throwable with anonymous class records the message`() {
        // Anonymous local class — KClass.simpleName may be null, exercising the
        // javaClass.name fallback path in renderThrowable.
        val (logger, fake) = enabledLogger()
        val anon: Throwable = object : RuntimeException("anon-msg") {}
        logger.atInfo().field("err", anon as Any?).log()
        val rendered = fake.records.single().keyValues.toMap()["err"] as String
        assertContains(rendered, "anon-msg")
    }

    // -- MDC allow-list ------------------------------------------------------------------

    @Test
    fun `default allow-list folds only trace_id and span_id from MDC`() {
        installBasicMdcAdapter()
        MDC.put("trace.id", "t1")
        MDC.put("span.id", "s1")
        MDC.put("x-app-tenant", "acme")
        MDC.put("user.id", "u42")
        val (logger, fake) = enabledLogger() // default mdcKeys = setOf("trace.id", "span.id")
        logger.atInfo().event("test.event").log()
        val kv = fake.records.single().keyValues.toMap()
        // Only allowed keys reach the event.
        assertEquals("t1", kv["trace.id"])
        assertEquals("s1", kv["span.id"])
        assertNull(kv["x-app-tenant"], "x-app-tenant must not reach event with default allow-list")
        assertNull(kv["user.id"], "user.id must not reach event with default allow-list")
    }

    @Test
    fun `custom allow-list includes additional MDC keys`() {
        installBasicMdcAdapter()
        MDC.put("trace.id", "t2")
        MDC.put("span.id", "s2")
        MDC.put("x-app-tenant", "widgets")
        MDC.put("user.id", "u99")
        val fake = FakeSlf4jLogger(threshold = org.slf4j.event.Level.TRACE)
        val logger = ClientLogger.forTesting(fake, mdcKeys = setOf("trace.id", "span.id", "x-app-tenant"))
        logger.atInfo().event("test.event").log()
        val kv = fake.records.single().keyValues.toMap()
        assertEquals("t2", kv["trace.id"])
        assertEquals("s2", kv["span.id"])
        assertEquals("widgets", kv["x-app-tenant"])
        // user.id is not in the custom allow-list.
        assertNull(kv["user.id"], "user.id must not reach event — not in custom allow-list")
    }

    @Test
    fun `null mdcKeys folds all MDC entries (backwards-compat)`() {
        installBasicMdcAdapter()
        MDC.put("trace.id", "t3")
        MDC.put("x-app-tenant", "legacy")
        MDC.put("user.id", "u0")
        val fake = FakeSlf4jLogger(threshold = org.slf4j.event.Level.TRACE)
        val logger = ClientLogger.forTesting(fake, mdcKeys = null)
        logger.atInfo().event("test.event").log()
        val kv = fake.records.single().keyValues.toMap()
        // With null allow-list, all MDC keys are folded.
        assertEquals("t3", kv["trace.id"])
        assertEquals("legacy", kv["x-app-tenant"])
        assertEquals("u0", kv["user.id"])
    }
}
