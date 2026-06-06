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
 * SLF4J MDC entries set on the caller's thread are captured at entry and reinstated inside
 * the adapter's own `.doOnSubscribe` / `.doOnCancel` hooks, so the log events emitted from
 * those hooks carry `trace.id` / `span.id` even when the hook fires on a different scheduler.
 * To extend MDC propagation through user-supplied downstream operators, enable
 * `Hooks.enableAutomaticContextPropagation()` at the application level.
 */
public fun AsyncHttpClient.executeMono(request: Request): Mono<Response> {
    val mdc = MdcSnapshot.capture()
    return Mono.fromFuture { mdc.withMdc { executeAsync(request) } }
        .doOnSubscribe {
            mdc.withMdc {
                log.atVerbose()
                    .event("async.adapter.subscribed")
                    .field("adapter.type", "reactor")
                    .log()
            }
        }
        .doOnCancel {
            mdc.withMdc {
                log.atVerbose()
                    .event("async.adapter.cancel_propagated")
                    .field("adapter.type", "reactor")
                    .log()
            }
        }
}

/**
 * Pipeline-level [Mono] facade — see [executeMono].
 *
 * ## MDC propagation
 *
 * SLF4J MDC entries set on the caller's thread are captured at entry and reinstated inside
 * the adapter's own `.doOnSubscribe` / `.doOnCancel` hooks, so the log events emitted from
 * those hooks carry `trace.id` / `span.id` even when the hook fires on a different scheduler.
 * To extend MDC propagation through user-supplied downstream operators, enable
 * `Hooks.enableAutomaticContextPropagation()` at the application level.
 */
public fun AsyncHttpPipeline.sendMono(request: Request): Mono<Response> {
    val mdc = MdcSnapshot.capture()
    return Mono.fromFuture { mdc.withMdc { sendAsync(request) } }
        .doOnSubscribe {
            mdc.withMdc {
                log.atVerbose()
                    .event("async.adapter.subscribed")
                    .field("adapter.type", "reactor")
                    .log()
            }
        }
        .doOnCancel {
            mdc.withMdc {
                log.atVerbose()
                    .event("async.adapter.cancel_propagated")
                    .field("adapter.type", "reactor")
                    .log()
            }
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
