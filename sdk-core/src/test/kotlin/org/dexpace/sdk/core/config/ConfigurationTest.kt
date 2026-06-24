/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.config

import java.time.Duration
import java.util.function.Function
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
        val cfg =
            ConfigurationBuilder()
                .put("MAX_RETRY_ATTEMPTS", "9")
                .envSource { "5" }
                .propsSource { "3" }
                .build()
        assertEquals("9", cfg.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `env wins over sysprop when env is non-empty`() {
        val cfg =
            ConfigurationBuilder()
                .envSource { name -> if (name == "MAX_RETRY_ATTEMPTS") "5" else null }
                .propsSource { name -> if (name == "max.retry.attempts") "3" else null }
                .build()
        assertEquals("5", cfg.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `sysprop wins over default when env is absent`() {
        val cfg =
            ConfigurationBuilder()
                .envSource { null }
                .propsSource { name -> if (name == "max.retry.attempts") "3" else null }
                .build()
        assertEquals("3", cfg.get("MAX_RETRY_ATTEMPTS", "1"))
    }

    @Test
    fun `default is returned when no layer matches`() {
        val cfg =
            ConfigurationBuilder()
                .envSource { null }
                .propsSource { null }
                .build()
        assertEquals("fallback", cfg.get("MISSING_KEY", "fallback"))
    }

    @Test
    fun `missing key returns null without default`() {
        val cfg =
            ConfigurationBuilder()
                .envSource { null }
                .propsSource { null }
                .build()
        assertNull(cfg.get("MISSING_KEY"))
    }

    @Test
    fun `empty env var falls through to sysprop`() {
        val cfg =
            ConfigurationBuilder()
                .envSource { "" } // shell-set-but-empty: present but empty
                .propsSource { name -> if (name == "max.retry.attempts") "7" else null }
                .build()
        assertEquals("7", cfg.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `empty env var falls through to default when sysprop also missing`() {
        val cfg =
            ConfigurationBuilder()
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
        val cfg =
            ConfigurationBuilder()
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
        val cfg =
            ConfigurationBuilder()
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
        val cfg =
            ConfigurationBuilder()
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
        val method =
            Configuration::class.java.getMethod(
                "setGlobalConfiguration",
                Configuration::class.java,
            )
        val ex =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                method.invoke(null, null as Configuration?)
            }
        assertTrue(ex.targetException is NullPointerException, "Expected NPE, got ${ex.targetException}")
    }

    // ----- ConfigurationBuilder behaviors -----

    @Test
    fun `builder factory returns a fresh empty builder each call`() {
        // Java-friendly factory: equivalent to `new ConfigurationBuilder()`, distinct each call.
        val first = Configuration.builder()
        val second = Configuration.builder()
        assertFalse(first === second)
        // It starts empty: with the seams pinned absent, an unset key resolves to null.
        val cfg = Configuration.builder().envSource { null }.propsSource { null }.build()
        assertNull(cfg.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `builder put null name throws NullPointerException`() {
        val method =
            ConfigurationBuilder::class.java.getMethod(
                "put",
                String::class.java,
                String::class.java,
            )
        val ex =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                method.invoke(ConfigurationBuilder(), null as String?, "v")
            }
        assertTrue(ex.targetException is NullPointerException, "Expected NPE, got ${ex.targetException}")
    }

    @Test
    fun `builder put null value throws NullPointerException`() {
        val method =
            ConfigurationBuilder::class.java.getMethod(
                "put",
                String::class.java,
                String::class.java,
            )
        val ex =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                method.invoke(ConfigurationBuilder(), "k", null as String?)
            }
        assertTrue(ex.targetException is NullPointerException, "Expected NPE, got ${ex.targetException}")
    }

    @Test
    fun `builder remove deletes an existing override`() {
        // Seams pinned absent, so a removed override leaves nothing to fall back to: get -> null.
        val cfg =
            ConfigurationBuilder()
                .put("MAX_RETRY_ATTEMPTS", "3")
                .remove("MAX_RETRY_ATTEMPTS")
                .envSource { null }
                .propsSource { null }
                .build()
        assertNull(cfg.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `builder remove of an absent key is a no-op`() {
        // Removing a key that was never overridden must not throw and must not disturb other keys.
        val cfg =
            ConfigurationBuilder()
                .put("A", "1")
                .remove("NEVER_SET")
                .envSource { null }
                .propsSource { null }
                .build()
        assertEquals("1", cfg.get("A"))
        assertNull(cfg.get("NEVER_SET"))
    }

    @Test
    fun `builder remove drops only the override layer - env seam still resolves`() {
        // remove un-pins the explicit override; it does NOT suppress the inherited env seam for
        // that key. The key must still resolve via env after the override is gone.
        val cfg =
            ConfigurationBuilder()
                .put("MAX_RETRY_ATTEMPTS", "3")
                .envSource { name -> if (name == "MAX_RETRY_ATTEMPTS") "5" else null }
                .propsSource { null }
                .remove("MAX_RETRY_ATTEMPTS")
                .build()
        assertEquals("5", cfg.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `builder remove drops only the override layer - property seam still resolves`() {
        // Mirror of the env fall-through case, exercising the distinct system-property branch of
        // get(): env is pinned absent, so the removed override falls through to the envToProp-
        // normalized property lookup (MAX_RETRY_ATTEMPTS -> max.retry.attempts).
        val cfg =
            ConfigurationBuilder()
                .put("MAX_RETRY_ATTEMPTS", "3")
                .envSource { null }
                .propsSource { name -> if (name == "max.retry.attempts") "9" else null }
                .remove("MAX_RETRY_ATTEMPTS")
                .build()
        assertEquals("9", cfg.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `builder remove returns same instance for chaining`() {
        val b = ConfigurationBuilder()
        assertSame(b, b.remove("anything"))
    }

    @Test
    fun `builder remove null name throws NullPointerException`() {
        val method =
            ConfigurationBuilder::class.java.getMethod(
                "remove",
                String::class.java,
            )
        val ex =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                method.invoke(ConfigurationBuilder(), null as String?)
            }
        assertTrue(ex.targetException is NullPointerException, "Expected NPE, got ${ex.targetException}")
    }

    @Test
    fun `builder test seams isolate from process env and sysprops`() {
        // Hermetic: neither the real env nor real sysprops should be touched.
        val cfg =
            ConfigurationBuilder()
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
        val cfg =
            ConfigurationBuilder()
                .envSource { null }
                .propsSource { null }
                .build()
        assertEquals("", cfg.get("X", ""))
    }

    // ----- getProperty (un-normalized sysprop) -----

    @Test
    fun `getProperty returns the raw system property value without normalization`() {
        // The propsSource gets the exact name passed in — no UPPER_SNAKE → lower.dot translation.
        val cfg =
            ConfigurationBuilder()
                .envSource { null }
                .propsSource { name -> if (name == "https.proxyHost") "secure.example.com" else null }
                .build()
        assertEquals("secure.example.com", cfg.getProperty("https.proxyHost"))
    }

    @Test
    fun `getProperty returns null when the property is unset`() {
        val cfg =
            ConfigurationBuilder()
                .envSource { null }
                .propsSource { null }
                .build()
        assertNull(cfg.getProperty("https.proxyHost"))
    }

    @Test
    fun `getProperty preserves casing - does not auto-normalize to lower dot`() {
        // get() would lowercase MAX_RETRY_ATTEMPTS → max.retry.attempts before hitting propsSource.
        // getProperty must NOT do that — verify by responding only to the exact UPPER_SNAKE name.
        val cfg =
            ConfigurationBuilder()
                .envSource { null }
                .propsSource { name -> if (name == "MAX_RETRY_ATTEMPTS") "42" else null }
                .build()
        assertEquals("42", cfg.getProperty("MAX_RETRY_ATTEMPTS"))
        assertNull(cfg.getProperty("max.retry.attempts"))
    }

    @Test
    fun `getProperty ignores envSource entirely`() {
        // getProperty is a pure system-property accessor — env-var fallback does not apply.
        val cfg =
            ConfigurationBuilder()
                .envSource { name -> if (name == "https.proxyHost") "from-env" else null }
                .propsSource { null }
                .build()
        assertNull(cfg.getProperty("https.proxyHost"))
    }

    // ----- parseDuration edge cases -----

    @Test
    fun `getDuration with empty string override returns default`() {
        // The override value is empty → parseDuration early-returns null at the `isEmpty` check.
        val cfg = ConfigurationBuilder().put("T", "").build()
        assertEquals(Duration.ofSeconds(5), cfg.getDuration("T", Duration.ofSeconds(5)))
    }

    @Test
    fun `getDuration with negative shorthand returns default`() {
        // `-5s` → numPart is `-5`, parses to -5 → rejected by the `n < 0` guard.
        val cfg = ConfigurationBuilder().put("T", "-5s").build()
        assertEquals(Duration.ofSeconds(9), cfg.getDuration("T", Duration.ofSeconds(9)))
    }

    @Test
    fun `parseDuration directly returns null on empty input`() {
        // Internal helper; assert the early return on empty input — branch coverage.
        assertNull(Configuration.parseDuration(""))
    }

    @Test
    fun `parseDuration directly returns null on negative shorthand`() {
        assertNull(Configuration.parseDuration("-5s"))
    }

    @Test
    fun `parseDuration rejects negative ISO-8601 duration`() {
        // `PT-5S` is a syntactically valid ISO-8601 duration that yields a negative Duration.
        // Consumers (Clock.sleep, Futures.delay) throw on negatives, so the parser must reject
        // it here rather than letting a negative value escape — matching the shorthand path.
        assertNull(Configuration.parseDuration("PT-5S"))
        assertNull(Configuration.parseDuration("P-1D"))
    }

    @Test
    fun `parseDuration accepts zero ISO-8601 duration`() {
        // Zero is non-negative and must survive the negativity guard.
        assertEquals(Duration.ZERO, Configuration.parseDuration("PT0S"))
    }

    @Test
    fun `getDuration with negative ISO-8601 override returns default`() {
        val cfg = ConfigurationBuilder().put("T", "PT-5S").build()
        assertEquals(Duration.ofSeconds(9), cfg.getDuration("T", Duration.ofSeconds(9)))
    }

    @Test
    fun `parseDuration handles lowercase P prefix for ISO-8601`() {
        // `Character.toUpperCase(raw[0]) == 'P'` covers both "P..." and "p..." case-insensitively.
        assertEquals(Duration.ofSeconds(5), Configuration.parseDuration("pt5s"))
    }

    // ----- @JvmStatic bridge methods (static side of Configuration) -----

    @Test
    fun `static bridge for getGlobalConfiguration is callable via reflection`() {
        // Covers the `@JvmStatic` static-bridge method on `Configuration` (distinct from
        // the inner-companion impl). A real Java caller hits this bridge first.
        val method = Configuration::class.java.getMethod("getGlobalConfiguration")
        val result = method.invoke(null)
        assertNotNull(result)
        assertTrue(result is Configuration)
    }

    @Test
    fun `static bridge for setGlobalConfiguration accepts non-null value via reflection`() {
        // Pairs with the existing null-throws test: the bridge with a valid argument forwards
        // to the companion successfully. Hits the static-bridge body lines.
        val custom = ConfigurationBuilder().put("BRIDGE", "hit").build()
        val method =
            Configuration::class.java.getMethod(
                "setGlobalConfiguration",
                Configuration::class.java,
            )
        method.invoke(null, custom)
        assertEquals("hit", Configuration.getGlobalConfiguration().get("BRIDGE"))
    }

    // ----- derive / newBuilder (copy-on-write derivation) -----

    @Test
    fun `derive adds a new override on the derived configuration`() {
        val base = ConfigurationBuilder().put("MAX_RETRY_ATTEMPTS", "3").build()
        val derived = base.derive { it.put("LOG_LEVEL", "DEBUG") }
        assertEquals("3", derived.get("MAX_RETRY_ATTEMPTS"))
        assertEquals("DEBUG", derived.get("LOG_LEVEL"))
    }

    @Test
    fun `derive leaves the original configuration unchanged`() {
        val base = ConfigurationBuilder().put("MAX_RETRY_ATTEMPTS", "3").build()
        val derived = base.derive { it.put("LOG_LEVEL", "DEBUG") }
        // The override added to the derived copy must not leak back into the receiver.
        assertNull(base.get("LOG_LEVEL"))
        assertEquals("3", base.get("MAX_RETRY_ATTEMPTS"))
        // The two instances are distinct objects.
        assertFalse(base === derived)
    }

    @Test
    fun `derive can override an existing key without mutating the original`() {
        val base = ConfigurationBuilder().put("MAX_RETRY_ATTEMPTS", "3").build()
        val derived = base.derive { it.put("MAX_RETRY_ATTEMPTS", "9") }
        assertEquals("9", derived.get("MAX_RETRY_ATTEMPTS"))
        assertEquals("3", base.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `derive inherits the env and property lookup seams`() {
        val base =
            ConfigurationBuilder()
                .envSource { name -> if (name == "MAX_RETRY_ATTEMPTS") "5" else null }
                .propsSource { name -> if (name == "log.level") "INFO" else null }
                .build()
        val derived = base.derive { it.put("LOG_LEVEL", "DEBUG") }
        // Inherited env seam still resolves on the derived copy.
        assertEquals("5", derived.get("MAX_RETRY_ATTEMPTS"))
        // Explicit override on the derived copy wins over the inherited property seam.
        assertEquals("DEBUG", derived.get("LOG_LEVEL"))
        // The base, queried for the same key, still falls through to the property seam.
        assertEquals("INFO", base.get("LOG_LEVEL"))
    }

    @Test
    fun `derive override wins over the inherited env seam on the derived copy`() {
        val base =
            ConfigurationBuilder()
                .envSource { name -> if (name == "MAX_RETRY_ATTEMPTS") "5" else null }
                .propsSource { null }
                .build()
        // The inherited env seam supplies "5"; the derived copy adds an explicit override for the
        // same key. Override -> env precedence must hold across derivation.
        val derived = base.derive { it.put("MAX_RETRY_ATTEMPTS", "9") }
        assertEquals("9", derived.get("MAX_RETRY_ATTEMPTS"))
        // The base, queried for the same key, still falls through to the inherited env seam.
        assertEquals("5", base.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `derive inherits the env and property seams by reference, not by copy`() {
        val env = Function<String, String?> { null }
        val props = Function<String, String?> { null }
        val base = ConfigurationBuilder().envSource(env).propsSource(props).build()
        val derived = base.derive { it.put("LOG_LEVEL", "DEBUG") }
        // The pure read seams are inherited by reference; the override map is the only copied state.
        assertSame(base.envSource, derived.envSource)
        assertSame(base.propsSource, derived.propsSource)
    }

    @Test
    fun `derive replacing a source detaches only the derived copy from the shared seam`() {
        val baseEnv = Function<String, String?> { null }
        val newEnv = Function<String, String?> { name -> if (name == "MAX_RETRY_ATTEMPTS") "9" else null }
        val base = ConfigurationBuilder().envSource(baseEnv).propsSource { null }.build()
        val derived = base.derive { it.envSource(newEnv) }
        // The derived copy swaps in the new seam...
        assertSame(newEnv, derived.envSource)
        // ...while the base keeps its original reference (copy-on-write, not aliased mutation).
        assertSame(baseEnv, base.envSource)
        // Behaviour follows the rebinding: derived resolves via newEnv, base does not.
        assertEquals("9", derived.get("MAX_RETRY_ATTEMPTS"))
        assertNull(base.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `derive preserves the empty-env skip-to-sysprop semantics on the derived copy`() {
        val base =
            ConfigurationBuilder()
                .envSource { "" } // present-but-empty: must be treated as absent
                .propsSource { name -> if (name == "max.retry.attempts") "7" else null }
                .build()
        val derived = base.derive { it.put("LOG_LEVEL", "DEBUG") }
        // The empty-env skip is inherited: the derived copy still falls through to the sysprop seam.
        assertEquals("7", derived.get("MAX_RETRY_ATTEMPTS"))
        assertEquals("DEBUG", derived.get("LOG_LEVEL"))
    }

    @Test
    fun `derive on a configuration with no overrides yields an override-only copy`() {
        // Empty override map + pinned-absent seams: exercises the prefilled constructor's
        // putAll(emptyMap) path hermetically, without touching the real process environment.
        val base = ConfigurationBuilder().envSource { null }.propsSource { null }.build()
        val derived = base.derive { it.put("LOG_LEVEL", "DEBUG") }
        assertEquals("DEBUG", derived.get("LOG_LEVEL"))
        assertNull(base.get("LOG_LEVEL"))
        assertFalse(base === derived)
    }

    @Test
    fun `derive chained twice accumulates overrides and leaves each level independent`() {
        val base =
            ConfigurationBuilder()
                .put("A", "1")
                .envSource { null }
                .propsSource { null }
                .build()
        val d1 = base.derive { it.put("B", "2") }
        val d2 =
            d1.derive {
                it.put("C", "3")
                it.put("A", "9")
            }
        // d2 accumulates across both hops, with its own override shadowing the inherited A.
        assertEquals("9", d2.get("A"))
        assertEquals("2", d2.get("B"))
        assertEquals("3", d2.get("C"))
        // The intermediate d1 is unaffected by d2's later additions/overrides.
        assertEquals("1", d1.get("A"))
        assertEquals("2", d1.get("B"))
        assertNull(d1.get("C"))
        // The base never sees anything added downstream.
        assertEquals("1", base.get("A"))
        assertNull(base.get("B"))
        assertNull(base.get("C"))
    }

    @Test
    fun `derive with an empty mutator yields an equivalent independent configuration`() {
        val base = ConfigurationBuilder().put("MAX_RETRY_ATTEMPTS", "3").build()
        val derived = base.derive { /* no-op */ }
        assertFalse(base === derived)
        assertEquals("3", derived.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `derive null mutator throws NullPointerException`() {
        // Force `null` past the Kotlin non-null param via reflection — mirrors a Java caller
        // who hands in null. The compiler-generated non-null check must trigger, as the KDoc claims.
        val method =
            Configuration::class.java.getMethod(
                "derive",
                java.util.function.Consumer::class.java,
            )
        val base = ConfigurationBuilder().put("MAX_RETRY_ATTEMPTS", "3").build()
        val ex =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                method.invoke(base, null as java.util.function.Consumer<ConfigurationBuilder>?)
            }
        assertTrue(ex.targetException is NullPointerException, "Expected NPE, got ${ex.targetException}")
    }

    @Test
    fun `newBuilder prefills overrides and sources and is independent of the source`() {
        val base = ConfigurationBuilder().put("MAX_RETRY_ATTEMPTS", "3").build()
        val builder = base.newBuilder()
        builder.put("LOG_LEVEL", "DEBUG")
        val derived = builder.build()
        assertEquals("3", derived.get("MAX_RETRY_ATTEMPTS"))
        assertEquals("DEBUG", derived.get("LOG_LEVEL"))
        // Mutating the builder after the fact never affects the already-derived configuration
        // nor the original.
        builder.put("EXTRA", "x")
        assertNull(derived.get("EXTRA"))
        assertNull(base.get("EXTRA"))
        assertNull(base.get("LOG_LEVEL"))
    }

    @Test
    fun `prefilled builder constructor copies the override map defensively`() {
        val base = ConfigurationBuilder().put("MAX_RETRY_ATTEMPTS", "3").build()
        val first = base.newBuilder().put("A", "1").build()
        val second = base.newBuilder().put("B", "2").build()
        // Two independent derivations from the same base do not see each other's overrides.
        assertEquals("1", first.get("A"))
        assertNull(first.get("B"))
        assertEquals("2", second.get("B"))
        assertNull(second.get("A"))
    }

    @Test
    fun `derive removing an inherited override un-pins it without mutating the original`() {
        val base =
            ConfigurationBuilder()
                .put("LOG_LEVEL", "DEBUG")
                .envSource { null }
                .propsSource { null }
                .build()
        val derived = base.derive { it.remove("LOG_LEVEL") }
        // The derived copy no longer carries the override and, with seams pinned absent, resolves null.
        assertNull(derived.get("LOG_LEVEL"))
        // The base keeps its override: removal on the derived copy must not leak back.
        assertEquals("DEBUG", base.get("LOG_LEVEL"))
    }

    @Test
    fun `derive remove drops only the override and falls back to the inherited env seam`() {
        // The crux of remove's contract across derivation: removing an inherited override un-pins
        // the explicit value but does NOT suppress the inherited env seam for that key. The base
        // forces "3"; the derived copy removes the override and so falls back to the env seam's "5".
        val base =
            ConfigurationBuilder()
                .put("MAX_RETRY_ATTEMPTS", "3")
                .envSource { name -> if (name == "MAX_RETRY_ATTEMPTS") "5" else null }
                .propsSource { null }
                .build()
        val derived = base.derive { it.remove("MAX_RETRY_ATTEMPTS") }
        assertEquals("5", derived.get("MAX_RETRY_ATTEMPTS"))
        // The base still resolves the explicit override it was built with.
        assertEquals("3", base.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `derive remove drops only the override and falls back to the inherited property seam`() {
        // Property-seam mirror of the env fall-through case: env pinned absent, so removing the
        // inherited override on the derived copy lets the key resolve via the inherited,
        // envToProp-normalized system-property seam (MAX_RETRY_ATTEMPTS -> max.retry.attempts).
        val base =
            ConfigurationBuilder()
                .put("MAX_RETRY_ATTEMPTS", "3")
                .envSource { null }
                .propsSource { name -> if (name == "max.retry.attempts") "9" else null }
                .build()
        val derived = base.derive { it.remove("MAX_RETRY_ATTEMPTS") }
        assertEquals("9", derived.get("MAX_RETRY_ATTEMPTS"))
        // The base still resolves the explicit override it was built with.
        assertEquals("3", base.get("MAX_RETRY_ATTEMPTS"))
    }

    @Test
    fun `derive remove of an absent override is a harmless no-op`() {
        val base = ConfigurationBuilder().put("A", "1").envSource { null }.propsSource { null }.build()
        val derived = base.derive { it.remove("NEVER_SET") }
        assertEquals("1", derived.get("A"))
        assertNull(derived.get("NEVER_SET"))
        assertFalse(base === derived)
    }
}
