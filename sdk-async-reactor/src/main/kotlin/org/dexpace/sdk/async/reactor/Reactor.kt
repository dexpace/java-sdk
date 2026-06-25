/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

@file:JvmName("ReactorAdapters")

package org.dexpace.sdk.async.reactor

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.pipeline.AsyncHttpPipeline
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.sse.ServerSentEvent
import org.dexpace.sdk.core.http.sse.ServerSentEventReader
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.instrumentation.MdcSnapshot
import org.dexpace.sdk.core.io.BufferedSource
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture

private val log = ClientLogger("org.dexpace.sdk.async.reactor.Reactor")

/**
 * Wraps [AsyncHttpClient.executeAsync] in a [Mono]. Each subscription invokes the supplier so
 * the underlying [java.util.concurrent.CompletableFuture] is created lazily — re-subscribing
 * re-executes the request, which matches Reactor's cold-publisher convention.
 *
 * Cancellation: cancelling the [Mono] subscription cancels the underlying future via
 * `CompletableFuture.cancel(true)`. Whether the in-flight transport sees the interrupt is up
 * to the [AsyncHttpClient] implementation.
 *
 * ## MDC propagation
 *
 * The SLF4J MDC is captured **per subscription**, not at assembly time: the [Mono.defer]
 * wrapper snapshots MDC the moment a subscriber subscribes, so each subscription reinstates
 * the *subscriber's* `trace.id` / `span.id` — both in the [AsyncHttpClient.executeAsync]
 * supplier and in the adapter's own `.doOnSubscribe` / `.doOnCancel` hooks, even when those
 * fire on a different scheduler. A `Mono` assembled once and re-subscribed therefore picks
 * up the MDC live at each subscribe, not a stale snapshot from the assembling thread.
 * To extend MDC propagation through user-supplied downstream operators, enable
 * `Hooks.enableAutomaticContextPropagation()` at the application level.
 */
public fun AsyncHttpClient.executeMono(request: Request): Mono<Response> = deferMono { executeAsync(request) }

/**
 * Pipeline-level [Mono] facade — see [executeMono].
 *
 * ## MDC propagation
 *
 * The SLF4J MDC is captured **per subscription**, not at assembly time: the [Mono.defer]
 * wrapper snapshots MDC the moment a subscriber subscribes, so each subscription reinstates
 * the *subscriber's* `trace.id` / `span.id` — both in the [AsyncHttpPipeline.sendAsync]
 * supplier and in the adapter's own `.doOnSubscribe` / `.doOnCancel` hooks, even when those
 * fire on a different scheduler. A `Mono` assembled once and re-subscribed therefore picks
 * up the MDC live at each subscribe, not a stale snapshot from the assembling thread.
 * To extend MDC propagation through user-supplied downstream operators, enable
 * `Hooks.enableAutomaticContextPropagation()` at the application level.
 */
public fun AsyncHttpPipeline.sendMono(request: Request): Mono<Response> = deferMono { sendAsync(request) }

/**
 * Bridges a [CompletableFuture]-returning [supplier] to a cold [Mono]. Each subscription runs
 * the supplier afresh under [Mono.defer], captures the subscriber's MDC, and reinstates it for
 * the future-producing call and for both lifecycle log hooks. Cancelling the subscription
 * cancels the underlying future through [Mono.fromFuture].
 */
private fun deferMono(supplier: () -> CompletableFuture<Response>): Mono<Response> =
    Mono.defer {
        val mdc = MdcSnapshot.capture()
        Mono.fromFuture { mdc.withMdc { supplier() } }
            .doOnSubscribe { logEvent(mdc, "async.adapter.subscribed") }
            .doOnCancel { logEvent(mdc, "async.adapter.cancel_propagated") }
    }

private fun logEvent(
    mdc: MdcSnapshot,
    event: String,
) {
    mdc.withMdc {
        log.atVerbose()
            .event(event)
            .field("adapter.type", "reactor")
            .log()
    }
}

/**
 * Exposes the SSE event stream as a Reactor [Flux]. Backpressure is honored via
 * [Flux.generate]: the source is polled exactly once per downstream demand request — never
 * eagerly.
 *
 * The returned [Flux] terminates with [reactor.core.publisher.SignalType.ON_COMPLETE] when
 * [ServerSentEventReader.next] returns null (stream end). Exceptions propagate via
 * [reactor.core.publisher.SignalType.ON_ERROR].
 *
 * Note: the underlying [BufferedSource] is single-threaded. Repeated subscribers MUST NOT
 * share a [BufferedSource]; create a fresh reader (and a fresh source) per subscription.
 */
public fun ServerSentEventReader.toFlux(): Flux<ServerSentEvent> =
    Flux.generate { sink ->
        val event =
            try {
                next()
            } catch (e: Exception) {
                // Per style guide §8.3: don't swallow Error; only handle Exception subclasses.
                sink.error(e)
                return@generate
            }
        if (event == null) sink.complete() else sink.next(event)
    }

/**
 * Builds a one-shot [Flux] that reads SSE events from [source] until the source is exhausted.
 *
 * Resource lifetime: this extension does NOT close the underlying [BufferedSource] on Flux
 * termination (complete, error, or cancel). The caller owns the source and is responsible for
 * closing it. If close-on-cancel is desired, chain `.doFinally { this.close() }` on the result.
 *
 * [Flux.using] is used here so the [ServerSentEventReader] is created and scoped alongside
 * the subscription. The reader does not hold any closeable resource beyond this source, so the
 * cleanup lambda is a no-op.
 */
public fun BufferedSource.readServerSentEventsAsFlux(): Flux<ServerSentEvent> =
    Flux.using(
        { ServerSentEventReader(this@readServerSentEventsAsFlux) },
        { reader -> reader.toFlux() },
        { /* ServerSentEventReader holds no additional resources beyond the caller-owned source */ },
    )
