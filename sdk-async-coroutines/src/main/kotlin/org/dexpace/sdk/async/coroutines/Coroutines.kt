package org.dexpace.sdk.async.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runInterruptible
import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipeline
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Suspend-friendly facade over [AsyncHttpClient]. Returns the [Response] directly; the underlying
 * `executeAsync(...)` future is awaited via `kotlinx-coroutines-jdk8`'s `CompletableFuture.await()`.
 *
 * Cancellation: cancelling the enclosing coroutine cancels the awaited future
 * (`CompletableFuture.cancel(true)`). Whether the in-flight transport is interrupted is up to
 * the [AsyncHttpClient] implementation — see [AsyncHttpClient.executeAsync]'s cancellation
 * contract.
 */
suspend fun AsyncHttpClient.execute(request: Request): Response = executeAsync(request).await()

/**
 * Suspend-friendly facade over [AsyncHttpPipeline.sendAsync]. Mirrors [execute] above but for
 * the pipeline entry point.
 */
suspend fun AsyncHttpPipeline.send(request: Request): Response = sendAsync(request).await()

/**
 * Builds a [CompletableFuture] from a suspending block inside [scope]. Thin re-export of
 * `kotlinx-coroutines-jdk8`'s `scope.future { ... }` — kept here so callers depending on
 * `sdk-async-coroutines` see one consistent surface for the coroutine-to-future bridge.
 *
 * Cancellation of the returned future cancels the coroutine launched in [scope] (the bridge's
 * contract). The [context] parameter passes through to the underlying builder for dispatcher
 * selection at the bridge boundary.
 */
fun <T> CoroutineScope.completableFutureOf(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T,
): CompletableFuture<T> = this.future(context, block = block)

/**
 * Adapts a synchronous [HttpClient] into an [AsyncHttpClient] backed by a coroutine [scope].
 * Each `executeAsync(...)` launches a coroutine that runs the blocking call via
 * [runInterruptible] on [Dispatchers.IO] — coroutine cancellation translates to thread
 * interruption on the carrier (kotlinx-coroutines style guide §2.8), and the future returned
 * to the caller propagates cancellation back into the coroutine via `kotlinx-coroutines-jdk8`.
 *
 * Prefer this over [HttpClient.asAsync][org.dexpace.sdk.core.client.asAsync] (the plain
 * executor-based bridge) when the underlying [HttpClient] respects [Thread.interrupt] —
 * `runInterruptible` will then abort the in-flight call on cancellation.
 */
fun HttpClient.asAsyncCoroutines(scope: CoroutineScope): AsyncHttpClient = AsyncHttpClient { request ->
    scope.future { runInterruptible(Dispatchers.IO) { execute(request) } }
}
