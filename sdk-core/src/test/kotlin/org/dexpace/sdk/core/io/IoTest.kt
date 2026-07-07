/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.io

import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.FakeSlf4jLogger
import org.dexpace.sdk.core.testing.withProvider
import org.dexpace.sdk.io.OkioIoProvider
import org.slf4j.event.Level
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import java.util.ServiceLoader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises [Io.installProvider] and [Io.provider] — the entry points into the SDK's global
 * IoProvider seam — and the test-fixtures [org.dexpace.sdk.core.testing.withProvider] seam.
 *
 * Tests that need a scoped provider use [org.dexpace.sdk.core.testing.withProvider] (from
 * the test-fixtures artifact) so the overall installed provider state is restored on exit.
 */
class IoTest {
    @BeforeTest
    fun installProvider() {
        // Ensure a provider is installed before each test; idempotent for OkioIoProvider.
        Io.installProvider(OkioIoProvider)
    }

    @AfterTest
    fun restoreProvider() {
        // Re-install OkioIoProvider in case any test clobbered it; idempotent.
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `provider returns the installed provider`() {
        assertSame(OkioIoProvider, Io.provider)
    }

    @Test
    fun `installProvider is idempotent for the same instance`() {
        Io.installProvider(OkioIoProvider)
        Io.installProvider(OkioIoProvider)
        Io.installProvider(OkioIoProvider)
        assertSame(OkioIoProvider, Io.provider)
    }

    @Test
    fun `installProvider rejects a different provider when one is installed`() {
        val different = object : IoProvider by OkioIoProvider {}
        val thrown =
            assertFailsWith<IllegalStateException> {
                Io.installProvider(different)
            }
        // Message names both providers and points to withProvider() for scoped overrides.
        assertTrue(thrown.message!!.contains("already installed"))
        assertTrue(thrown.message!!.contains("withProvider"))
        // Original provider untouched.
        assertSame(OkioIoProvider, Io.provider)
    }

    // ---- org.dexpace.sdk.core.testing.withProvider seam -------------------------

    @Test
    fun `testing withProvider scopes a temporary provider`() {
        val scoped = object : IoProvider by OkioIoProvider {}
        withProvider(scoped) {
            assertSame(scoped, Io.provider)
        }
        // After exit, the previous provider is restored.
        assertSame(OkioIoProvider, Io.provider)
    }

    @Test
    fun `testing withProvider restores previous provider when block throws`() {
        val scoped = object : IoProvider by OkioIoProvider {}
        assertFailsWith<IllegalStateException> {
            withProvider(scoped) {
                error("boom")
            }
        }
        // Even on an exception, the previous provider must be restored.
        assertSame(OkioIoProvider, Io.provider)
    }

    @Test
    fun `testing withProvider returns the block result`() {
        val scoped = object : IoProvider by OkioIoProvider {}
        val result = withProvider(scoped) { 42 }
        assertSame(42, result)
    }

    @Test
    fun `installProvider conflict surfaces fallback descriptors when classes are anonymous`() {
        // When the qualifiedName of an installed provider's class is null (anonymous class),
        // the Elvis fallback to `existing::class` fires. Set this up by temporarily replacing
        // OkioIoProvider with an anonymous provider via withProvider, then attempt to install
        // another anonymous one — the conflict throws and the message must contain both
        // class descriptors. This covers Io.installProvider's Elvis branches.
        val anon1 = object : IoProvider by OkioIoProvider {}
        withProvider(anon1) {
            val anon2 = object : IoProvider by OkioIoProvider {}
            val thrown = assertFailsWith<IllegalStateException> { Io.installProvider(anon2) }
            assertTrue(thrown.message!!.contains("already installed"))
        }
    }

    @Test
    fun `testing withProvider supports nesting with restoration of outer scope`() {
        val a = object : IoProvider by OkioIoProvider {}
        val b = object : IoProvider by OkioIoProvider {}
        withProvider(a) {
            assertSame(a, Io.provider)
            withProvider(b) {
                assertSame(b, Io.provider)
            }
            // Inner exit restores `a` (not the top-level OkioIoProvider).
            assertSame(a, Io.provider)
        }
        // Outer exit restores OkioIoProvider.
        assertSame(OkioIoProvider, Io.provider)
    }

    // ---- selectProvider unit tests (internal function, visible to tests) ----------

    @Test
    fun `selectProvider with empty list throws actionable message`() {
        val thrown =
            assertFailsWith<IllegalStateException> {
                Io.selectProvider(emptyList())
            }
        assertTrue(thrown.message!!.contains("No IoProvider installed"))
        assertTrue(thrown.message!!.contains("Io.installProvider"))
    }

    @Test
    fun `selectProvider with single candidate returns it`() {
        val fake = object : IoProvider by OkioIoProvider {}
        val result = Io.selectProvider(listOf(fake))
        assertSame(fake, result)
    }

    @Test
    fun `selectProvider with multiple candidates throws listing all class names`() {
        val fakeA = object : IoProvider by OkioIoProvider {}
        val fakeB = object : IoProvider by OkioIoProvider {}
        val thrown =
            assertFailsWith<IllegalStateException> {
                Io.selectProvider(listOf(fakeA, fakeB))
            }
        assertTrue(thrown.message!!.contains("Multiple"))
        assertTrue(thrown.message!!.contains("Io.installProvider"))
    }

    // ---- ServiceLoader integration tests ----------------------------------------

    @Test
    fun `ServiceLoader discovers exactly one IoProvider on the classpath`() {
        // sdk-io-okio3 registers OkioIoProviderLoader; with no other provider on the
        // test classpath there should be exactly one candidate.
        val providers =
            ServiceLoader.load(IoProvider::class.java, IoProvider::class.java.classLoader).toList()
        assertTrue(providers.isNotEmpty(), "Expected ServiceLoader to find at least one IoProvider")
        assertTrue(providers.size == 1, "Expected exactly one IoProvider, found: ${providers.size}")
    }

    @Test
    fun `provider resolves via ServiceLoader when no explicit install`() {
        // Clear the explicitly installed provider and the resolved cache, then confirm that
        // Io.provider still returns a functional provider auto-discovered via ServiceLoader.
        val saved = Io.swapProvider(null)
        try {
            val p = Io.provider
            assertNotNull(p)
            // Confirm it is actually functional (not a no-op stub).
            assertNotNull(p.buffer())
        } finally {
            // Restore previous explicit install so @AfterTest sees a consistent state.
            Io.swapProvider(saved)
        }
    }

    // ---- classloader search order (TCCL fallback) -------------------------------

    @Test
    fun `loaderSearchOrder puts the thread-context loader first`() {
        val tccl = object : ClassLoader() {}
        val defining = object : ClassLoader() {}
        assertEquals(listOf<ClassLoader?>(tccl, defining), Io.loaderSearchOrder(tccl, defining))
    }

    @Test
    fun `loaderSearchOrder falls back to the defining loader when no thread-context loader`() {
        val defining = object : ClassLoader() {}
        assertEquals(listOf<ClassLoader?>(defining), Io.loaderSearchOrder(null, defining))
    }

    @Test
    fun `loaderSearchOrder does not scan twice when both loaders are the same`() {
        val loader = object : ClassLoader() {}
        assertEquals(listOf<ClassLoader?>(loader), Io.loaderSearchOrder(loader, loader))
    }

    @Test
    fun `loadCandidates discovers a provider visible only via the thread-context loader`() {
        // Simulate a hierarchical-classloader deployment: the okio provider is visible via the
        // defining loader, while a second provider is registered only in a child loader that the
        // thread-context classloader exposes. The hardened lookup must find BOTH, and must count
        // the okio provider (visible through both loaders) exactly once.
        val servicesFile = File.createTempFile("io-provider-service", ".txt")
        servicesFile.writeText(TccOnlyProvider::class.java.name)
        servicesFile.deleteOnExit()

        val parent = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
        val childLoader = ServiceInjectingClassLoader(parent, servicesFile.toURI().toURL())
        val savedTccl = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = childLoader
        try {
            val candidates = Io.loadCandidates()
            val classNames = candidates.map { it::class.java.name }
            assertTrue(
                candidates.any { it is TccOnlyProvider },
                "expected the TCCL-only provider to be discovered; got $classNames",
            )
            assertEquals(
                1,
                candidates.count { it::class.java.name == "org.dexpace.sdk.io.OkioIoProviderLoader" },
                "the okio provider is visible via both loaders and must be de-duplicated; got $classNames",
            )
        } finally {
            Thread.currentThread().contextClassLoader = savedTccl
            servicesFile.delete()
        }
    }

    // ---- install-after-resolve WARN ---------------------------------------------

    @Test
    fun `installProvider warns when replacing a resolved-and-handed-out provider`() {
        val fake = FakeSlf4jLogger("io.test")
        val savedLogger = Io.logger
        val savedInstalled = Io.swapProvider(null)
        Io.logger = ClientLogger.forTesting(fake)
        try {
            // Force ServiceLoader resolution AND hand-out (library code touching Io.provider early).
            val handedOut = Io.provider
            assertNotNull(handedOut)
            // A late explicit install of a genuinely DIFFERENT provider still wins, but warns.
            // `underlying` is overridden to itself so it is not treated as the same implementation
            // as the auto-resolved provider (whose `underlying` is the Okio singleton).
            val explicit =
                object : IoProvider by OkioIoProvider {
                    override val underlying: IoProvider get() = this
                }
            Io.installProvider(explicit)
            assertSame(explicit, Io.provider, "explicit install must win over the auto-resolved provider")

            val warn =
                fake.records.singleOrNull { rec ->
                    rec.level == Level.WARN &&
                        rec.keyValues.any { it.key == "event" && it.value == "io.provider.install_after_resolve" }
                }
            assertNotNull(warn, "expected exactly one install-after-resolve WARN")
            assertTrue(
                warn.keyValues.any { it.key == "installed" },
                "warning should name the newly-installed provider",
            )
        } finally {
            Io.logger = savedLogger
            Io.swapProvider(savedInstalled)
        }
    }

    @Test
    fun `installProvider does not warn when installing the same auto-resolved provider`() {
        val fake = FakeSlf4jLogger("io.test")
        val savedLogger = Io.logger
        val savedInstalled = Io.swapProvider(null)
        Io.logger = ClientLogger.forTesting(fake)
        try {
            val handedOut = Io.provider
            Io.installProvider(handedOut) // same instance -> explicit install, no warning
            assertSame(handedOut, Io.provider)
            assertTrue(
                fake.records.none { it.level == Level.WARN },
                "installing the same resolved provider must not warn",
            )
        } finally {
            Io.logger = savedLogger
            Io.swapProvider(savedInstalled)
        }
    }

    @Test
    fun `installing OkioIoProvider after its ServiceLoader shim was resolved does not warn`() {
        // The auto-resolved provider is the OkioIoProviderLoader shim (registered in
        // META-INF/services), which delegates to OkioIoProvider. A later explicit
        // installProvider(OkioIoProvider) is a DIFFERENT instance than the shim, but they share the
        // same `underlying` implementation — so no mixed-provider state can arise and no WARN must
        // fire (contrast the "replacing a resolved-and-handed-out provider" test, whose provider
        // overrides `underlying` to itself precisely to force the warning).
        val fake = FakeSlf4jLogger("io.test")
        val savedLogger = Io.logger
        val savedInstalled = Io.swapProvider(null)
        Io.logger = ClientLogger.forTesting(fake)
        try {
            // Force ServiceLoader resolution AND hand-out.
            val handedOut = Io.provider
            assertTrue(
                handedOut !== OkioIoProvider,
                "the resolved provider must be the ServiceLoader shim, not the OkioIoProvider singleton",
            )
            assertSame(
                OkioIoProvider,
                handedOut.underlying,
                "the shim delegates to OkioIoProvider — they share the same underlying implementation",
            )
            Io.installProvider(OkioIoProvider)
            assertSame(OkioIoProvider, Io.provider, "explicit install must take effect")
            assertTrue(
                fake.records.none {
                    it.level == Level.WARN &&
                        it.keyValues.any { kv -> kv.key == "event" && kv.value == "io.provider.install_after_resolve" }
                },
                "installing OkioIoProvider after resolving its own ServiceLoader shim must not warn",
            )
        } finally {
            Io.logger = savedLogger
            Io.swapProvider(savedInstalled)
        }
    }

    @Test
    fun `installProvider does not warn on a first install with no prior resolution`() {
        val fake = FakeSlf4jLogger("io.test")
        val savedLogger = Io.logger
        // swapProvider(null) clears installed + resolved + the handed-out flag; no getter is called,
        // so nothing was ever resolved.
        val savedInstalled = Io.swapProvider(null)
        Io.logger = ClientLogger.forTesting(fake)
        try {
            val explicit = object : IoProvider by OkioIoProvider {}
            Io.installProvider(explicit)
            assertSame(explicit, Io.provider)
            assertTrue(
                fake.records.none { it.level == Level.WARN },
                "a first install with nothing previously resolved must not warn",
            )
        } finally {
            Io.logger = savedLogger
            Io.swapProvider(savedInstalled)
        }
    }
}

/**
 * Test provider registered only through [ServiceInjectingClassLoader]. Public with a no-arg
 * constructor so [ServiceLoader] can instantiate it; delegates all behaviour to [OkioIoProvider].
 */
class TccOnlyProvider : IoProvider by OkioIoProvider

/**
 * Classloader that injects one extra `META-INF/services/<IoProvider>` entry on top of whatever the
 * parent already exposes, letting a test simulate a provider registered only in a child loader.
 * Class loading itself delegates to the parent so the injected provider class remains resolvable.
 */
private class ServiceInjectingClassLoader(
    parent: ClassLoader,
    private val extraServiceUrl: URL,
) : ClassLoader(parent) {
    override fun getResources(name: String): Enumeration<URL> {
        val resources = Collections.list(super.getResources(name))
        if (name == "META-INF/services/${IoProvider::class.java.name}") {
            resources.add(extraServiceUrl)
        }
        return Collections.enumeration(resources)
    }
}
