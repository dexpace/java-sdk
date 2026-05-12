package org.dexpace.sdk.core.io

import org.dexpace.sdk.io.OkioIoProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises [Io.installProvider], [Io.provider], and [Io.withProvider] — the entry points
 * into the SDK's global IoProvider seam.
 *
 * These tests rely on `Io.withProvider` to scope alternative providers so other tests in the
 * suite (which expect [OkioIoProvider] to be installed) are not disturbed.
 */
class IoTest {

    @BeforeTest
    fun installProvider() {
        // Make sure something is installed for the @Test that exercises "no provider installed"
        // before flipping the state. We use withProvider() inside those tests so the overall
        // installed provider state is restored on exit.
        Io.installProvider(OkioIoProvider)
    }

    @AfterTest
    fun restoreProvider() {
        // Re-install OkioIoProvider in case any test in this class clobbered it; idempotent.
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
        val thrown = assertFailsWith<IllegalStateException> {
            Io.installProvider(different)
        }
        // Message names both providers and points to withProvider() for scoped overrides.
        assertTrue(thrown.message!!.contains("already installed"))
        assertTrue(thrown.message!!.contains("withProvider"))
        // Original provider untouched.
        assertSame(OkioIoProvider, Io.provider)
    }

    @Test
    fun `withProvider scopes a temporary provider`() {
        val scoped = object : IoProvider by OkioIoProvider {}
        Io.withProvider(scoped) {
            assertSame(scoped, Io.provider)
        }
        // After exit, the previous provider is restored.
        assertSame(OkioIoProvider, Io.provider)
    }

    @Test
    fun `withProvider restores previous provider when block throws`() {
        val scoped = object : IoProvider by OkioIoProvider {}
        assertFailsWith<IllegalStateException> {
            Io.withProvider(scoped) {
                error("boom")
            }
        }
        // Even on an exception, the previous provider must be restored.
        assertSame(OkioIoProvider, Io.provider)
    }

    @Test
    fun `withProvider returns the block result`() {
        val scoped = object : IoProvider by OkioIoProvider {}
        val result = Io.withProvider(scoped) { 42 }
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
        Io.withProvider(anon1) {
            val anon2 = object : IoProvider by OkioIoProvider {}
            val thrown = assertFailsWith<IllegalStateException> { Io.installProvider(anon2) }
            assertTrue(thrown.message!!.contains("already installed"))
        }
    }

    @Test
    fun `withProvider supports nesting with restoration of outer scope`() {
        val a = object : IoProvider by OkioIoProvider {}
        val b = object : IoProvider by OkioIoProvider {}
        Io.withProvider(a) {
            assertSame(a, Io.provider)
            Io.withProvider(b) {
                assertSame(b, Io.provider)
            }
            // Inner exit restores `a` (not the top-level OkioIoProvider).
            assertSame(a, Io.provider)
        }
        // Outer exit restores OkioIoProvider.
        assertSame(OkioIoProvider, Io.provider)
    }
}
