/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

@file:JvmName("Coroutines")

package org.dexpace.sdk.async.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.slf4j.MDCContext
import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipeline
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestOptions
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private val log = ClientLogger("org.dexpace.sdk.async.coroutines.Coroutines")

/**
 * Suspend-friendly facade over [AsyncHttpClient]. Returns the [Response] directly; the underlying
 * `executeAsync(...)` future is awaited via `kotlinx-coroutines-jdk8`'s `CompletableFuture.await()`.
 *
 * Cancellation: cancelling the enclosing coroutine cancels the awaited future via
 * `CompletableFuture.cancel(false)` — `kotlinx-coroutines-jdk8`'s `await()` cancels *without*
 * requesting interruption. A transport whose cancellation hook only fires on `cancel(true)` (e.g.
 * the executor-bridged blocking clients built on sdk-core's `interruptibleFuture`) therefore keeps
 * running the in-flight call to completion; native async transports that abort on a plain
 * `cancel(false)` release it eagerly. See [AsyncHttpClient.executeAsync]'s cancellation contract.
 */
public suspend fun AsyncHttpClient.execute(request: Request): Response = executeAsync(request).await()

/**
 * Suspend-friendly facade over [AsyncHttpClient] with per-call [options]. Threads [options] into
 * `executeAsync(request, options)` so per-request overrides (timeout, retry budget, tags) survive
 * the coroutine bridge instead of being dropped. Otherwise identical to the no-options [execute].
 */
public suspend fun AsyncHttpClient.execute(
    request: Request,
    options: RequestOptions,
): Response = executeAsync(request, options).await()

/**
 * Suspend-friendly facade over [AsyncHttpPipeline.sendAsync]. Mirrors [execute] above but for
 * the pipeline entry point.
 */
public suspend fun AsyncHttpPipeline.send(request: Request): Response = sendAsync(request).await()

/**
 * Suspend-friendly facade over [AsyncHttpPipeline.sendAsync] with per-call [options]. Threads
 * [options] into `sendAsync(request, options)` so per-request overrides survive the coroutine
 * bridge. Otherwise identical to the no-options [send].
 */
public suspend fun AsyncHttpPipeline.send(
    request: Request,
    options: RequestOptions,
): Response = sendAsync(request, options).await()

/**
 * Builds a [CompletableFuture] from a suspending block inside [scope]. Thin re-export of
 * `kotlinx-coroutines-jdk8`'s `scope.future { ... }` — kept here so callers depending on
 * `sdk-async-coroutines` see one consistent surface for the coroutine-to-future bridge.
 *
 * Cancellation of the returned future cancels the coroutine launched in [scope] (the bridge's
 * contract). The [context] parameter passes through to the underlying builder for dispatcher
 * selection at the bridge boundary.
 *
 * **Dispatcher footgun.** With the default [EmptyCoroutineContext], the future's coroutine
 * inherits [scope]'s dispatcher. If [scope] is single-threaded (e.g. rooted in `runBlocking`)
 * and the caller uses a blocking consumer like [CompletableFuture.get], the consumer parks
 * the only dispatcher thread and the coroutine cannot progress — deadlock. Pass an explicit
 * non-blocking dispatcher such as [Dispatchers.Default] when consumers will block, or use
 * `await()` from `kotlinx-coroutines-jdk8` inside a coroutine.
 *
 * The current thread's MDC is captured via `MDCContext()` so log events emitted inside [block]
 * see the caller's `trace.id` / `span.id`.
 */
public fun <T> CoroutineScope.completableFutureOf(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T,
): CompletableFuture<T> = this.future(context + MDCContext(), block = block)

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
public fun HttpClient.asAsyncCoroutines(scope: CoroutineScope): AsyncHttpClient {
    log.atVerbose()
        .event("async.adapter.wrapped")
        .field("adapter.type", "coroutines")
        .field("scope.coroutineContext", scope.coroutineContext.toString())
        .log()
    val client = this
    return object : AsyncHttpClient {
        override fun executeAsync(request: Request): CompletableFuture<Response> =
            executeAsync(request, RequestOptions.EMPTY)

        override fun executeAsync(
            request: Request,
            options: RequestOptions,
        ): CompletableFuture<Response> {
            // `scope.future { }` propagates cancellation of the returned CompletableFuture into the
            // coroutine (cancel() → Job cancel → runInterruptible interrupts the blocking call,
            // regardless of the mayInterruptIfRunning flag), but kotlinx DISCARDS the coroutine's
            // result once the Job is already cancelled. A
            // Response computed in the cancellation race window would therefore be dropped without
            // being closed, leaking its connection — unlike sdk-core's `interruptibleFuture`, which
            // closes a lost value. Guard with a lock-free handshake so whoever loses the race — the
            // coroutine producing a value the future will not deliver, or the future terminating
            // before that value is produced — closes the Response exactly once.
            val handoff = AtomicReference<Any?>(null)
            val future =
                scope.future(MDCContext()) {
                    val response = runInterruptible(Dispatchers.IO) { client.execute(request, options) }
                    // Register the response. If the future already terminated (cancelled / failed)
                    // before we got here, its value is discarded, so close the orphan ourselves.
                    if (handoff.getAndSet(response) === DISCARDED) {
                        closeQuietly(response)
                    }
                    response
                }
            future.whenComplete { result, _ ->
                // A null result means the future did NOT hand a Response to the caller (cancelled or
                // exceptional). If the coroutine already registered one, it is orphaned — close it;
                // otherwise publish DISCARDED so the coroutine closes the Response it is about to
                // produce. `result != null` is the normal-delivery path: the caller owns the value.
                if (result == null) {
                    val prev = handoff.getAndSet(DISCARDED)
                    if (prev is Response) closeQuietly(prev)
                }
            }
            return future
        }
    }
}

/**
 * Sentinel published to the [asAsyncCoroutines] handshake when the returned future terminates
 * without delivering a value, signalling the coroutine to close any Response it produces.
 */
private val DISCARDED: Any = Any()

/**
 * Best-effort close on a discard path — the Response lost the completion race, so a failure to
 * close it has nothing actionable to surface.
 */
private fun closeQuietly(response: Response) {
    try {
        response.close()
    } catch (ignored: Throwable) {
        // Intentionally ignored: the response is already being discarded.
    }
}
