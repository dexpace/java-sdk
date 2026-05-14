@file:JvmName("HttpPipelineBridges")

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

/**
 * Adapts a synchronous [HttpPipeline] into an [AsyncHttpPipeline] by submitting each
 * `send(request)` call to [executor]. Callers MUST supply an executor — there is intentionally
 * no default. Recommended setups:
 *  - JDK 21+: `Executors.newVirtualThreadPerTaskExecutor()` (or use `sdk-async-virtualthreads`
 *    which wires this for you with MDC propagation).
 *  - JDK 8–20: a `ThreadPoolExecutor` sized for your concurrent-request ceiling.
 *
 * `ForkJoinPool.commonPool()` is **not** an acceptable production choice — blocking HTTP calls
 * starve every other commonPool consumer (parallel streams, CompletableFuture default chains).
 *
 * The returned `AsyncHttpPipeline` is a single-step facade around the wrapped sync pipeline;
 * the wrapped pipeline's individual steps remain synchronous and run on the dispatch thread.
 * For per-step async behavior (true concurrency inside the pipeline), implement [AsyncHttpStep]
 * directly and use [AsyncHttpPipelineBuilder].
 *
 * Cancellation of the returned future does NOT interrupt the in-flight sync pipeline —
 * Java's `CompletableFuture` has no such hook for arbitrary tasks.
 */
public fun HttpPipeline.toAsync(executor: Executor): AsyncHttpPipeline {
    val sync = this
    return AsyncHttpPipeline.of { request ->
        CompletableFuture.supplyAsync({ sync.send(request) }, executor)
    }
}

/**
 * Adapts an [AsyncHttpPipeline] into a synchronous [HttpPipeline] by blocking on
 * `sendAsync(request).join()` for each `send(...)` call. The current thread blocks until the
 * future completes; pair with virtual threads (JDK 21+) on the caller side to keep carrier
 * threads available.
 */
public fun AsyncHttpPipeline.toBlocking(): HttpPipeline {
    val async = this
    return HttpPipeline.of { request ->
        try {
            async.sendAsync(request).join()
        } catch (ce: CompletionException) {
            throw Futures.unwrap(ce)
        }
    }
}
