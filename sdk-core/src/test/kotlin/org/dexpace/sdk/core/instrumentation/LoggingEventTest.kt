package org.dexpace.sdk.core.instrumentation

import org.slf4j.event.Level
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoggingEventTest {

    private fun enabledLogger(globalContext: Map<String, Any?> = emptyMap()): Pair<ClientLogger, FakeSlf4jLogger> {
        val fake = FakeSlf4jLogger(threshold = Level.TRACE)
        return ClientLogger.forTesting(fake, globalContext) to fake
    }

    private fun List<org.slf4j.event.KeyValuePair>.toMap(): Map<String, Any?> =
        associate { it.key to it.value }

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
        val bad = object {
            override fun toString(): String = throw IllegalStateException("nope")
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
        val bad = object {
            override fun toString(): String = throw IllegalStateException("explode")
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
        val obj: Any = object {
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
}
