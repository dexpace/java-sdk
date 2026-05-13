package org.dexpace.sdk.async.virtualthreads

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.client.asAsync
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val LOG = ClientLogger("org.dexpace.sdk.async.virtualthreads.VirtualThreads")

/**
 * Wraps a blocking [HttpClient] as an [AsyncHttpClient] backed by a virtual-thread-per-task
 * executor. Each `executeAsync(...)` call submits the blocking transport call to a fresh
 * virtual thread, which lets the carrier thread cooperate with the JVM scheduler — see
 * Kotlin JVM style guide §2.2.
 *
 * Requires JDK 21+. The owning [ExecutorService] is returned so callers can `close()` it on
 * shutdown to release any pinned platform threads. The executor is `newVirtualThreadPerTaskExecutor`
 * — virtual threads are cheap to spawn but the executor object itself holds no platform
 * resources beyond the small bookkeeping for in-flight tasks.
 *
 * Use cases:
 * - You have a blocking [HttpClient] (Apache HttpClient, the JDK 11 HttpClient in sync mode,
 *   etc.) and want cheap concurrency without rewriting the transport as async.
 * - You don't need coroutine-style structured concurrency; virtual threads alone are enough.
 *
 * If you need cancellation that interrupts the blocking call, wrap in coroutines instead —
 * see [org.dexpace.sdk.async.coroutines.asAsyncCoroutines] and `runInterruptible` (style
 * guide §2.8).
 *
 * ## MDC propagation
 *
 * Every task submitted to the underlying virtual-thread executor is wrapped in an MDC
 * capture-and-restore step (see [MdcAwareExecutor]) so log events emitted on the virtual
 * thread carry the caller's `trace.id` / `span.id`. Virtual threads do not inherit MDC by
 * default.
 */
fun HttpClient.asAsyncVirtualThreads(): VirtualThreadAsyncHttpClient {
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val mdcAware = MdcAwareExecutor(executor)
    val async = asAsync(mdcAware)
    return VirtualThreadAsyncHttpClient(async, executor)
}

/**
 * An [AsyncHttpClient] paired with the [ExecutorService] backing it. Implements both the
 * client contract (so callers can pass it to a pipeline) and [AutoCloseable] so the executor
 * is released on shutdown.
 *
 * Closing the executor does NOT cancel in-flight requests — virtual threads are
 * non-interruptible by default; the executor's `close()` waits for tasks to finish.
 */
class VirtualThreadAsyncHttpClient internal constructor(
    private val delegate: AsyncHttpClient,
    private val executor: ExecutorService,
) : AsyncHttpClient, AutoCloseable {

    override fun executeAsync(request: Request): CompletableFuture<Response> =
        delegate.executeAsync(request)

    /** Shuts down the virtual-thread executor and waits for in-flight tasks to complete. */
    override fun close() {
        LOG.atInfo()
            .event("executor.closed")
            .field("adapter.type", "virtualthreads")
            .log()
        executor.close()
    }
}
