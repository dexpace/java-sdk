package org.dexpace.sdk.core.client

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * Asynchronous transport SPI. The async-first counterpart of [HttpClient] — implementations
 * return a [CompletableFuture] that completes with the [Response] (or completes exceptionally).
 *
 * `CompletableFuture` is the SDK's canonical async contract: it ships with the Java 8 standard
 * library, has no third-party dependency, and is the lowest-common-denominator that every
 * other async ecosystem (Kotlin coroutines, Reactor `Mono`, Netty `Future`) can adapt to and
 * from without losing semantics. Adapter modules (`sdk-async-coroutines`,
 * `sdk-async-reactor`, `sdk-async-netty`, `sdk-async-virtualthreads`) provide ergonomic
 * facades for each ecosystem, all of which bridge through `CompletableFuture`.
 *
 * ## Thread-safety
 * Implementations are expected to be safe for concurrent calls from multiple threads. Per-call
 * state must be confined to the returned future's completion graph.
 *
 * ## Cancellation
 * Cancelling the returned [CompletableFuture] is a best-effort request to abort the in-flight
 * exchange — implementations should release transport resources (sockets, file descriptors)
 * via the underlying transport's cancellation hook. If the response has already been delivered,
 * cancelling the future does NOT close the [Response] body; callers must still call
 * `Response.close()` on success-path completions even when discarding the value.
 *
 * ## Bridging to/from sync
 * - `HttpClient.asAsync(executor)` wraps a blocking transport in an async facade by submitting
 *   each `execute(...)` call to the given [Executor]. Pair with a virtual-thread executor on
 *   JDK 21+ for cheap concurrency.
 * - `AsyncHttpClient.asBlocking()` returns a sync [HttpClient] that calls
 *   `executeAsync(...).join()` and unwraps the [CompletionException] so callers see the
 *   original [Exception] / [Response] semantics.
 */
fun interface AsyncHttpClient {
    /**
     * Sends [request] over the underlying transport. The returned future completes with the
     * matching [Response] (caller owns close) or completes exceptionally with the transport
     * failure. The future MUST NOT return `null` on success — implementations that have no
     * response must complete exceptionally instead.
     */
    fun executeAsync(request: Request): CompletableFuture<Response>
}

/**
 * Wraps a blocking [HttpClient] as an [AsyncHttpClient] by submitting each `execute` call to
 * [executor]. Default executor is the JVM's [ForkJoinPool.commonPool] — fine for low-volume
 * use, but production code should pass a dedicated thread pool (or
 * `Executors.newVirtualThreadPerTaskExecutor()` on JDK 21+).
 *
 * The returned future completes with the response from `execute`. Cancellation of the future
 * does NOT interrupt the in-flight blocking call (Java's `CompletableFuture` has no such
 * hook); for true cancellation, use an [AsyncHttpClient] backed by an async transport.
 */
@JvmOverloads
fun HttpClient.asAsync(executor: Executor = ForkJoinPool.commonPool()): AsyncHttpClient =
    AsyncHttpClient { request ->
        CompletableFuture.supplyAsync({ execute(request) }, executor)
    }

/**
 * Wraps an [AsyncHttpClient] as a blocking [HttpClient] by calling `executeAsync(...).join()`
 * and unwrapping the [CompletionException] so callers see the original exception. The current
 * thread blocks until the future completes — pair with virtual threads (JDK 21+) to keep
 * carrier threads available.
 *
 * The returned [Response] must be closed by the caller, per the [HttpClient.execute] contract.
 */
fun AsyncHttpClient.asBlocking(): HttpClient = HttpClient { request ->
    try {
        executeAsync(request).join()
    } catch (ce: CompletionException) {
        // `join()` wraps every exceptional completion in CompletionException; unwrap so
        // callers' `catch (IOException)` blocks see the original failure rather than the
        // JDK wrapper. `CancellationException` from a cancelled future is unaffected — it
        // is a RuntimeException, not a CompletionException, so it propagates as-is.
        throw Futures.unwrap(ce)
    }
}
