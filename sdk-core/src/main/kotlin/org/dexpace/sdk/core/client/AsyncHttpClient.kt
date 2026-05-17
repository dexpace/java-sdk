@file:JvmName("AsyncHttpClients")

package org.dexpace.sdk.core.client

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.Futures
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

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
 * ## Lifecycle
 * [AsyncHttpClient] extends [AutoCloseable]. The default [close] is a no-op so SAM literals
 * (`AsyncHttpClient { request -> ... }`) and existing implementations remain valid without
 * modification. Transport implementations that own background threads or connection pools should
 * override [close] to release them. The contract mirrors [HttpClient.close]:
 *
 *  1. **Idempotent.** Repeated calls must be safe.
 *  2. **Ownership-aware.** Only release SDK-owned resources; user-supplied executors/clients
 *     are left untouched.
 *  3. **Interrupt-safe.** Honour `Thread.interrupt()` on any blocking shutdown step.
 *
 * After [close] returns, calls to [executeAsync] have undefined behaviour.
 *
 * ## Bridging to/from sync
 * - `HttpClient.asAsync(executor)` wraps a blocking transport in an async facade by submitting
 *   each `execute(...)` call to the given [Executor]. Pair with a virtual-thread executor on
 *   JDK 21+ for cheap concurrency.
 * - `AsyncHttpClient.asBlocking()` returns a sync [HttpClient] that calls
 *   `executeAsync(...).join()` and unwraps the [CompletionException] so callers see the
 *   original [Exception] / [Response] semantics.
 */
public fun interface AsyncHttpClient : AutoCloseable {
    /**
     * Sends [request] over the underlying transport. The returned future completes with the
     * matching [Response] (caller owns close) or completes exceptionally with the transport
     * failure. The future MUST NOT return `null` on success — implementations that have no
     * response must complete exceptionally instead.
     */
    public fun executeAsync(request: Request): CompletableFuture<Response>

    /**
     * Releases any resources held by this transport. The default implementation is a no-op so
     * SAM literals and lightweight client wrappers do not need to implement [AutoCloseable.close]
     * explicitly. Transport implementations that own background threads, connection pools, or
     * executors must override this method; see the class-level "Lifecycle" KDoc for the contract.
     */
    public override fun close() {
        // No-op default. Transports with owned resources override this.
    }
}

/**
 * Wraps a blocking [HttpClient] as an [AsyncHttpClient] by submitting each `execute` call to
 * [executor]. Callers MUST supply an executor sized for their workload — there is intentionally
 * no default. Recommended setups:
 *  - JDK 21+: `Executors.newVirtualThreadPerTaskExecutor()` (or use
 *    `sdk-async-virtualthreads` which wires this for you with MDC propagation).
 *  - JDK 8-20: a `ThreadPoolExecutor` sized for your concurrent-request ceiling.
 *
 * `ForkJoinPool.commonPool()` is **not** an acceptable production choice — it pins a small
 * number of platform threads that the JVM shares with parallel streams and CompletableFuture
 * default executors, so a blocking HTTP call starves every other commonPool consumer.
 *
 * The returned future completes with the response from `execute`. Cancellation of the future
 * does NOT interrupt the in-flight blocking call (Java's `CompletableFuture` has no such
 * hook); for true cancellation, use an [AsyncHttpClient] backed by an async transport.
 */
public fun HttpClient.asAsync(executor: Executor): AsyncHttpClient =
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
@Throws(IOException::class)
public fun AsyncHttpClient.asBlocking(): HttpClient =
    HttpClient { request ->
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
