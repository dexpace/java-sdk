package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * Adapts a synchronous [HttpPipeline] into an [AsyncHttpPipeline] by submitting each
 * `send(request)` call to [executor]. Default executor is the common [ForkJoinPool] —
 * production callers should pass a dedicated pool, or
 * `Executors.newVirtualThreadPerTaskExecutor()` on JDK 21+ (see `sdk-async-virtualthreads`).
 *
 * The returned `AsyncHttpPipeline` is a single-step facade around the wrapped sync pipeline;
 * the wrapped pipeline's individual steps remain synchronous and run on the dispatch thread.
 * For per-step async behavior (true concurrency inside the pipeline), implement [AsyncHttpStep]
 * directly and use [AsyncHttpPipelineBuilder].
 *
 * Cancellation of the returned future does NOT interrupt the in-flight sync pipeline —
 * Java's `CompletableFuture` has no such hook for arbitrary tasks.
 */
@JvmOverloads
fun HttpPipeline.toAsync(executor: Executor = ForkJoinPool.commonPool()): AsyncHttpPipeline {
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
fun AsyncHttpPipeline.toBlocking(): HttpPipeline {
    val async = this
    return HttpPipeline.of { request ->
        try {
            async.sendAsync(request).join()
        } catch (ce: CompletionException) {
            throw Futures.unwrap(ce)
        }
    }
}
