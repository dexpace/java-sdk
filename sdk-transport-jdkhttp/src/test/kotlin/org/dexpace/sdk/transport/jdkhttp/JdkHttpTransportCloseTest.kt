package org.dexpace.sdk.transport.jdkhttp

import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.net.http.HttpClient
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the [JdkHttpTransport.close] lifecycle contract added in WU-4.
 *
 * Ownership rules:
 *  - Transports built via [JdkHttpTransport.builder] own the underlying
 *    [java.net.http.HttpClient]; [close] releases it via [AutoCloseable] when the JDK
 *    runtime supports the interface (JDK 21+ per JEP 461). On JDK 11–20 the JDK client
 *    has no public close hook, so the close is a documented no-op — but the call must
 *    still be safe and idempotent.
 *  - Transports created via [JdkHttpTransport.create] do NOT own the client; [close]
 *    must leave the user-supplied client untouched on every runtime.
 *
 * The ownership assertions reflect on the transport's private `owned` field — the same
 * field that gates the close branch — rather than trying to observe the JDK client's
 * post-close state. The JDK client provides no public "isShutdown" surface, and runtime
 * behaviour differs across JDK versions; reading the ownership latch directly is the
 * portable assertion.
 *
 * `Io.installProvider(OkioIoProvider)` is invoked in `@BeforeTest` to satisfy the SDK's
 * I/O contract; the close tests don't exercise the IO path but the install is cheap and
 * idempotent.
 */
class JdkHttpTransportCloseTest {
    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `builder-built transport is marked SDK-owned`() {
        val transport = JdkHttpTransport.builder().build()
        assertTrue(extractOwned(transport), "builder().build() must produce an SDK-owned transport")
    }

    @Test
    fun `create-built transport is marked user-supplied`() {
        val userClient = HttpClient.newHttpClient()
        val transport = JdkHttpTransport.create(userClient)
        assertFalse(
            extractOwned(transport),
            "create(client) must produce a user-supplied (non-owning) transport",
        )

        val transportWithTimeout = JdkHttpTransport.create(userClient, java.time.Duration.ofSeconds(10))
        assertFalse(
            extractOwned(transportWithTimeout),
            "create(client, timeout) must produce a user-supplied (non-owning) transport",
        )
    }

    @Test
    fun `builder-built transport close runs without throwing`() {
        val transport = JdkHttpTransport.builder().build()

        // On JDK 21+ this closes the JDK HttpClient's internal selector + executor.
        // On JDK 11–20 it's a no-op because the JDK client lacks AutoCloseable.
        // Either way the call must not throw.
        transport.close()
    }

    @Test
    fun `builder-built transport close is idempotent`() {
        val transport = JdkHttpTransport.builder().build()

        transport.close()
        transport.close()
        transport.close()

        // No exception thrown on repeat calls; the AtomicBoolean latch in the transport
        // keeps the second and third calls a no-op.
        assertTrue(extractClosed(transport), "first close() must latch the closed flag")
    }

    @Test
    fun `create-built transport close does not modify a user-supplied client`() {
        val userClient = HttpClient.newHttpClient()
        val transport = JdkHttpTransport.create(userClient)

        transport.close()
        transport.close()

        // The closed latch flips even on a non-owning transport — that's correct
        // idempotency. The point of the test is that the early return in `close()`
        // skipped the underlying client. The user can continue using their client.
        assertTrue(extractClosed(transport))
        assertFalse(
            extractOwned(transport),
            "non-owning transport's owned flag must stay false through close()",
        )

        // On JDK 21+ release the user's client to keep the test runtime tidy.
        (userClient as? AutoCloseable)?.close()
    }

    @Test
    fun `transport implements AutoCloseable so it works with Kotlin use`() {
        val transport = JdkHttpTransport.builder().build()
        var executed = false

        transport.use { executed = true }

        assertTrue(executed, "use { } must invoke the block")
        assertTrue(extractClosed(transport), "use { } must invoke close() on exit")
    }

    /**
     * Reads the private `owned` field on the transport. Reflection is the only way to
     * observe the field without expanding the public API; this test pins the close
     * contract by checking the same flag the transport itself reads in `close()`.
     */
    private fun extractOwned(transport: JdkHttpTransport): Boolean {
        val field = JdkHttpTransport::class.java.getDeclaredField("owned")
        field.isAccessible = true
        return field.getBoolean(transport)
    }

    /**
     * Reads the private `closed` AtomicBoolean's value. Used to verify the idempotency
     * latch flips after the first close call.
     */
    private fun extractClosed(transport: JdkHttpTransport): Boolean {
        val field = JdkHttpTransport::class.java.getDeclaredField("closed")
        field.isAccessible = true
        val atomic = field.get(transport) as java.util.concurrent.atomic.AtomicBoolean
        return atomic.get()
    }
}
