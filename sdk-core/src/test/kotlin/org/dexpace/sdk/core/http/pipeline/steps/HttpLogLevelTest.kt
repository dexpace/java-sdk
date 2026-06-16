/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.config.ConfigurationBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpLogLevelTest {
    /** Builds a [org.dexpace.sdk.core.config.Configuration] whose env layer returns [entries] and nothing else. */
    private fun configWithEnv(vararg entries: Pair<String, String>) =
        ConfigurationBuilder()
            .envSource { name -> entries.firstOrNull { it.first == name }?.second }
            .propsSource { null }
            .build()

    // ----- Known value resolves (case-insensitive) -----

    @Test
    fun `known key resolves to the matching level`() {
        val cfg = configWithEnv("MY_PRODUCT_LOG_LEVEL" to "HEADERS")
        assertEquals(
            HttpLogLevel.HEADERS,
            HttpLogLevel.fromConfiguration("MY_PRODUCT_LOG_LEVEL", cfg, HttpLogLevel.NONE),
        )
    }

    @Test
    fun `value resolves case-insensitively`() {
        val cfg = configWithEnv("MY_PRODUCT_LOG_LEVEL" to "body_and_headers")
        assertEquals(
            HttpLogLevel.BODY_AND_HEADERS,
            HttpLogLevel.fromConfiguration("MY_PRODUCT_LOG_LEVEL", cfg, HttpLogLevel.NONE),
        )
    }

    @Test
    fun `value resolves with surrounding whitespace trimmed`() {
        val cfg = configWithEnv("MY_PRODUCT_LOG_LEVEL" to "  Headers  ")
        assertEquals(
            HttpLogLevel.HEADERS,
            HttpLogLevel.fromConfiguration("MY_PRODUCT_LOG_LEVEL", cfg, HttpLogLevel.NONE),
        )
    }

    @Test
    fun `each level name round-trips`() {
        HttpLogLevel.entries.forEach { level ->
            val cfg = configWithEnv("LL" to level.name)
            assertEquals(level, HttpLogLevel.fromConfiguration("LL", cfg, HttpLogLevel.NONE))
        }
    }

    // ----- Resolution honors the full Configuration layering, not just env -----

    @Test
    fun `value resolves from the system-property layer when the env is unset`() {
        // Env unset; the property is looked up under the normalized name (MY_PRODUCT_LOG_LEVEL
        // -> my.product.log.level). The resolved value must still feed the level parsing.
        val cfg =
            ConfigurationBuilder()
                .envSource { null }
                .propsSource { name -> if (name == "my.product.log.level") "HEADERS" else null }
                .build()
        assertEquals(
            HttpLogLevel.HEADERS,
            HttpLogLevel.fromConfiguration("MY_PRODUCT_LOG_LEVEL", cfg, HttpLogLevel.NONE),
        )
    }

    @Test
    fun `explicit override wins over a conflicting env value`() {
        // Override is the top layer; it must take precedence over the env entry for the same key.
        val cfg =
            ConfigurationBuilder()
                .put("MY_PRODUCT_LOG_LEVEL", "BODY_AND_HEADERS")
                .envSource { name -> if (name == "MY_PRODUCT_LOG_LEVEL") "HEADERS" else null }
                .propsSource { null }
                .build()
        assertEquals(
            HttpLogLevel.BODY_AND_HEADERS,
            HttpLogLevel.fromConfiguration("MY_PRODUCT_LOG_LEVEL", cfg, HttpLogLevel.NONE),
        )
    }

    // ----- Unset key falls back to the supplied default -----

    @Test
    fun `unset key returns the supplied default`() {
        val cfg = configWithEnv() // nothing set
        assertEquals(
            HttpLogLevel.HEADERS,
            HttpLogLevel.fromConfiguration("MY_PRODUCT_LOG_LEVEL", cfg, HttpLogLevel.HEADERS),
        )
    }

    @Test
    fun `empty env value returns the supplied default`() {
        // An empty env var (EXAMPLE=) is treated as absent by Configuration, so the default applies.
        val cfg = configWithEnv("MY_PRODUCT_LOG_LEVEL" to "")
        assertEquals(
            HttpLogLevel.BODY_AND_HEADERS,
            HttpLogLevel.fromConfiguration("MY_PRODUCT_LOG_LEVEL", cfg, HttpLogLevel.BODY_AND_HEADERS),
        )
    }

    @Test
    fun `whitespace-only value returns the supplied default`() {
        // Configuration only treats an exactly-empty env string as absent, so a whitespace-only
        // value is returned as-is; fromConfiguration's own trim collapses it to "" and falls to the default.
        val cfg = configWithEnv("MY_PRODUCT_LOG_LEVEL" to "   ")
        assertEquals(
            HttpLogLevel.HEADERS,
            HttpLogLevel.fromConfiguration("MY_PRODUCT_LOG_LEVEL", cfg, HttpLogLevel.HEADERS),
        )
    }

    // ----- Unrecognized value falls back to the supplied default -----

    @Test
    fun `unrecognized value returns the supplied default`() {
        val cfg = configWithEnv("MY_PRODUCT_LOG_LEVEL" to "VERBOSE")
        assertEquals(
            HttpLogLevel.NONE,
            HttpLogLevel.fromConfiguration("MY_PRODUCT_LOG_LEVEL", cfg, HttpLogLevel.NONE),
        )
    }

    // ----- Default of the default-defaulted overload -----

    @Test
    fun `default parameter is NONE when omitted`() {
        val cfg = configWithEnv() // nothing set
        assertEquals(HttpLogLevel.NONE, HttpLogLevel.fromConfiguration("MY_PRODUCT_LOG_LEVEL", cfg))
    }
}
