/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigurationTimeoutTest {
    @Test
    fun `empty configuration yields NONE`() {
        val cfg = ConfigurationBuilder().build()
        assertEquals(Timeout.NONE, cfg.getTimeout())
    }

    @Test
    fun `request timeout key drives read and write inheritance`() {
        val cfg = ConfigurationBuilder().put(Configuration.REQUEST_TIMEOUT, "10s").build()
        val t = cfg.getTimeout()
        assertEquals(Duration.ofSeconds(10), t.requestTimeout)
        assertEquals(Duration.ofSeconds(10), t.effectiveReadTimeout)
        assertEquals(Duration.ofSeconds(10), t.effectiveWriteTimeout)
        assertEquals(Duration.ZERO, t.connectTimeout)
    }

    @Test
    fun `each phase key maps to its phase`() {
        val cfg =
            ConfigurationBuilder()
                .put(Configuration.CONNECT_TIMEOUT, "PT1S")
                .put(Configuration.READ_TIMEOUT, "2s")
                .put(Configuration.WRITE_TIMEOUT, "3000ms")
                .put(Configuration.REQUEST_TIMEOUT, "30s")
                .build()
        val t = cfg.getTimeout()
        assertEquals(Duration.ofSeconds(1), t.connectTimeout)
        assertEquals(Duration.ofSeconds(2), t.effectiveReadTimeout)
        assertEquals(Duration.ofSeconds(3), t.effectiveWriteTimeout)
        assertEquals(Duration.ofSeconds(30), t.requestTimeout)
    }

    @Test
    fun `explicit read key pins read independently of request`() {
        val cfg =
            ConfigurationBuilder()
                .put(Configuration.REQUEST_TIMEOUT, "10s")
                .put(Configuration.READ_TIMEOUT, "4s")
                .build()
        val t = cfg.getTimeout()
        assertEquals(Duration.ofSeconds(4), t.effectiveReadTimeout)
        assertEquals(Duration.ofSeconds(10), t.effectiveWriteTimeout)
    }

    @Test
    fun `unparseable read key is ignored and inherits request`() {
        val cfg =
            ConfigurationBuilder()
                .put(Configuration.REQUEST_TIMEOUT, "10s")
                .put(Configuration.READ_TIMEOUT, "not-a-duration")
                .build()
        val t = cfg.getTimeout()
        // Bad value behaves as if absent: read inherits request.
        assertEquals(Duration.ofSeconds(10), t.effectiveReadTimeout)
    }

    @Test
    fun `default fills missing connect and request phases`() {
        val cfg = ConfigurationBuilder().put(Configuration.READ_TIMEOUT, "2s").build()
        val default =
            Timeout
                .builder()
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(20))
                .build()
        val t = cfg.getTimeout(default)
        assertEquals(Duration.ofSeconds(5), t.connectTimeout)
        assertEquals(Duration.ofSeconds(20), t.requestTimeout)
        // The configured read key overrides the default's inherited read phase.
        assertEquals(Duration.ofSeconds(2), t.effectiveReadTimeout)
    }

    @Test
    fun `env and sysprop layers feed the timeout keys`() {
        val cfg =
            ConfigurationBuilder()
                .envSource { name -> if (name == Configuration.CONNECT_TIMEOUT) "1s" else null }
                .propsSource { name -> if (name == "request.timeout") "15s" else null }
                .build()
        val t = cfg.getTimeout()
        assertEquals(Duration.ofSeconds(1), t.connectTimeout)
        assertEquals(Duration.ofSeconds(15), t.requestTimeout)
    }
}
