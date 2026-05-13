package org.dexpace.sdk.async.netty

import io.netty.util.concurrent.EventExecutor
import io.netty.util.concurrent.Future
import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipeline
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture

private val LOG = ClientLogger("org.dexpace.sdk.async.netty.Netty")

/**
 * Wraps [AsyncHttpClient.executeAsync] in a Netty [Future]. The returned future fires its
 * listeners on [executor]; the underlying [CompletableFuture]'s completion is mirrored into a
 * [io.netty.util.concurrent.Promise] obtained from [executor].
 *
 * Bridging both directions:
 * - Caller cancels the Netty future → we cancel the underlying [CompletableFuture] via
 *   `cancel(true)`, mirroring intent into the SDK transport. Whether the in-flight HTTP call
 *   honors interruption is the [AsyncHttpClient]'s contract — see its KDoc.
 * - Underlying future completes → we set success or failure on the promise.
 *
 * The promise is created and completed on [executor]; this matches Netty's threading model
 * where listener callbacks fire on the event executor that owns the promise.
 */
fun AsyncHttpClient.executeNetty(request: Request, executor: EventExecutor): Future<Response> =
    executeAsync(request).bridgeToNetty(executor)

/** Pipeline-level Netty future facade — see [executeNetty]. */
fun AsyncHttpPipeline.sendNetty(request: Request, executor: EventExecutor): Future<Response> =
    sendAsync(request).bridgeToNetty(executor)

/**
 * Shared bridge: mirror this [CompletableFuture]'s completion onto a Netty [Promise] created
 * by [executor]. The promise's cancellation propagates back to this source future.
 *
 * `Futures.unwrap` strips `CompletionException` / `ExecutionException` wrappers so Netty
 * listeners see the original failure rather than the JDK's wrapper.
 */
private fun CompletableFuture<Response>.bridgeToNetty(executor: EventExecutor): Future<Response> {
    val source = this
    val promise = executor.newPromise<Response>()
    source.whenComplete { response, error ->
        if (error != null) promise.setFailure(Futures.unwrap(error)) else promise.setSuccess(response)
    }
    // Forward Netty cancellation back to the source future. Listener fires on `executor`.
    promise.addListener {
        if (it.isCancelled && !source.isDone) {
            source.cancel(true)
            LOG.atVerbose()
                .event("async.adapter.cancel_propagated")
                .field("adapter.type", "netty")
                .log()
        }
    }
    return promise
}
