package org.dexpace.sdk.core.config

import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfigurationTest {

    // ----- Lookup order -----

    @Test
    fun `override wins over env and sysprop`() {
        val cfg = ConfigurationBuilder()
            .put("MAX_RETRY_ATTEMPTS", "9")
            .envSource { "5" }
            .propsSource { "3" }
            .build()
        assertEquals("9", cfg.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `env wins over sysprop when env is non-empty`() {
        val cfg = ConfigurationBuilder()
            .envSource { name -> if (name == "MAX_RETRY_ATTEMPTS") "5" else null }
            .propsSource { name -> if (name == "max.retry.attempts") "3" else null }
            .build()
        assertEquals("5", cfg.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `sysprop wins over default when env is absent`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { name -> if (name == "max.retry.attempts") "3" else null }
            .build()
        assertEquals("3", cfg.get("MAX_RETRY_ATTEMPTS", "1"))
    }

    @Test
    fun `default is returned when no layer matches`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { null }
            .build()
        assertEquals("fallback", cfg.get("MISSING_KEY", "fallback"))
    }

    @Test
    fun `missing key returns null without default`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { null }
            .build()
        assertNull(cfg.get("MISSING_KEY"))
    }

    @Test
    fun `empty env var falls through to sysprop`() {
        val cfg = ConfigurationBuilder()
            .envSource { "" } // shell-set-but-empty: present but empty
            .propsSource { name -> if (name == "max.retry.attempts") "7" else null }
            .build()
        assertEquals("7", cfg.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `empty env var falls through to default when sysprop also missing`() {
        val cfg = ConfigurationBuilder()
            .envSource { "" }
            .propsSource { null }
            .build()
        assertEquals("default-value", cfg.get("MAX_RETRY_ATTEMPTS", "default-value"))
    }

    // ----- getInt -----

    @Test
    fun `getInt parses numeric value`() {
        val cfg = ConfigurationBuilder().put("ATTEMPTS", "42").build()
        assertEquals(42, cfg.getInt("ATTEMPTS", 1))
    }

    @Test
    fun `getInt returns default on non-numeric input`() {
        val cfg = ConfigurationBuilder().put("ATTEMPTS", "not-a-number").build()
        assertEquals(7, cfg.getInt("ATTEMPTS", 7))
    }

    @Test
    fun `getInt returns default when missing`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { null }
            .build()
        assertEquals(3, cfg.getInt("MISSING", 3))
    }

    @Test
    fun `getInt handles negative numbers`() {
        val cfg = ConfigurationBuilder().put("N", "-5").build()
        assertEquals(-5, cfg.getInt("N", 0))
    }

    // ----- getBoolean (strict) -----

    @Test
    fun `getBoolean accepts true lowercase`() {
        val cfg = ConfigurationBuilder().put("FLAG", "true").build()
        assertTrue(cfg.getBoolean("FLAG", false))
    }

    @Test
    fun `getBoolean accepts false lowercase`() {
        val cfg = ConfigurationBuilder().put("FLAG", "false").build()
        assertFalse(cfg.getBoolean("FLAG", true))
    }

    @Test
    fun `getBoolean accepts TRUE uppercase`() {
        val cfg = ConfigurationBuilder().put("FLAG", "TRUE").build()
        assertTrue(cfg.getBoolean("FLAG", false))
    }

    @Test
    fun `getBoolean accepts mixed case False`() {
        val cfg = ConfigurationBuilder().put("FLAG", "False").build()
        assertFalse(cfg.getBoolean("FLAG", true))
    }

    @Test
    fun `getBoolean rejects 1`() {
        val cfg = ConfigurationBuilder().put("FLAG", "1").build()
        assertFalse(cfg.getBoolean("FLAG", false))
        assertTrue(cfg.getBoolean("FLAG", true))
    }

    @Test
    fun `getBoolean rejects yes`() {
        val cfg = ConfigurationBuilder().put("FLAG", "yes").build()
        assertFalse(cfg.getBoolean("FLAG", false))
    }

    @Test
    fun `getBoolean rejects on`() {
        val cfg = ConfigurationBuilder().put("FLAG", "on").build()
        assertFalse(cfg.getBoolean("FLAG", false))
    }

    @Test
    fun `getBoolean returns default when missing`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { null }
            .build()
        assertTrue(cfg.getBoolean("MISSING", true))
        assertFalse(cfg.getBoolean("MISSING", false))
    }

    // ----- getDuration -----

    @Test
    fun `getDuration parses ISO-8601 seconds`() {
        val cfg = ConfigurationBuilder().put("T", "PT5S").build()
        assertEquals(Duration.ofSeconds(5), cfg.getDuration("T", Duration.ZERO))
    }

    @Test
    fun `getDuration parses ISO-8601 day`() {
        val cfg = ConfigurationBuilder().put("T", "P1D").build()
        assertEquals(Duration.ofDays(1), cfg.getDuration("T", Duration.ZERO))
    }

    @Test
    fun `getDuration parses shorthand milliseconds`() {
        val cfg = ConfigurationBuilder().put("T", "500ms").build()
        assertEquals(Duration.ofMillis(500), cfg.getDuration("T", Duration.ZERO))
    }

    @Test
    fun `getDuration parses shorthand seconds`() {
        val cfg = ConfigurationBuilder().put("T", "5s").build()
        assertEquals(Duration.ofSeconds(5), cfg.getDuration("T", Duration.ZERO))
    }

    @Test
    fun `getDuration parses shorthand minutes`() {
        val cfg = ConfigurationBuilder().put("T", "1m").build()
        assertEquals(Duration.ofMinutes(1), cfg.getDuration("T", Duration.ZERO))
    }

    @Test
    fun `getDuration parses shorthand hours`() {
        val cfg = ConfigurationBuilder().put("T", "2h").build()
        assertEquals(Duration.ofHours(2), cfg.getDuration("T", Duration.ZERO))
    }

    @Test
    fun `getDuration parses shorthand days`() {
        val cfg = ConfigurationBuilder().put("T", "1d").build()
        assertEquals(Duration.ofDays(1), cfg.getDuration("T", Duration.ZERO))
    }

    @Test
    fun `getDuration parses bare number as milliseconds`() {
        val cfg = ConfigurationBuilder().put("T", "1000").build()
        assertEquals(Duration.ofMillis(1000), cfg.getDuration("T", Duration.ZERO))
        assertEquals(Duration.ofSeconds(1), cfg.getDuration("T", Duration.ZERO))
    }

    @Test
    fun `getDuration returns default on parse failure`() {
        val cfg = ConfigurationBuilder().put("T", "nonsense").build()
        assertEquals(Duration.ofSeconds(42), cfg.getDuration("T", Duration.ofSeconds(42)))
    }

    @Test
    fun `getDuration returns default on unknown unit`() {
        val cfg = ConfigurationBuilder().put("T", "5z").build()
        assertEquals(Duration.ofSeconds(99), cfg.getDuration("T", Duration.ofSeconds(99)))
    }

    @Test
    fun `getDuration returns default on invalid ISO-8601`() {
        val cfg = ConfigurationBuilder().put("T", "PTnope").build()
        assertEquals(Duration.ofSeconds(11), cfg.getDuration("T", Duration.ofSeconds(11)))
    }

    @Test
    fun `getDuration returns default when missing`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { null }
            .build()
        assertEquals(Duration.ofSeconds(30), cfg.getDuration("MISSING", Duration.ofSeconds(30)))
    }

    // ----- envToProp -----

    @Test
    fun `envToProp converts MAX_RETRY_ATTEMPTS to max dot retry dot attempts`() {
        assertEquals("max.retry.attempts", Configuration.envToProp("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `envToProp converts LOG_LEVEL to log dot level`() {
        assertEquals("log.level", Configuration.envToProp("LOG_LEVEL"))
    }

    @Test
    fun `envToProp lowercases single word`() {
        assertEquals("foo", Configuration.envToProp("FOO"))
    }

    @Test
    fun `envToProp leaves already-lower input untouched apart from underscore replacement`() {
        assertEquals("foo.bar", Configuration.envToProp("foo_bar"))
    }

    // ----- Well-known keys exist -----

    @Test
    fun `well-known keys have expected string values`() {
        assertEquals("MAX_RETRY_ATTEMPTS", Configuration.MAX_RETRY_ATTEMPTS)
        assertEquals("LOG_LEVEL", Configuration.LOG_LEVEL)
        assertEquals("HTTP_PROXY", Configuration.HTTP_PROXY)
        assertEquals("HTTPS_PROXY", Configuration.HTTPS_PROXY)
        assertEquals("NO_PROXY", Configuration.NO_PROXY)
    }

    // ----- Global configuration -----

    @AfterTest
    fun resetGlobal() {
        // Restore a fresh, empty Configuration so per-test mutations don't leak.
        Configuration.setGlobalConfiguration(ConfigurationBuilder().build())
    }

    @Test
    fun `getGlobalConfiguration returns a non-null Configuration by default`() {
        val cfg = Configuration.getGlobalConfiguration()
        assertNotNull(cfg)
    }

    @Test
    fun `setGlobalConfiguration round-trips`() {
        val custom = ConfigurationBuilder().put("X", "y").build()
        Configuration.setGlobalConfiguration(custom)
        assertSame(custom, Configuration.getGlobalConfiguration())
        assertEquals("y", Configuration.getGlobalConfiguration().get("X"))
    }

    @Test
    fun `setGlobalConfiguration null throws NullPointerException`() {
        // Force `null` past the Kotlin non-null param via reflection — mirrors a Java caller
        // who hands in null. The runtime `c == null` guard inside the function must trigger.
        val method = Configuration::class.java.getMethod(
            "setGlobalConfiguration",
            Configuration::class.java,
        )
        val ex = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            method.invoke(null, null as Configuration?)
        }
        assertTrue(ex.targetException is NullPointerException, "Expected NPE, got ${ex.targetException}")
    }

    // ----- ConfigurationBuilder behaviors -----

    @Test
    fun `builder put null name throws NullPointerException`() {
        val method = ConfigurationBuilder::class.java.getMethod(
            "put",
            String::class.java,
            String::class.java,
        )
        val ex = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            method.invoke(ConfigurationBuilder(), null as String?, "v")
        }
        assertTrue(ex.targetException is NullPointerException, "Expected NPE, got ${ex.targetException}")
    }

    @Test
    fun `builder put null value throws NullPointerException`() {
        val method = ConfigurationBuilder::class.java.getMethod(
            "put",
            String::class.java,
            String::class.java,
        )
        val ex = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            method.invoke(ConfigurationBuilder(), "k", null as String?)
        }
        assertTrue(ex.targetException is NullPointerException, "Expected NPE, got ${ex.targetException}")
    }

    @Test
    fun `builder test seams isolate from process env and sysprops`() {
        // Hermetic: neither the real env nor real sysprops should be touched.
        val cfg = ConfigurationBuilder()
            .envSource { name -> if (name == "K") "from-env" else null }
            .propsSource { name -> if (name == "k") "from-props" else null }
            .build()
        assertEquals("from-env", cfg.get("K"))
        // Real env vars (e.g. PATH) must not leak through the custom envSource.
        assertNull(cfg.get("PATH"))
    }

    @Test
    fun `builder put returns same instance for chaining`() {
        val b = ConfigurationBuilder()
        assertSame(b, b.put("a", "1"))
        assertSame(b, b.envSource { null })
        assertSame(b, b.propsSource { null })
    }

    @Test
    fun `overrides survive builder snapshot - later builder mutation does not leak`() {
        // ConfigurationBuilder.build copies overrides into a Configuration. Verify subsequent
        // builder mutations do not affect an already-built Configuration.
        val b = ConfigurationBuilder().put("A", "1")
        val cfg = b.build()
        b.put("A", "2")
        assertEquals("1", cfg.get("A"))
    }

    // ----- Empty/edge inputs -----

    @Test
    fun `get with empty default returns empty string`() {
        val cfg = ConfigurationBuilder()
            .envSource { null }
            .propsSource { null }
            .build()
        assertEquals("", cfg.get("X", ""))
    }
}
