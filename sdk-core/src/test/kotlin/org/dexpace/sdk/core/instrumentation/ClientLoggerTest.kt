package org.dexpace.sdk.core.instrumentation

import org.slf4j.event.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClientLoggerTest {

    @Test
    fun `atError returns enabled event when ERROR is enabled and NOOP otherwise`() {
        val fake = FakeSlf4jLogger(threshold = Level.ERROR)
        val logger = ClientLogger.forTesting(fake)
        assertNotSame(LoggingEvent.NOOP, logger.atError())

        fake.disableAll()
        assertSame(LoggingEvent.NOOP, logger.atError())
    }

    @Test
    fun `atWarning returns enabled event when WARN is enabled and NOOP otherwise`() {
        val fake = FakeSlf4jLogger(threshold = Level.WARN)
        val logger = ClientLogger.forTesting(fake)
        assertNotSame(LoggingEvent.NOOP, logger.atWarning())

        fake.setThreshold(Level.ERROR)
        assertSame(LoggingEvent.NOOP, logger.atWarning())
    }

    @Test
    fun `atInfo returns enabled event when INFO is enabled and NOOP otherwise`() {
        val fake = FakeSlf4jLogger(threshold = Level.INFO)
        val logger = ClientLogger.forTesting(fake)
        assertNotSame(LoggingEvent.NOOP, logger.atInfo())

        fake.setThreshold(Level.WARN)
        assertSame(LoggingEvent.NOOP, logger.atInfo())
    }

    @Test
    fun `atVerbose returns enabled event when DEBUG is enabled and NOOP otherwise`() {
        val fake = FakeSlf4jLogger(threshold = Level.DEBUG)
        val logger = ClientLogger.forTesting(fake)
        assertNotSame(LoggingEvent.NOOP, logger.atVerbose())

        fake.setThreshold(Level.INFO)
        assertSame(LoggingEvent.NOOP, logger.atVerbose())
    }

    @Test
    fun `canLog reflects underlying SLF4J threshold for each level`() {
        val fake = FakeSlf4jLogger(threshold = Level.INFO)
        val logger = ClientLogger.forTesting(fake)

        assertTrue(logger.canLog(LogLevel.ERROR))
        assertTrue(logger.canLog(LogLevel.WARNING))
        assertTrue(logger.canLog(LogLevel.INFO))
        assertFalse(logger.canLog(LogLevel.VERBOSE))

        fake.setThreshold(Level.DEBUG)
        assertTrue(logger.canLog(LogLevel.VERBOSE))
    }

    @Test
    fun `constructor accepts String name`() {
        ClientLogger("org.dexpace.test")
    }

    @Test
    fun `constructor accepts KClass name`() {
        ClientLogger(ClientLoggerTest::class)
    }

    @Test
    fun `constructor accepts Java Class name`() {
        // Exercises the `Class<*>` overload — the natural Java-side `new ClientLogger(MyClass.class)`
        // surface. Construction must succeed without throwing; the underlying SLF4J binding is
        // slf4j-nop in tests so we cannot assert on the resolved logger name.
        val logger = ClientLogger(ClientLoggerTest::class.java)
        assertEquals("", logger.globalContext.entries.joinToString())
    }

    @Test
    fun `constructor accepts Java Class name with globalContext`() {
        val ctx = mapOf("svc" to "billing")
        val logger = ClientLogger(ClientLoggerTest::class.java, ctx)
        // The map reference is retained — not copied — so identity equality holds.
        assertSame(ctx, logger.globalContext)
    }

    @Test
    fun `empty name is rejected`() {
        assertFailsWith<IllegalArgumentException> { ClientLogger("") }
    }

    @Test
    fun `globalContext flows into every event`() {
        val fake = FakeSlf4jLogger(threshold = Level.DEBUG)
        val logger = ClientLogger.forTesting(
            fake,
            globalContext = mapOf("service" to "auth", "version" to "1.2.3"),
        )

        logger.atInfo().log("hello")
        logger.atVerbose().log("world")

        assertEquals(2, fake.records.size)
        for (rec in fake.records) {
            val kvMap = rec.keyValues.associate { it.key to it.value }
            assertEquals("auth", kvMap["service"])
            assertEquals("1.2.3", kvMap["version"])
        }
    }

    @Test
    fun `VERBOSE maps to SLF4J DEBUG`() {
        val fake = FakeSlf4jLogger(threshold = Level.TRACE)
        val logger = ClientLogger.forTesting(fake)

        logger.atError().log("e")
        logger.atWarning().log("w")
        logger.atInfo().log("i")
        logger.atVerbose().log("v")

        assertEquals(
            listOf(Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG),
            fake.records.map { it.level },
        )
    }
}
