package org.dexpace.sdk.transport.okhttp

import okhttp3.OkHttpClient
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the [OkHttpTransport.close] lifecycle contract added in WU-4.
 *
 * Ownership rules:
 *  - Transports built via [OkHttpTransport.builder] own the underlying [OkHttpClient];
 *    [close] shuts down its dispatcher executor and evicts its connection pool.
 *  - Transports created via [OkHttpTransport.create] do NOT own the client; [close]
 *    must leave the dispatcher executor and connection pool untouched.
 *
 * The tests inspect the OkHttp client's dispatcher executor (`isShutdown`) directly,
 * because OkHttp exposes those properties publicly — no spy/mocking framework needed.
 *
 * `Io.installProvider(OkioIoProvider)` is invoked in `@BeforeTest` to satisfy the SDK's
 * I/O contract; the close tests don't exercise the IO path but the install is cheap and
 * idempotent.
 */
class OkHttpTransportCloseTest {
    @BeforeTest
    fun setUp() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `builder-built transport shuts down its dispatcher executor on close`() {
        val transport = OkHttpTransport.builder().build()
        val dispatcherExecutor = extractDispatcherExecutor(transport)

        assertFalse(dispatcherExecutor.isShutdown, "executor should be running before close")

        transport.close()

        assertTrue(dispatcherExecutor.isShutdown, "executor should be shut down after close")
    }

    @Test
    fun `builder-built transport close is idempotent`() {
        val transport = OkHttpTransport.builder().build()
        val dispatcherExecutor = extractDispatcherExecutor(transport)

        transport.close()
        transport.close()
        transport.close()

        // Still shut down — and no exception thrown on the repeat calls.
        assertTrue(dispatcherExecutor.isShutdown)
    }

    @Test
    fun `create-built transport does NOT shut down a user-supplied OkHttpClient`() {
        val userClient = OkHttpClient.Builder().build()
        val userExecutor = userClient.dispatcher.executorService
        val transport = OkHttpTransport.create(userClient)

        assertFalse(userExecutor.isShutdown)

        transport.close()
        transport.close()

        assertFalse(
            userExecutor.isShutdown,
            "user-supplied OkHttpClient executor must NOT be shut down by transport.close()",
        )
        assertEquals(0, userClient.connectionPool.connectionCount(), "no connections were opened")

        // Caller can still use their client and shut it down on their own schedule.
        userClient.dispatcher.executorService.shutdown()
        assertTrue(userClient.dispatcher.executorService.isShutdown)
    }

    @Test
    fun `create-built transport close is idempotent`() {
        val userClient = OkHttpClient.Builder().build()
        val transport = OkHttpTransport.create(userClient)

        // Calling close repeatedly on a non-owning transport stays a no-op forever.
        transport.close()
        transport.close()
        transport.close()
        assertFalse(userClient.dispatcher.executorService.isShutdown)

        userClient.dispatcher.executorService.shutdown()
    }

    @Test
    fun `transport implements AutoCloseable so it works with Kotlin use`() {
        val transport = OkHttpTransport.builder().build()
        val executor = extractDispatcherExecutor(transport)

        transport.use { /* no-op body — closing is the point */ }

        assertTrue(executor.isShutdown, "use { } must invoke close() on exit")
    }

    /**
     * Reaches into the transport's underlying [OkHttpClient] to read its dispatcher
     * executor. The transport stores the `OkHttpClient` in a `private val client` field,
     * so reflection is the only way to observe it without changing the public API. The
     * field name is stable — this test is one of the assertions on the OkHttp adapter's
     * close contract and any rename would be a deliberate refactor that updates this
     * helper too.
     */
    private fun extractDispatcherExecutor(transport: OkHttpTransport): java.util.concurrent.ExecutorService {
        val field = OkHttpTransport::class.java.getDeclaredField("client")
        field.isAccessible = true
        val client = field.get(transport) as OkHttpClient
        return client.dispatcher.executorService
    }
}
